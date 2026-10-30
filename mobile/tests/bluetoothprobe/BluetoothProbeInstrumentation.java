package org.caremonitor.demo;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Isolated Bluetooth-only acceptance checks. It never contacts a server or logs samples. */
public final class BluetoothProbeInstrumentation extends Instrumentation {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final String EXPECTED_NAME = "ElderSensors-elder-demo-01";
    private static final String DEVICE_ID = "elder-demo-01";
    private static final int LINE_LIMIT = 4096;
    private Bundle arguments;
    private BluetoothAdapter adapter;
    private BluetoothDevice device;
    private byte[] key;
    private int checks;
    private int frames;
    private String phase = "initialization";

    @Override public void onCreate(Bundle input) {
        super.onCreate(input);
        arguments = input == null ? new Bundle() : new Bundle(input);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int exit = Activity.RESULT_CANCELED;
        File keyFile = new File(getTargetContext().getFilesDir(), "wearable-key.private");
        try {
            BluetoothManager manager = (BluetoothManager)getTargetContext().getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = manager == null ? null : manager.getAdapter();
            require(adapter != null && adapter.isEnabled(), "BLUETOOTH_DISABLED");
            phase = "device_selection";
            device = selectDevice();
            require(device != null, "DEVICE_NOT_FOUND");
            adapter.cancelDiscovery();
            if ("true".equals(arguments.getString("pair_only"))) {
                phase = "system_pairing";
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    require(device.createBond(), "PAIRING_NOT_STARTED");
                    long end = now() + 55_000;
                    while (now() < end && device.getBondState() != BluetoothDevice.BOND_BONDED) SystemClock.sleep(200);
                }
                require(device.getBondState() == BluetoothDevice.BOND_BONDED, "NEEDS_SYSTEM_PAIRING_CONFIRMATION");
                result.putString("result", "PASS");
                result.putString("scope", "system_pairing_only");
                exit = Activity.RESULT_OK;
            } else {
                phase = "private_key";
                key = readKey(keyFile);
                require(keyFile.delete(), "PRIVATE_KEY_CLEANUP_FAILED");
                phase = "unauthenticated_timeout";
                try (Link link = connect(25_000)) {
                    long begin = now();
                    expectDenied(link, begin + 12_000);
                    result.putLong("unauthenticated_disconnect_ms", now() - begin);
                    result.putBoolean("unauthenticated_timeout", true);
                }
                pauseBetween();
                phase = "wrong_key";
                try (Link link = connect(25_000)) {
                    Handshake handshake = startHandshake(link);
                    byte[] wrong = key.clone();
                    wrong[0] ^= 1;
                    link.send("CM1 AUTH " + proof(wrong, "phone", handshake.clientNonce, handshake.serverNonce));
                    Arrays.fill(wrong, (byte)0);
                    expectDenied(link, now() + 12_000);
                    result.putBoolean("wrong_key_rejected", true);
                }
                pauseBetween();
                phase = "mutual_authentication";
                String previousPhoneProof;
                String previousServerNonce;
                try (Link owner = connect(25_000)) {
                    Handshake handshake = startHandshake(owner);
                    previousPhoneProof = handshake.phoneProof;
                    previousServerNonce = handshake.serverNonce;
                    finishHandshake(owner, handshake, previousPhoneProof);
                    receiveFrames(owner, 3, 6_000);
                    result.putBoolean("mutual_authentication", true);
                    if ("true".equals(arguments.getString("external_contender"))) {
                        phase = "second_radio_exclusion";
                        Bundle signal = new Bundle();
                        signal.putString("phase", "external_contender_ready");
                        // Transport-only coordination. The host consumes this address
                        // in memory and excludes it from all logs and evidence.
                        signal.putString("peer_address", device.getAddress());
                        sendStatus(2, signal);
                        holdOwner(owner, 15_000);
                        receiveFrames(owner, 3, 6_000);
                        result.putBoolean("owner_survived_external_contender", true);
                        result.putString("exclusivity_scope", "Android owner and separate Linux physical Bluetooth radio");
                    } else {
                        phase = "second_socket_exclusion";
                        // A second RFCOMM socket is a separate transport client from the same
                        // physical phone. This proves exclusivity at the SPP socket boundary;
                        // it is not represented as a second physical handset test.
                        Link contender = null;
                        boolean rejected = false;
                        try {
                            contender = connect(5_000);
                            contender.send("CM1 HELLO " + randomNonce());
                            long end = now() + 3_000;
                            while (now() < end) {
                                String line = contender.poll(Math.min(200, end - now()));
                                require(line == null || !line.startsWith("{"), "SECOND_SOCKET_RECEIVED_SENSOR_DATA");
                                require(line == null || !line.startsWith("CM1 READY"), "SECOND_SOCKET_AUTHENTICATED");
                                require(line == null || !line.startsWith("CM1 CHALLENGE"), "SECOND_SOCKET_WAS_ADMITTED");
                                if (contender.ended()) { rejected = true; break; }
                            }
                            rejected |= contender.ended();
                        } catch (IOException expected) {
                            rejected = true;
                        } finally {
                            if (contender != null) contender.close();
                        }
                        require(rejected, "SECOND_SOCKET_NOT_REJECTED");
                        receiveFrames(owner, 3, 6_000);
                        result.putBoolean("second_socket_rejected", true);
                        result.putBoolean("owner_survived_second_socket", true);
                        result.putString("exclusivity_scope", "two RFCOMM sockets on one physical handset");
                    }
                }
                pauseBetween();
                phase = "replayed_proof";
                try (Link link = connect(25_000)) {
                    Handshake handshake = startHandshake(link);
                    require(!previousServerNonce.equals(handshake.serverNonce), "SERVER_NONCE_REUSED");
                    link.send("CM1 AUTH " + previousPhoneProof);
                    expectDenied(link, now() + 12_000);
                    result.putBoolean("stale_proof_rejected", true);
                }
                previousPhoneProof = null;
                previousServerNonce = null;
                pauseBetween();
                phase = "reconnect_after_owner_disconnect";
                try (Link link = connect(25_000)) {
                    Handshake handshake = startHandshake(link);
                    finishHandshake(link, handshake, handshake.phoneProof);
                    receiveFrames(link, 3, 6_000);
                    result.putBoolean("reconnect_after_owner_disconnect", true);
                }
                result.putString("result", "PASS");
                result.putString("scope", "physical Bluetooth SPP, no account, backend or retained sample values");
                exit = Activity.RESULT_OK;
            }
        } catch (ProbeFailure failure) {
            result.putString("result", "FAIL");
            result.putString("detail", failure.code);
        } catch (SecurityException failure) {
            result.putString("result", "FAIL");
            result.putString("detail", "SECURITY_CHECK_FAILED");
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            // Exception messages may contain peer identity or protocol material.
            result.putString("detail", failure.getClass().getSimpleName());
        } finally {
            if (key != null) Arrays.fill(key, (byte)0);
            key = null;
            if (keyFile.exists() && !keyFile.delete()) result.putString("private_key_cleanup", "FAILED");
            else result.putString("private_key_cleanup", "PASS");
            result.putString("phase", phase);
            result.putInt("checks", checks);
            result.putInt("sensor_frames_discarded", frames);
            result.putBoolean("server_access", false);
            result.putBoolean("sample_values_retained", false);
        }
        finish(exit, result);
    }

    private BluetoothDevice selectDevice() throws Exception {
        String address = arguments.getString("address", "");
        if (!address.isEmpty()) {
            require(BluetoothAdapter.checkBluetoothAddress(address), "INVALID_DEVICE_ADDRESS");
            return adapter.getRemoteDevice(address);
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        BluetoothDevice selected = null;
        if (bonded != null) for (BluetoothDevice candidate : bonded) {
            if (EXPECTED_NAME.equals(candidate.getName())) {
                require(selected == null, "MULTIPLE_MATCHING_DEVICES");
                selected = candidate;
            }
        }
        if (selected != null) return selected;
        final BlockingQueue<BluetoothDevice> found = new ArrayBlockingQueue<>(4);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
                BluetoothDevice candidate;
                if (Build.VERSION.SDK_INT >= 33) candidate = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                else candidate = legacyDevice(intent);
                if (candidate != null && EXPECTED_NAME.equals(candidate.getName())) found.offer(candidate);
            }
        };
        Context context = getTargetContext();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
        try {
            adapter.cancelDiscovery();
            require(adapter.startDiscovery(), "DISCOVERY_NOT_STARTED");
            return found.poll(20, TimeUnit.SECONDS);
        } finally {
            adapter.cancelDiscovery();
            context.unregisterReceiver(receiver);
        }
    }

    @SuppressWarnings("deprecation") private static BluetoothDevice legacyDevice(Intent intent) {
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private Link connect(long timeout) throws Exception {
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
        final IOException[] error = new IOException[1];
        Thread worker = new Thread(() -> {
            try { socket.connect(); }
            catch (IOException failure) { error[0] = failure; }
        }, "bt-probe-connect");
        worker.setDaemon(true);
        worker.start();
        worker.join(timeout);
        if (worker.isAlive()) {
            socket.close();
            worker.join(1000);
            throw new IOException("connection timeout");
        }
        if (error[0] != null) { socket.close(); throw error[0]; }
        return new Link(socket);
    }

    private Handshake startHandshake(Link link) throws Exception {
        // Exact production authentication class, compiled unchanged into this isolated APK.
        WearableAuth auth = new WearableAuth(DEVICE_ID, hex(key));
        String hello = auth.hello();
        require(hello.matches("CM1 HELLO [0-9a-f]{32}"), "MALFORMED_CLIENT_HELLO");
        String clientNonce = hello.substring("CM1 HELLO ".length());
        link.send(hello);
        String line = awaitControl(link, "CM1 CHALLENGE ", now() + 5_000);
        String answer = auth.answer(line);
        String[] fields = line.split(" ", -1);
        require(fields.length == 5 && DEVICE_ID.equals(fields[2])
                && fields[3].matches("[0-9a-f]{32}") && fields[4].matches("[0-9a-f]{64}"), "MALFORMED_BOARD_CHALLENGE");
        require(answer.matches("CM1 AUTH [0-9a-f]{64}"), "MALFORMED_CLIENT_PROOF");
        return new Handshake(auth, clientNonce, fields[3], answer.substring("CM1 AUTH ".length()));
    }

    private void finishHandshake(Link link, Handshake handshake, String phoneProof) throws Exception {
        link.send("CM1 AUTH " + phoneProof);
        String line = awaitControl(link, "CM1 READY ", now() + 5_000);
        require(handshake.auth.verifyReady(line), "READY_PROOF_INVALID");
    }

    private String awaitControl(Link link, String prefix, long deadline) throws Exception {
        while (now() < deadline) {
            String line = link.poll(Math.min(200, deadline - now()));
            if (line != null) {
                require(!line.startsWith("{"), "UNAUTHENTICATED_SENSOR_DATA");
                require(line.startsWith(prefix), "UNEXPECTED_PROTOCOL_MESSAGE");
                return line;
            }
            require(!link.ended(), "DISCONNECTED_DURING_AUTHENTICATION");
        }
        throw new ProbeFailure("AUTHENTICATION_RESPONSE_TIMEOUT");
    }

    private void expectDenied(Link link, long deadline) throws Exception {
        while (now() < deadline) {
            String line = link.poll(Math.min(200, deadline - now()));
            if (line != null) {
                require(!line.startsWith("{"), "UNAUTHENTICATED_SENSOR_DATA");
                require(!line.startsWith("CM1 READY"), "UNAUTHORIZED_CLIENT_ACCEPTED");
            }
            if (link.ended()) { require(true, "DENIED"); return; }
        }
        throw new ProbeFailure("UNAUTHORIZED_CONNECTION_NOT_CLOSED_WITHIN_12_SECONDS");
    }

    private void receiveFrames(Link link, int minimum, long timeout) throws Exception {
        long end = now() + timeout;
        int count = 0;
        while (now() < end && count < minimum) {
            String line = link.poll(Math.min(200, end - now()));
            if (line != null) {
                require(line.startsWith("{"), "UNEXPECTED_AUTHENTICATED_MESSAGE");
                // Parsing checks framing and peer identity. Values leave scope immediately;
                // only the count is emitted in the instrumentation result.
                JSONObject sample = new JSONObject(line);
                require(DEVICE_ID.equals(sample.optString("device_id")), "SAMPLE_DEVICE_ID_MISMATCH");
                count++;
                frames++;
            }
            require(!link.ended(), "OWNER_CONNECTION_LOST");
        }
        require(count >= minimum, "SENSOR_STREAM_NOT_RECEIVED");
    }

    private void holdOwner(Link owner, long duration) throws Exception {
        long end = now() + duration;
        int count = 0;
        long lastFrame = now();
        while (now() < end) {
            String line = owner.poll(Math.min(200, end - now()));
            if (line != null) {
                require(line.startsWith("{"), "UNEXPECTED_AUTHENTICATED_MESSAGE");
                JSONObject sample = new JSONObject(line);
                require(DEVICE_ID.equals(sample.optString("device_id")), "SAMPLE_DEVICE_ID_MISMATCH");
                count++;
                frames++;
                lastFrame = now();
            }
            require(!owner.ended(), "OWNER_CONNECTION_LOST");
        }
        require(count >= 8 && now() - lastFrame < 2_500, "OWNER_STREAM_STALLED_DURING_EXTERNAL_ATTEMPT");
    }

    private byte[] readKey(File file) throws Exception {
        require(file.isFile() && file.length() <= 34, "PRIVATE_KEY_FILE_MISSING_OR_INVALID");
        byte[] encoded = new byte[(int)file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = 0;
            while (read < encoded.length) {
                int got = input.read(encoded, read, encoded.length - read);
                if (got < 0) throw new IOException("key file truncated");
                read += got;
            }
        }
        String hex = new String(encoded, StandardCharsets.US_ASCII).trim();
        Arrays.fill(encoded, (byte)0);
        require(hex.matches("[0-9a-fA-F]{32}"), "PRIVATE_KEY_FILE_INVALID");
        byte[] decoded = new byte[16];
        for (int i = 0; i < decoded.length; i++) decoded[i] = (byte)Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return decoded;
    }

    private String proof(byte[] secret, String role, String clientNonce, String serverNonce) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return hex(mac.doFinal(("CM1|" + role + "|" + DEVICE_ID + "|" + clientNonce + "|" + serverNonce).getBytes(StandardCharsets.US_ASCII)));
    }

    private static String randomNonce() {
        byte[] value = new byte[16];
        new SecureRandom().nextBytes(value);
        return hex(value);
    }

    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) text.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return text.toString();
    }

    private void require(boolean condition, String code) throws ProbeFailure {
        if (!condition) throw new ProbeFailure(code);
        checks++;
    }

    private static long now() { return SystemClock.elapsedRealtime(); }
    private static void pauseBetween() { SystemClock.sleep(900); }

    private static final class Handshake {
        final WearableAuth auth;
        final String clientNonce;
        final String serverNonce;
        final String phoneProof;
        Handshake(WearableAuth auth, String clientNonce, String serverNonce, String phoneProof) {
            this.auth = auth; this.clientNonce = clientNonce; this.serverNonce = serverNonce; this.phoneProof = phoneProof;
        }
    }

    private static final class ProbeFailure extends Exception {
        final String code;
        ProbeFailure(String code) { super(code); this.code = code; }
    }

    /** Bounded line reader. No raw bytes, samples, identity or secrets enter logs/files. */
    private static final class Link implements AutoCloseable {
        final BluetoothSocket socket;
        final OutputStream output;
        final BlockingQueue<String> lines = new ArrayBlockingQueue<>(32);
        volatile boolean eof;
        volatile boolean malformed;
        volatile boolean closed;
        Link(BluetoothSocket socket) throws IOException {
            this.socket = socket;
            output = socket.getOutputStream();
            InputStream input = socket.getInputStream();
            Thread reader = new Thread(() -> {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                try {
                    while (!closed) {
                        int next = input.read();
                        if (next < 0) break;
                        if (next == '\n') {
                            byte[] encoded = line.toByteArray();
                            String text = new String(encoded, StandardCharsets.US_ASCII);
                            line.reset();
                            if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
                            if (text.indexOf('\r') >= 0) { malformed = true; break; }
                            if (!text.isEmpty() && !lines.offer(text)) { malformed = true; break; }
                        } else {
                            if ((next < 32 && next != '\r') || next > 126 || line.size() >= LINE_LIMIT) { malformed = true; break; }
                            line.write(next);
                        }
                    }
                } catch (IOException ignored) { /* Only sanitized connection state is retained. */ }
                finally { eof = true; }
            }, "bt-probe-read");
            reader.setDaemon(true);
            reader.start();
        }
        void send(String line) throws IOException {
            output.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
        String poll(long timeout) throws Exception {
            if (malformed) throw new ProbeFailure("MALFORMED_OR_EXCESSIVE_STREAM");
            return lines.poll(Math.max(1, timeout), TimeUnit.MILLISECONDS);
        }
        boolean ended() { return eof && lines.isEmpty(); }
        @Override public void close() throws IOException { closed = true; lines.clear(); socket.close(); }
    }
}
