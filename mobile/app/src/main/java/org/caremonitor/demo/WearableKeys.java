package org.caremonitor.demo;

import android.content.Context;

/** Per-wearable credentials, encrypted and bound to one elder account and server. */
final class WearableKeys {
    private WearableKeys() {}
    static String load(Context context,String address) throws Exception {
        ApiSession account=ApiSession.load(context);
        if(account==null||!account.elder())throw new SecurityException("Sign in as the older adult to authorise your wearable.");
        String saved=SecureStore.read(context,file(account,address),128);
        return saved.isEmpty()?"":WearableAuth.normalizeKey(saved);
    }
    static boolean has(Context context,String address) {
        try{return !load(context,address).isEmpty();}catch(Exception failure){return false;}
    }
    static void save(Context context,ApiSession expected,String address,String setupKey) throws Exception {
        ApiSession current=ApiSession.load(context);
        if(expected==null||current==null||!current.elder()||!current.id.equals(expected.id)||!current.base.equals(expected.base)||!current.token.equals(expected.token))
            throw new SecurityException("Your account changed. Sign in again before setting up the wearable.");
        SecureStore.write(context,file(current,address),WearableAuth.normalizeKey(setupKey));
    }
    private static String file(ApiSession account,String address) {
        return "wearable_"+WearableAuth.scope(account.base,account.id,address)+".enc";
    }
}
