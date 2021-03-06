package com.blerpc;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import com.blerpc.device.test.proto.TestBleReadRequest;
import com.blerpc.device.test.proto.TestBleReadResponse;
import com.blerpc.device.test.proto.TestBleService;
import com.blerpc.device.test.proto.TestBleSubscribeRequest;
import com.blerpc.device.test.proto.TestBleSubscribeResponse;
import com.blerpc.device.test.proto.TestBleWriteRequest;
import com.blerpc.device.test.proto.TestBleWriteResponse;
import com.blerpc.device.test.proto.TestIntegerEmbeddedMessage;
import com.blerpc.device.test.proto.TestLittleEndianMessage;
import com.blerpc.device.test.proto.TestValuesEnum;
import com.blerpc.proto.Blerpc;
import com.google.protobuf.ByteString;
import com.google.protobuf.RpcCallback;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Integration tests for {@link BleRpcChannel}.
 */
@RunWith(MockitoJUnitRunner.class)
public class TestServiceTest {

  private static final UUID TEST_SERVICE = UUID.fromString(TestBleService.getDescriptor().getOptions()
      .getExtension(Blerpc.service).getUuid());
  private static final UUID TEST_CHARACTERISTIC = UUID.fromString(
      TestBleService.getDescriptor().findMethodByName("TestWriteChar").getOptions()
          .getExtension(Blerpc.characteristic).getUuid());
  private static final UUID TEST_DESCRIPTOR = UUID.fromString(
      TestBleService.getDescriptor().findMethodByName("TestSubscribeChar").getOptions()
          .getExtension(Blerpc.characteristic).getDescriptorUuid());
  private static final TestBleReadRequest TEST_READ_REQUEST = TestBleReadRequest.newBuilder()
      .setIntValue(1000)
      .build();
  private static final byte[] TEST_READ_RESPONSE_BYTES = new byte[]{0, 0, 0, 45};
  private static final byte[] TEST_READ_INVALID_RESPONSE_BYTES = new byte[]{0, 0, 0};
  private static final TestBleReadResponse TEST_READ_RESPONSE = TestBleReadResponse.newBuilder()
      .setIntValue(45)
      .build();
  private static byte[] TEST_WRITE_REQUEST_BYTES =
      new byte[]{0, 0, 0, 50, 0, 0, 0, 0, 0, 0, 0, 80, 1, 0, 2, 0, 0, 0, 0, 100, 1, 2, 3, 4, 50, 0, 0, 0};
  private static final TestBleWriteRequest TEST_WRITE_REQUEST = TestBleWriteRequest.newBuilder()
      .setIntValue(50)
      .setLongValue(80)
      .setBoolValue(true)
      .setByteStringValue(ByteString.copyFrom(new byte[]{1, 2, 3, 4}))
      .setEnumValue(TestValuesEnum.VALUE_2)
      .setMessageValue(TestIntegerEmbeddedMessage.newBuilder()
          .setIntValue(100))
      .setLittleEndianIntValue(50)
      .build();
  private static byte[] TEST_WRITE_RESPONSE_BYTES =
      new byte[]{60, 0, 0, 0, 95, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 115, 0, 0, 0, 5, 6, 7, 8, 0, 0, 0, 50, 0, 0, 0, 0, 0, 0, 0, 110};
  private static final TestBleWriteResponse TEST_WRITE_RESPONSE = TestBleWriteResponse.newBuilder()
      .setIntValue(60)
      .setLongValue(95)
      .setBoolValue(true)
      .setByteStringValue(ByteString.copyFrom(new byte[]{5, 6, 7, 8}))
      .setEnumValue(TestValuesEnum.VALUE_1)
      .setMessageValue(TestIntegerEmbeddedMessage.newBuilder()
          .setIntValue(115))
      .setBigEndianIntValue(50)
      .setLittleEndianValue(TestLittleEndianMessage.newBuilder()
          .setLongValue(110))
      .build();
  private static final TestBleSubscribeRequest TEST_SUBSCRIBE_REQUEST = TestBleSubscribeRequest.newBuilder()
      .setIntValue(5000)
      .build();
  private static byte[] TEST_SUBSCRIBE_RESPONSE1_BYTES = new byte[]{0, 0, 0, 80};
  private static final TestBleSubscribeResponse TEST_SUBSCRIBE_RESPONSE1 = TestBleSubscribeResponse.newBuilder()
      .setIntValue(80)
      .build();
  private static byte[] TEST_SUBSCRIBE_RESPONSE2_BYTES = new byte[]{0, 0, 0, 90};
  private static final TestBleSubscribeResponse TEST_SUBSCRIBE_RESPONSE2 = TestBleSubscribeResponse.newBuilder()
      .setIntValue(90)
      .build();

