package org.caremonitor.demo;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Caregiver credential and server-only alert channel. All network and disk work is off the UI thread. */
final class RemoteAlertClient {
    interface Listener{void onChanged();}
    private final Context context;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"CareMonitor-ServerAlerts");t.setDaemon(true);return t;});
    private final ExecutorService cancellation=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"CareMonitor-AlertCancellation");t.setDaemon(true);return t;});
    private final AtomicLong generation=new AtomicLong();private final AtomicBoolean refreshQueued=new AtomicBoolean();
    private volatile boolean closed,ready,configured,monitoring;
    private volatile String endpoint="",state="Loading saved caregiver settings…";
    private volatile ServerCredentialState credential=ServerCredentialState.empty();
    private volatile RemoteAlertInbox inbox=new RemoteAlertInbox();
    private volatile long lastSync;
    private volatile HttpsURLConnection active;
    private RelaySecureSettings settings;private String key="",binding="",cachedServerId="";
    private AtomicFile cache;private SSLSocketFactory tls;private ConnectivityManager connectivity;
    private boolean cacheBlocked,authRejected;private ScheduledFuture<?> scheduled;
    RemoteAlertClient(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        worker.setRemoveOnCancelPolicy(true);worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        execute(this::initialise);
    }
    String endpoint(){return endpoint;}String state(){return state;}long lastSync(){return lastSync;}
    boolean configured(){return ready&&configured;}ServerCredentialState credential(){return credential.current(System.currentTimeMillis());}
    List<RemoteAlertInbox.Entry> entries(){return inbox.entries();}
    int pendingCount(){return inbox.pendingCount();}int urgentPendingCount(){return inbox.urgentPendingCount();}
    void configure(String sensorEndpoint,String newKey){
        String canonical=RelayPolicy.endpoint(sensorEndpoint);ServerAuthWire.origin(canonical);RelayPolicy.apiKey(newKey);
        if(closed||!ready)throw new IllegalArgumentException("Caregiver settings are still loading.");
        long epoch=invalidate();configured=false;credential=ServerCredentialState.saved(false,"Saving caregiver key securely…");
        // Clear only the visible previous identity immediately. Each identity retains its own durable cache.
        inbox=new RemoteAlertInbox();lastSync=0;publish("Saving caregiver key securely…");
        execute(()->{
            if(!current(epoch))return;
            try{
                settings.save(canonical,newKey);endpoint=canonical;key=newKey;configured=true;authRejected=false;
                credential=ServerCredentialState.saved(true,"Key saved securely · server not yet verified");
                setupCache();publish(credential.status);if(monitoring)schedule(0);
            }catch(Exception e){configured=false;credential=ServerCredentialState.saved(false,"Could not save caregiver key securely");publish(credential.status);}
        });
    }
    void verify(){long epoch=invalidate();execute(()->{authRejected=false;if(verifyOnWorker(epoch)&&monitoring)refreshOnWorker(epoch);});}
    void refresh(){
        if(closed||!refreshQueued.compareAndSet(false,true))return;
        long epoch=generation.get();execute(()->{try{refreshOnWorker(epoch);}finally{refreshQueued.set(false);}});
    }
    /** Caller enables only while app is visible or an actual connected-device foreground session is active. */
    void monitor(boolean enabled){monitoring=enabled;if(!enabled)invalidate();execute(()->{if(scheduled!=null)scheduled.cancel(false);scheduled=null;if(enabled)schedule(0);});}
    void acknowledge(long id){
        if(id<=0||closed)return;long epoch=generation.get();
        execute(()->{
            if(!current(epoch))return;
            if(authRejected){publish("Caregiver key rejected · save a corrected key or explicitly verify again");return;}
            if(!credential.allows("alerts:ack",System.currentTimeMillis())){
                if(!verifyOnWorker(epoch)||!credential.allows("alerts:ack",System.currentTimeMillis())){publish("Caregiver key needs alerts:ack permission; alert remains unchanged");return;}
            }
            HttpsURLConnection c=null;
            try{
                c=open("/alerts/"+id+"/acknowledge");c.setRequestMethod("POST");c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json; charset=utf-8");
                byte[] payload=new JSONObject().put("device_id",RelayPolicy.EXPECTED_DEVICE_ID).toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(payload.length);active=c;if(!current(epoch))return;
                try(OutputStream out=c.getOutputStream()){out.write(payload);}
                int code=c.getResponseCode();if(!current(epoch))return;
                if(code!=200){handleHttp(code);return;}
                JSONObject response;try(InputStream input=c.getInputStream()){response=ServerAuthWire.response(input,256*1024);}
                validateIdentity(response);
                if(!"acknowledged".equals(ServerAuthWire.text(response,"status",32)))throw new IllegalArgumentException("No acknowledgement.");
                RemoteAlertInbox.Entry confirmed=parseEntry(response.getJSONObject("alert"));
                if(confirmed.id!=id)throw new IllegalArgumentException("Unexpected acknowledgement.");
                RemoteAlertInbox candidate=inbox.acknowledge(confirmed);if(!current(epoch))return;
                persist(candidate,lastSync);if(!current(epoch))return;inbox=candidate;
                publish("Server confirmed acknowledgement · episode may still be active");
            }catch(SSLException e){failure(epoch,"TLS verification failed · cached alert remains unchanged");}
            catch(Exception e){failure(epoch,"Acknowledgement unconfirmed · cached alert remains unchanged; refresh before retrying");}
            finally{if(active==c)active=null;if(c!=null)c.disconnect();}
        });
    }
    void close(){if(closed)return;closed=true;monitoring=false;invalidate();execute(()->{key="";if(scheduled!=null)scheduled.cancel(false);worker.shutdown();cancellation.shutdown();});}
    private void initialise(){
        settings=new RelaySecureSettings(context,"caregiver_alerts");
        connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try{tls=ServerRelay.createTlsFactory(context);}catch(Exception ignored){tls=null;}
        try{ServerEndpointMigration.Selection selection=ServerEndpointMigration.select(settings.savedEndpoint());
            endpoint=selection.endpoint;ServerAuthWire.origin(endpoint);
            if(selection.requiresNewCredential){
                // Keep the previous encrypted key and cache on disk, without loading either into this new identity.
                credential=ServerCredentialState.saved(false,"Server address updated · enter the local server's caregiver key");
            }else{RelaySecureSettings.Saved saved=settings.load();
                if(!saved.apiKey.isEmpty()){RelayPolicy.apiKey(saved.apiKey);key=saved.apiKey;configured=true;
                    credential=ServerCredentialState.saved(true,"Key saved securely · server not yet verified");setupCache();}}
        }catch(Exception e){configured=false;credential=ServerCredentialState.saved(false,"Saved caregiver key is unavailable; enter it again");}
        ready=true;publish(configured?(cacheBlocked?"Cached alerts need attention; existing file retained":credential.status)
            :("No key saved".equals(credential.status)?"Set up a separate caregiver key to read server alerts":credential.status));
        if(monitoring)schedule(0);
    }
    private void setupCache()throws Exception{
        binding=digest(endpoint+"\n"+RelayPolicy.EXPECTED_DEVICE_ID+"\n"+key);
        cache=new AtomicFile(new File(context.getFilesDir(),"server_alerts_"+binding+".json"));
        cacheBlocked=false;cachedServerId="";inbox=new RemoteAlertInbox();lastSync=0;
        if(!cache.getBaseFile().exists()&&!new File(cache.getBaseFile()+".bak").exists())return;
        try(FileInputStream input=cache.openRead()){
            JSONObject body=ServerAuthWire.response(input,2*1024*1024);
            if(body.optInt("version")!=1||!binding.equals(body.optString("binding"))||!endpoint.equals(body.optString("endpoint"))
                    ||!RelayPolicy.EXPECTED_DEVICE_ID.equals(body.optString("device_id")))throw new IllegalArgumentException("Invalid cache identity.");
            cachedServerId=ServerAuthWire.text(body,"server_id",100);
            List<RemoteAlertInbox.Entry> entries=parseEntries(body.getJSONArray("alerts"),RemoteAlertInbox.MAX_ENTRIES);
            inbox=RemoteAlertInbox.restore(entries,ServerAuthWire.integer(body,"revision"));lastSync=ServerAuthWire.integer(body,"last_sync_ms");
            if(lastSync<0)throw new IllegalArgumentException("Invalid sync time.");
        }catch(Exception e){cacheBlocked=true;}
    }
    private boolean verifyOnWorker(long epoch){
        if(!current(epoch))return false;
        if(!configured){publish("No caregiver key saved");return false;}
        HttpsURLConnection c=null;
        try{
            publish("Verifying caregiver permissions…");c=open("/auth/status?device_id="+RelayPolicy.EXPECTED_DEVICE_ID);active=c;
            if(!current(epoch))return false;int code=c.getResponseCode();if(!current(epoch))return false;
            if(code!=200){handleHttp(code);return false;}
            JSONObject body;try(InputStream input=c.getInputStream()){body=ServerAuthWire.response(input,RelayPolicy.MAX_RESPONSE_BYTES);}
            ServerCredentialState candidate=ServerAuthWire.verified(body,"alerts:read",System.currentTimeMillis());
            if(!cachedServerId.isEmpty()&&!cachedServerId.equals(candidate.serverId))throw new IllegalArgumentException("Server identity changed.");
            if(!current(epoch))return false;credential=candidate;cachedServerId=candidate.serverId;publish(candidate.status);return true;
        }catch(SSLException e){if(current(epoch)){credential=credential.failure("TLS verification failed · check server certificate and phone date");publish(credential.status);}}
        catch(IllegalArgumentException|org.json.JSONException e){if(current(epoch)){credential=credential.failure("Verification failed · identity, permission, or response mismatch");publish(credential.status);}}
        catch(Exception e){if(current(epoch)){credential=credential.failure("Server unreachable or verification unavailable · key kept securely");publish(credential.status);}}
        finally{if(active==c)active=null;if(c!=null)c.disconnect();}return false;
    }
    private void refreshOnWorker(long epoch){
        try{
            if(!current(epoch))return;
            if(authRejected){publish("Caregiver key rejected · save a corrected key or explicitly verify again");return;}
            if(cacheBlocked){publish("Cached alerts cannot be read safely; retained on phone for recovery");return;}
            if(!credential.allows("alerts:read",System.currentTimeMillis())&&!verifyOnWorker(epoch))return;
            for(int page=0;page<4&&current(epoch);page++){
                HttpsURLConnection c=null;
                try{
                    c=open("/alerts?device_id="+RelayPolicy.EXPECTED_DEVICE_ID+"&after_revision="+inbox.revision+"&limit=50");active=c;
                    if(!current(epoch))return;int code=c.getResponseCode();if(!current(epoch))return;if(code!=200){handleHttp(code);return;}
                    JSONObject body;try(InputStream input=c.getInputStream()){body=ServerAuthWire.response(input,512*1024);}
                    validateIdentity(body);if(!"ok".equals(ServerAuthWire.text(body,"status",32)))throw new IllegalArgumentException("Invalid result.");
                    if(!"shadow".equals(body.optString("mode")))throw new IllegalArgumentException("Unexpected ML mode.");
                    ServerAuthWire.timestamp(ServerAuthWire.text(body,"server_time",100),false);
                    Object more=body.get("has_more");if(!(more instanceof Boolean))throw new IllegalArgumentException("Invalid pagination.");
                    RemoteAlertInbox candidate=inbox.page(parseEntries(body.getJSONArray("alerts"),50),ServerAuthWire.integer(body,"next_revision"),(Boolean)more);
                    long now=System.currentTimeMillis();if(!current(epoch))return;persist(candidate,now);
                    if(!current(epoch))return;inbox=candidate;lastSync=now;publish("Server alerts synced · ML remains in shadow mode");
                    if(!((Boolean)more))return;
                }finally{if(active==c)active=null;if(c!=null)c.disconnect();}
            }
            if(current(epoch))publish("More alert changes available · refresh again to continue");
        }catch(SSLException e){failure(epoch,"TLS verification failed · showing cached alerts");}
        catch(Exception e){failure(epoch,"Sync unconfirmed · cached alerts retained; cursor has not advanced for this page");}
        finally{if(monitoring&&current(epoch))schedule(15000);}
    }
    private void validateIdentity(JSONObject body)throws Exception{
        if(!RelayPolicy.EXPECTED_DEVICE_ID.equals(ServerAuthWire.text(body,"device_id",100))
                ||!credential.serverId.equals(ServerAuthWire.text(body,"server_id",100)))throw new IllegalArgumentException("Unexpected server identity.");
    }
    private static List<RemoteAlertInbox.Entry> parseEntries(JSONArray array,int max)throws Exception{
        if(array.length()>max)throw new IllegalArgumentException("Too many alerts.");List<RemoteAlertInbox.Entry> entries=new ArrayList<>();
        for(int i=0;i<array.length();i++)entries.add(parseEntry(array.getJSONObject(i)));return entries;
    }
    private static RemoteAlertInbox.Entry parseEntry(JSONObject body)throws Exception{
        JSONObject evidence=body.getJSONObject("evidence");if(evidence.toString().length()>8192)throw new IllegalArgumentException("Evidence too large.");
        return new RemoteAlertInbox.Entry(ServerAuthWire.integer(body,"id"),ServerAuthWire.integer(body,"revision"),
            ServerAuthWire.text(body,"type",100),ServerAuthWire.text(body,"severity",32),ServerAuthWire.text(body,"source",32),
            ServerAuthWire.text(body,"status",32),ServerAuthWire.text(body,"device_id",100),ServerAuthWire.optionalText(body,"patient_id",100),
            ServerAuthWire.optionalText(body,"reading",1000),ServerAuthWire.timestamp(ServerAuthWire.text(body,"occurred_at",100),false),
            ServerAuthWire.timestamp(ServerAuthWire.text(body,"updated_at",100),false),ServerAuthWire.timestamp(ServerAuthWire.optionalText(body,"acknowledged_at",100),true),
            ServerAuthWire.timestamp(ServerAuthWire.optionalText(body,"resolved_at",100),true),ServerAuthWire.optionalText(body,"rule_version",100),
            ServerAuthWire.optionalText(body,"model_version",100),evidence.toString());
    }
    private void persist(RemoteAlertInbox candidate,long sync)throws Exception{
        if(cacheBlocked)throw new java.io.IOException("Cache unavailable.");JSONObject body=new JSONObject();JSONArray entries=new JSONArray();
        for(RemoteAlertInbox.Entry e:candidate.entries())entries.put(new JSONObject().put("id",e.id).put("revision",e.revision)
            .put("type",e.type).put("severity",e.severity).put("source",e.source).put("status",e.status).put("device_id",e.deviceId)
            .put("patient_id",e.patientId).put("reading",e.reading).put("occurred_at",iso(e.occurredAt)).put("updated_at",iso(e.updatedAt))
            .put("acknowledged_at",e.acknowledgedAt==0?JSONObject.NULL:iso(e.acknowledgedAt)).put("resolved_at",e.resolvedAt==0?JSONObject.NULL:iso(e.resolvedAt))
            .put("rule_version",e.ruleVersion).put("model_version",e.modelVersion).put("evidence",new JSONObject(e.evidence)));
        body.put("version",1).put("binding",binding).put("endpoint",endpoint).put("device_id",RelayPolicy.EXPECTED_DEVICE_ID)
            .put("server_id",credential.serverId).put("revision",candidate.revision).put("last_sync_ms",sync).put("alerts",entries);
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>2*1024*1024)throw new java.io.IOException("Cache full.");
        FileOutputStream output=null;try{output=cache.startWrite();output.write(bytes);output.flush();output.getFD().sync();cache.finishWrite(output);}
        catch(Exception e){if(output!=null)cache.failWrite(output);throw e;}
    }
    private static String iso(long time){java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",java.util.Locale.US);f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));return f.format(new java.util.Date(time));}
    private HttpsURLConnection open(String path)throws Exception{
        Network wifi=null;if(connectivity!=null)for(Network network:connectivity.getAllNetworks()){
            NetworkCapabilities caps=connectivity.getNetworkCapabilities(network);
            if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){wifi=network;break;}}
        return ServerAuthWire.open(wifi,ServerAuthWire.origin(endpoint)+path,key,tls);
    }
    private void handleHttp(int code){
        if(code==401||code==403){authRejected=true;credential=credential.failure("Key expired, revoked, or permission denied (HTTP "+code+")");publish(credential.status+" · cached alerts retained");}
        else publish("Server request unconfirmed (HTTP "+code+") · cached alerts retained");
    }
    private void failure(long epoch,String message){if(current(epoch))publish(message);}
    private void schedule(long delay){if(closed||!monitoring)return;if(scheduled!=null)scheduled.cancel(false);scheduled=worker.schedule(()->refreshOnWorker(generation.get()),delay,TimeUnit.MILLISECONDS);}
    private long invalidate(){long epoch=generation.incrementAndGet();HttpsURLConnection c=active;if(c!=null)try{cancellation.execute(c::disconnect);}catch(RejectedExecutionException ignored){}return epoch;}
    private boolean current(long epoch){return !closed&&epoch==generation.get();}
    private void execute(Runnable runnable){try{worker.execute(runnable);}catch(RejectedExecutionException ignored){}}
    private void publish(String value){state=value;main.post(()->{if(!closed)listener.onChanged();});}
    private static String digest(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();}
}
