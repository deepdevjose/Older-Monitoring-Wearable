package org.caremonitor.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Called exclusively on the relay worker. No API key is stored as plain text. */
final class RelaySecureSettings {
    private static final String ALIAS = "care_monitor_relay_api_key_v1";
    private final SharedPreferences preferences;

    static final class Saved {
        final String endpoint;
        final String apiKey;
        Saved(String endpoint, String apiKey) { this.endpoint = endpoint; this.apiKey = apiKey; }
    }

    RelaySecureSettings(Context context) {
        preferences = context.getSharedPreferences("care_monitor_relay_secure", Context.MODE_PRIVATE);
    }

    String savedEndpoint() { return preferences.getString("endpoint", ""); }

    Saved load() throws GeneralSecurityException, IOException {
        String endpoint = savedEndpoint();
        String iv = preferences.getString("iv", "");
        String encrypted = preferences.getString("ciphertext", "");
        if (iv.isEmpty() || encrypted.isEmpty()) return new Saved(endpoint, "");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret(false),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
            return new Saved(endpoint, new String(cipher.doFinal(
                    Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Saved credential cannot be decoded.");
        }
    }

    void save(String endpoint, String apiKey) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret(true));
        cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        boolean stored = preferences.edit().putString("endpoint", endpoint)
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit();
        if (!stored) throw new IOException("Cannot persist encrypted credential.");
    }

    private SecretKey secret(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create) throw new GeneralSecurityException("AndroidKeyStore key is unavailable.");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
