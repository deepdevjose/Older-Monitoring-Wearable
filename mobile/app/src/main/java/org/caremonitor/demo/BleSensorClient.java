package org.caremonitor.demo;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * Foreground BLE notification client. The Activity owns permission requests and
 * the user-visible device choice. All operations and listener calls are queued
 * onto the main thread. No sensor payloads or credentials are logged.
 */
public final class BleSensorClient implements SensorTransport {
    public static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final String EXPECTED_NAME = "CareMonitor-elder-demo-01";
    private static final long SCAN_MS = 20_000L;
    private static final long PHASE_TIMEOUT_MS = 15_000L;

    /** Retained for source compatibility with older BLE-only callers. */
    public interface Listener extends SensorTransport.Listener { }

    private final Context context;
    private final SensorTransport.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NdjsonFramer framer = new NdjsonFramer();
    // Notifications can arrive before Android acknowledges the CCCD write.
    // Buffer a bounded number of complete records without exposing them as ready.
    private final ArrayDeque<String> subscriptionLines = new ArrayDeque<String>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Runnable scanTimeout;
    private Runnable phaseTimeout;
    private BluetoothGatt currentGatt;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private BluetoothGattDescriptor pendingDescriptor;
    private long generation;
    private String phase = "disconnected";
    private boolean closed;
    private volatile boolean ready;