  @Mock private RpcCallback<TestBleReadResponse> callbackRead;
  @Mock private RpcCallback<TestBleWriteResponse> callbackWrite;
  @Mock private RpcCallback<TestBleSubscribeResponse> callbackSubscribe;
  @Mock private BluetoothDevice bluetoothDevice;
  @Mock private Context context;
  @Mock private BluetoothGatt bluetoothGatt;
  @Mock private BluetoothGattService gattService;
  @Mock private BluetoothGattCharacteristic characteristic;
  @Mock private BluetoothGattDescriptor descriptor;

  private BleRpcController controller = new BleRpcController();
  private AnnotationMessageConverter messageConverter = new AnnotationMessageConverter();
  private TestBleService testService;
  private ArgumentCaptor<BluetoothGattCallback> bluetoothCallback =
      ArgumentCaptor.forClass(BluetoothGattCallback.class);
  private ArgumentCaptor<byte[]> descriptorValue = ArgumentCaptor.forClass(byte[].class);

  /**
   * Prepare rpc.
   */
  @Before
  public void setUp() throws Exception {
    Handler workHandler = Mockito.mock(Handler.class);
    Handler listenerHandler = Mockito.mock(Handler.class);

    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(workHandler).post(any());

    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(listenerHandler).post(any());

    doAnswer(invocation -> {
      bluetoothCallback.getValue().onServicesDiscovered(bluetoothGatt, BluetoothGatt.GATT_SUCCESS);
      return true;
    }).when(bluetoothGatt).discoverServices();

    doAnswer(invocation -> {
      BluetoothGattCharacteristic characteristic = (BluetoothGattCharacteristic) invocation.getArgument(0);
      bluetoothCallback.getValue()
          .onCharacteristicRead(bluetoothGatt, characteristic, BluetoothGatt.GATT_SUCCESS);
      return true;
    }).when(bluetoothGatt).readCharacteristic(any(BluetoothGattCharacteristic.class));

    doAnswer(invocation -> {
      BluetoothGattCharacteristic characteristic = (BluetoothGattCharacteristic) invocation.getArgument(0);
      bluetoothCallback.getValue()
          .onCharacteristicWrite(bluetoothGatt, characteristic, BluetoothGatt.GATT_SUCCESS);
      return true;
    }).when(bluetoothGatt).writeCharacteristic(any(BluetoothGattCharacteristic.class));

    doAnswer(invocation -> {
      BluetoothGattDescriptor descriptor = (BluetoothGattDescriptor) invocation.getArgument(0);
      bluetoothCallback.getValue()
          .onDescriptorWrite(bluetoothGatt, descriptor, BluetoothGatt.GATT_SUCCESS);
      return true;
    }).when(bluetoothGatt).writeDescriptor(any(BluetoothGattDescriptor.class));

    doAnswer(invocation -> {
      verify(descriptor, atLeastOnce()).setValue(descriptorValue.capture());
      return descriptorValue.getValue();
    }).when(descriptor).getValue();

    when(bluetoothDevice.connectGatt(eq(context), anyBoolean(), any())).thenReturn(bluetoothGatt);
    when(bluetoothGatt.getService(TEST_SERVICE)).thenReturn(gattService);
    when(bluetoothGatt.setCharacteristicNotification(eq(characteristic), anyBoolean())).thenReturn(true);
    when(gattService.getCharacteristic(TEST_CHARACTERISTIC)).thenReturn(characteristic);
    when(characteristic.getUuid()).thenReturn(TEST_CHARACTERISTIC);
    when(characteristic.setValue(any(byte[].class))).thenReturn(true);
    when(characteristic.getDescriptor(TEST_DESCRIPTOR)).thenReturn(descriptor);
    when(characteristic.getProperties()).thenReturn(
        BluetoothGattCharacteristic.PROPERTY_READ
            | BluetoothGattCharacteristic.PROPERTY_WRITE
            | BluetoothGattCharacteristic.PROPERTY_NOTIFY);
    when(descriptor.setValue(any(byte[].class))).thenReturn(true);

    BleRpcChannel channel = new BleRpcChannel(bluetoothDevice, context, messageConverter, workHandler,
        listenerHandler, Logger.getGlobal());
    testService = TestBleService.newStub(channel);
  }

