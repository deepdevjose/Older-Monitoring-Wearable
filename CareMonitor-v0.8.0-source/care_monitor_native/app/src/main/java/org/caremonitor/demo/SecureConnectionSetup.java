package org.caremonitor.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONObject;

/** Encrypted setup import; never accepts an API key, path or credential URL from an Intent. */
final class SecureConnectionSetup {
    static final String PREPARE_ACTION="org.caremonitor.demo.PREPARE_CONNECTION_SETUP";
    static final String REVIEW_ACTION="org.caremonitor.demo.REVIEW_CONNECTION_SETUP";
    static final String FORMAT="care-monitor-setup-v1";
    static final String PUBLIC_FILE="caremonitor-setup-public.json";
    static final String ENVELOPE_FILE="caremonitor-setup.enc.json";
    private static final String ALIAS="care_monitor_connection_setup_rsa_v1";
    private static final int MAX_BYTES=16*1024;

    static final class Draft {
        final String endpoint,deviceId,uploadKey,alertsKey,setupId,serverId;
        final long expiresAtMs;
        Draft(JSONObject json)throws Exception {
            if(json.length()!=7)throw new IllegalArgumentException();
            endpoint=ServerAuthWire.text(json,"endpoint",2048);
            deviceId=ServerAuthWire.text(json,"device_id",100);
            uploadKey=ServerAuthWire.text(json,"upload_key",1024);
            alertsKey=ServerAuthWire.text(json,"alerts_key",1024);
            setupId=ServerAuthWire.text(json,"setup_id",100);
            serverId=ServerAuthWire.text(json,"server_id",100);
            expiresAtMs=ServerAuthWire.integer(json,"expires_at_ms");
            SecureSetupPolicy.metadata(endpoint,deviceId,expiresAtMs,System.currentTimeMillis(),setupId,serverId);
            RelayPolicy.apiKey(uploadKey);RelayPolicy.apiKey(alertsKey);
            if(uploadKey.equals(alertsKey))throw new IllegalArgumentException();
        }
    }
    static final class Verified {
        final Draft draft;
        final String uploadKeyId,alertsKeyId;
        Verified(Draft draft,String upload,String alerts){this.draft=draft;uploadKeyId=upload;alertsKeyId=alerts;}
    }
    private SecureConnectionSetup() {}

