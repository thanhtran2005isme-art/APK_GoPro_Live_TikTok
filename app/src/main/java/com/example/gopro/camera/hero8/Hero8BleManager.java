package com.example.gopro.camera.hero8;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.UUID;

/**
 * HERO8 BLE bootstrap used before connecting to the camera Wi-Fi AP.
 *
 * <p>HERO8-era cameras can expose their Wi-Fi AP while the legacy HTTP control server is not yet
 * ready for a new controller. The GoPro control stack enables Wi-Fi through BLE first. This class
 * performs the minimum bootstrap required by the app: discover a GoPro advertisement, bond if
 * needed, connect GATT, subscribe to the command response characteristic and issue Set AP Control
 * (command 0x17, mode=enable).</p>
 *
 * <p>The GATT connection is intentionally kept open while the app uses Wi-Fi. It is released by
 * {@link #close()}.</p>
 */
@SuppressLint("MissingPermission")
public final class Hero8BleManager implements AutoCloseable {

    public interface Listener {
        void onScanning();

        void onPairing(@NonNull String deviceName);

        void onBleConnecting(@NonNull String deviceName);

        void onWifiApEnabled(@NonNull String deviceName);

        void onError(@NonNull String message);
    }

    private static final UUID GOPRO_ADVERTISEMENT_SERVICE =
            UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb");
    private static final UUID COMMAND_UUID =
            UUID.fromString("b5f90072-aa8d-11e3-9046-0002a5d5c51b");
    private static final UUID COMMAND_RESPONSE_UUID =
            UUID.fromString("b5f90073-aa8d-11e3-9046-0002a5d5c51b");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Open GoPro Set AP Control: length=3, command=0x17, parameter length=1, mode=1 (enable).
    private static final byte[] ENABLE_WIFI_AP = new byte[] {0x03, 0x17, 0x01, 0x01};
    private static final long SCAN_TIMEOUT_MS = 12_000L;
    private static final long COMMAND_TIMEOUT_MS = 7_000L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter bluetoothAdapter;

    @Nullable private BluetoothLeScanner scanner;
    @Nullable private BluetoothGatt gatt;
    @Nullable private BluetoothDevice pendingBondDevice;
    private boolean scanRunning;
    private boolean receiverRegistered;
    private boolean waitingForCommandResponse;

    private final Runnable scanTimeout =
            () -> {
                if (!scanRunning) {
                    return;
                }
                stopScan();
                listener.onError(
                        "Không tìm thấy HERO8 qua Bluetooth. Trên camera hãy mở Connections > Connect Device > GoPro Quik App rồi thử lại.");
            };

    private final Runnable commandTimeout =
            () -> {
                if (!waitingForCommandResponse) {
                    return;
                }
                waitingForCommandResponse = false;
                listener.onError(
                        "HERO8 đã kết nối BLE nhưng không phản hồi lệnh bật Wi-Fi AP (0x17). Hãy bật chế độ Pairing/GoPro Quik App trên camera và thử lại.");
            };

