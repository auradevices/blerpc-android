import CoreBluetooth
import Foundation
import RxBluetoothKit
import RxSwift

/// Enum that describes Ble Service Driver errors.
public enum BleServiceDriverErrors: Error {
    /// Called when device returned empty response so we can not parse it as Proto object.
    case emptyResponse

    /// Called when client sends non empty request for read or subscribe methods.
    case nonEmptyRequest

    /// Called when disconnect read/write and not end operation
    case disconnected

    /// Called when charecteristic not found
    case notFoundCharecteristic
}

/// Class which holds all operation with data transfer between iOS and Ble Device.
open class BleServiceDriver {
    // MARK: - Variables

    /// Connected peripheral.
    private var peripheral: Peripheral?

    /// Event for disconnect all subscription.
    private var disconnectSubscription: PublishSubject<Void> = PublishSubject<Void>()

    /// Event for disconnect all read/write.
    private var disconnectReadWrite: PublishSubject<Void> = PublishSubject<Void>()

    /// Lock for establish connection.
    private let establishConnectionLock = NSLock()

    /// DisposeBag for establish connection.
    private var establishConnectionDisposeBag: DisposeBag = DisposeBag()

    /// Peripheral event for connection.
    private let peripheralEvent: BehaviorSubject<Peripheral>
    // MARK: - Initializers

    /// Please use init(peripheral:) instead.
    private init() {
        fatalError("Please use init(peripheral:) instead.")
    }

    /// Initialize with a connected peripheral.
    /// - parameter device: peripheral to operate with.
    /// - returns: created *BleServiceDriver*.
    public init(peripheral: Peripheral) {
        self.peripheral = peripheral
        self.peripheralEvent = BehaviorSubject<Peripheral>(value: peripheral)
    }

    // MARK: - Internal methods

    /// Disconnect all flow.
    public func disconnect() {
        establishConnectionDisposeBag = DisposeBag()
        disconnectSubscription.onNext(())
        disconnectReadWrite.onNext(())
        peripheral?.manager.manager.cancelPeripheralConnection(peripheral!.peripheral)
    }

    /// Call subscribe request over Ble.
    /// - parameter request: proto request encoded to Data. Must be empty message.
    /// - parameter serviceUUID: *UUID* of a service.
    /// - parameter characteristicUUID: *UUID* of a characteristic.
    /// - returns: Data as observable value.
    /// - warning: request must be empty for Read requests.
    public func subscribe(
        request data: Data,
        serviceUUID: String,
        characteristicUUID: String
    ) throws -> Observable<Data> {
        if data.count > 0 {
            return Observable.error(BleServiceDriverErrors.nonEmptyRequest)
        }
        return connectToDeviceAndDiscoverCharacteristic(
            serviceUUID: serviceUUID,
            characteristicUUID: characteristicUUID)
            .flatMap { characteristic in
                characteristic.observeValueUpdateAndSetNotification()
            }.map { characteristic in
                guard let data = characteristic.value else {
                    throw BluetoothError.characteristicReadFailed(
                        characteristic,
                        BleServiceDriverErrors.emptyResponse)
                }
                return data
            }
            .takeUntil(disconnectSubscription)
    }

    /// Call read request over Ble.
    /// - parameter request: proto request encoded to Data. Must be empty message.
    /// - parameter serviceUUID: *UUID* of a service.
    /// - parameter characteristicUUID: *UUID* of a characteristic.
    /// - returns: Data as observable value.
    /// - warning: request must be empty for Read requests.
    public func read(request data: Data, serviceUUID: String, characteristicUUID: String) throws -> Single<Data> {
        if data.count > 0 {
            return Single.error(BleServiceDriverErrors.nonEmptyRequest)
        }
        return connectToDeviceAndDiscoverCharacteristic(
            serviceUUID: serviceUUID,
            characteristicUUID: characteristicUUID
        ).flatMap { [weak self] characteristic -> Observable<Characteristic> in
            guard let `self` = self else { return .just(characteristic) }
            return Observable.merge(
                .just(characteristic),
                self.disconnectReadWrite.asObservable().map { _ in throw BleServiceDriverErrors.disconnected }
            )
        }.flatMap { characteristic in
            characteristic.readValue()
        }.take(1).asSingle().map { characteristic in
            guard let data = characteristic.value else {
                throw BluetoothError.characteristicReadFailed(
                    characteristic,
                    BleServiceDriverErrors.emptyResponse)
            }
            return data
        }
    }

    /// Call write request over Ble.
    /// - parameter request: proto request encoded to Data.
    /// - parameter serviceUUID: *UUID* of a service.
    /// - parameter characteristicUUID: *UUID* of a characteristic.
    /// - returns: Data as observable value.
    public func write(request: Data, serviceUUID: String, characteristicUUID: String) -> Single<Data> {
        return connectToDeviceAndDiscoverCharacteristic(
            serviceUUID: serviceUUID,
            characteristicUUID: characteristicUUID
        ).flatMap { [weak self] characteristic -> Observable<Characteristic> in
            guard let `self` = self else { return .just(characteristic) }
            return Observable.merge(
                .just(characteristic),
                self.disconnectReadWrite.asObservable().map { _ in throw BleServiceDriverErrors.disconnected }
            )
        }.flatMap { characteristic in
            characteristic.writeValue(request, type: .withResponse)
        }.take(1).asSingle().map { _ in
            return Data()
        }
    }

    // MARK: - Private methods

    /// We block flows, we receive peripheral which at us is installed in behaviorsubject.
    /// If the peripheral state is in online mode and has not yet received a connection,
    /// then reset all previous connection attempts and try to establish a new connection.
    /// Unlock threads. And we pass from the function an element with an established connection.
    /// If we have a connection established, simply transfer the element with the connection established.
    /// - returns: Peripheral as observable value.
    private func getConnectedPeripheral() -> Observable<Peripheral> {
        establishConnection()
        return peripheralEvent.asObservable().filter { $0.isConnected }
    }

    /// Establish connection and emit from peripheralEvent.
    private func establishConnection() {
        establishConnectionLock.lock()
        defer {
            establishConnectionLock.unlock()
        }
        if let peripheralValue = try? peripheralEvent.value() {
            if peripheralValue.state != .connecting && !peripheralValue.isConnected {
                establishConnectionDisposeBag = DisposeBag()
                peripheralValue.establishConnection()
                    .subscribe(onNext: peripheralEvent.onNext)
                    .disposed(by: establishConnectionDisposeBag)
            }
        }
    }

    /// Method which connects to device (if needed) and discover requested characteristic.
    /// - parameter serviceUUID: UUID of requested service.
    /// - parameter characteristicUUID: UUID of requested characteristic.
    /// - returns: Characteristic as observable value.
    private func connectToDeviceAndDiscoverCharacteristic(
        serviceUUID: String,
        characteristicUUID: String
    ) -> Observable<Characteristic> {
        return getConnectedPeripheral().flatMap { peripheral -> Observable<Characteristic> in
            peripheral.discoverServices([CBUUID(string: serviceUUID)]).asObservable().flatMap { services in
                Observable.from(services)
            }.flatMap { service in
                service.discoverCharacteristics([CBUUID(string: characteristicUUID)])
            }.flatMap { characteristics -> Observable<Characteristic> in
                guard !characteristics.isEmpty else {
                    return .error(BleServiceDriverErrors.notFoundCharecteristic)
                }
                return Observable.from(characteristics)
            }
        }
    }
}
