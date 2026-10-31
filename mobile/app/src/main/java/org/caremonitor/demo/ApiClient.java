package org.caremonitor.demo;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.*;
import javax.net.ssl.*;

/** Bounded HTTPS client. Platform CA roots plus the deployment's public CA, strict hostnames, no redirects. */
final class ApiClient {
    static final class Failure extends Exception {
        final int status;
        Failure(int status,String text){super(text);this.status=status;}
    }
    private static SSLSocketFactory tls;
    static String base(String input){
        String value=RelayPolicy.endpoint(input);
        if(!new URIUnchecked(value).path().equals("/api/v1"))throw new IllegalArgumentException("The server address must end with /api/v1.");
        return value;
    }
    private static final class URIUnchecked{final URI uri;URIUnchecked(String text){uri=URI.create(text);}String path(){return uri.getPath();}}
    static boolean lanAllowed(Context context){return Build.VERSION.SDK_INT<37||context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==PackageManager.PERMISSION_GRANTED;}
    static JSONObject call(Context context,ApiSession session,String method,String path,JSONObject body)throws Exception{
        return call(context,session,method,path,body,6000);
    }
    static JSONObject call(Context context,ApiSession session,String method,String path,JSONObject body,int readTimeoutMs)throws Exception{
        if(session==null||!session.valid())throw new Failure(401,"Your session has expired. Sign in again.");
        if(!"/auth/logout".equals(path)){
            ApiSession current=ApiSession.load(context);
            if(current==null||!current.id.equals(session.id)||!current.base.equals(session.base)||!current.token.equals(session.token))
                throw new Failure(401,"Your session has changed. Sign in again.");
        }
        return request(context,session.base,session.token,method,path,body,readTimeoutMs);
    }
    static JSONObject history(Context context,ApiSession session,String patient,String metric,String period,long endMillis)throws Exception{
        MetricHistoryQuery query=new MetricHistoryQuery(patient,metric,period,endMillis);
        JSONObject result=call(context,session,"GET",query.path(),null,MetricHistoryQuery.READ_TIMEOUT_MS);
        if(!query.matches(result.optString("patient_id"),result.optString("metric"),result.optString("period"),result.optString("source")))
            throw new Failure(0,"The saved readings could not be verified. Refresh history to try again.");
        return result;
    }
    static JSONObject request(Context context,String base,String token,String method,String path,JSONObject body)throws Exception{
        return request(context,base,token,method,path,body,6000);
    }
    private static JSONObject request(Context context,String base,String token,String method,String path,JSONObject body,int readTimeoutMs)throws Exception{
        if(!lanAllowed(context))throw new Failure(0,"Allow local network access to connect to your server.");
        HttpsURLConnection connection=null;
        try{
            connection=(HttpsURLConnection)new URL(base+path).openConnection();connection.setSSLSocketFactory(tls(context));
            connection.setConnectTimeout(6000);connection.setReadTimeout(readTimeoutMs);connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");
            if(token!=null&&!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=connection.getOutputStream()){out.write(bytes);}}
            int status=connection.getResponseCode();
            InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            String response=stream==null?"{}":new String(readLimited(stream,512*1024),StandardCharsets.UTF_8);
            JSONObject result=new JSONObject(response.isEmpty()?"{}":response);
            if(status<200||status>=300){
                String message=status==401?"Your session is no longer valid. Sign in again.":status==403?"You do not have permission to do this.":
                    status==409?"This request already exists or needs review.":status==429?"Too many attempts. Wait a moment and try again.":
                    status==422?"Check the form and try again.":status>=500?"The server is unavailable. Try again later.":"The request could not be completed ("+status+").";
                throw new Failure(status,message);
            }return result;
        }catch(SSLException error){throw new Failure(0,"The server certificate or hostname could not be verified.");}
        catch(Failure error){throw error;}catch(Exception error){throw new Failure(0,"Could not connect securely to the server. Check your network and server address.");}
        finally{if(connection!=null)connection.disconnect();}
    }
    static byte[] readLimited(InputStream input,int maximum)throws IOException{
        try(InputStream source=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;while((count=source.read(buffer))!=-1){if(out.size()+count>maximum)throw new IOException("Response limit");out.write(buffer,0,count);}return out.toByteArray();}
    }
    static synchronized SSLSocketFactory tls(Context context)throws Exception{
        if(tls!=null)return tls;
        TrustManagerFactory platform=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());platform.init((KeyStore)null);
        KeyStore local=KeyStore.getInstance(KeyStore.getDefaultType());local.load(null,null);
        try(InputStream input=context.getAssets().open("server_ca.crt")){
            local.setCertificateEntry("caremonitor-deployment",CertificateFactory.getInstance("X.509").generateCertificate(input));}
        TrustManagerFactory project=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());project.init(local);
        final X509TrustManager system=x509(platform.getTrustManagers()),bundled=x509(project.getTrustManagers());
        X509TrustManager merged=new X509TrustManager(){
            public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{system.checkClientTrusted(chain,type);}
            public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{
                try{system.checkServerTrusted(chain,type);}catch(CertificateException failure){
                    for(X509Certificate certificate:chain)certificate.checkValidity();bundled.checkServerTrusted(chain,type);}}
            public X509Certificate[] getAcceptedIssuers(){X509Certificate[] a=system.getAcceptedIssuers(),b=bundled.getAcceptedIssuers(),all=new X509Certificate[a.length+b.length];
                System.arraycopy(a,0,all,0,a.length);System.arraycopy(b,0,all,a.length,b.length);return all;}};
        SSLContext contextTls=SSLContext.getInstance("TLS");contextTls.init(null,new TrustManager[]{merged},null);tls=contextTls.getSocketFactory();return tls;
    }
    private static X509TrustManager x509(TrustManager[] managers)throws CertificateException{
        for(TrustManager manager:managers)if(manager instanceof X509TrustManager)return (X509TrustManager)manager;throw new CertificateException("No trust manager");}
}