    public BleSensorClient(Context context, SensorTransport.Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("Context and listener are required");
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isReady() { return ready; }

    public void startScan() { main.post(() -> startScanOnMain()); }
    public void stopScan() { main.post(() -> stopScanOnMain(true)); }
    public void connect(String address) { main.post(() -> connectOnMain(address)); }
    public void disconnect() { main.post(() -> disconnectOnMain(false)); }
    public void close() { main.post(() -> disconnectOnMain(true)); }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean usable() {
        if (closed) { listener.onError("The Bluetooth client is closed."); return false; }
        return true;
    }

    private void state(String next) {
        phase = next;
        listener.onState(next);
    }

    private void startScanOnMain() {
        if (!usable()) return;
        if (currentGatt != null) {
            listener.onError("Disconnect the current device before scanning again.");
            return;
        }
        stopScanOnMain(false);
        try {
            BluetoothAdapter adapter = adapter();
            if (adapter == null || !adapter.isEnabled()) {
                listener.onError("Turn on Bluetooth before scanning.");
                state("scan_stopped");
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                listener.onError("Bluetooth scanning is unavailable. Turn Bluetooth off and on, then retry.");
                state("scan_stopped");
                return;
            }
            ScanCallback callback = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    main.post(() -> deliverScan(this, result));
                }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult result : results) main.post(() -> deliverScan(this, result));
                }
                @Override public void onScanFailed(int errorCode) {
                    main.post(() -> {
                        if (scanCallback != this || closed) return;
                        stopScanOnMain(false);
                        state("scan_stopped");
                        listener.onError("Bluetooth scan failed (code " + errorCode + "). Wait a moment and retry.");
                    });
                }
            };
            scanCallback = callback;
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            state("scanning");
            scanTimeout = () -> {
                if (scanCallback == callback && !closed) stopScanOnMain(true);
            };
            main.postDelayed(scanTimeout, SCAN_MS);
        } catch (SecurityException exception) {
            scanFailure("Bluetooth permission is required to scan. Grant Nearby devices permission and retry.");
        } catch (RuntimeException exception) {
            scanFailure("Bluetooth scanning could not start. Check that Bluetooth is enabled and retry.");
        }
    }

    private void deliverScan(ScanCallback callback, ScanResult result) {
        if (closed || scanCallback != callback || result == null || result.getDevice() == null) return;
        try {
            ScanRecord record = result.getScanRecord();
            String name = record == null ? null : record.getDeviceName();
            if (name == null || name.length() == 0) name = result.getDevice().getName();
            if (name == null || name.length() == 0) name = "CareMonitor BLE device";
            listener.onDevice(result.getDevice().getAddress(), name, result.getRssi());
        } catch (SecurityException exception) {
            scanFailure("Bluetooth permission was revoked. Grant Nearby devices permission and scan again.");
        }
    }

    private void scanFailure(String message) {
        stopScanOnMain(false);
        state("scan_stopped");
        listener.onError(message);
    }

    private void stopScanOnMain(boolean reportState) {
        ScanCallback callback = scanCallback;
        BluetoothLeScanner previous = scanner;
        scanCallback = null;
        scanner = null;
        if (scanTimeout != null) main.removeCallbacks(scanTimeout);
        scanTimeout = null;
        if (callback != null && previous != null) {
            try { previous.stopScan(callback); }
            catch (SecurityException ignored) { /* The callback is invalidated even if permission was revoked. */ }
            catch (RuntimeException ignored) { /* Adapter may have been switched off. */ }
        }
        if (reportState && !closed && callback != null) state("scan_stopped");
    }

    private void connectOnMain(String address) {
        if (!usable()) return;
        stopScanOnMain(false);
        releaseGatt();
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            state("disconnected");
            listener.onError("Select a valid device from the Bluetooth scan list.");
            return;
        }
        final long session = generation;
        try {
            BluetoothAdapter adapter = adapter();
            if (adapter == null || !adapter.isEnabled()) {
                state("disconnected");
                listener.onError("Turn on Bluetooth before connecting.");
                return;
            }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            state("connecting");
            currentGatt = device.connectGatt(context, false, callback(session), BluetoothDevice.TRANSPORT_LE);
            if (currentGatt == null) { fail("Bluetooth connection could not start. Retry the connection."); return; }
            armTimeout("Connection timed out. Keep the device nearby and try again.");
        } catch (SecurityException exception) {
            fail("Bluetooth permission is required to connect. Grant Nearby devices permission and retry.");
        } catch (RuntimeException exception) {
            fail("Bluetooth connection could not start. Check Bluetooth and retry.");
        }
    }

    private BluetoothGattCallback callback(final long session) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                main.post(() -> {
                    if (!active(gatt, session)) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("Bluetooth connection ended (code " + status + "). Reconnect the device.");
                    } else if (newState == BluetoothProfile.STATE_CONNECTED && "connecting".equals(phase)) {
                        try {
                            state("discovering");
                            if (!gatt.discoverServices()) { fail("Could not discover the sensor service. Reconnect the device."); return; }
                            armTimeout("Sensor service discovery timed out. Reconnect the device.");
                        } catch (SecurityException exception) {
                            fail("Bluetooth permission was revoked. Grant permission and reconnect.");
                        } catch (RuntimeException exception) {
                            fail("Sensor service discovery failed. Reconnect the device.");
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        releaseGatt();
                        state("disconnected");
                    }
                });
            }

            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                main.post(() -> {
                    if (!active(gatt, session) || !"discovering".equals(phase)) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) { fail("Sensor service discovery failed (code " + status + ")."); return; }
                    subscribe(gatt);
                });
            }

            @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                main.post(() -> {
                    if (!active(gatt, session) || !"subscribing".equals(phase) || descriptor != pendingDescriptor) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("The device did not enable sensor notifications (code " + status + "). Reconnect and retry.");
                        return;
                    }
                    cancelPhaseTimeout();
                    pendingDescriptor = null;
                    ready = true;
                    state("ready");
                    while (!subscriptionLines.isEmpty()) listener.onJsonLine(subscriptionLines.removeFirst());
                });
            }

            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                // Android 13+ delivers the immutable-value overload below. Reading
                // both would duplicate notification fragments on those releases.
                if (Build.VERSION.SDK_INT >= 33) return;
                byte[] value = characteristic.getValue();
                final byte[] copy = value == null ? null : value.clone();
                main.post(() -> notification(gatt, session, characteristic, copy));
            }

            @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                    BluetoothGattCharacteristic characteristic, byte[] value) {
                final byte[] copy = value == null ? null : value.clone();
                main.post(() -> notification(gatt, session, characteristic, copy));
            }
        };
    }

    @SuppressWarnings("deprecation")
    private void subscribe(BluetoothGatt gatt) {
        try {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            notifyCharacteristic = service == null ? null : service.getCharacteristic(TX_UUID);
            if (notifyCharacteristic == null ||
                    (notifyCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                fail("This device does not provide the CareMonitor sensor notification service.");
                return;
            }
            pendingDescriptor = notifyCharacteristic.getDescriptor(CCCD_UUID);
            if (pendingDescriptor == null) { fail("The sensor notification descriptor is missing. Check the ESP32 firmware."); return; }
            framer.reset();
            subscriptionLines.clear();
            if (!gatt.setCharacteristicNotification(notifyCharacteristic, true)) {
                fail("Could not register sensor notifications. Reconnect and retry.");
                return;
            }
            state("subscribing");
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) {
                accepted = gatt.writeDescriptor(pendingDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                accepted = pendingDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        && gatt.writeDescriptor(pendingDescriptor);
            }
            if (!accepted) { fail("Could not enable sensor notifications. Reconnect and retry."); return; }
            // Becoming connected is not enough: only the descriptor write callback
            // marks this client ready to accept complete sensor records.
            armTimeout("Enabling sensor notifications timed out. Reconnect and retry.");
        } catch (SecurityException exception) {
            fail("Bluetooth permission was revoked. Grant permission and reconnect.");
        } catch (RuntimeException exception) {
            fail("Could not configure the sensor connection. Reconnect and retry.");
        }
    }

    private boolean active(BluetoothGatt gatt, long session) {
        return !closed && currentGatt == gatt && generation == session;
    }

    private void notification(BluetoothGatt gatt, long session,
            BluetoothGattCharacteristic characteristic, byte[] value) {
        if (!active(gatt, session) || (!ready && !"subscribing".equals(phase)) ||
                characteristic == null || !TX_UUID.equals(characteristic.getUuid())) return;
        for (String line : framer.feed(value)) {
            if (ready) listener.onJsonLine(line);
            else {
                if (subscriptionLines.size() == 4) subscriptionLines.removeFirst();
                subscriptionLines.addLast(line);
            }
        }
    }

    private void armTimeout(String message) {
        cancelPhaseTimeout();
        final long session = generation;
        final BluetoothGatt expected = currentGatt;
        phaseTimeout = () -> {
            if (active(expected, session)) fail(message);
        };
        main.postDelayed(phaseTimeout, PHASE_TIMEOUT_MS);
    }

    private void cancelPhaseTimeout() {
        if (phaseTimeout != null) main.removeCallbacks(phaseTimeout);
        phaseTimeout = null;
    }

    private void fail(String message) {
        releaseGatt();
        state("disconnected");
        listener.onError(message);
    }

    private void disconnectOnMain(boolean permanently) {
        if (closed) return;
        stopScanOnMain(false);
        releaseGatt();
        closed = permanently;
        state(permanently ? "closed" : "disconnected");
    }

    private void releaseGatt() {
        generation++;
        ready = false;
        framer.reset();
        subscriptionLines.clear();
        cancelPhaseTimeout();
        BluetoothGatt previous = currentGatt;
        currentGatt = null; // Invalidate queued callbacks before closing Android resources.
        notifyCharacteristic = null;
        pendingDescriptor = null;
        if (previous != null) {
            try { previous.disconnect(); }
            catch (SecurityException ignored) { }
            catch (RuntimeException ignored) { }
            try { previous.close(); }
            catch (SecurityException ignored) { }
            catch (RuntimeException ignored) { }
        }
    }
}