  /**
   * Set up constants.
   */
  @BeforeClass
  public static void setUpConstants() {
    BleRpcChannel.ENABLE_NOTIFICATION_VALUE = new byte[]{1};
    BleRpcChannel.DISABLE_NOTIFICATION_VALUE = new byte[]{2};
  }

  void connectAndRun() {
    verify(bluetoothDevice).connectGatt(any(), anyBoolean(), bluetoothCallback.capture());
    bluetoothCallback.getValue().onConnectionStateChange(bluetoothGatt, BluetoothGatt.GATT_SUCCESS,
        BluetoothProfile.STATE_CONNECTED);
  }

  @Test
  public void testRead() throws Exception {
    when(characteristic.getValue()).thenReturn(TEST_READ_RESPONSE_BYTES);
    testService.testReadChar(controller, TEST_READ_REQUEST, callbackRead);
    connectAndRun();
    assertCallSucceeded(controller);
    verify(callbackRead).run(TEST_READ_RESPONSE);
  }

  @Test
  public void testWrite() throws Exception {
    when(characteristic.getValue()).thenReturn(TEST_WRITE_RESPONSE_BYTES);
    testService.testWriteChar(controller, TEST_WRITE_REQUEST, callbackWrite);
    connectAndRun();
    assertCallSucceeded(controller);
    verify(characteristic).setValue(TEST_WRITE_REQUEST_BYTES);
    verify(callbackWrite).run(TEST_WRITE_RESPONSE);
  }

  @Test
  public void testCancel() throws Exception {
    testService.testReadChar(controller, TEST_READ_REQUEST, callbackRead);
    controller.startCancel();
    connectAndRun();
    assertCallSucceeded(controller);
    verify(callbackRead).run(TestBleReadResponse.getDefaultInstance());
  }

  @Test
  public void testTwoCalls() throws Exception {
    when(characteristic.getValue()).thenReturn(TEST_READ_RESPONSE_BYTES);
    testService.testReadChar(controller, TEST_READ_REQUEST, callbackRead);
    connectAndRun();
    assertCallSucceeded(controller);
    verify(callbackRead).run(TEST_READ_RESPONSE);

    when(characteristic.getValue()).thenReturn(TEST_WRITE_RESPONSE_BYTES);
    testService.testWriteChar(controller, TEST_WRITE_REQUEST, callbackWrite);
    assertCallSucceeded(controller);
    verify(callbackWrite).run(TEST_WRITE_RESPONSE);
    verify(characteristic).setValue(TEST_WRITE_REQUEST_BYTES);
  }

