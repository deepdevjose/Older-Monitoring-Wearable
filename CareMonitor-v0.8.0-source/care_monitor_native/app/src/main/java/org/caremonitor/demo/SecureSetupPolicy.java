package org.caremonitor.demo;

import java.util.List;
import java.util.UUID;

/** Limits for a user-confirmed, encrypted local connection setup. Contains no secrets. */
final class SecureSetupPolicy {
    static final long MAX_LIFETIME_MS=15*60*1000L;
    private SecureSetupPolicy() {}
    static void metadata(String endpoint,String deviceId,long expiry,long now,String setupId,String serverId) {
        String canonical=RelayPolicy.endpoint(endpoint);ServerAuthWireOrigin.check(canonical);
        if(!canonical.equals(endpoint)||!RelayPolicy.EXPECTED_DEVICE_ID.equals(deviceId)
                ||expiry<=now||expiry-now>MAX_LIFETIME_MS||serverId==null||serverId.isEmpty()||serverId.length()>100)
            throw new IllegalArgumentException("Invalid setup metadata.");
        try {if(!UUID.fromString(setupId).toString().equals(setupId))throw new IllegalArgumentException();}
        catch(Exception error){throw new IllegalArgumentException("Invalid setup identity.");}
    }
    static boolean exactScopes(List<String> scopes,boolean caregiver) {
        return scopes!=null&&(caregiver?scopes.size()==2&&scopes.contains("alerts:read")&&scopes.contains("alerts:ack"):
            scopes.size()==1&&scopes.contains("sensor:write"));
    }
    // Kept independent of Android/JSON so safety limits can run on the JVM.
    private static final class ServerAuthWireOrigin {
        static void check(String endpoint){
            try {if(!"/sensor-data".equals(new java.net.URI(endpoint).getRawPath()))throw new IllegalArgumentException();}
            catch(java.net.URISyntaxException error){throw new IllegalArgumentException("Invalid endpoint.");}
        }
    }
}
