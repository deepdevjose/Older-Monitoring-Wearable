package org.caremonitor.wearableprovision;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Authorized USB setup only; no account creation, token disclosure or monitoring start. */
public final class WearableProvisionInstrumentation extends Instrumentation {
    private Bundle arguments;
    private String phase = "session_guard";
    private int checks;
    private LocalServerSocket server;
    private LocalSocket accepted;

    @Override public void onCreate(Bundle input) {
        super.onCreate(input);
        arguments = input == null ? new Bundle() : new Bundle(input);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int exit = Activity.RESULT_CANCELED;
        byte[] encoded = new byte[32];
        try {
            Context target = getTargetContext();
            ActivityManager activities = (ActivityManager)target.getSystemService(Context.ACTIVITY_SERVICE);
            require(activities != null, "SERVICE_GUARD_UNAVAILABLE");
            for (ActivityManager.RunningServiceInfo service : activities.getRunningServices(100)) {
                if ("org.caremonitor.demo".equals(service.service.getPackageName()))
                    require(!service.started && !service.foreground, "ACTIVE_APP_SERVICE_MUST_STAY_UNTOUCHED");
            }
            Class<?> sessions = targetClass("ApiSession");
            Method load = method(sessions, "load", Context.class);
            Object expected = load.invoke(null, target);
            require(expected != null, "NEEDS_EXISTING_VALID_SIGN_IN");
            require(Boolean.TRUE.equals(method(sessions, "elder").invoke(expected)), "NEEDS_OLDER_ADULT_ROLE");
            result.putString("role", "elder");
            phase = "bluetooth_guard";
            require(Build.VERSION.SDK_INT < 31 || target.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED, "NEEDS_EXISTING_NEARBY_DEVICES_PERMISSION");
            BluetoothManager manager = (BluetoothManager)target.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            require(adapter != null && adapter.isEnabled(), "BLUETOOTH_DISABLED");
            BluetoothDevice selected = null;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) for (BluetoothDevice candidate : bonded) {
                if ("ElderSensors-elder-demo-01".equals(candidate.getName())) {
                    require(selected == null, "MULTIPLE_MATCHING_BONDED_WEARABLES");
                    selected = candidate;
                }
            }
            require(selected != null, "PAIR_WEARABLE_WITH_ANDROID_FIRST");
            String address = selected.getAddress();
            require(BluetoothAdapter.checkBluetoothAddress(address), "WEARABLE_ADDRESS_UNAVAILABLE");
            phase = "private_usb_channel";
            String channel = arguments.getString("channel", "");
            require(channel.matches("cm_wearable_[0-9a-f]{32}"), "INVALID_USB_CHANNEL");
            server = new LocalServerSocket(channel);
            AtomicReference<LocalSocket> incoming = new AtomicReference<>();
            AtomicReference<Exception> acceptError = new AtomicReference<>();
            Thread acceptor = new Thread(() -> {
                try { incoming.set(server.accept()); }
                catch (Exception failure) { acceptError.set(failure); }
            }, "wearable-setup-usb");
            acceptor.setDaemon(true);
            acceptor.start();
            acceptor.join(45_000);
            if (acceptor.isAlive()) {
                server.close();
                acceptor.join(1000);
                throw new SetupFailure("USB_CHANNEL_TIMEOUT");
            }
            require(acceptError.get() == null && incoming.get() != null, "USB_CHANNEL_NOT_ACCEPTED");
            accepted = incoming.get();
            accepted.setSoTimeout(15_000);
            Credentials peer = accepted.getPeerCredentials();
            // Only adbd (shell/root), never another ordinary installed phone app.
            require(peer != null && (peer.getUid() == 2000 || peer.getUid() == 0), "USB_CHANNEL_PEER_REJECTED");
            accepted.getOutputStream().write("CM1 PROVISION READY\n".getBytes(StandardCharsets.US_ASCII));
            accepted.getOutputStream().flush();
            InputStream input = accepted.getInputStream();
            int count = 0;
            while (count < encoded.length) {
                int read = input.read(encoded, count, encoded.length - count);
                require(read > 0, "USB_KEY_TRANSFER_INCOMPLETE");
                count += read;
            }
            String key = new String(encoded, StandardCharsets.US_ASCII);
            require(key.matches("[0-9a-f]{32}"), "USB_KEY_FORMAT_INVALID");
            phase = "encrypted_key_save";
            Class<?> keys = targetClass("WearableKeys");
            method(keys, "save", Context.class, sessions, String.class, String.class).invoke(null, target, expected, address, key);
            key = null;
            require(Boolean.TRUE.equals(method(keys, "has", Context.class, String.class).invoke(null, target, address)), "ENCRYPTED_KEY_NOT_AVAILABLE");
            accepted.getOutputStream().write("CM1 PROVISION SAVED\n".getBytes(StandardCharsets.US_ASCII));
            accepted.getOutputStream().flush();
            result.putBoolean("encrypted_wearable_key_saved", true);
            result.putBoolean("account_session_reused", true);
            result.putBoolean("account_created_or_changed", false);
            result.putBoolean("account_token_disclosed", false);
            result.putBoolean("monitoring_started", false);
            result.putBoolean("server_access", false);
            result.putString("scope", "existing elder account wearable key only");
            result.putString("result", "PASS");
            exit = Activity.RESULT_OK;
        } catch (SetupFailure failure) {
            result.putString("result", "FAIL");
            result.putString("detail", failure.code);
        } catch (Throwable failure) {
            if (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
            result.putString("result", "FAIL");
            // Never include exception messages, account fields, key or peer identity.
            result.putString("detail", failure.getClass().getSimpleName());
        } finally {
            Arrays.fill(encoded, (byte)0);
            try { if (accepted != null) accepted.close(); } catch (Exception ignored) { }
            try { if (server != null) server.close(); } catch (Exception ignored) { }
            result.putString("phase", phase);
            result.putInt("checks", checks);
            result.putBoolean("private_key_file_created", false);
        }
        finish(exit, result);
    }

    private Class<?> targetClass(String name) throws ClassNotFoundException {
        return Class.forName("org.caremonitor.demo." + name, true, getTargetContext().getClassLoader());
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
    private void require(boolean condition, String code) throws SetupFailure {
        if (!condition) throw new SetupFailure(code);
        checks++;
    }
    private static final class SetupFailure extends Exception {
        final String code;
        SetupFailure(String code) { super(code); this.code = code; }
    }
}