    static File fixedFile(Context context)throws Exception {
        File directory=context.getExternalFilesDir(null);
        if(directory==null)throw new java.io.IOException();
        return new File(directory,ENVELOPE_FILE);
    }
    static void prepare(Context context)throws Exception {
        KeyStore store=store();
        if(!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();store=store();
        }
        PublicKey publicKey=store.getCertificate(ALIAS).getPublicKey();
        JSONObject json=new JSONObject().put("format","care-monitor-setup-public-v1")
            .put("public_key_spki",Base64.encodeToString(publicKey.getEncoded(),Base64.NO_WRAP))
            .put("recipient_sha256",fingerprint(publicKey));
        File directory=context.getExternalFilesDir(null);if(directory==null)throw new java.io.IOException();
        try(FileOutputStream output=new FileOutputStream(new File(directory,PUBLIC_FILE))){
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();
        }
    }
    static Draft decrypt(Context context,InputStream input)throws Exception {
        JSONObject envelope=ServerAuthWire.response(input,MAX_BYTES);
        if(envelope.length()!=5||!FORMAT.equals(ServerAuthWire.text(envelope,"format",64)))throw new IllegalArgumentException();
        String recipient=ServerAuthWire.text(envelope,"recipient_sha256",64);
        if(!recipient.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
        KeyStore store=store();if(!store.containsAlias(ALIAS))throw new IllegalArgumentException();
        String expected=fingerprint(store.getCertificate(ALIAS).getPublicKey());
        if(!MessageDigest.isEqual(recipient.getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException();
        byte[] wrapped=decoded(envelope,"wrapped_key",512),iv=decoded(envelope,"iv",32),ciphertext=decoded(envelope,"ciphertext",MAX_BYTES);
        if(wrapped.length!=256||iv.length!=12||ciphertext.length<16)throw new IllegalArgumentException();
        byte[] aes=null,plain=null;
        try {
            Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.DECRYPT_MODE,(PrivateKey)store.getKey(ALIAS,null),
                new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
            aes=rsa.doFinal(wrapped);if(aes.length!=32)throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD((FORMAT+"\n"+recipient).getBytes(StandardCharsets.UTF_8));plain=cipher.doFinal(ciphertext);
            Draft draft=new Draft(ServerAuthWire.response(new ByteArrayInputStream(plain),MAX_BYTES));
            if(applied(context).contains(draft.setupId))throw new IllegalArgumentException();
            return draft;
        } finally {if(aes!=null)Arrays.fill(aes,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    static Verified verify(Context context,Draft draft)throws Exception {
        SecureSetupPolicy.metadata(draft.endpoint,draft.deviceId,draft.expiresAtMs,System.currentTimeMillis(),draft.setupId,draft.serverId);
        if(applied(context).contains(draft.setupId))throw new IllegalArgumentException();
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network wifi=null;if(manager!=null)for(Network network:manager.getAllNetworks()){
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){wifi=network;break;}
        }
        if(wifi==null)throw new java.io.IOException();
        SSLSocketFactory tls=ServerRelay.createTlsFactory(context);
        ServerCredentialState upload=verifyKey(wifi,tls,draft,draft.uploadKey,"sensor:write",false);
        ServerCredentialState alerts=verifyKey(wifi,tls,draft,draft.alertsKey,"alerts:read",true);
        if(upload.keyId.equals(alerts.keyId))throw new IllegalArgumentException();
        return new Verified(draft,upload.keyId,alerts.keyId);
    }
    private static ServerCredentialState verifyKey(Network wifi,SSLSocketFactory tls,Draft draft,String key,String scope,boolean caregiver)throws Exception {
        HttpsURLConnection connection=null;
        try {
            connection=ServerAuthWire.open(wifi,ServerAuthWire.origin(draft.endpoint)+"/auth/status?device_id="+RelayPolicy.EXPECTED_DEVICE_ID,key,tls);
            if(connection.getResponseCode()!=200)throw new java.io.IOException();
            ServerCredentialState state;
            try(InputStream input=connection.getInputStream()){state=ServerAuthWire.verified(ServerAuthWire.response(input,MAX_BYTES),scope,System.currentTimeMillis());}
            if(!draft.serverId.equals(state.serverId)||!SecureSetupPolicy.exactScopes(state.scopes,caregiver))throw new IllegalArgumentException();
            return state;
        } finally {if(connection!=null)connection.disconnect();}
    }
    static boolean rememberApplied(Context context,String setupId) {
        Set<String> values=new HashSet<>(applied(context));values.add(setupId);
        return context.getSharedPreferences("care_monitor_setup_import",Context.MODE_PRIVATE).edit().putStringSet("applied_ids",values).commit();
    }
    private static Set<String> applied(Context context){
        return context.getSharedPreferences("care_monitor_setup_import",Context.MODE_PRIVATE).getStringSet("applied_ids",java.util.Collections.emptySet());
    }
    private static byte[] decoded(JSONObject envelope,String name,int max)throws Exception {
        String value=ServerAuthWire.text(envelope,name,MAX_BYTES);byte[] bytes=Base64.decode(value,Base64.NO_WRAP);
        if(bytes.length>max)throw new IllegalArgumentException();return bytes;
    }
    private static KeyStore store()throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return store;}
    private static String fingerprint(PublicKey key)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(key.getEncoded());StringBuilder out=new StringBuilder();
        for(byte value:bytes)out.append(String.format(java.util.Locale.US,"%02x",value&255));return out.toString();
    }
}
