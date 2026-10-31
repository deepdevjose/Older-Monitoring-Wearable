package org.caremonitor.demo;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real HTTPS/SPP report campaign in a separate app and database, never a UI fixture. */
public final class ReportSystemInstrumentation extends Instrumentation {
    private static final String BASE="https://127.0.0.1:19444/api/v1";
    private final JSONObject evidence=new JSONObject();
    private final JSONArray apiTimes=new JSONArray(),noticeTimes=new JSONArray(),failedNotices=new JSONArray();
    private Context context; private Activity activity; private ApiSession adult,caregiver;
    private Bundle arguments; private int checks,captures;
    private boolean atlas;
    private interface Job {void run() throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private void main(Job job)throws Exception {AtomicReference<Exception> error=new AtomicReference<>();runOnMainSync(()->{try{job.run();}catch(Exception e){error.set(e);}});if(error.get()!=null)throw error.get();waitForIdleSync();}
    private Object field(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private void set(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private Object invoke(Object object,String name,Class<?>[] types,Object...values)throws Exception {Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,values);}
    private void check(boolean value,String message){checks++;if(!value)throw new IllegalStateException(message);}
    private void waitFor(String message,int seconds,java.util.concurrent.Callable<Boolean> condition)throws Exception {
        long until=SystemClock.elapsedRealtime()+seconds*1000L;while(SystemClock.elapsedRealtime()<until){if(condition.call()){checks++;return;}Thread.sleep(100);}throw new IllegalStateException(message);
    }
    private JSONObject request(ApiSession owner,String method,String path,JSONObject body)throws Exception {
        return ApiClient.request(context,BASE,owner==null?null:owner.token,method,path,body);
    }
    private ApiSession register(String role,String name)throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        JSONObject response=request(null,"POST","/auth/register",new JSONObject().put("email",role+"-"+suffix+"@example.invalid")
            .put("password",UUID.randomUUID().toString()+UUID.randomUUID()).put("display_name",name).put("role",role).put("educational_ack",true));
        return new ApiSession(BASE,response);
    }
    private void save(ApiSession account)throws Exception {
        JSONObject user=new JSONObject().put("id",account.id).put("role",account.role).put("display_name",account.name).put("email",account.email);
        ApiSession.save(context,BASE,new JSONObject().put("access_token",account.token).put("expires_at",account.expires).put("user",user));
    }
    private void launch(ApiSession owner)throws Exception {
        close();save(owner);
        activity=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(()->{Configuration config=new Configuration(activity.getResources().getConfiguration());config.fontScale=1f;
            activity.getResources().updateConfiguration(config,activity.getResources().getDisplayMetrics());
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);invoke(activity,"render",new Class<?>[]{});invoke(activity,"refresh",new Class<?>[]{});});
        waitFor("Patient overview did not load over HTTPS",30,()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);
            main(()->ready.set(owner.elder()?owner.id.equals(field(activity,"patientId")):!((String)field(activity,"patientId")).isEmpty()));return ready.get();});
    }
    private void close()throws Exception {if(activity!=null){Activity old=activity;main(old::finish);activity=null;}}
    private void page(String name)throws Exception {main(()->invoke(activity,"openPage",new Class<?>[]{String.class},name));Thread.sleep(800);waitForIdleSync();}
    private void capture(String name,boolean bottom)throws Exception {
        main(()->{View decor=activity.getWindow().getDecorView();ScrollView scroll=findScroll(decor);
            View focus=activity.getCurrentFocus();if(focus!=null)focus.clearFocus();decor.setFocusableInTouchMode(true);decor.requestFocus();
            if(scroll!=null)scroll.scrollTo(0,bottom&&scroll.getChildCount()>0?Math.max(0,scroll.getChildAt(0).getHeight()-scroll.getHeight()):0);});
        captureCurrent(name);
    }
    private void captureCurrent(String name)throws Exception {
        Thread.sleep(350);main(()->{
            check((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"Secure window changed");
            View decor=activity.getWindow().getDecorView();check(decor.getWidth()>0&&decor.getHeight()>0,"Unmeasured capture");
            File folder=new File(context.getFilesDir(),"report-captures");if(!folder.isDirectory()&&!folder.mkdir())throw new IOException("Capture folder unavailable");
            Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
            try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){decor.draw(new Canvas(bitmap));check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out),"PNG encode failed");captures++;}finally{bitmap.recycle();}
        });
    }
    private ScrollView findScroll(View view){if(view instanceof ScrollView)return (ScrollView)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){ScrollView result=findScroll(group.getChildAt(i));if(result!=null)return result;}}return null;}
    private void capturePage(String role,String page)throws Exception {page(page);capture(role+"_"+page+"_top",false);capture(role+"_"+page+"_bottom",true);}
    private void captureMiddle(String name)throws Exception {
        main(()->{ScrollView scroll=findScroll(activity.getWindow().getDecorView());if(scroll!=null&&scroll.getChildCount()>0)scroll.scrollTo(0,Math.max(0,(scroll.getChildAt(0).getHeight()-scroll.getHeight())/2));});
        Thread.sleep(350);main(()->{
            View decor=activity.getWindow().getDecorView();check((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"Secure window changed");
            File folder=new File(context.getFilesDir(),"report-captures");if(!folder.isDirectory()&&!folder.mkdir())throw new IOException("Capture folder unavailable");
            Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
            try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){decor.draw(new Canvas(bitmap));check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out),"PNG encode failed");captures++;}finally{bitmap.recycle();}
        });
    }
    private void registrationDrafts()throws Exception {
        context.getSharedPreferences("caremonitor_endpoint",0).edit().putString("base",BASE).commit();
        activity=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(()->{Configuration c=new Configuration(activity.getResources().getConfiguration());c.fontScale=1f;activity.getResources().updateConfiguration(c,activity.getResources().getDisplayMetrics());activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);invoke(activity,"render",new Class<?>[]{});});
        capture("elder_signin_top",false);
        AccountSetup draft=new AccountSetup();draft.role="elder";draft.name="Alex Rivera";draft.email="alex@example.invalid";
        for(int step=0;step<4;step++){
            final int current=step;main(()->{draft.step=current;set(activity,"registering",true);set(activity,"setup",draft);invoke(activity,"render",new Class<?>[]{});});
            capture("elder_register_"+new String[]{"role","name","email","password"}[step]+"_top",false);
        }
        evidence.put("registration_captures","Native unsubmitted registration drafts with fictional fields; API accounts are created separately for the physical run.");close();
    }
    private void setupScreens()throws Exception {
        for(int step=0;step<3;step++){
            final int current=step;main(()->{set(activity,"deviceStep",current);invoke(activity,"openPage",new Class<?>[]{String.class},"device");if(current==2){LiveMonitorService service=(LiveMonitorService)field(activity,"monitor");service.setTransport("spp");service.scan();}});
            Thread.sleep(500);capture("elder_setup_"+new String[]{"ready","nearby","choose"}[step]+"_top",false);
        }
    }
    private void histories(String role)throws Exception {
        for(String metric:new String[]{"heart_rate_bpm","spo2_percent","surface_temp_c","ambient_temp_c","acceleration_g"}){
            main(()->invoke(activity,"openHistory",new Class<?>[]{String.class},metric));
            waitFor("Metric history did not finish",25,()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);main(()->ready.set(!(Boolean)field(activity,"historyLoading")&&field(activity,"historyData")!=null));return ready.get();});
            capture(role+"_history_"+metric+"_top",false);capture(role+"_history_"+metric+"_bottom",true);
        }
        for(String period:new String[]{"week","month"}){
            main(()->{set(activity,"historyPeriod",period);invoke(activity,"loadHistory",new Class<?>[]{});});
            waitFor("History period did not finish",25,()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);main(()->ready.set(!(Boolean)field(activity,"historyLoading")&&field(activity,"historyData")!=null));return ready.get();});
            capture(role+"_history_"+period+"_top",false);
        }
    }
    private void history(String role)throws Exception {
        main(()->invoke(activity,"openHistory",new Class<?>[]{String.class},"surface_temp_c"));
        waitFor("Metric history did not finish",25,()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);main(()->ready.set(!(Boolean)field(activity,"historyLoading")&&field(activity,"historyData")!=null));return ready.get();});
        capture(role+"_history_top",false);capture(role+"_history_bottom",true);
    }
    private void learning(String role,ApiSession owner)throws Exception {
        close();save(owner);activity=startActivitySync(new Intent(context,LearningActivity.class).putExtra("patient_id",adult.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor("Learning records did not load",30,()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);main(()->ready.set(field(activity,"data")!=null&&!(Boolean)field(activity,"busy")));return ready.get();});
        capture(role+"_learning_top",false);capture(role+"_learning_bottom",true);
        if(atlas){main(()->{View decor=activity.getWindow().getDecorView();ScrollView scroll=findScroll(decor);TextView critical=findText(decor,"Critical");check(scroll!=null&&critical!=null,"Critical result must be in saved model history");
            Rect bounds=new Rect();critical.getDrawingRect(bounds);scroll.offsetDescendantRectToMyCoords(critical,bounds);scroll.scrollTo(0,Math.max(0,bounds.top-16));});captureCurrent(role+"_learning_results");}
        close();
    }
    private TextView findText(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=findText(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private void seedLearning()throws Exception {
        JSONObject history=request(adult,"GET","/patients/"+adult.id+"/learning/readings",null);JSONArray examples=history.getJSONArray("examples");
        for(int i=0;i<examples.length();i++)request(adult,"POST","/patients/"+adult.id+"/learning/readings",new JSONObject().put("source","synthetic").put("sample_id",UUID.randomUUID().toString()).put("features",examples.getJSONObject(i).getJSONObject("features")));
        evidence.put("saved_synthetic_exercises",examples.length());
    }
    private LiveMonitorService monitor()throws Exception {AtomicReference<LiveMonitorService> service=new AtomicReference<>();main(()->service.set((LiveMonitorService)field(activity,"monitor")));return service.get();}
    private void physical()throws Exception {
        BluetoothAdapter adapter=((BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();check(adapter!=null&&adapter.isEnabled(),"Enable Bluetooth before report campaign");
        ArrayList<BluetoothDevice> candidates=new ArrayList<>();for(BluetoothDevice device:adapter.getBondedDevices())if(device.getName()!=null&&device.getName().startsWith("ElderSensors-"))candidates.add(device);
        check(candidates.size()==1,"Exactly one bonded ElderSensors wearable required");String address=candidates.get(0).getAddress();
        File file=new File(context.getFilesDir(),"report-setup.key");byte[] bytes=new byte[(int)file.length()];try(FileInputStream in=new FileInputStream(file)){check(in.read(bytes)==bytes.length,"Wearable key transfer incomplete");}
        save(adult);
        try{WearableKeys.save(context,adult,address,new String(bytes,StandardCharsets.US_ASCII).trim());}finally{Arrays.fill(bytes,(byte)0);file.delete();}
        launch(adult);waitFor("Wearable service not bound",15,()->monitor()!=null);
        if(atlas)setupScreens();
        main(()->{LiveMonitorService monitor=(LiveMonitorService)field(activity,"monitor");check(monitor.setTransport("spp"),"SPP transport unavailable");
            Intent connect=new Intent(context,LiveMonitorService.class).setAction(LiveMonitorService.ACTION_CONNECT).putExtra("address",address).putExtra("transport","spp");
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(connect);else context.startService(connect);});
        waitFor("No authenticated physical samples received",45,()->monitor().packetCount()>=3);
        int opticalEstimates=0,lowQualityEstimates=0;
        long start=SystemClock.elapsedRealtime();while(SystemClock.elapsedRealtime()-start<(atlas?45000:65000)){Thread.sleep(500);
            JSONObject current=monitor().snapshot();JSONObject optical=current==null?null:current.optJSONObject("max30102");
            if(optical!=null&&OpticalReading.value(optical.opt("heart_rate_bpm"))!=null&&OpticalReading.value(optical.opt("spo2_percent"))!=null){opticalEstimates++;if(!optical.optBoolean("heart_rate_valid")||!optical.optBoolean("spo2_valid"))lowQualityEstimates++;}
        }
        evidence.put("optical_observation_checks_with_both_estimates",opticalEstimates).put("optical_observation_checks_with_low_quality_estimates",lowQualityEstimates);
        if(atlas)check(opticalEstimates>0,"No computed optical estimate during capture; keep a finger on MAX30102 and repeat");
        LiveMonitorService service=monitor();evidence.put("physical_samples_received",service.packetCount());
        JSONObject snapshot=service.snapshot();JSONObject max=snapshot.optJSONObject("max30102"),temp=snapshot.optJSONObject("gy906"),mpu=snapshot.optJSONObject("mpu6050");
        evidence.put("observed_sensors",new JSONObject().put("optical_status",max==null?"missing":max.optString("status"))
            .put("heart_rate_valid",max!=null&&max.optBoolean("heart_rate_valid")).put("spo2_valid",max!=null&&max.optBoolean("spo2_valid"))
            .put("gy906_online",temp!=null&&temp.optBoolean("online")).put("mpu6050_online",mpu!=null&&mpu.optBoolean("online"))
            .put("legacy_light_field_present",snapshot.has("photoresistor")).put("legacy_microphone_field_present",snapshot.has("microphone")));
        main(()->invoke(activity,"refresh",new Class<?>[]{}));Thread.sleep(1800);
        page("home");capture("elder_live_home_top",false);capture("elder_live_home_bottom",true);
        if(atlas)captureMiddle("elder_live_home_middle");
        main(()->{Field step=activity.getClass().getDeclaredField("deviceStep");step.setAccessible(true);step.setInt(activity,3);});
        capturePage("elder","device");history("elder");capturePage("elder","reading_details");
        if(atlas){
            histories("elder");
            capturePage("elder","alerts");capturePage("elder","care");capturePage("elder","account");
            for(String name:new String[]{"notification_settings","privacy","connection","security","about"})capturePage("elder",name);
            evidence.put("adult_screens_captured_while_gateway_active",true);
        }
        waitFor("Upload queue did not drain",30,()->monitor().queuedCount()==0);
        evidence.put("physical_pending_uploads",service.queuedCount()).put("physical_server_confirmed_at_ms",service.uploadedAt());
        check(service.uploadedAt()>0,"No server upload acknowledgement observed");
        main(service::stopMonitoring);Thread.sleep(700);
        JSONObject retained=request(adult,"GET","/patients/"+adult.id+"/readings?limit=500",null);
        JSONArray readings=retained.getJSONArray("readings");evidence.put("retained_device_readings",readings.length());check(readings.length()>0,"Server retained no wearable samples");
        JSONObject one=readings.getJSONObject(0);evidence.put("representative_reading",new JSONObject().put("record_id",one.get("record_id")).put("sample_id",one.get("sample_id")).put("captured_at",one.get("captured_at")).put("source",one.get("source")));
    }
    private void screenshotRoles()throws Exception {
        seedLearning();
        request(adult,"POST","/events",new JSONObject().put("type","sos").put("confirmed",true).put("source","synthetic").put("occurred_at",ApiSession.utc(System.currentTimeMillis())));
        launch(adult);capturePage("elder","home");capturePage("elder","care");capturePage("elder","alerts");capturePage("elder","account");learning("elder",adult);
        launch(caregiver);capturePage("caregiver","home");capturePage("caregiver","care");capturePage("caregiver","alerts");history("caregiver");capturePage("caregiver","reading_details");capturePage("caregiver","account");learning("caregiver",caregiver);
        if(atlas){launch(caregiver);histories("caregiver");for(String name:new String[]{"notification_settings","connection","security","about"})capturePage("caregiver",name);}
    }
    private void timings()throws Exception {
        for(int i=0;i<30;i++){long start=SystemClock.elapsedRealtimeNanos();request(caregiver,"GET","/patients/"+adult.id+"/overview",null);apiTimes.put((SystemClock.elapsedRealtimeNanos()-start)/1e6);}
        evidence.put("api_overview_roundtrip_ms",apiTimes);
        launch(caregiver);main(()->{Intent intent=new Intent(context,NotificationChannelService.class).setAction("start");if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);});
        waitFor("Notification connection did not start",20,()->NotificationChannelService.running());
        int trials=arguments==null?0:Integer.parseInt(arguments.getString("notification_trials","0"));
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        for(int i=0;i<trials;i++){
            long next=SystemClock.elapsedRealtime()+7000;int previous=0;for(StatusBarNotification n:manager.getActiveNotifications())if("caremonitor-events".equals(n.getTag()))previous=Math.max(previous,n.getId());
            long start=SystemClock.elapsedRealtimeNanos();request(adult,"POST","/events",new JSONObject().put("type","sos").put("confirmed",true).put("source","synthetic").put("occurred_at",ApiSession.utc(System.currentTimeMillis())));
            long deadline=SystemClock.elapsedRealtime()+10000;boolean delivered=false;
            while(SystemClock.elapsedRealtime()<deadline&&!delivered){for(StatusBarNotification n:manager.getActiveNotifications())if("caremonitor-events".equals(n.getTag())&&n.getId()>previous){delivered=true;break;}if(!delivered)Thread.sleep(20);}
            if(delivered)noticeTimes.put((SystemClock.elapsedRealtimeNanos()-start)/1e6);else failedNotices.put(i+1);
            while(SystemClock.elapsedRealtime()<next)Thread.sleep(100);
        }
        evidence.put("caregiver_notification_trials",trials).put("native_notification_post_ms",noticeTimes).put("native_notification_timeouts",failedNotices)
            .put("notification_observation_window_seconds",10).put("notification_scope","Synthetic confirmed SOS dispatch on this phone to native notification posting for the linked caregiver on the same phone, screen on; HTTPS over USB. No second handset or audible/locked-screen acceptance.");
    }
    @Override public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try{context=getTargetContext();atlas=arguments!=null&&Boolean.parseBoolean(arguments.getString("capture_atlas","false"));check("org.caremonitor.reportqa".equals(context.getPackageName()),"Report campaign package isolation failed");
            evidence.put("started_at",ApiSession.utc(System.currentTimeMillis())).put("base",BASE).put("package",context.getPackageName()).put("production_application_touched",false);
            check("ok".equals(request(null,"GET","/health",null).getString("status")),"HTTPS unavailable");
            boolean learningOnly=arguments!=null&&Boolean.parseBoolean(arguments.getString("learning_only","false"));
            if(atlas&&!learningOnly)registrationDrafts();
            adult=register("elder","Alex Rivera");caregiver=register("caregiver","Sam Taylor");
            check(request(caregiver,"GET","/patients",null).getJSONArray("patients").length()==0,"Unapproved caregiver saw an adult");
            String invitation=request(adult,"POST","/invitations",null).getString("code");
            String id=request(caregiver,"POST","/link-requests",new JSONObject().put("code",invitation)).getString("id");
            request(adult,"POST","/link-requests/"+id+"/approve",null);
            check(request(caregiver,"GET","/patients",null).getJSONArray("patients").length()==1,"Approved caregiver cannot read adult");
            if(learningOnly){evidence.put("capture_mode","Saved synthetic model exercises only; no physical wearable acquisition");seedLearning();launch(adult);learning("elder",adult);launch(caregiver);learning("caregiver",caregiver);}
            else {request(caregiver,"POST","/patients/"+adult.id+"/reminders",new JSONObject().put("title","Agreed hydration reminder").put("instructions","Follow your agreed daily routine.").put("due_at",ApiSession.utc(System.currentTimeMillis()+3600000)));
                physical();screenshotRoles();timings();}
            check(failedNotices.length()==0,"Native notification delivery timed out; preserve all failed trials");
            evidence.put("result","PASS");code=Activity.RESULT_OK;
        }catch(Throwable failure){try{evidence.put("result","FAIL").put("detail",failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));}catch(Exception ignored){}}
        finally{try{if(context!=null){context.stopService(new Intent(context,LiveMonitorService.class));NotificationSync.cancel(context);}close();
            evidence.put("checks",checks).put("screenshots",captures).put("finished_at",ApiSession.utc(System.currentTimeMillis()));
            if(context!=null)try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"report-result.json"))){out.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}
        }catch(Exception failure){code=Activity.RESULT_CANCELED;}result.putString("result",code==Activity.RESULT_OK?"PASS":"FAIL");result.putInt("checks",checks);result.putInt("screenshots",captures);finish(code,result);}
    }
}