    private final BroadcastReceiver bondReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    BluetoothDevice device =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                    ? intent.getParcelableExtra(
                                            BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null || pendingBondDevice == null
                            || !device.getAddress().equals(pendingBondDevice.getAddress())) {
                        return;
                    }

                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        BluetoothDevice bonded = pendingBondDevice;
                        pendingBondDevice = null;
                        unregisterBondReceiver();
                        connectGatt(bonded);
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        pendingBondDevice = null;
                        unregisterBondReceiver();
                        listener.onError("Ghép đôi Bluetooth với HERO8 không thành công.");
                    }
                }
            };

    private final ScanCallback scanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, @NonNull ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    stopScan();
                    ensureBondThenConnect(device);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    stopScan();
                    listener.onError("BLE scan lỗi, mã=" + errorCode + ".");
                }
            };

    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(
                        @NonNull BluetoothGatt callbackGatt, int status, int newState) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post(
                                () -> listener.onError(
                                        "Kết nối BLE HERO8 lỗi GATT status=" + status + "."));
                        callbackGatt.close();
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        callbackGatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        waitingForCommandResponse = false;
                    }
                }

                @Override
                public void onServicesDiscovered(@NonNull BluetoothGatt callbackGatt, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post(
                                () -> listener.onError(
                                        "Không đọc được GATT services của HERO8, status=" + status + "."));
                        return;
                    }

                    BluetoothGattCharacteristic command = findCharacteristic(callbackGatt, COMMAND_UUID);
                    BluetoothGattCharacteristic response =
                            findCharacteristic(callbackGatt, COMMAND_RESPONSE_UUID);
                    if (command == null || response == null) {
                        mainHandler.post(
                                () -> listener.onError(
                                        "HERO8 không expose GP-0072/GP-0073 trên BLE session này. Hãy bật Pairing/GoPro Quik App rồi thử lại."));
                        return;
                    }

                    if (!callbackGatt.setCharacteristicNotification(response, true)) {
                        mainHandler.post(
                                () -> listener.onError("Không bật được notification GP-0073."));
                        return;
                    }

                    BluetoothGattDescriptor cccd = response.getDescriptor(CCCD_UUID);
                    if (cccd == null) {
                        mainHandler.post(
                                () -> listener.onError("Không tìm thấy CCCD của GP-0073."));
                        return;
                    }

                    int result;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        result = callbackGatt.writeDescriptor(
                                cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        result = callbackGatt.writeDescriptor(cccd)
                                ? BluetoothGatt.GATT_SUCCESS
                                : BluetoothGatt.GATT_FAILURE;
                    }

                    if (result != BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post(
                                () -> listener.onError(
                                        "Không subscribe được GP-0073, GATT=" + result + "."));
                    }
                }

                @Override
                public void onDescriptorWrite(
                        @NonNull BluetoothGatt callbackGatt,
                        @NonNull BluetoothGattDescriptor descriptor,
                        int status) {
                    if (!CCCD_UUID.equals(descriptor.getUuid())) {
                        return;
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post(
                                () -> listener.onError(
                                        "Subscribe GP-0073 thất bại, status=" + status + "."));
                        return;
                    }
                    writeEnableWifiCommand(callbackGatt);
                }

                @Override
                public void onCharacteristicChanged(
                        @NonNull BluetoothGatt callbackGatt,
                        @NonNull BluetoothGattCharacteristic characteristic,
                        @NonNull byte[] value) {
                    handleCommandResponse(value);
                }

                @SuppressWarnings("deprecation")
                @Override
                public void onCharacteristicChanged(
                        @NonNull BluetoothGatt callbackGatt,
                        @NonNull BluetoothGattCharacteristic characteristic) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        return;
                    }
                    byte[] value = characteristic.getValue();
                    if (value != null) {
                        handleCommandResponse(value);
                    }
                }
            };

    public Hero8BleManager(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager =
                (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
    }

    public void enableWifiAp() {
        closeGattOnly();

        if (bluetoothAdapter == null) {
            listener.onError("Điện thoại không hỗ trợ Bluetooth.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            listener.onError("Bluetooth đang tắt. Hãy bật Bluetooth rồi bấm Kết nối HERO8 lại.");
            return;
        }

        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError("Không khởi tạo được BLE scanner.");
            return;
        }

        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(GOPRO_ADVERTISEMENT_SERVICE))
                        .build();
        ScanSettings settings =
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();

        scanRunning = true;
        listener.onScanning();
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    private void ensureBondThenConnect(@NonNull BluetoothDevice device) {
        String name = safeName(device);
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            connectGatt(device);
            return;
        }

        pendingBondDevice = device;
        registerBondReceiver();
        listener.onPairing(name);
        if (!device.createBond()) {
            pendingBondDevice = null;
            unregisterBondReceiver();
            listener.onError("Android không bắt đầu được quá trình ghép đôi HERO8.");
        }
    }

    private void connectGatt(@NonNull BluetoothDevice device) {
        listener.onBleConnecting(safeName(device));
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            listener.onError("Không tạo được BLE GATT connection tới HERO8.");
        }
    }

    @Nullable
    private static BluetoothGattCharacteristic findCharacteristic(
            @NonNull BluetoothGatt callbackGatt, @NonNull UUID uuid) {
        for (BluetoothGattService service : callbackGatt.getServices()) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(uuid);
            if (characteristic != null) {
                return characteristic;
            }
        }
        return null;
    }

    private void writeEnableWifiCommand(@NonNull BluetoothGatt callbackGatt) {
        BluetoothGattCharacteristic command = findCharacteristic(callbackGatt, COMMAND_UUID);
        if (command == null) {
            listener.onError("Mất GP-0072 trước khi gửi lệnh Wi-Fi.");
            return;
        }

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result = callbackGatt.writeCharacteristic(
                    command, ENABLE_WIFI_AP, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            command.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            command.setValue(ENABLE_WIFI_AP);
            result = callbackGatt.writeCharacteristic(command)
                    ? BluetoothGatt.GATT_SUCCESS
                    : BluetoothGatt.GATT_FAILURE;
        }

        if (result != BluetoothGatt.GATT_SUCCESS) {
            listener.onError("Không gửi được lệnh BLE Set AP Control, GATT=" + result + ".");
            return;
        }

        waitingForCommandResponse = true;
        mainHandler.removeCallbacks(commandTimeout);
        mainHandler.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS);
    }

    private void handleCommandResponse(@NonNull byte[] value) {
        if (value.length < 3 || value[1] != 0x17) {
            return;
        }

        waitingForCommandResponse = false;
        mainHandler.removeCallbacks(commandTimeout);
        int status = value[2] & 0xFF;
        if (status == 0) {
            BluetoothGatt currentGatt = gatt;
            String name = currentGatt == null ? "GoPro" : safeName(currentGatt.getDevice());
            mainHandler.post(() -> listener.onWifiApEnabled(name));
        } else {
            mainHandler.post(
                    () -> listener.onError(
                            "HERO8 từ chối lệnh bật Wi-Fi AP, status=" + status + "."));
        }
    }

    private void stopScan() {
        mainHandler.removeCallbacks(scanTimeout);
        if (scanRunning && scanner != null) {
            scanner.stopScan(scanCallback);
        }
        scanRunning = false;
    }

    private void registerBondReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(bondReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered by Android.
        }
        receiverRegistered = false;
    }

    @NonNull
    private static String safeName(@NonNull BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.isBlank() ? device.getAddress() : name;
    }

    private void closeGattOnly() {
        waitingForCommandResponse = false;
        mainHandler.removeCallbacks(commandTimeout);
        BluetoothGatt currentGatt = gatt;
        gatt = null;
        if (currentGatt != null) {
            currentGatt.disconnect();
            currentGatt.close();
        }
    }

    @Override
    public void close() {
        stopScan();
        pendingBondDevice = null;
        unregisterBondReceiver();
        closeGattOnly();
    }
}
