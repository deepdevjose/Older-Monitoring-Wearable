package org.caremonitor.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded, mutually authenticated CM1 handshake. No sensor data is accepted here. */
final class WearableAuth {
    static final String DEVICE_ID="elder-demo-01";
    private final byte[] key;
    private final String clientNonce;
    private String serverNonce;
    private int phase;

    WearableAuth(String setupKey) { this(DEVICE_ID,setupKey,randomNonce()); }
    WearableAuth(String deviceId,String setupKey) { this(deviceId,setupKey,randomNonce()); }
    // Fixed nonces are only used by independent protocol-vector tests.
    WearableAuth(String deviceId,String setupKey,String clientNonce) {
        if(!DEVICE_ID.equals(deviceId))throw new IllegalArgumentException("Unexpected wearable identity");
        this.key=decode(normalizeKey(setupKey));
        if(!hex(clientNonce,32))throw new IllegalArgumentException("Invalid client nonce");
        this.clientNonce=clientNonce;
    }
    static String normalizeKey(String input) {
        if(input==null||input.length()>128)throw new IllegalArgumentException("Use the 32-character setup key supplied with your wearable.");
        String value=input.replaceAll("[\\s-]","").toLowerCase(Locale.ROOT);
        if(!hex(value,32))throw new IllegalArgumentException("Use the 32-character setup key supplied with your wearable.");
        return value;
    }
    static String scope(String base,String owner,String address) {
        if(base==null||base.isEmpty()||owner==null||owner.isEmpty()||address==null||!address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
            throw new IllegalArgumentException("Wearable identity unavailable");
        try{return encode(MessageDigest.getInstance("SHA-256").digest((base+"\n"+owner+"\n"+address.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("Private wearable storage unavailable",failure);}
    }
    String hello() {
        if(phase!=0){phase=4;throw reject();}
        phase=1;return "CM1 HELLO "+clientNonce;
    }
    String answer(String challenge) {
        if(phase!=1){phase=4;throw reject();}
        return receive(challenge);
    }
    boolean verifyReady(String line) {
        if(phase!=2){phase=4;throw reject();}
        receive(line);return isReady();
    }
    /** Returns the phone proof, or null after verifying READY. Failures are terminal. */
    String receive(String line) {
        try {
            if(line==null||line.length()>256)throw reject();
            String[] fields=line.split(" ",-1);
            if(phase==1&&fields.length==5&&fields[0].equals("CM1")&&fields[1].equals("CHALLENGE")
                    &&fields[2].equals(DEVICE_ID)&&hex(fields[3],32)&&hex(fields[4],64)) {
                serverNonce=fields[3];
                if(!matches(fields[4],proof("board")))throw reject();
                phase=2;return "CM1 AUTH "+proof("phone");
            }
            if(phase==2&&fields.length==3&&fields[0].equals("CM1")&&fields[1].equals("READY")&&hex(fields[2],64)
                    &&matches(fields[2],proof("ready"))) {
                phase=3;return null;
            }
            throw reject();
        } catch(SecurityException failure){phase=4;throw failure;}
    }
    boolean isReady(){return phase==3;}
    private String proof(String role) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            String message="CM1|"+role+"|"+DEVICE_ID+"|"+clientNonce+"|"+serverNonce;
            return encode(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception failure){throw reject();}
    }
    private static boolean matches(String supplied,String expected){return MessageDigest.isEqual(decode(supplied),decode(expected));}
    private static SecurityException reject(){return new SecurityException("Wearable authentication failed");}
    private static boolean hex(String text,int length){return text!=null&&text.length()==length&&text.matches("[0-9a-f]+") ;}
    private static byte[] decode(String text){byte[] result=new byte[text.length()/2];for(int i=0;i<result.length;i++)result[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return result;}
    private static String randomNonce(){byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);return encode(nonce);}
    private static String encode(byte[] bytes){char[] text=new char[bytes.length*2];char[] alphabet="0123456789abcdef".toCharArray();for(int i=0;i<bytes.length;i++){text[i*2]=alphabet[(bytes[i]>>>4)&15];text[i*2+1]=alphabet[bytes[i]&15];}return new String(text);}
}
