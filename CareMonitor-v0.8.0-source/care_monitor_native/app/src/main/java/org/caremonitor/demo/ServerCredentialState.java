package org.caremonitor.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Public credential metadata only. This object never contains the credential. */
public final class ServerCredentialState {
    public final boolean saved,verified;
    public final String keyId,deviceId,expiresAt,serverId,status;
    public final List<String> scopes;
    public final long verifiedAt,expiresAtMs;
    private ServerCredentialState(boolean saved,boolean verified,String keyId,String deviceId,List<String> scopes,
            String expiresAt,long expiresAtMs,String serverId,long verifiedAt,String status) {
        this.saved=saved;this.verified=verified;this.keyId=keyId;this.deviceId=deviceId;
        this.scopes=Collections.unmodifiableList(new ArrayList<>(scopes));this.expiresAt=expiresAt;
        this.expiresAtMs=expiresAtMs;this.serverId=serverId;this.verifiedAt=verifiedAt;this.status=status;
    }
    public static ServerCredentialState empty() {return saved(false,"No key saved");}
    public static ServerCredentialState saved(boolean saved,String status) {
        return new ServerCredentialState(saved,false,"","",Collections.emptyList(),"",0,"",0,status);
    }
    public static ServerCredentialState verified(String keyId,String deviceId,List<String> scopes,String expiresAt,
            long expiresAtMs,String serverId,long now) {
        if(keyId==null||keyId.isEmpty()||serverId==null||serverId.isEmpty()||expiresAtMs<=now)
            throw new IllegalArgumentException("Invalid verification metadata.");
        return new ServerCredentialState(true,true,keyId,deviceId,scopes,expiresAt,expiresAtMs,serverId,now,"Server verified");
    }
    public boolean allows(String scope,long now) {return saved&&verified&&now<expiresAtMs&&scopes.contains(scope);}
    /** Expiry is reflected even before another network request or callback happens. */
    public ServerCredentialState current(long now){return verified&&now>=expiresAtMs?failure("Key expired · issue and save a new key"):this;}
    public ServerCredentialState failure(String reason) {
        return new ServerCredentialState(saved,false,keyId,deviceId,scopes,expiresAt,expiresAtMs,serverId,verifiedAt,reason);
    }
}
