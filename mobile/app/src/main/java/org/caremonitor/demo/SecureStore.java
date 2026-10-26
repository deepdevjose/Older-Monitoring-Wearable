package org.caremonitor.demo;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Private AES-GCM files with AndroidKeyStore keys; no credentials or samples in plain preferences. */
final class SecureStore {
    private static final String ALIAS="caremonitor_private_v2";
    private SecureStore() {}
    private static synchronized SecretKey key(boolean create) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        java.security.Key saved=store.getKey(ALIAS,null);
        if(saved instanceof SecretKey)return (SecretKey)saved;
        if(!create)throw new IOException("Secure key unavailable");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());return generator.generateKey();
    }
    static synchronized String read(Context context,String name,int limit) throws Exception {
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),name));
        if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists())return "";
        byte[] bytes;
        try(InputStream input=file.openRead()){bytes=ApiClient.readLimited(input,limit+64);}
        if(bytes.length<30||bytes[0]!=2)throw new IOException("Secure file format unavailable");
        int ivLength=bytes[1]&255;if(ivLength!=12)throw new IOException("Secure file format unavailable");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,bytes,2,ivLength));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(bytes,2+ivLength,bytes.length-2-ivLength),StandardCharsets.UTF_8);
    }
    static synchronized void write(Context context,String name,String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),name));FileOutputStream out=null;
        try{out=file.startWrite();out.write(2);out.write(cipher.getIV().length);out.write(cipher.getIV());out.write(ciphertext);
            out.flush();out.getFD().sync();file.finishWrite(out);
        }catch(Exception error){if(out!=null)file.failWrite(out);throw error;}
    }
    static synchronized void delete(Context context,String name){new AtomicFile(new File(context.getFilesDir(),name)).delete();}
}
