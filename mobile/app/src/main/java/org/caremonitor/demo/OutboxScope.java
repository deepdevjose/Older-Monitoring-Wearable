package org.caremonitor.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable private storage names; an account or endpoint change never rebinds samples. */
public final class OutboxScope {
    private OutboxScope() {}
    public static String file(String owner,String endpoint) {
        if(owner==null||owner.isEmpty())throw new IllegalArgumentException("Missing queue owner");
        String canonical=RelayPolicy.endpoint(endpoint);
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((owner.length()+":"+owner+canonical).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.US,"%02x",b&255));
            return "sensor_outbox_scope_"+hex+".enc";
        }catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
}
