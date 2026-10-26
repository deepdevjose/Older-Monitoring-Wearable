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
        if(!new URIUnchecked(value).path().equals("/api/v1"))throw new IllegalArgumentException("La dirección debe terminar en /api/v1");
        return value;
    }
    private static final class URIUnchecked{final URI uri;URIUnchecked(String text){uri=URI.create(text);}String path(){return uri.getPath();}}
    static boolean lanAllowed(Context context){return Build.VERSION.SDK_INT<37||context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==PackageManager.PERMISSION_GRANTED;}
    static JSONObject call(Context context,ApiSession session,String method,String path,JSONObject body)throws Exception{
        return call(context,session,method,path,body,6000);
    }
    static JSONObject call(Context context,ApiSession session,String method,String path,JSONObject body,int readTimeoutMs)throws Exception{
        if(session==null||!session.valid())throw new Failure(401,"La sesión venció. Vuelve a iniciar sesión.");
        if(!"/auth/logout".equals(path)){
            ApiSession current=ApiSession.load(context);
            if(current==null||!current.id.equals(session.id)||!current.base.equals(session.base)||!current.token.equals(session.token))
                throw new Failure(401,"La sesión cambió. Vuelve a iniciar sesión.");
        }
        return request(context,session.base,session.token,method,path,body,readTimeoutMs);
    }
    static JSONObject request(Context context,String base,String token,String method,String path,JSONObject body)throws Exception{
        return request(context,base,token,method,path,body,6000);
    }
    private static JSONObject request(Context context,String base,String token,String method,String path,JSONObject body,int readTimeoutMs)throws Exception{
        if(!lanAllowed(context))throw new Failure(0,"Permite el acceso a la red local para conectar con tu servidor.");
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
                String message=status==401?"Sesión no válida. Inicia sesión otra vez.":status==403?"No tienes permiso para esta acción.":
                    status==409?"La solicitud ya existe o requiere revisión.":status==429?"Demasiados intentos. Espera y vuelve a intentarlo.":
                    status==422?"Revisa los campos y vuelve a intentarlo.":status>=500?"El servidor no está disponible. Inténtalo después.":"No se pudo completar la solicitud ("+status+").";
                throw new Failure(status,message);
            }return result;
        }catch(SSLException error){throw new Failure(0,"No se pudo verificar el certificado o nombre del servidor.");}
        catch(Failure error){throw error;}catch(Exception error){throw new Failure(0,"Sin conexión segura con el servidor. Revisa la red y su dirección.");}
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
