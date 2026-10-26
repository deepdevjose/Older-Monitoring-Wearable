package org.caremonitor.demo;

import java.util.Arrays;
import java.util.Collections;

public final class SecureSetupPolicyTest {
    private static int checks;
    private static final String ENDPOINT="https://10.110.244.208:8000/sensor-data";
    private static final String ID="f5ea22b9-3fb9-4e63-8e04-0591a2a7f7a4";
    private static final long NOW=1_800_000_000_000L;
    public static void main(String[] args) {
        valid(ENDPOINT,"elder-demo-01",NOW+1,ID,"server-1");
        valid(ENDPOINT,"elder-demo-01",NOW+SecureSetupPolicy.MAX_LIFETIME_MS,ID,"server-1");
        invalid(ENDPOINT,"elder-demo-01",NOW,ID,"server-1");
        invalid(ENDPOINT,"elder-demo-01",NOW-1,ID,"server-1");
        invalid(ENDPOINT,"elder-demo-01",NOW+SecureSetupPolicy.MAX_LIFETIME_MS+1,ID,"server-1");
        invalid(ENDPOINT,"other-device",NOW+1000,ID,"server-1");
        invalid(ENDPOINT,"elder-demo-01",NOW+1000,"bad-id","server-1");
        invalid(ENDPOINT,"elder-demo-01",NOW+1000,ID,"");
        invalid(ENDPOINT,"elder-demo-01",NOW+1000,ID,null);
        invalid("http://10.110.244.208:8000/sensor-data","elder-demo-01",NOW+1000,ID,"server-1");
        invalid("https://example.org/other","elder-demo-01",NOW+1000,ID,"server-1");
        invalid(ENDPOINT+"?secret=bad","elder-demo-01",NOW+1000,ID,"server-1");
        invalid("https://person@example.org/sensor-data","elder-demo-01",NOW+1000,ID,"server-1");
        invalid("https://EXAMPLE.ORG/sensor-data","elder-demo-01",NOW+1000,ID,"server-1");
        check(SecureSetupPolicy.exactScopes(Collections.singletonList("sensor:write"),false));
        check(!SecureSetupPolicy.exactScopes(Arrays.asList("sensor:write","sensor:read"),false));
        check(!SecureSetupPolicy.exactScopes(Collections.singletonList("alerts:read"),false));
        check(SecureSetupPolicy.exactScopes(Arrays.asList("alerts:read","alerts:ack"),true));
        check(SecureSetupPolicy.exactScopes(Arrays.asList("alerts:ack","alerts:read"),true));
        check(!SecureSetupPolicy.exactScopes(Collections.singletonList("alerts:read"),true));
        check(!SecureSetupPolicy.exactScopes(Arrays.asList("alerts:read","alerts:read"),true));
        check(!SecureSetupPolicy.exactScopes(Arrays.asList("alerts:read","alerts:ack","sensor:write"),true));
        check(!SecureSetupPolicy.exactScopes(null,true));
        System.out.println("SecureSetupPolicy: "+checks+" checks passed");
    }
    private static void valid(String endpoint,String device,long expiry,String id,String server){
        SecureSetupPolicy.metadata(endpoint,device,expiry,NOW,id,server);checks++;
    }
    private static void invalid(String endpoint,String device,long expiry,String id,String server){
        try{SecureSetupPolicy.metadata(endpoint,device,expiry,NOW,id,server);throw new AssertionError("Unsafe metadata accepted");}
        catch(IllegalArgumentException expected){checks++;}
    }
    private static void check(boolean value){if(!value)throw new AssertionError("Policy check failed");checks++;}
}
