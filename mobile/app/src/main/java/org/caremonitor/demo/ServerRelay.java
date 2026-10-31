package org.caremonitor.demo;

import android.content.Context;
import android.net.*;
import android.os.*;
import org.json.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Encrypted durable outbox. Stable UUID acknowledgement, one elder + endpoint, delivery over Android's default network (including VPN). */
public final class ServerRelay {
    public interface Listener{void onStatus(String status,int queued,long lastSuccessMs);}
    private static final String FILE="sensor_outbox_v2.enc";
    private String activeFile=FILE;
    private volatile int retainedOther;
    private final Context context;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1);
    private final ExecutorService canceller=Executors.newSingleThreadExecutor();
    private final ArrayDeque<JSONObject> queue=new ArrayDeque<>();
    private final AtomicLong generation=new AtomicLong();private final AtomicInteger pending=new AtomicInteger();
    private volatile boolean closed,running,ready,blocked;
    private volatile int queued;private volatile long lastSuccess;
    private volatile String status="Loading your encrypted queue…",queueEndpoint="",queueOwner="";
    private volatile HttpsURLConnection active;
    private int failures;private long nextAllowed,dropped;
    private ScheduledFuture<?> timer;
    private ConnectivityManager networks;
    private final ConnectivityManager.NetworkCallback networkCallback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){wake();}
        @Override public void onLost(Network network){wake();}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){wake();}
    };
    private boolean callbackRegistered;
    public ServerRelay(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        worker.setRemoveOnCancelPolicy(true);worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        execute(()->{networks=(ConnectivityManager)this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
            try{String saved=SecureStore.read(this.context,FILE,4*1024*1024);if(!saved.isEmpty()){
                JSONObject body=new JSONObject(saved);queueEndpoint=RelayPolicy.endpoint(body.getString("endpoint"));queueOwner=body.getString("owner");
                JSONArray samples=body.getJSONArray("samples");if(samples.length()>RelayPolicy.MAX_QUEUE_SAMPLES)throw new IOException("Queue limit");
                for(int i=0;i<samples.length();i++){JSONObject entry=samples.getJSONObject(i);entry.getString("sample_id");entry.getJSONObject("payload");queue.add(entry);}
                dropped=body.optLong("dropped");lastSuccess=body.optLong("last_success");queued=queue.size();}}
            catch(Exception failure){blocked=true;publish("The encrypted queue could not be opened. You can clear it to start a new queue.");}
            try{networks.registerDefaultNetworkCallback(networkCallback);callbackRegistered=true;}
            catch(RuntimeException ignored){}
            ready=true;if(!blocked)publish("Sending paused · encrypted queue ready");});
    }
    public void refreshSession(){generation.incrementAndGet();cancelConnection();execute(()->{failures=0;nextAllowed=0;if(running)schedule(0);});}
    public void start(){if(closed)return;running=true;execute(()->schedule(0));}
    public void pause(){running=false;generation.incrementAndGet();cancelConnection();execute(()->{cancelTimer();publish("Sending paused · encrypted data saved");});}
    public void enqueue(String raw){
        if(closed||!running)return;
        final ApiSession session=ApiSession.load(context);
        if(session==null||!session.elder()){publish("Sign in as the older adult to send readings");return;}
        if(!RelayPolicy.bodyFits(raw)){publish("Sample rejected: size limit exceeded");return;}
        if(pending.incrementAndGet()>RelayPolicy.MAX_PENDING_ENQUEUES){pending.decrementAndGet();publish("Queue busy · new sample discarded");return;}
        final String owner=session.id,endpoint=session.base+"/readings";
        final long captured=System.currentTimeMillis();
        execute(()->{try{
            if(blocked){publish("Queue blocked · new sample discarded");return;}
            ApiSession current=ApiSession.load(context);
            if(current==null||!current.id.equals(owner)||!current.base.equals(session.base)){publish("Session changed · sample discarded");return;}
            JSONTokener tokens=new JSONTokener(raw);Object value=tokens.nextValue();
            if(!(value instanceof JSONObject)||tokens.nextClean()!=0)throw new JSONException("Invalid JSON");
            JSONObject payload=(JSONObject)value;
            if(!RelayPolicy.EXPECTED_DEVICE_ID.equals(payload.optString("device_id")))throw new JSONException("Unexpected device");
            if(!selectScope(current)){return;}
            if(queue.size()>=RelayPolicy.MAX_QUEUE_SAMPLES){dropped++;publish("Queue full (200) · new sample discarded");return;}
            if(queue.isEmpty()){queueEndpoint=endpoint;queueOwner=owner;}
            JSONObject envelope=new JSONObject().put("sample_id",UUID.randomUUID().toString()).put("captured_at",ApiSession.utc(captured))
                .put("source","device_diagnostic").put("payload",payload);
            queue.addLast(envelope);
            try{save();queued=queue.size();schedule(0);}catch(Exception failure){queue.removeLast();blocked=true;publish("The sample could not be saved · sending paused");}
        }catch(Exception failure){publish("Invalid sample discarded");}finally{pending.decrementAndGet();}});
    }
    public void clearQueue(){generation.incrementAndGet();cancelConnection();execute(()->{
        cancelTimer();try{ApiSession account=ApiSession.load(context);if(account==null||!account.elder()||!selectScope(account))return;
            SecureStore.delete(context,activeFile);queue.clear();queueEndpoint="";queueOwner="";queued=0;dropped=0;blocked=false;
            publish("Queue cleared at your request");if(running)schedule(0);
        }catch(Exception failure){blocked=true;publish("The queue could not be cleared");}});}
    private void save()throws Exception{
        JSONArray samples=new JSONArray();for(JSONObject entry:queue)samples.put(entry);
        SecureStore.write(context,activeFile,new JSONObject().put("version",2).put("endpoint",queueEndpoint).put("owner",queueOwner)
            .put("samples",samples).put("dropped",dropped).put("last_success",lastSuccess).toString());
    }
    private void pump(){
        timer=null;if(closed||!running||!ready||blocked)return;
        ApiSession session=ApiSession.load(context);
        if(session==null||!session.elder()){publish("Signed out or session expired · private queue paused");return;}
        if(!selectScope(session))return;
        if(!ApiClient.lanAllowed(context)){publish("Local network permission revoked · private queue paused");schedule(10000);return;}
        if(queue.isEmpty()){publish("Ready · waiting for wearable samples");return;}
        if(!RelayPolicy.mayDeliver(queueEndpoint,queueOwner,session.base+"/readings",session.id)){publish("Queue belongs to another account or server · sending blocked");return;}
        long wait=nextAllowed-SystemClock.elapsedRealtime();if(wait>0){schedule(wait);return;}
        if(!defaultNetworkAvailable()){publish("Waiting for your server · samples saved encrypted");schedule(3000);return;}
        final long epoch=generation.get();JSONObject head=queue.peekFirst();HttpsURLConnection connection=null;
        try{
            // Let Android route this socket over the app default network, preserving a configured VPN.
            // Binding to a physical Wi-Fi Network would bypass VPN routing and break remote ingestion.
            connection=(HttpsURLConnection)new URL(queueEndpoint).openConnection();active=connection;
            connection.setSSLSocketFactory(ApiClient.tls(context));connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Authorization","Bearer "+session.token);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] bytes=head.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
            if(!current(epoch))return;try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
            int code=connection.getResponseCode();if(!current(epoch))return;
            if(code>=200&&code<300){JSONObject response=new JSONObject(new String(ApiClient.readLimited(connection.getInputStream(),RelayPolicy.MAX_RESPONSE_BYTES),StandardCharsets.UTF_8));
                if(!RelayPolicy.isAcknowledgement(response.optString("status"),response.opt("record_id"))){retry("The server did not confirm storage");return;}
                long previous=lastSuccess;lastSuccess=System.currentTimeMillis();queue.removeFirst();
                try{save();}catch(Exception failure){queue.addFirst(head);lastSuccess=previous;blocked=true;publish("The server confirmed storage, but the queue could not be updated. Sending paused.");return;}
                queued=queue.size();failures=0;nextAllowed=SystemClock.elapsedRealtime()+RelayPolicy.MIN_SEND_INTERVAL_MS;
                publish("Sample received and confirmed by the server");if(!queue.isEmpty())schedule(RelayPolicy.MIN_SEND_INTERVAL_MS);
            }else if(RelayPolicy.shouldPauseForHttp(code)){
                publish(code==401?"Session expired · sign in again; samples retained":"The server rejected the sample ("+code+") · sending paused");
                // Pause fail-closed; a fresh authenticated session explicitly resumes delivery.
                running=false;
            }else retry("Server unavailable ("+code+")");
        }catch(SSLException failure){if(current(epoch))retry("The server certificate, hostname or date could not be verified");}
        catch(Exception failure){if(current(epoch))retry("Delivery pending · retrying with the same sample ID");}
        finally{if(active==connection)active=null;if(connection!=null)connection.disconnect();}
    }
    private boolean current(long epoch){return !closed&&running&&generation.get()==epoch;}
    /** Switch files, never ownership. The legacy encrypted queue remains untouched. */
    private boolean selectScope(ApiSession session){
        String endpoint=session.base+"/readings";
        if(RelayPolicy.mayDeliver(queueEndpoint,queueOwner,endpoint,session.id))return true;
        try{
            String target=OutboxScope.file(session.id,endpoint);
            String legacy=SecureStore.read(context,FILE,4*1024*1024);
            if(!legacy.isEmpty()){
                JSONObject old=new JSONObject(legacy);
                if(RelayPolicy.mayDeliver(RelayPolicy.endpoint(old.getString("endpoint")),old.getString("owner"),endpoint,session.id))target=FILE;
            }
            String saved=SecureStore.read(context,target,4*1024*1024);
            ArrayDeque<JSONObject> selected=new ArrayDeque<>();long selectedDropped=0,selectedSuccess=0;
            if(!saved.isEmpty()){
                JSONObject body=new JSONObject(saved);
                if(!RelayPolicy.mayDeliver(RelayPolicy.endpoint(body.getString("endpoint")),body.getString("owner"),endpoint,session.id))throw new IOException("Queue scope mismatch");
                JSONArray samples=body.getJSONArray("samples");if(samples.length()>RelayPolicy.MAX_QUEUE_SAMPLES)throw new IOException("Queue limit");
                for(int i=0;i<samples.length();i++){JSONObject item=samples.getJSONObject(i);item.getString("sample_id");item.getJSONObject("payload");selected.add(item);}
                selectedDropped=body.optLong("dropped");selectedSuccess=body.optLong("last_success");
            }
            int retained=0;
            File[] files=context.getFilesDir().listFiles();
            if(files!=null)for(File file:files){String name=file.getName();
                if(name.equals(target)||!(name.equals(FILE)||name.matches("sensor_outbox_scope_[0-9a-f]{64}\\.enc")))continue;
                String content=SecureStore.read(context,name,4*1024*1024);
                if(!content.isEmpty())retained+=new JSONObject(content).getJSONArray("samples").length();
            }
            queue.clear();queue.addAll(selected);activeFile=target;queueEndpoint=endpoint;queueOwner=session.id;
            queued=queue.size();retainedOther=retained;dropped=selectedDropped;lastSuccess=selectedSuccess;
            failures=0;nextAllowed=0;return true;
        }catch(Exception failure){publish("Saved queue could not be opened safely · sending paused");return false;}
    }
    private void retry(String message){failures++;long delay=RelayPolicy.retryDelayMs(failures);nextAllowed=SystemClock.elapsedRealtime()+delay;publish(message+" · retrying in "+delay/1000+" s");schedule(delay);}
    private boolean defaultNetworkAvailable(){
        if(networks==null)return true; // A request still fails safely if Android has no route.
        try{return networks.getActiveNetwork()!=null;}catch(RuntimeException ignored){return true;}
    }
    private void wake(){execute(()->{if(running)schedule(0);});}
    private void schedule(long delay){if(closed||!running||blocked)return;cancelTimer();timer=worker.schedule(this::pump,Math.max(0,delay),TimeUnit.MILLISECONDS);}
    private void cancelTimer(){if(timer!=null){timer.cancel(false);timer=null;}}
    private void cancelConnection(){HttpsURLConnection connection=active;if(connection!=null)try{canceller.execute(connection::disconnect);}catch(RejectedExecutionException ignored){}}
    private void execute(Runnable action){try{worker.execute(action);}catch(RejectedExecutionException ignored){}}
    private void publish(String text){status=text+(dropped>0?" · "+dropped+" discarded from this queue":"");main.post(()->{if(!closed)listener.onStatus(status,queued,lastSuccess);});}
    public String endpoint(){ApiSession session=ApiSession.load(context);return session==null?"":session.base+"/readings";}
    public boolean isConfigured(){ApiSession session=ApiSession.load(context);return ready&&session!=null&&session.elder();}
    public int queuedCount(){return queued;}public int retainedOtherCount(){return retainedOther;}public String status(){return status;}public long lastSuccessMs(){return lastSuccess;}
    public void close(){if(closed)return;closed=true;running=false;generation.incrementAndGet();cancelConnection();execute(()->{
        cancelTimer();if(callbackRegistered)try{networks.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
        canceller.shutdown();worker.shutdown();});}
}
