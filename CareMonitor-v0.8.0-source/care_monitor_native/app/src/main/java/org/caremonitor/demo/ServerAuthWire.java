package org.caremonitor.demo;

import android.net.Network;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Shared strict response parsing and origin derivation. No exception includes a credential. */
final class ServerAuthWire {
    static String origin(String sensorEndpoint) {
        String canonical=RelayPolicy.endpoint(sensorEndpoint);
        try {
            java.net.URI uri=new java.net.URI(canonical);
            if(!"/sensor-data".equals(uri.getRawPath()))throw new IllegalArgumentException("Use the /sensor-data endpoint.");
            return "https://"+uri.getRawAuthority();
        }catch(java.net.URISyntaxException e){throw new IllegalArgumentException("Invalid endpoint.");}
    }
    static HttpsURLConnection open(Network wifi,String url,String key,SSLSocketFactory tls) throws Exception {
        if(wifi==null||tls==null)throw new java.io.IOException("Wi-Fi or TLS unavailable.");
        HttpsURLConnection c=(HttpsURLConnection)wifi.openConnection(new URL(url));
        c.setSSLSocketFactory(tls);c.setConnectTimeout(5000);c.setReadTimeout(5000);
        c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("X-API-Key",key);return c;
    }
    static JSONObject response(InputStream input,int maximum)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        while((n=input.read(buffer))!=-1){if(out.size()+n>maximum)throw new java.io.IOException("Response exceeds limit.");out.write(buffer,0,n);}
        String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();
        JSONTokener tokens=new JSONTokener(decoded);Object value=tokens.nextValue();
        if(!(value instanceof JSONObject)||tokens.nextClean()!=0)throw new IllegalArgumentException("Invalid response.");
        return (JSONObject)value;
    }
    static ServerCredentialState verified(JSONObject body,String scope,long now)throws Exception {
        if(!"verified".equals(text(body,"status",32))||!RelayPolicy.EXPECTED_DEVICE_ID.equals(text(body,"device_id",100)))
            throw new IllegalArgumentException("Unexpected credential identity.");
        JSONArray values=body.getJSONArray("scopes");if(values.length()>20)throw new IllegalArgumentException("Too many scopes.");
        ArrayList<String> scopes=new ArrayList<>();
        for(int i=0;i<values.length();i++){Object value=values.get(i);if(!(value instanceof String)||((String)value).length()>80)throw new IllegalArgumentException("Invalid scope.");scopes.add((String)value);}
        if(!scopes.contains(scope))throw new IllegalArgumentException("Credential lacks required permission.");
        String expiry=text(body,"expires_at",100);
        return ServerCredentialState.verified(text(body,"key_id",100),text(body,"device_id",100),scopes,expiry,
                timestamp(expiry,false),text(body,"server_id",100),now);
    }
    static String text(JSONObject body,String field,int max)throws Exception {
        Object value=body.get(field);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>max)
            throw new IllegalArgumentException("Invalid response field.");return (String)value;
    }
    static String optionalText(JSONObject body,String field,int max)throws Exception {
        if(!body.has(field)||body.isNull(field))return "";
        Object value=body.get(field);if(!(value instanceof String)||((String)value).length()>max)throw new IllegalArgumentException("Invalid response field.");return (String)value;
    }
    static long integer(JSONObject body,String field)throws Exception {
        Object value=body.get(field);if(!(value instanceof Number))throw new IllegalArgumentException("Invalid integer.");
        double n=((Number)value).doubleValue();long result=((Number)value).longValue();
        if(!Double.isFinite(n)||n!=result||Math.abs(n)>9007199254740991d)throw new IllegalArgumentException("Invalid integer.");return result;
    }
    static long timestamp(String value,boolean nullable)throws Exception {
        if(nullable&&(value==null||value.isEmpty()))return 0;
        if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})"))throw new IllegalArgumentException("Invalid timestamp.");
        String adjusted=value;
        int dot=value.indexOf('.');if(dot>=0){int end=dot+1;while(end<value.length()&&Character.isDigit(value.charAt(end)))end++;
            String fraction=value.substring(dot+1,end);fraction=(fraction+"000").substring(0,3);adjusted=value.substring(0,dot)+"."+fraction+value.substring(end);}
        SimpleDateFormat f=new SimpleDateFormat(dot>=0?"yyyy-MM-dd'T'HH:mm:ss.SSSXXX":"yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US);f.setLenient(false);
        ParsePosition position=new ParsePosition(0);Date date=f.parse(adjusted,position);
        if(date==null||position.getIndex()!=adjusted.length()||date.getTime()<=0)throw new IllegalArgumentException("Invalid timestamp.");return date.getTime();
    }
}
