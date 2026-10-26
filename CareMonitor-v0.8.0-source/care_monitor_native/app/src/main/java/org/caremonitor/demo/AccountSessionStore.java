package org.caremonitor.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Account bearer sessions are independent of ingest/alert API keys. Passwords are never saved. */
final class AccountSessionStore {
    private static final String ALIAS = "care_monitor_account_session_v1";
    private final SharedPreferences preferences;

    static final class Session {
        final String origin, token, username, role;
        final long expires;
        Session(String origin, String token, String username, String role, long expires) {
            this.origin = AccountPresentationPolicy.origin(origin);
            if (!AccountPresentationPolicy.token(token) || !AccountPresentationPolicy.role(role)
                    || username == null || username.isEmpty() || username.length() > 64 || expires <= 0)
                throw new IllegalArgumentException("Invalid account session.");
            this.token = token; this.username = username; this.role = role; this.expires = expires;
        }
    }

    AccountSessionStore(Context context) {
        preferences = context.getSharedPreferences("care_monitor_account_session", Context.MODE_PRIVATE);
    }
    String endpoint() { return preferences.getString("origin", ""); }

    synchronized Session load() throws Exception {
        String origin = endpoint();
        String encrypted = preferences.getString("ciphertext", "");
        if (encrypted.isEmpty()) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(false), new GCMParameterSpec(128,
                Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)));
        cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));
        JSONObject data = new JSONObject(new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8));
        Session session = new Session(origin, data.getString("token"), data.getString("username"), data.getString("role"), data.getLong("expires"));
        if (!AccountPresentationPolicy.sessionValid(session.expires, System.currentTimeMillis())) { clear(); return null; }
        return session;
    }

    synchronized void save(Session session) throws Exception {
        JSONObject data = new JSONObject().put("token", session.token).put("username", session.username)
                .put("role", session.role).put("expires", session.expires);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret(true));
        cipher.updateAAD(session.origin.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        if (!preferences.edit().putString("origin", session.origin)
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("Unable to save encrypted account session.");
    }

    synchronized boolean clear() {
        if (preferences.edit().remove("iv").remove("ciphertext").commit()) return true;
        // If disk persistence fails, invalidate the encryption key so any old ciphertext
        // surviving a process restart cannot be restored as a usable session.
        try {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            keys.deleteEntry(ALIAS);
            return !keys.containsAlias(ALIAS);
        } catch (Exception e) { return false; }
    }

    private SecretKey secret(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        java.security.Key key = store.getKey(ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        if (!create) throw new java.security.GeneralSecurityException("Account session key unavailable.");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
