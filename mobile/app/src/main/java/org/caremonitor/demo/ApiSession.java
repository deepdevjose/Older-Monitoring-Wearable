package org.caremonitor.demo;

import android.content.Context;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

/** The only account session. Token is encrypted on disk and expires fail-closed. */
final class ApiSession {
    static final String DEFAULT_BASE="https://192.168.3.3:8443/api/v1";
    private static final String FILE="session_v2.enc";
    private static volatile boolean locallyClosed;
    final String base,token,id,role,name,email,expires;
    ApiSession(String base,JSONObject body)throws Exception{
        this.base=base;token=body.getString("access_token");expires=body.getString("expires_at");
        JSONObject user=body.getJSONObject("user");id=user.getString("id");role=user.getString("role");
        name=user.getString("display_name");email=user.getString("email");
        if(!id.matches("[A-Za-z0-9_-]{1,128}")||!token.matches("[A-Za-z0-9_.~-]{32,4096}"))throw new IllegalArgumentException("Session identity unavailable");
        if(!role.equals("elder")&&!role.equals("caregiver"))throw new IllegalArgumentException("Role unavailable");
    }
    static ApiSession load(Context context){
        if(locallyClosed||context.getSharedPreferences("caremonitor_session_control",0).getBoolean("signed_out",false))return null;
        try{String value=SecureStore.read(context,FILE,32768);if(value.isEmpty())return null;
            JSONObject saved=new JSONObject(value);ApiSession session=new ApiSession(saved.getString("base"),saved);
            return session.valid()?session:null;
        }catch(Exception failure){return null;}
    }
    static synchronized ApiSession save(Context context,String base,JSONObject response)throws Exception{
        ApiSession session=new ApiSession(base,response);if(!session.valid())throw new IllegalArgumentException("Expired session");
        JSONObject saved=new JSONObject(response.toString());saved.put("base",base);
        SecureStore.write(context,FILE,saved.toString());
        if(!context.getSharedPreferences("caremonitor_session_control",0).edit().putBoolean("signed_out",false).commit())throw new java.io.IOException("No se pudo guardar el control de sesión.");
        locallyClosed=false;
        context.getSharedPreferences("caremonitor_endpoint",0).edit().putString("base",base).apply();return session;
    }
    static synchronized void clear(Context context){locallyClosed=true;context.getSharedPreferences("caremonitor_session_control",0).edit().putBoolean("signed_out",true).commit();SecureStore.delete(context,FILE);NotificationSync.cancel(context);}
    static String savedBase(Context context){return context.getSharedPreferences("caremonitor_endpoint",0).getString("base",DEFAULT_BASE);}
    boolean valid(){return expiryMs(expires)>System.currentTimeMillis()+5000;}
    static long expiryMs(String value){
        try{String input=value.replaceAll("\\.\\d+","");
            if(input.endsWith("+00:00"))input=input.substring(0,input.length()-6)+"Z";
            SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));fmt.setLenient(false);return fmt.parse(input).getTime();
        }catch(Exception ignored){return 0;}
    }
    static String utc(long milliseconds){SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));return fmt.format(new Date(milliseconds));}
    boolean elder(){return role.equals("elder");}
}
