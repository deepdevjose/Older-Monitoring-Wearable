package org.caremonitor.demo;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Secure Classic Bluetooth SPP plus per-wearable mutual application authentication.
 * Pairing stays under Android control. No serial-monitor confirmation is needed.
 */
public final class SppSensorClient implements SensorTransport {
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    public static final String EXPECTED_NAME = "ElderSensors-elder-demo-01";
    public static final int UNKNOWN_RSSI = Integer.MIN_VALUE;
    private static final long SCAN_MS = 20_000L;
    private static final long CONNECT_MS = 45_000L;
    private static final long AUTH_MS = 10_000L;
    private static final int DELIVERY_BATCH = 4;

    private final Context context;
    private final SensorTransport.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SessionLineBuffer lines = new SessionLineBuffer(16);
    private volatile BluetoothSocket currentSocket;
    private volatile long generation;
    private volatile boolean closed;
    private volatile boolean ready;
    private Thread worker;
    private boolean scanning;
    private boolean registered;
    private boolean drainScheduled;
    private Runnable scanTimeout;
    private Runnable connectTimeout;

    public SppSensorClient(Context context, SensorTransport.Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("Context and listener are required");
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @Override public boolean isReady() { return ready; }
    @Override public void startScan() { main.post(() -> startScanOnMain()); }
    @Override public void stopScan() { main.post(() -> stopScanOnMain(true)); }
    @Override public void connect(String address) { main.post(() -> connectOnMain(address)); }
    @Override public void disconnect() { main.post(() -> disconnectOnMain(false)); }
    @Override public void close() { main.post(() -> disconnectOnMain(true)); }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed || intent == null) return;
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int next = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (next == BluetoothAdapter.STATE_OFF || next == BluetoothAdapter.STATE_TURNING_OFF) {
                    boolean hadWork = scanning || currentSocket != null;
                    stopScanOnMain(false);
                    releaseSocket();
                    unregisterIfIdle();
                    if (hadWork) {
                        listener.onState("disconnected");
                        listener.onError("Bluetooth was turned off. Turn it on and reconnect the ESP32.");
                    }
                }
            } else if (BluetoothDevice.ACTION_FOUND.equals(action) && scanning) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= 33) device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                else device = legacyDevice(intent);
                int rssi = intent.hasExtra(BluetoothDevice.EXTRA_RSSI)
                        ? intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE) : UNKNOWN_RSSI;
                if (rssi == Short.MIN_VALUE) rssi = UNKNOWN_RSSI;
                deliverDevice(device, rssi);
            }
        }
    };

    @SuppressWarnings("deprecation")
    private static BluetoothDevice legacyDevice(Intent intent) {
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private void ensureReceiver() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        // Bluetooth broadcasts can originate from the highly privileged Bluetooth
        // process, outside the system UID. NOT_EXPORTED would miss these on API 33+.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
        registered = true;
    }

    private void unregisterIfIdle() {
        if (!registered || scanning || currentSocket != null) return;
        registered = false;
        try { context.unregisterReceiver(receiver); }
        catch (IllegalArgumentException ignored) { /* Already unregistered by platform cleanup. */ }
    }

    private void startScanOnMain() {
        if (closed) return;
        if (currentSocket != null) {
            listener.onError("Disconnect the current device before scanning again.");
            return;
        }
        stopScanOnMain(false);
        try {
            BluetoothAdapter adapter = adapter();
            if (adapter == null || !adapter.isEnabled()) {
                scanFailure("Turn on Bluetooth before scanning.");
                return;
            }
            ensureReceiver();
            scanning = true;
            listener.onState("scanning");
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) for (BluetoothDevice device : bonded) deliverDevice(device, UNKNOWN_RSSI);
            // Discovery is adapter-wide. Cancel an unfinished previous scan before
            // starting this bounded user-requested discovery window.
            adapter.cancelDiscovery();
            if (!adapter.startDiscovery()) {
                scanFailure("Classic Bluetooth discovery could not start. You may still select a listed paired ESP32, or retry shortly.");
                return;
            }
            // Keep a fixed scan window. A delayed DISCOVERY_FINISHED broadcast
            // from cancelDiscovery() could otherwise end this new scan early.
            scanTimeout = () -> { if (!closed && scanning) stopScanOnMain(true); };
            main.postDelayed(scanTimeout, SCAN_MS);
        } catch (SecurityException exception) {
            scanFailure("Bluetooth permission is required to scan. Grant Nearby devices permission and retry.");
        } catch (RuntimeException exception) {
            scanFailure("Classic Bluetooth discovery could not start. Check Bluetooth and retry.");
        }
    }

    private void deliverDevice(BluetoothDevice device, int rssi) {
        if (closed || !scanning || device == null) return;
        try {
            String name = device.getName();
            if (!candidate(name) || device.getType() == BluetoothDevice.DEVICE_TYPE_LE) return;
            String address = device.getAddress();
            if (BluetoothAdapter.checkBluetoothAddress(address)) listener.onDevice(address, name, rssi);
        } catch (SecurityException exception) {
            scanFailure("Bluetooth permission was revoked. Grant Nearby devices permission and scan again.");
        } catch (RuntimeException ignored) { /* A disappearing discovery result can be skipped. */ }
    }

    private static boolean candidate(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return EXPECTED_NAME.equals(name) || lower.startsWith("eldersensors-") || lower.contains("esp32");
    }

    private void scanFailure(String message) {
        stopScanOnMain(false);
        if (closed) return;
        listener.onState("scan_stopped");
        listener.onError(message);
    }

    private void stopScanOnMain(boolean reportState) {
        boolean wasScanning = scanning;
        scanning = false;
        if (scanTimeout != null) main.removeCallbacks(scanTimeout);
        scanTimeout = null;
        if (wasScanning) {
            try {
                BluetoothAdapter adapter = adapter();
                if (adapter != null) adapter.cancelDiscovery();
            } catch (SecurityException ignored) { /* Local scan callbacks are already disabled. */ }
            catch (RuntimeException ignored) { /* Adapter may be shutting down. */ }
        }
        unregisterIfIdle();
        if (reportState && !closed && wasScanning) listener.onState("scan_stopped");
    }

    private void connectOnMain(String address) {
        if (closed) return;
        stopScanOnMain(false);
        releaseSocket();
        if (address != null) address = address.trim().toUpperCase(Locale.ROOT);
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            fail("Select an ESP32 from the scan list or enter its valid Bluetooth MAC address.");
            return;
        }
        final long session = generation;
        try {
            String setupKey=WearableKeys.load(context,address);
            if(setupKey.isEmpty()) {
                failAuthentication("Add your wearable's setup key in the app before connecting.");
                return;
            }
            final WearableAuth authentication=new WearableAuth(setupKey);
            BluetoothAdapter adapter = adapter();
            if (adapter == null || !adapter.isEnabled()) {
                fail("Turn on Bluetooth before connecting.");
                return;
            }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
                fail("This device supports BLE only. Use BLE mode with compatible CareMonitor firmware.");
                return;
            }
            ensureReceiver();
            adapter.cancelDiscovery();
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            currentSocket = socket;
            listener.onState("connecting");
            connectTimeout = () -> {
                if (active(socket, session)) fail("Bluetooth connection timed out. Confirm any pairing request on your phone. If another phone is connected, disconnect it first.");
            };
            main.postDelayed(connectTimeout, CONNECT_MS);
            worker = new Thread(() -> connectAndRead(socket, session, authentication), "care-monitor-spp");
            worker.setDaemon(true);
            worker.start();
        } catch (SecurityException exception) {
            fail("Bluetooth permission is required to connect. Grant Nearby devices permission and retry.");
        } catch (IOException exception) {
            fail("The secure Bluetooth connection could not be opened. Check that your wearable is running Classic Bluetooth firmware.");
        } catch (RuntimeException exception) {
            fail("Classic Bluetooth connection could not start. Check Bluetooth and retry.");
        } catch (Exception exception) {
            failAuthentication("Your private wearable key could not be opened. Update its setup key in the app and retry.");
        }
    }

    private boolean active(BluetoothSocket socket, long session) {
        return !closed && currentSocket == socket && generation == session;
    }

    private void connectAndRead(BluetoothSocket socket, long session, WearableAuth authentication) {
        boolean connected=false;
        try {
            if (!active(socket, session)) return;
            socket.connect();
            connected=true;
            if (!active(socket, session)) return;
            main.post(() -> {
                if (!active(socket, session)) return;
                cancelConnectTimeout();
                listener.onState("authenticating");
                connectTimeout=()->{if(active(socket,session))failAuthentication("Wearable authorisation timed out. Check its setup key and reconnect from the app.");};
                main.postDelayed(connectTimeout,AUTH_MS);
            });
            InputStream input = socket.getInputStream();
            OutputStream output=socket.getOutputStream();
            writeCommand(output,authentication.hello());
            // Framing state belongs to this socket only. It can never be reused by
            // a subsequent connection, even when an old blocked read wakes late.
            NdjsonFramer framer = new NdjsonFramer();
            byte[] buffer = new byte[1024];
            while (active(socket, session)) {
                int count = input.read(buffer);
                if (count < 0) throw new IOException("end of stream");
                if (count == 0) continue;
                if (!active(socket, session)) return;
                for (String line : framer.feed(Arrays.copyOf(buffer, count))) {
                    if(!authentication.isReady()) {
                        String response=authentication.receive(line);
                        if(response!=null)writeCommand(output,response);
                        if(authentication.isReady())main.post(()->{
                            if(!active(socket,session))return;
                            cancelConnectTimeout();ready=true;listener.onState("ready");
                        });
                        continue;
                    }
                    if(line.startsWith("CM1 "))throw new SecurityException("Unexpected authentication command");
                    if (!enqueue(socket, session, line)) return;
                }
            }
        } catch (SecurityException exception) {
            postAuthenticationFailure(socket,session,"Wearable authorisation failed. Update its setup key in the app and try again.");
        } catch (IOException exception) {
            if(connected&&!authentication.isReady())postAuthenticationFailure(socket,session,"The wearable closed the connection before authorisation. Check its setup key and disconnect any other phone first.");
            else postFailure(socket, session, "The Bluetooth link ended. Keep the ESP32 nearby, confirm any pairing request on your phone, and disconnect any other phone using the wearable.");
        } catch (RuntimeException exception) {
            postFailure(socket, session, "The Classic Bluetooth link could not read sensor data. Reconnect the ESP32.");
        } finally {
            closeSocket(socket);
        }
    }

    private static void writeCommand(OutputStream output,String line)throws IOException {
        output.write((line+"\n").getBytes(StandardCharsets.UTF_8));output.flush();
    }

    private void postAuthenticationFailure(BluetoothSocket socket,long session,String message) {
        main.post(()->{if(active(socket,session))failAuthentication(message);});
    }

    private void failAuthentication(String message) {
        if(closed)return;
        releaseSocket();unregisterIfIdle();
        listener.onState("authentication_failed");listener.onError(message);
    }

    private boolean enqueue(BluetoothSocket socket, long session, String line) {
        synchronized (lines) {
            if (!active(socket, session)) return false;
            SessionLineBuffer.Result result = lines.offer(session, line);
            if (result == SessionLineBuffer.Result.STALE) return false;
            if (result == SessionLineBuffer.Result.FULL) {
                postFailure(socket, session, "Sensor data arrived faster than the app could process it. The bounded buffer was full; reconnect the ESP32.");
                return false;
            }
            if (!drainScheduled) {
                drainScheduled = true;
                main.post(() -> drain(socket, session));
            }
            return true;
        }
    }

    private void drain(BluetoothSocket socket, long session) {
        List<String> batch;
        synchronized (lines) {
            if (!active(socket, session)) return;
            batch = lines.take(session, DELIVERY_BATCH);
        }
        for (String line : batch) {
            if (!active(socket, session) || !ready) return;
            listener.onJsonLine(line);
        }
        synchronized (lines) {
            if (!active(socket, session)) return;
            if (lines.hasPending(session)) main.post(() -> drain(socket, session));
            else drainScheduled = false;
        }
    }

    private void postFailure(BluetoothSocket socket, long session, String message) {
        main.post(() -> { if (active(socket, session)) fail(message); });
    }

    private void cancelConnectTimeout() {
        if (connectTimeout != null) main.removeCallbacks(connectTimeout);
        connectTimeout = null;
    }

    private void fail(String message) {
        if (closed) return;
        releaseSocket();
        unregisterIfIdle();
        listener.onState("disconnected");
        listener.onError(message);
    }

    private void disconnectOnMain(boolean permanently) {
        if (closed) return;
        stopScanOnMain(false);
        releaseSocket();
        closed = permanently;
        unregisterIfIdle();
        listener.onState(permanently ? "closed" : "disconnected");
    }

    private void releaseSocket() {
        generation++;
        ready = false;
        cancelConnectTimeout();
        BluetoothSocket previous = currentSocket;
        currentSocket = null; // Invalidate callbacks before aborting blocked IO.
        synchronized (lines) {
            lines.reset(generation);
            drainScheduled = false;
        }
        Thread previousWorker = worker;
        worker = null;
        closeSocket(previous);
        if (previousWorker != null) previousWorker.interrupt();
    }

    private static void closeSocket(BluetoothSocket socket) {
        if (socket == null) return;
        try { socket.close(); }
        catch (IOException ignored) { }
        catch (RuntimeException ignored) { }
    }
}