  @Test
  public void testSubscribe() throws Exception {
    BleRpcController bleRpcController = spy(controller);
    testService.testSubscribeChar(bleRpcController, TEST_SUBSCRIBE_REQUEST, callbackSubscribe);
    connectAndRun();
    verify(bleRpcController).onSubscribeSuccess();
    verify(callbackSubscribe, never()).run(any());
    sendUpdate(TEST_SUBSCRIBE_RESPONSE1_BYTES);
    assertCallSucceeded(controller);
    verify(callbackSubscribe, times(1)).run(TEST_SUBSCRIBE_RESPONSE1);
    sendUpdate(TEST_SUBSCRIBE_RESPONSE2_BYTES);
    assertCallSucceeded(controller);
    verify(callbackSubscribe, times(1)).run(TEST_SUBSCRIBE_RESPONSE2);

    BleRpcController secondBleRpcController = spy(new BleRpcController());
    testService.testSubscribeChar(secondBleRpcController, TEST_SUBSCRIBE_REQUEST, callbackSubscribe);
    verify(secondBleRpcController).onSubscribeSuccess();
  }

  @Test
  public void testValueChangedBeforeOnDescriptorWriteSubscribe() throws Exception {
    doAnswer(invocation -> true).when(bluetoothGatt).writeDescriptor(any(BluetoothGattDescriptor.class));
    testService.testSubscribeChar(controller, TEST_SUBSCRIBE_REQUEST, callbackSubscribe);
    connectAndRun();
    sendUpdate(TEST_SUBSCRIBE_RESPONSE1_BYTES);
    // If fail - java.lang.IllegalArgumentException: The characteristic f0cdaa72-0451-4000-b000-000000000000 is not subscribed.
    verify(callbackSubscribe, never()).run(any());
  }

  @Test
  public void testValueChangedBeforeOnDescriptorWriteUnsubscribe() throws Exception {
    testService.testSubscribeChar(controller, TEST_SUBSCRIBE_REQUEST, callbackSubscribe);
    connectAndRun();
    doAnswer(invocation -> true).when(bluetoothGatt).writeDescriptor(any(BluetoothGattDescriptor.class));
    controller.startCancel();
    // first sendUpdate to remove canceled subscriptions and start unsubscribing.
    sendUpdate(TEST_SUBSCRIBE_RESPONSE1_BYTES);
    sendUpdate(TEST_SUBSCRIBE_RESPONSE1_BYTES);
    // If fail - java.lang.IllegalArgumentException: The characteristic f0cdaa72-0451-4000-b000-000000000000 is not subscribed.
    verify(callbackSubscribe, never()).run(any());
  }

  @Test
  public void testSubscribeCancel() throws Exception {
    testService.testSubscribeChar(controller, TEST_SUBSCRIBE_REQUEST, callbackSubscribe);
    controller.startCancel();
    connectAndRun();
    assertCallSucceeded(controller);
    verify(callbackSubscribe, never()).run(any());
  }

  @Test
  public void testSubscribeCancel_afterUpdate() throws Exception {
    testService.testSubscribeChar(controller, TEST_SUBSCRIBE_REQUEST, callbackSubscribe);
    connectAndRun();
    verify(callbackSubscribe, never()).run(any());
    sendUpdate(TEST_SUBSCRIBE_RESPONSE1_BYTES);
    assertCallSucceeded(controller);
    verify(callbackSubscribe, times(1)).run(TEST_SUBSCRIBE_RESPONSE1);
    controller.startCancel();
    sendUpdate(TEST_SUBSCRIBE_RESPONSE2_BYTES);
    assertCallSucceeded(controller);
    verify(callbackSubscribe, never()).run(TEST_SUBSCRIBE_RESPONSE2);
  }

  void sendUpdate(byte[] bytes) throws CouldNotConvertMessageException {
    when(characteristic.getValue()).thenReturn(bytes);
    bluetoothCallback.getValue().onCharacteristicChanged(bluetoothGatt, characteristic);
  }

  void assertCallSucceeded(BleRpcController controller) {
    assertThat(controller.failed()).isFalse();
    assertThat(controller.errorText()).isNull();
  }
}
