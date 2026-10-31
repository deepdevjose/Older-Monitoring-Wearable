package org.caremonitor.demo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.text.Layout;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.CompoundButton;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Production UI, isolated package, synthetic fixtures. Never targets the installed care app. */
public final class PreviewInstrumentation extends Instrumentation {
    private Activity activity;
    private int checks;
    private Throwable mainFailure;
    private boolean captureScreenshots;
    private boolean onlyWearable;
    private int screenshots;
    private static final class DiscardExecutor extends AbstractExecutorService {
        private boolean closed;
        private int submitted;
        public void shutdown(){closed=true;}
        public List<Runnable> shutdownNow(){closed=true;return Collections.emptyList();}
        public boolean isShutdown(){return closed;}
        public boolean isTerminated(){return closed;}
        public boolean awaitTermination(long value,TimeUnit unit){return closed;}
        public void execute(Runnable action){submitted++;/* Deliberately discard all network work. */}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);captureScreenshots=args!=null&&"true".equals(args.getString("capture_screenshots"));onlyWearable=args!=null&&"true".equals(args.getString("only_wearable"));start();}
    @Override public void callActivityOnCreate(Activity target,Bundle state){
        // Install a nonexecuting worker before production onCreate/onResume can schedule anything.
        if(target instanceof MainActivity)disableWorker(target,"network");
        if(target instanceof LearningActivity)disableWorker(target,"worker");
        super.callActivityOnCreate(target,state);
    }
    private void disableWorker(Activity target,String name){try{((ExecutorService)get(target,name)).shutdownNow();set(target,name,new DiscardExecutor());}catch(Exception failure){throw new IllegalStateException(failure);}}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try{
            check("isolated target package",getTargetContext().getPackageName().equals("org.caremonitor.preview"));
            PackageInfo manifest=getTargetContext().getPackageManager().getPackageInfo("org.caremonitor.preview",android.content.pm.PackageManager.GET_PERMISSIONS|android.content.pm.PackageManager.GET_SERVICES);
            check("preview requests no permissions",manifest.requestedPermissions==null||manifest.requestedPermissions.length==0);
            check("preview declares no services",manifest.services==null||manifest.services.length==0);
            scopedOutbox();
            launch(MainActivity.class,null);
            main(()->{((Handler)get(activity,"main")).removeCallbacks((Runnable)get(activity,"poll"));((Handler)get(activity,"main")).removeCallbacks((Runnable)get(activity,"presentationTick"));set(activity,"visible",false);});
            if(onlyWearable){for(float scale:new float[]{1f,2f}){main(()->font(scale));wearableKeySetup(scale);}}
            else{for(float scale:new float[]{1f,2f}){
                main(()->font(scale));
                productOverview(false,scale);
                main(()->mainHistory(false,scale));
                historyDateScopes(false);
                idle();main(()->{layout(root());chartLayout();});
                capture("elder_history_"+fontLabel(scale));
                productOverview(true,scale);
                main(()->mainHistory(true,scale));
                historyDateScopes(true);
                idle();main(()->{layout(root());chartLayout();});
                capture("caregiver_history_"+fontLabel(scale));
                wearableKeySetup(scale);
            }
            helpFeedback();
            motion();
            close();
            for(float scale:new float[]{1f,2f})for(boolean caregiver:new boolean[]{false,true})learning(caregiver,scale);
            }
            result.putString("result","PASS");result.putString("scope",onlyWearable?"Isolated wearable-setup dialog synthetic privacy, role, cancel and large-font checks only; no server or Bluetooth operations.":"Isolated production UI with synthetic fixtures; role-specific Today, Updates, Care, Settings reading details, histories, person selection and reminder guards, stable live-callback views/scroll, expiring cached freshness/offline banner, help presentation callbacks without queued requests, loading/empty/error, accessibility, reduced/finite connection motion, learning labels/confidence and wearable-key privacy/role/cancel checks at 100/200 percent font. No real account, server or Bluetooth checks.");
            code=Activity.RESULT_OK;
        }catch(Throwable failure){result.putString("result","FAIL");result.putString("detail",failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));try{captureFailure();}catch(Throwable ignored){/* Keep the original assertion as the result. */}}
        finally{try{close();}catch(Exception failure){result.putString("result","FAIL");result.putString("detail","Preview cleanup failed");code=Activity.RESULT_CANCELED;}result.putInt("checks",checks);result.putInt("screenshots",screenshots);finish(code,result);}
    }
    /** Real encrypted storage, isolated package: an old queue must not block or join a new owner. */
    private void scopedOutbox()throws Exception{
        Context context=getTargetContext();String legacy="sensor_outbox_v2.enc";
        ApiSession old=account(false);
        JSONObject body=new JSONObject().put("access_token","fixture-token-never-used-for-network-123456789").put("expires_at","2099-01-01T00:00:00Z").put("user",new JSONObject().put("id","fixture-new-owner").put("role","elder").put("display_name","Fixture").put("email","fixture@example.invalid"));
        ApiSession current=ApiSession.save(context,old.base,body);String own=OutboxScope.file(current.id,current.base+"/readings");
        JSONArray original=new JSONArray();for(int i=0;i<200;i++)original.put(new JSONObject().put("sample_id","old-"+i).put("payload",new JSONObject()));
        SecureStore.write(context,legacy,new JSONObject().put("endpoint",old.base+"/readings").put("owner",old.id).put("samples",original).toString());
        SecureStore.write(context,own,new JSONObject().put("endpoint",current.base+"/readings").put("owner",current.id).put("samples",new JSONArray().put(new JSONObject().put("sample_id","current-only").put("payload",new JSONObject()))).toString());
        byte[] encrypted;try(java.io.InputStream input=new java.io.FileInputStream(new File(context.getFilesDir(),legacy))){encrypted=ApiClient.readLimited(input,1024*1024);}
        ServerRelay relay=new ServerRelay(context,(s,q,t)->{});
        java.util.concurrent.ScheduledThreadPoolExecutor worker=(java.util.concurrent.ScheduledThreadPoolExecutor)get(relay,"worker");
        try{
            check("new owner selects a separate encrypted outbox",worker.submit(()->(Boolean)invoke(relay,"selectScope",new Class<?>[]{ApiSession.class},current)).get(10,TimeUnit.SECONDS));
            check("old 200 do not block current owner's queue",relay.queuedCount()==1&&relay.retainedOtherCount()==200);
            check("returning to original owner retrieves original queue",worker.submit(()->(Boolean)invoke(relay,"selectScope",new Class<?>[]{ApiSession.class},old)).get(10,TimeUnit.SECONDS)&&relay.queuedCount()==200);
            check("returning to current owner restores only its own queue",worker.submit(()->(Boolean)invoke(relay,"selectScope",new Class<?>[]{ApiSession.class},current)).get(10,TimeUnit.SECONDS)&&relay.queuedCount()==1);
            relay.clearQueue();worker.submit(()->{}).get(10,TimeUnit.SECONDS);
            check("clearing current queue leaves old readings retained",relay.queuedCount()==0&&new JSONObject(SecureStore.read(context,legacy,1024*1024)).getJSONArray("samples").length()==200);
            try(java.io.InputStream input=new java.io.FileInputStream(new File(context.getFilesDir(),legacy))){check("original encrypted queue remains byte for byte unchanged",java.util.Arrays.equals(encrypted,ApiClient.readLimited(input,1024*1024)));}
            check("storage check never starts delivery",!(Boolean)get(relay,"running"));
        }finally{relay.close();worker.awaitTermination(5,TimeUnit.SECONDS);SecureStore.delete(context,legacy);SecureStore.delete(context,own);SecureStore.delete(context,"session_v2.enc");}
    }
    private ApiSession account(boolean caregiver)throws Exception{return new ApiSession("https://192.0.2.1:8443/api/v1",new JSONObject().put("access_token","fixture-token-never-used-for-network-123456789").put("expires_at","2099-01-01T00:00:00Z").put("user",new JSONObject().put("id",caregiver?"fixture-caregiver":"fixture-patient").put("role",caregiver?"caregiver":"elder").put("display_name",caregiver?"Fixture Caregiver":"Fixture Older Adult").put("email","fixture@example.invalid")));}
    private JSONObject currentOverview()throws Exception{
        JSONObject raw=new JSONObject()
            .put("max30102",new JSONObject().put("online",true).put("status","ok").put("finger_detected",true).put("heart_rate_valid",true).put("spo2_valid",true).put("heart_rate_bpm",72).put("spo2_percent",98))
            .put("gy906",new JSONObject().put("online",true).put("status","ok").put("object_temp_c",33.5).put("ambient_temp_c",24.5))
            .put("photoresistor",new JSONObject().put("adc_raw",1234))
            .put("mpu6050",new JSONObject().put("online",true).put("status","ok").put("accel_g",new JSONObject().put("x",0).put("y",0).put("z",1)));
        return new JSONObject().put("patient",new JSONObject().put("id","fixture-patient").put("display_name","Fixture Older Adult"))
            .put("freshness",new JSONObject().put("state","fresh").put("age_seconds",1))
            .put("alerts",new JSONArray().put(new JSONObject().put("id","fixture-alert-1").put("acknowledged",false)).put(new JSONObject().put("id","fixture-alert-2").put("acknowledged",false)).put(new JSONObject().put("id","fixture-alert-3").put("acknowledged",true)))
            .put("reading",new JSONObject().put("payload",raw).put("source","device_diagnostic").put("captured_at",ApiSession.utc(System.currentTimeMillis())));
    }
    private void overviewFixture(JSONObject value)throws Exception{set(activity,"overview",value);set(activity,"overviewReceivedAt",android.os.SystemClock.elapsedRealtime());}
    /** Exercise the finished information hierarchy, including role and selected-person guards. */
    private void productOverview(boolean caregiver,float scale)throws Exception{
        String role=caregiver?"caregiver":"elder",size=fontLabel(scale);
        main(()->{
            set(activity,"session",account(caregiver));set(activity,"patientId",caregiver?"fixture-patient":"");
            set(activity,"patients",new JSONArray().put(new JSONObject().put("id","fixture-patient").put("display_name","Fixture Older Adult")));
            overviewFixture(currentOverview());set(activity,"notifications",new JSONArray());set(activity,"reminders",new JSONArray());
            set(activity,"notice","");set(activity,"page","home");invoke(activity,"render");
            check("Today omits recent readings summary",text("Recent readings")==null);
            check("Today has one prominent heading",count(root(),v->v instanceof TextView&&"Today".contentEquals(((TextView)v).getText())&&((TextView)v).getTextSize()>30*activity.getResources().getDisplayMetrics().scaledDensity)==1);
            check("Today keeps prototype explanations in Settings",!prototypeCopy(root()));
            check("Today groups readings by role and everyday meaning",caregiver?text("Their readings")!=null&&text("Their surroundings")!=null&&text("Your readings")==null:text("Your readings")!=null&&text("Around you")!=null&&text("Their readings")==null);
            check("Today hides raw light ADC",find(root(),v->v instanceof TextView&&((TextView)v).getText().toString().contains("1234"))==null);
            check("Today omits the retired light sensor",descriptionPrefix("Light,")==null);
            check("Today offers role-appropriate help",caregiver?text("Ask for help")==null:text("Ask for help")!=null);
            check("Updates is a persistent navigation destination",description("Updates")!=null);
            if(caregiver){check("caregiver sees selected person's name on Today",text("Fixture Older Adult")!=null||text("✓  Fixture Older Adult")!=null);View updates=description("Needs your review");check("caregiver can review pending updates",updates!=null&&updates.isClickable()&&updates.isFocusable()&&text("Review updates")==null);updates.performClick();check("pending update row opens review destination",get(activity,"page").equals("alerts"));set(activity,"page","home");invoke(activity,"render");}
            else check("older adult can open their care plan",text("View my care plan")!=null);
            secure();
        });
        idle();main(()->{layout(root());targets(root());View tile=descriptionPrefix("Heart rate,");check("health reading cards use the available page width",tile!=null&&tile.getWidth()==((View)get(activity,"content")).getWidth()-((View)get(activity,"content")).getPaddingLeft()-((View)get(activity,"content")).getPaddingRight());});capture(role+"_today_"+size);
        stableReadings(caregiver);
        main(()->{
            description("Updates").performClick();
            check("Updates opens the persistent review destination",get(activity,"page").equals("alerts")&&description("Updates").isSelected());
            check("review actions belong to the caregiver",caregiver?text("Mark as reviewed")!=null:text("Mark as reviewed")==null);
            check("Updates offers notification preferences",text("Set up notifications")!=null);
            if(caregiver)check("Updates names the selected person",text("Fixture Older Adult")!=null);
        });
        idle();main(()->{layout(root());targets(root());});capture(role+"_updates_"+size);
        main(()->{
            description("Care").performClick();
            check("Care opens the relationship and plan destination",get(activity,"page").equals("care")&&description("Care").isSelected());
            check("care roles have appropriate invitation controls",caregiver?text("Request access")!=null&&text("Create an invitation code")==null:text("Create an invitation code")!=null&&text("Request access")==null);
            check("manual fall reporting belongs to the older adult",caregiver?text("Report a fall")==null:text("Report a fall")!=null);
            if(caregiver)check("Care names the selected person",text("Fixture Older Adult")!=null);
        });
        idle();main(()->{layout(root());targets(root());});capture(role+"_care_"+size);
        main(()->{set(activity,"page","account");invoke(activity,"render");check("Settings owns reading details",description("Reading details")!=null);check("Settings keeps learning and server controls",text("Learning insights")!=null&&text("Server connection")!=null);View about=description("About CareMonitor");check("About information is accessible from Settings",about!=null&&about.isClickable()&&about.isFocusable());about.performClick();check("About opens under Settings navigation",get(activity,"page").equals("about")&&description("Settings").isSelected());check("About retains educational and clinical limitations",prototypeCopy(root()));invoke(activity,"goBack");check("About returns to Settings",get(activity,"page").equals("account"));});
        idle();main(()->{layout(root());targets(root());});capture(role+"_settings_"+size);
        main(()->{
            View details=description("Reading details");check("Reading details action is operable",details.isClickable()&&details.isFocusable());details.performClick();
            check("reading details opens its Settings page",get(activity,"page").equals("reading_details"));
            check("Recent readings is relocated to Settings",text("Recent readings")!=null);
            check("details omits the retired light channel",descriptionPrefix("Light,")==null&&find(root(),v->v instanceof TextView&&((TextView)v).getText().toString().contains("1234"))==null);
            check("details explains temperature limits",find(root(),v->v instanceof TextView&&((TextView)v).getText().toString().contains("not core body temperature"))!=null);
            check("caregiver cannot configure the local wearable",!caregiver||text("Set up my wearable")==null);
            check("only older adult can request saved-reading uploads",caregiver?text("Sync saved readings")==null:text("Sync saved readings")!=null);
            for(String label:new String[]{"Heart rate","Estimated oxygen","Surface temperature","Ambient temperature","Movement"})check("details retains metric history: "+label,descriptionPrefix(label+",")!=null);
            check("reading details belongs to Settings navigation",description("Settings")!=null&&description("Settings").isSelected());
        });
        idle();main(()->{layout(root());targets(root());});capture(role+"_reading_details_"+size);
        if(caregiver){reminderSelectionGuard();personSelection();}
        main(()->{
            set(activity,"page","reading_details");invoke(activity,"goBack");check("reading details Back returns to Settings",get(activity,"page").equals("account"));
            if(caregiver){
                set(activity,"patients",new JSONArray());set(activity,"patientId","");set(activity,"page","home");invoke(activity,"render");
                check("unlinked caregiver has a clear Care action",text("Link to a person")!=null);
                check("unlinked caregiver sees no other person's cards",descriptionPrefix("Heart rate,")==null&&descriptionPrefix("Light,")==null);
                set(activity,"page","reading_details");invoke(activity,"render");
                check("unlinked caregiver cannot inspect stale person's diagnostics",descriptionPrefix("Heart rate,")==null&&find(root(),v->v instanceof TextView&&((TextView)v).getText().toString().contains("1234"))==null);
            }
        });
    }
    private View dialogWithTitle(String title){
        if(Build.VERSION.SDK_INT<29)return null;
        for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews())if(window.isAttachedToWindow()&&find(window,v->v instanceof TextView&&title.contentEquals(((TextView)v).getText()))!=null)return window;
        return null;
    }
    /** A confirmation opened for one person must not mutate another person's plan. */
    private void reminderSelectionGuard()throws Exception{
        if(Build.VERSION.SDK_INT<29)return;
        for(boolean water:new boolean[]{true,false}){
            String title=water?"Water reminder for Fixture Older Adult?":"Reminder for Fixture Older Adult";
            main(()->{
                set(activity,"patients",new JSONArray().put(new JSONObject().put("id","fixture-patient").put("display_name","Fixture Older Adult")).put(new JSONObject().put("id","fixture-other-person").put("display_name","Fixture Second Person")));
                set(activity,"patientId","fixture-patient");overviewFixture(currentOverview());set(activity,"page","care");invoke(activity,"render");invoke(activity,water?"waterReminder":"reminderDialog");
            });
            idle();main(()->{
                View dialog=dialogWithTitle(title);check("reminder confirmation identifies its intended person",dialog!=null);
                DiscardExecutor worker=(DiscardExecutor)get(activity,"network");int before=worker.submitted;set(activity,"patientId","fixture-other-person");
                View confirm=dialog.findViewById(android.R.id.button1);check("reminder confirmation remains operable",confirm!=null&&confirm.performClick());
                check("changed person prevents any reminder mutation being queued",worker.submitted==before);
            });
            idle();
        }
    }
    /** The production sensor callback updates labels without replacing controls or scrolling. */
    private void stableReadings(boolean caregiver)throws Exception{
        final View[] content=new View[1],tile=new View[1],status=new View[1];final int[] y=new int[1];final String[] key=new String[1];
        main(()->{
            content[0]=(View)get(activity,"content");tile[0]=descriptionPrefix("Heart rate,");status[0]=(View)get(activity,"homeStatus");key[0]=(String)invoke(activity,"homeStructureKey");
            android.widget.ScrollView scroll=(android.widget.ScrollView)get(activity,"scroll");scroll.scrollTo(0,Math.round(180*activity.getResources().getDisplayMetrics().density));y[0]=scroll.getScrollY();
            ((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
            check("unchanged readings preserve the page object",get(activity,"content")==content[0]);
            check("unchanged readings preserve the metric control",descriptionPrefix("Heart rate,")==tile[0]);
            check("unchanged readings preserve connection decoration",get(activity,"homeStatus")==status[0]);
            check("unchanged readings preserve scroll position",scroll.getScrollY()==y[0]);
            check("connection decoration never invents a live device",((CareStatusView)status[0]).getState()==(caregiver?CareStatusView.State.WAITING:CareStatusView.State.OFFLINE)&&!((CareStatusView)status[0]).isAnimating());
            JSONObject updated=currentOverview();updated.getJSONObject("reading").getJSONObject("payload").getJSONObject("max30102").put("heart_rate_bpm",74);overviewFixture(updated);
            ((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
            check("new sensor values preserve navigation and controls",get(activity,"content")==content[0]&&descriptionPrefix("Heart rate,")==tile[0]&&get(activity,"homeStatus")==status[0]);
            check("new sensor value is shown exactly",descriptionPrefix("Heart rate, 74.0 bpm")!=null);
            JSONObject extreme=currentOverview();JSONObject optical=extreme.getJSONObject("reading").getJSONObject("payload").getJSONObject("max30102");
            optical.put("status","poor_signal").put("heart_rate_valid",false).put("spo2_valid",false).put("heart_rate_bpm",450).put("spo2_percent",130);
            overviewFixture(extreme);((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
            check("outlying heart rate remains visible despite quality flags",descriptionPrefix("Heart rate, 450.0 bpm")!=null);
            check("outlying oxygen remains visible despite quality flags",descriptionPrefix("Estimated oxygen, 130.0 %")!=null);
            overviewFixture(updated);((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
            check("new sensor values preserve scroll position",scroll.getScrollY()==y[0]);
            check("only readings changing preserves the refresh structure key",key[0].equals(invoke(activity,"homeStructureKey")));
            check("raw protocol and storage details stay off Today",text("Source: Wearable")==null&&find(root(),v->v instanceof TextView&&((TextView)v).getText().toString().contains("Last stored reading:"))==null);
            if(caregiver){
                JSONObject mismatched=currentOverview();mismatched.getJSONObject("patient").put("id","fixture-other-person");overviewFixture(mismatched);((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
                check("different person's payload cannot appear under the selected person",descriptionPrefix("Heart rate, Unavailable")!=null||descriptionPrefix("Heart rate, No valid reading")!=null);
            }
            overviewFixture(currentOverview());((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
            check("Today shows the age of a fresh saved reading",text("Last reading: just now")!=null);
            set(activity,"overviewReceivedAt",android.os.SystemClock.elapsedRealtime()-61000);((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
            check("cached freshness expires without a new server response",invoke(activity,"readingFreshness").equals("stale"));
            check("Today explains older saved readings",text("Last reading: 1 minute ago · saved")!=null);
            check("expired saved readings never trigger live motion",!((CareStatusView)status[0]).isAnimating());
            String offline="Connection unavailable. Showing saved information. Try Refresh when you are connected.";set(activity,"syncProblem",offline);invoke(activity,"updateSyncLabel");((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
            check("connection failure remains readable during value updates",text(offline)!=null&&text(offline).getVisibility()==View.VISIBLE);
            check("saved values remain accessible during a connection failure",descriptionPrefix("Heart rate, 72.0 bpm")!=null);
            set(activity,"syncProblem","");invoke(activity,"updateSyncLabel");overviewFixture(currentOverview());((LiveMonitorService.Listener)get(activity,"liveListener")).onLiveChanged();
        });
    }
    /** Selection is a visible person-first action and clears the previous person's values. */
    private void personSelection()throws Exception{
        if(Build.VERSION.SDK_INT<29)return;
        main(()->{
            set(activity,"patients",new JSONArray().put(new JSONObject().put("id","fixture-patient").put("display_name","Fixture Older Adult")).put(new JSONObject().put("id","fixture-other-person").put("display_name","Fixture Second Person")));
            set(activity,"patientId","fixture-patient");overviewFixture(currentOverview());set(activity,"page","home");invoke(activity,"render");
            check("care actions accept the current approved person",(Boolean)invoke(activity,"careSelectionCurrent",new Class<?>[]{ApiSession.class,String.class},get(activity,"session"),"fixture-patient"));
            check("multiple approved people have a clear switch action",text("Change person")!=null);text("Change person").performClick();
        });
        idle();main(()->{
            View dialog=null;for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews())if(window.isAttachedToWindow()&&find(window,v->v instanceof TextView&&"Whose information?".contentEquals(((TextView)v).getText()))!=null){dialog=window;break;}
            check("person switch asks whose information to show",dialog!=null);
            android.widget.ListView people=(android.widget.ListView)find(dialog,v->v instanceof android.widget.ListView);
            check("person switch offers only approved fixture people",people!=null&&people.getAdapter().getCount()==2);
            check("second person can be chosen",people.performItemClick(people.getChildAt(1),1,people.getAdapter().getItemId(1)));
            check("person switch changes the selected identity",get(activity,"patientId").equals("fixture-other-person"));
            check("care actions reject the previous person after selection changes",!(Boolean)invoke(activity,"careSelectionCurrent",new Class<?>[]{ApiSession.class,String.class},get(activity,"session"),"fixture-patient"));
            check("new person is named before showing any readings",text("Fixture Second Person")!=null);
            check("person switch clears prior readings immediately",((JSONObject)get(activity,"overview")).length()==0&&descriptionPrefix("Heart rate, 72.0 bpm")==null&&descriptionPrefix("Heart rate, 74.0 bpm")==null);
            check("person switch clears prior history",get(activity,"historyData")==null);
        });
    }
    /** Only presentation callbacks are exercised; no help request is sent or queued. */
    private void helpFeedback()throws Exception{
        final Object[] attempt=new Object[1];
        final int[] queued=new int[1];main(()->queued[0]=((DiscardExecutor)get(activity,"network")).submitted);
        String failure="We could not confirm your request. Check Updates before trying again. No caregiver response is confirmed.";
        String success="Request saved. Your approved care circle can review it. This does not confirm they have seen it.";
        for(float scale:new float[]{1f,2f}){
            main(()->{
                font(scale);set(activity,"session",account(false));set(activity,"patientId","");overviewFixture(currentOverview());set(activity,"helpOutcome","");set(activity,"page","home");
                Class<?> type=Class.forName("org.caremonitor.demo.MainActivity$HelpAttempt");java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(ApiSession.class);constructor.setAccessible(true);attempt[0]=constructor.newInstance(get(activity,"session"));
                set(activity,"helpAttempt",attempt[0]);invoke(attempt[0],"attach",new Class<?>[]{MainActivity.class},activity);invoke(activity,"render");
                check("pending help state is clear and prevents repeated activation",text("Sending request…")!=null&&!text("Sending request…").isEnabled()&&text("Saving your request…")!=null);
                invoke(attempt[0],"complete",new Class<?>[]{String.class},failure);
            });
            idle();main(()->{
                check("failed help confirmation gives an actionable outcome",text(failure)!=null&&text("Ask for help")!=null&&text("Ask for help").isEnabled());
                invoke(activity,"render");check("help outcome survives ordinary page rebuilds",text(failure)!=null);
                invoke(attempt[0],"complete",new Class<?>[]{String.class},success);
            });
            idle();main(()->{
                check("saved help request does not claim caregiver receipt",text(success)!=null);
                Bundle saved=new Bundle();callActivityOnSaveInstanceState(activity,saved);check("help outcome survives configuration state",success.equals(saved.getString("help_outcome")));
                set(activity,"session",account(true));set(activity,"helpOutcome","");set(activity,"patientId","fixture-patient");invoke(activity,"render");
                invoke(attempt[0],"complete",new Class<?>[]{String.class},success);
            });
            idle();main(()->{check("old account help callback cannot appear in a caregiver session",get(activity,"helpOutcome").equals("")&&text(success)==null&&text("Ask for help")==null);set(activity,"helpAttempt",null);});
        }
        main(()->check("help presentation fixtures queue no request",((DiscardExecutor)get(activity,"network")).submitted==queued[0]));
    }
    /** Test actual production motion without changing system animation or power preferences. */
    private void motion()throws Exception{
        final CareStatusView[] status=new CareStatusView[1];final TextView[] value=new TextView[1];
        final boolean[] timedPulse=new boolean[1];
        android.content.SharedPreferences appearance=getTargetContext().getSharedPreferences("caremonitor_appearance",0);
        boolean hadPreference=appearance.contains("reduce_motion"),previousPreference=appearance.getBoolean("reduce_motion",false);
        try{
        main(()->{
            set(activity,"session",account(false));set(activity,"page","account");invoke(activity,"render");
            android.widget.Switch control=(android.widget.Switch)description("Reduce motion");check("Settings has an accessible reduced-motion switch",control!=null&&control.isEnabled()&&control.isClickable());
            boolean before=control.isChecked();control.performClick();check("Settings motion preference persists the chosen state",appearance.getBoolean("reduce_motion",before)==!before);control.performClick();check("Settings motion preference can be restored",appearance.getBoolean("reduce_motion",!before)==before);
            status[0]=new CareStatusView(activity);((ViewGroup)get(activity,"content")).addView(status[0]);
            value[0]=new TextView(activity);value[0].setText("72 bpm");((ViewGroup)get(activity,"content")).addView(value[0]);
            check("status decoration is excluded from accessibility",status[0].getImportantForAccessibility()==View.IMPORTANT_FOR_ACCESSIBILITY_NO&&!status[0].isFocusable()&&!status[0].isClickable());
        });
        idle();main(()->{
            CareStatusView view=status[0];view.setState(CareStatusView.State.LIVE);timedPulse[0]=view.isAnimating();
        });
        if(timedPulse[0]){
            float durationScale=android.provider.Settings.Global.getFloat(activity.getContentResolver(),android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f);
            android.os.SystemClock.sleep(Math.round(3100*Math.max(1f,Math.min(10f,durationScale))));idle();main(()->{check("live connection rings finish without a new reading",!status[0].isAnimating()&&status[0].getState()==CareStatusView.State.LIVE);});
        }
        main(()->{
            CareStatusView view=status[0];view.setState(CareStatusView.State.WAITING);check("waiting for data never pulses",!view.isAnimating());
            view.setActive(false);view.setState(CareStatusView.State.LIVE);check("inactive live status remains static",!view.isAnimating());
            view.setActive(true);check("returning to the foreground never replays a pulse",!view.isAnimating());
            view.setState(CareStatusView.State.WAITING);view.setState(CareStatusView.State.LIVE);
            boolean eligible=view.isAttachedToWindow()&&view.isShown()&&view.hasWindowFocus()&&view.getWindowVisibility()==View.VISIBLE&&CareMotion.allowed(activity);
            check("connection motion follows visibility and platform preferences",view.isAnimating()==eligible);
            Object pulse=get(view,"pulse");view.setState(CareStatusView.State.LIVE);check("identical status never restarts animation",get(view,"pulse")==pulse);
            view.setVisibility(View.INVISIBLE);check("hidden status stops connection motion",!view.isAnimating());view.setVisibility(View.VISIBLE);check("showing status never replays connection motion",!view.isAnimating());
            view.setState(CareStatusView.State.WAITING);view.setState(CareStatusView.State.LIVE);((ViewGroup)view.getParent()).removeView(view);check("detached status stops connection motion",!view.isAnimating());
            view.setState(null);check("missing status defaults to offline",view.getState()==CareStatusView.State.OFFLINE&&!view.isAnimating());
            CareMotion.fadeText(value[0],"73 bpm");check("changed values update immediately without invented samples","73 bpm".contentEquals(value[0].getText()));
            CareMotion.cancel(value[0]);check("cancelled text motion leaves content readable",value[0].getAlpha()==1f&&value[0].getTranslationY()==0f);
            value[0].setAlpha(.8f);CareMotion.fadeText(value[0],"73 bpm");check("unchanged values do not restart text motion",value[0].getAlpha()==.8f);value[0].setAlpha(1f);
            CareMotion.enter(value[0]);CareMotion.cancel(value[0]);check("cancelled page motion restores final position",value[0].getAlpha()==1f&&value[0].getTranslationY()==0f);
            check("isolated reduced-motion preference saves",appearance.edit().putBoolean("reduce_motion",true).commit());
            check("app reduced-motion preference overrides animation eligibility",!CareMotion.allowed(activity));
            ((ViewGroup)get(activity,"content")).addView(view);view.setState(CareStatusView.State.WAITING);view.setState(CareStatusView.State.LIVE);check("reduced motion keeps live connection static",!view.isAnimating());
            CareMotion.fadeText(value[0],"74 bpm");check("reduced motion changes actual text without a fade","74 bpm".contentEquals(value[0].getText())&&value[0].getAlpha()==1f);
            CareMotion.enter(value[0]);check("reduced motion leaves page at its final position",value[0].getAlpha()==1f&&value[0].getTranslationY()==0f);
        });
        }finally{
            android.content.SharedPreferences.Editor restore=appearance.edit();if(hadPreference)restore.putBoolean("reduce_motion",previousPreference);else restore.remove("reduce_motion");
            if(!restore.commit())throw new AssertionError("Preview reduced-motion preference was not restored");
        }
    }
    private String fontLabel(float scale){return scale==2f?"200":"100";}
    private void captureFailure()throws Exception{
        if(!captureScreenshots||activity==null)return;
        main(()->{
            View window=wearableDialog();if(window==null)window=root();if(window.getWidth()==0||window.getHeight()==0)return;
            Bitmap bitmap=Bitmap.createBitmap(window.getWidth(),window.getHeight(),Bitmap.Config.ARGB_8888);File directory=new File(getTargetContext().getFilesDir(),"ui-previews");if(!directory.isDirectory()&&!directory.mkdir())return;
            File target=new File(directory,"fixture_failure_"+fontLabel(activity.getResources().getConfiguration().fontScale)+"_top.png");if(target.exists()){bitmap.recycle();return;}
            try(FileOutputStream output=new FileOutputStream(target)){window.draw(new Canvas(bitmap));if(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))screenshots++;}finally{bitmap.recycle();}
        });
    }
    /** Canvas captures contain synthetic preview data only; FLAG_SECURE remains enabled. */
    private void capture(String name)throws Exception{
        if(!captureScreenshots)return;
        for(boolean bottom:new boolean[]{false,true}){
            main(()->{android.widget.ScrollView scroll=(android.widget.ScrollView)find(root(),v->v instanceof android.widget.ScrollView);if(scroll!=null)scroll.scrollTo(0,bottom?scroll.getChildAt(0).getHeight():0);});
            idle();main(()->{
                View decor=root();check("synthetic capture has measured viewport",decor.getWidth()>0&&decor.getHeight()>0);
                Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
                File directory=new File(getTargetContext().getFilesDir(),"ui-previews");check("synthetic capture directory is private",directory.isDirectory()||directory.mkdir());
                try(FileOutputStream output=new FileOutputStream(new File(directory,name+(bottom?"_bottom":"_top")+".png"))){
                    decor.draw(new Canvas(bitmap));java.util.HashSet<Integer> colors=new java.util.HashSet<>();int opaque=0,total=0;
                    for(int y=0;y<bitmap.getHeight();y+=Math.max(1,bitmap.getHeight()/100))for(int x=0;x<bitmap.getWidth();x+=Math.max(1,bitmap.getWidth()/100)){int pixel=bitmap.getPixel(x,y);colors.add(pixel);if((pixel>>>24)==255)opaque++;total++;}
                    check("synthetic viewport capture contains visible content",colors.size()>=8&&opaque>=total*.9);
                    check("synthetic viewport PNG encoded",bitmap.compress(Bitmap.CompressFormat.PNG,100,output));screenshots++;
                }finally{bitmap.recycle();}
                secure();
            });
        }
    }
    private void mainHistory(boolean caregiver,float scale)throws Exception{
        ApiSession session=account(caregiver);set(activity,"session",session);set(activity,"patientId",caregiver?"fixture-patient":"");set(activity,"patients",new JSONArray().put(new JSONObject().put("id","fixture-patient").put("display_name","Fixture Older Adult")));
        set(activity,"overview",new JSONObject().put("patient",new JSONObject().put("id","fixture-patient").put("display_name","Fixture Older Adult")));
        String[] labels={"Heart rate","Estimated oxygen","Surface temperature","Ambient temperature","Movement"};
        String[] metrics={"heart_rate_bpm","spo2_percent","surface_temp_c","ambient_temp_c","acceleration_g"};
        for(int i=0;i<labels.length;i++){
            set(activity,"page","home");invoke(activity,"render");check("home omits recent readings summary",text("Recent readings")==null);
            View tile=descriptionPrefix(labels[i]+",");check("metric card is accessible and clickable",tile!=null&&tile.isClickable()&&tile.isFocusable());tile.performClick();
            check("card opens matching history",get(activity,"page").equals("history")&&get(activity,"historyMetric").equals(metrics[i]));
            check("initial history chooses day",get(activity,"historyPeriod").equals("day"));
            if(caregiver)check("caregiver sees selected person's name",text("For Fixture Older Adult")!=null);
            if(i==0){
                click("Week");check("week selector changes range",get(activity,"historyPeriod").equals("week"));
                click("Month");check("month selector changes range",get(activity,"historyPeriod").equals("month"));click("Day");
            }
            set(activity,"historyLoading",false);set(activity,"historyError","");set(activity,"historyData",history(metrics[i],false));invoke(activity,"render");
            MetricHistoryChartView chart=(MetricHistoryChartView)find(root(),v->v instanceof MetricHistoryChartView);
            check("stored history mounts production chart",chart!=null);
            check("history keeps prototype explanations in Settings",!prototypeCopy(root()));
            check("chart accessibility names gaps and provenance",chart.getContentDescription().toString().contains("Missing intervals are gaps")&&chart.getContentDescription().toString().contains("Wearable sensor diagnostics"));
            check("chart keyboard navigation works",chart.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)));
            check("chart selection explains actual count",chart.getContentDescription().toString().contains("2 observations"));
            chart.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));check("missing interval remains missing",chart.getContentDescription().toString().contains("Selected:")&&chart.getContentDescription().toString().endsWith("No valid observations"));
            check("summary reports valid sample count",text("4 readings")!=null);
            if(i==0){
                JSONObject huge=history(metrics[i],false);JSONArray points=huge.getJSONArray("points");points.getJSONObject(0).put("value",1e308);points.getJSONObject(2).put("value",-1e308);
                chart.setHistory(huge);float ordinate=((Number)invoke(chart,"y",new Class<?>[]{double.class},1e308)).floatValue();
                check("unbounded finite estimates do not overflow chart coordinates",!Float.isNaN(ordinate)&&!Float.isInfinite(ordinate));
                chart.setHistory(history(metrics[i],false));
            }
            if(scale>=1.3f)check("large-font history summary stacks label and value",((android.widget.LinearLayout)text("Latest").getParent()).getOrientation()==android.widget.LinearLayout.VERTICAL);
            if(scale==2f)check("large font stacks period controls",((android.widget.LinearLayout)text("Day").getParent()).getOrientation()==android.widget.LinearLayout.VERTICAL);
            secure();
        }
        set(activity,"historyData",history("heart_rate_bpm",true).put("available_readings",new JSONObject().put("count",0)));set(activity,"historyMetric","heart_rate_bpm");invoke(activity,"render");
        check("account without saved readings is distinct from an empty date range",text("No saved readings yet")!=null&&text("No readings for this period")==null);
        check("only older adult can connect a wearable from empty history",caregiver?text("Connect my wearable")==null:text("Connect my wearable")!=null);
        if(!caregiver){text("Connect my wearable").performClick();check("empty-history connection action opens guided setup",get(activity,"page").equals("device"));set(activity,"page","history");set(activity,"historyLoading",false);}
        JSONObject empty=history("heart_rate_bpm",true);long latest=ApiSession.expiryMs("2026-10-25T16:00:00Z");empty.put("available_readings",new JSONObject().put("count",4).put("latest_at",ApiSession.utc(latest)));
        set(activity,"historyData",empty);set(activity,"historyMetric","heart_rate_bpm");invoke(activity,"render");check("empty range is explicit",text("No readings for this period")!=null);
        String person=(String)invoke(activity,"selectedPatient");click("View latest saved day");check("latest saved day preserves the selected person and measurement",person.equals(invoke(activity,"selectedPatient"))&&get(activity,"historyMetric").equals("heart_rate_bpm"));check("latest saved day selects the actual stored day",get(activity,"historyPeriod").equals("day")&&(Long)get(activity,"historyEndMillis")==latest+1000);
        set(activity,"historyLoading",true);invoke(activity,"render");check("loading state is visible",text("Loading your history…")!=null);
        set(activity,"historyLoading",false);set(activity,"historyError","Fixture server unreachable");set(activity,"historyData",null);invoke(activity,"render");check("network failure gives actionable history error",text("History could not be loaded")!=null&&text("Fixture server unreachable")!=null);
        long epoch=(Long)get(activity,"epoch"),request=(Long)get(activity,"historyRequest");
        check("same patient response is current",(Boolean)invoke(activity,"historyCurrent",new Class<?>[]{ApiSession.class,long.class,long.class,String.class,String.class,String.class,long.class},session,epoch,request,"fixture-patient","heart_rate_bpm",get(activity,"historyPeriod"),get(activity,"historyEndMillis")));
        set(activity,"epoch",epoch+1);check("superseded response cannot render",!(Boolean)invoke(activity,"historyCurrent",new Class<?>[]{ApiSession.class,long.class,long.class,String.class,String.class,String.class,long.class},session,epoch,request,"fixture-patient","heart_rate_bpm",get(activity,"historyPeriod"),get(activity,"historyEndMillis")));
        if(caregiver){set(activity,"patientId","different-patient");set(activity,"page","home");invoke(activity,"openHistory",new Class<?>[]{String.class},"heart_rate_bpm");check("unapproved patient cannot open history",get(activity,"page").equals("home"));}
        set(activity,"page","account");invoke(activity,"render");check("learning is available to both roles",text("Learning insights")!=null);check("connection belongs to signed-in settings",text("Server connection")!=null);
        set(activity,"patientId",caregiver?"fixture-patient":"");set(activity,"page","history");set(activity,"historyError","");set(activity,"historyData",history("heart_rate_bpm",false));invoke(activity,"render");
    }
    private JSONObject history(String metric,boolean empty)throws Exception{
        JSONArray points=new JSONArray().put(new JSONObject().put("at","2026-10-26T00:00:00Z").put("value",71).put("count",2)).put(new JSONObject().put("at","2026-10-26T08:00:00Z").put("value",JSONObject.NULL).put("count",0)).put(new JSONObject().put("at","2026-10-26T16:00:00Z").put("value",75).put("count",2));
        return new JSONObject().put("source","device_diagnostic").put("metric",metric).put("unit","bpm").put("period","day").put("start","2026-10-26T00:00:00Z").put("end","2026-10-27T00:00:00Z").put("points",empty?new JSONArray():points).put("coverage",new JSONObject().put("complete",true)).put("summary",new JSONObject().put("count",empty?0:4).put("average",73).put("min",71).put("max",75).put("latest",75).put("latest_at","2026-10-26T16:00:00Z"));
    }
    private View datePickerWindow(){
        if(Build.VERSION.SDK_INT<29)return null;
        for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews())if(window.isAttachedToWindow()&&find(window,v->v instanceof android.widget.DatePicker)!=null)return window;
        return null;
    }
    /** A date chosen for one metric/account/person cannot overwrite another history scope. */
    private void historyDateScopes(boolean caregiver)throws Exception{
        if(Build.VERSION.SDK_INT<29)return;
        for(String change:caregiver?new String[]{"none","person","metric","page","account"}:new String[]{"none","metric","page","account"}){
            final long[] request=new long[1];
            main(()->{
                set(activity,"session",account(caregiver));set(activity,"patientId",caregiver?"fixture-patient":"");
                set(activity,"patients",new JSONArray().put(new JSONObject().put("id","fixture-patient").put("display_name","Fixture Older Adult")).put(new JSONObject().put("id","fixture-other-person").put("display_name","Fixture Second Person")));
                set(activity,"page","history");set(activity,"historyMetric","heart_rate_bpm");set(activity,"historyPeriod","week");set(activity,"historyEndMillis",0L);set(activity,"historyLoading",false);set(activity,"historyError","");set(activity,"historyData",history("heart_rate_bpm",false));invoke(activity,"render");
                click("Choose date");request[0]=(Long)get(activity,"historyRequest");
            });
            idle();main(()->{
                View window=datePickerWindow();check("history date picker is available",window!=null);
                android.widget.DatePicker picker=(android.widget.DatePicker)find(window,v->v instanceof android.widget.DatePicker);check("history date picker excludes future days",picker.getMaxDate()<=System.currentTimeMillis());picker.updateDate(2026,java.util.Calendar.OCTOBER,10);
                if(change.equals("person"))set(activity,"patientId","fixture-other-person");
                else if(change.equals("metric"))set(activity,"historyMetric","spo2_percent");
                else if(change.equals("page"))set(activity,"page","home");
                else if(change.equals("account"))set(activity,"session",account(!caregiver));
                View confirm=window.findViewById(android.R.id.button1);check("history date confirmation is operable",confirm!=null&&confirm.isClickable()&&confirm.performClick());
            });
            // AlertDialog delivers its standard positive action through the main message queue.
            idle();main(()->{
                if(change.equals("none")){
                    java.util.Calendar expected=java.util.Calendar.getInstance();expected.set(2026,java.util.Calendar.OCTOBER,10,23,59,59);expected.set(java.util.Calendar.MILLISECOND,999);
                    check("date picker preserves person metric and period",get(activity,"page").equals("history")&&get(activity,"historyMetric").equals("heart_rate_bpm")&&get(activity,"historyPeriod").equals("week")&&invoke(activity,"selectedPatient").equals("fixture-patient"));
                    check("date picker loads through the selected local day's end (actual "+get(activity,"historyEndMillis")+", expected "+expected.getTimeInMillis()+")",(Long)get(activity,"historyEndMillis")==expected.getTimeInMillis());
                    check("date picker requests the selected date",(Long)get(activity,"historyRequest")>request[0]);
                }else check("stale date picker cannot change history after "+change+" change",(Long)get(activity,"historyRequest")==request[0]&&(Long)get(activity,"historyEndMillis")==0L);
            });idle();
        }
        main(()->{
            set(activity,"session",account(caregiver));set(activity,"patientId",caregiver?"fixture-patient":"");set(activity,"page","history");set(activity,"historyOrigin","home");set(activity,"historyMetric","heart_rate_bpm");set(activity,"historyPeriod","day");set(activity,"historyEndMillis",0L);set(activity,"historyLoading",false);set(activity,"historyError","");set(activity,"historyData",history("heart_rate_bpm",false));invoke(activity,"render");secure();
        });
    }
    /** Exercise the real production dialog while the isolated package has no saved account. */
    private void wearableKeySetup(float scale)throws Exception {
        if(Build.VERSION.SDK_INT<29)return;
        final String fixtureKey="0123456789abcdef0123456789abcdef";
        final EditText[] input=new EditText[1];
        final View[] dialog=new View[1];
        final int before=credentialFileCount();
        try {
            main(()->{
                check("preview has no persisted account before key UI",ApiSession.load(getTargetContext())==null);
                set(activity,"session",account(false));set(activity,"page","device");set(activity,"deviceStep",1);invoke(activity,"render");
                check("pairing copy removes serial assistant",find(root(),v->v instanceof TextView&&((TextView)v).getText().toString().contains("USB pairing assistant"))==null);
                check("pairing starts with in-app selection",text("Choose it in the next step. Your phone will ask you to confirm Bluetooth pairing if needed.")!=null&&text("Continue")!=null);
                check("first-time setup is explained as an assisted one-time step",text("First time? Keep the private setup card that came with your wearable nearby. Someone you trust can help you with this step. Only one phone can use the wearable at a time.")!=null);
                check("system Bluetooth settings is optional help",text("Need help with Bluetooth?")!=null);
                invoke(activity,"authoriseWearable",new Class<?>[]{String.class},"02:00:00:00:00:01");
            });
            // Dialog attachment and its OnShow message finish after show() returns.
            idle();main(()->{dialog[0]=wearableDialog();check("elder can open setup key dialog",dialog[0]!=null);});
            awaitLayout(dialog[0]);
            main(()->{
                View window=dialog[0];
                check("key dialog uses requested font scale",Float.compare(window.getResources().getConfiguration().fontScale,scale)==0);
                input[0]=(EditText)find(window,v->v instanceof EditText&&"Setup code from your card".contentEquals(v.getContentDescription()));
                check("setup key input available",input[0]!=null&&input[0].isEnabled());
                check("setup key starts masked",input[0].getTransformationMethod() instanceof PasswordTransformationMethod);
                check("setup key saving disabled",!input[0].isSaveEnabled());
                check("setup key autofill disabled",input[0].getImportantForAutofill()==View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                check("setup dialog has secure window",window.getLayoutParams() instanceof WindowManager.LayoutParams&&(((WindowManager.LayoutParams)window.getLayoutParams()).flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                input[0].requestRectangleOnScreen(new Rect(0,0,input[0].getWidth(),input[0].getHeight()),true);
                visible("setup key input",input[0]);textFits("setup key input",input[0],false);
                check("key input meets 56dp target",input[0].getHeight()+1>=56*activity.getResources().getDisplayMetrics().density);
                TextView show=(TextView)find(window,v->v instanceof TextView&&"Show setup key".contentEquals(((TextView)v).getText()));
                check("key visibility control accessible",show instanceof CompoundButton&&show.isClickable()&&show.isEnabled()&&"Show setup key".contentEquals(show.getContentDescription()));
                show.requestRectangleOnScreen(new Rect(0,0,show.getWidth(),show.getHeight()),true);
                visible("key visibility control",show);textFits("key visibility control",show,true);
                input[0].setText(fixtureKey);show.performClick();
                check("key reveal requires explicit toggle",!(input[0].getTransformationMethod() instanceof PasswordTransformationMethod)&&fixtureKey.contentEquals(input[0].getText()));
                show.performClick();check("key masking can be restored",input[0].getTransformationMethod() instanceof PasswordTransformationMethod&&fixtureKey.contentEquals(input[0].getText()));
                Bundle saved=new Bundle();callActivityOnSaveInstanceState(activity,saved);Parcel parcel=Parcel.obtain();
                try {
                    saved.writeToParcel(parcel,0);byte[] bytes=parcel.marshall();
                    check("key excluded from saved state UTF8",!contains(bytes,fixtureKey.getBytes(StandardCharsets.UTF_8)));
                    check("key excluded from saved state UTF16",!contains(bytes,fixtureKey.getBytes(StandardCharsets.UTF_16LE))&&!contains(bytes,fixtureKey.getBytes(StandardCharsets.UTF_16BE)));
                }finally{parcel.recycle();}
                for(int id:new int[]{android.R.id.button1,android.R.id.button2}) {
                    TextView action=window.findViewById(id);check("key dialog action operable",action!=null&&action.isEnabled()&&action.isClickable());
                    visible("key dialog action",action);textFits("key dialog action",action,true);
                    check("platform dialog action meets 48dp target",action.getHeight()+1>=48*activity.getResources().getDisplayMetrics().density);
                }
                check("connect action labelled", "Connect".contentEquals(((TextView)window.findViewById(android.R.id.button1)).getText()));
                check("Cancel works without saving",window.findViewById(android.R.id.button2).performClick());
            });
            idle();
            main(()->{
                check("Cancel dismisses key dialog",!dialog[0].isAttachedToWindow()&&wearableDialog()==null);
                check("Cancel clears key from view",input[0].getText().length()==0);
                check("key UI creates no persisted account",ApiSession.load(getTargetContext())==null);
                check("key UI creates no credential file",credentialFileCount()==before);
                check("key UI never starts account authentication",get(activity,"authentication")==null);
                set(activity,"session",account(true));set(activity,"page","device");invoke(activity,"render");
                invoke(activity,"authoriseWearable",new Class<?>[]{String.class},"02:00:00:00:00:01");
                check("caregiver cannot open private-key dialog",wearableDialog()==null);
                check("caregiver key guard requests no permission or transport",get(activity,"deviceList")==null&&"".equals(get(activity,"pendingAction")));
                secure();
            });
        }catch(Exception|Error failure){try{captureFailure();}catch(Exception ignored){}throw failure;}finally {
            main(()->{View window=wearableDialog();if(window!=null&&window.findViewById(android.R.id.button2)!=null)window.findViewById(android.R.id.button2).performClick();});
        }
    }
    private View wearableDialog(){
        if(Build.VERSION.SDK_INT<29)return null;
        for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews())
            if(window.isAttachedToWindow()&&find(window,v->v instanceof EditText&&"Setup code from your card".contentEquals(v.getContentDescription()))!=null)return window;
        return null;
    }
    private int credentialFileCount(){int count=0;File[] files=getTargetContext().getFilesDir().listFiles();if(files!=null)for(File file:files)if(file.getName().startsWith("wearable_"))count++;return count;}
    private static boolean contains(byte[] bytes,byte[] sought){outer:for(int i=0;i<=bytes.length-sought.length;i++){for(int j=0;j<sought.length;j++)if(bytes[i+j]!=sought[j])continue outer;return true;}return false;}
    private void visible(String label,View view){
        Rect visible=new Rect();check(label+" laid out",view.getWidth()>0&&view.getHeight()>0);
        check(label+" visible",view.getLocalVisibleRect(visible));
        visible.offset(-view.getScrollX(),-view.getScrollY());
        check(label+" fits viewport [local visible="+visible+", width="+view.getWidth()+", height="+view.getHeight()+"]",visible.left<=0&&visible.top<=0&&visible.right+1>=view.getWidth()&&visible.bottom+1>=view.getHeight());
    }
    private void textFits(String label,TextView view,boolean width){
        Layout layout=view.getLayout();check(label+" text measured",layout!=null&&layout.getLineCount()>0);
        check(label+" text fits height",layout.getHeight()<=view.getHeight()-view.getCompoundPaddingTop()-view.getCompoundPaddingBottom()+1);
        // getLineWidth includes a wrapping line's trailing spaces, which are not drawn.
        for(int line=0;line<layout.getLineCount();line++){check(label+" no ellipsis",layout.getEllipsisCount(line)==0);if(width)check(label+" text fits width",layout.getLineMax(line)<=view.getWidth()-view.getCompoundPaddingLeft()-view.getCompoundPaddingRight()+1);}
    }
    private void awaitLayout(View view)throws Exception {
        CountDownLatch frame=new CountDownLatch(1);ViewTreeObserver.OnPreDrawListener[] listener=new ViewTreeObserver.OnPreDrawListener[1];
        main(()->{listener[0]=()->{view.getViewTreeObserver().removeOnPreDrawListener(listener[0]);frame.countDown();return true;};view.getViewTreeObserver().addOnPreDrawListener(listener[0]);view.requestLayout();view.invalidate();});
        boolean drawn=frame.await(5,TimeUnit.SECONDS);
        main(()->{if(view.getViewTreeObserver().isAlive())view.getViewTreeObserver().removeOnPreDrawListener(listener[0]);check("key dialog layout completed",drawn);});idle();
    }
    private void learning(boolean caregiver,float scale)throws Exception{
        ApiSession session=account(caregiver);
        JSONObject body=new JSONObject().put("access_token",session.token).put("expires_at",session.expires).put("user",new JSONObject().put("id",session.id).put("role",session.role).put("display_name",session.name).put("email",session.email));
        ApiSession.save(getTargetContext(),session.base,body);
        launch(LearningActivity.class,"fixture-patient");
        main(()->{
            font(scale);set(activity,"busy",false);set(activity,"data",learningData(caregiver));invoke(activity,"render");
            check("learning heading exists",text("Learning insights")!=null);check("synthetic context remains visible",text("Educational data only")!=null);
            check("classification is visible as text",text("Warning")!=null);check("confidence is a model score",text("87.5% model confidence")!=null);
            check("caregiver has read-only exercises",caregiver?text("Classify and save")==null&&text("Shared learning history")!=null:text("Classify and save")!=null);
            check("body temperature is explicitly distinguished",find(root(),v->v instanceof TextView&&((TextView)v).getText().toString().contains("not body temperature"))!=null);
            secure();
        });
        idle();main(()->layout(root()));
        main(()->{
            if(!caregiver){set(activity,"pulse","not-a-number");invoke(activity,"classify");check("invalid model input has readable feedback",text("Enter all three numbers within the ranges shown below.")!=null);}
            invoke(activity,"failed",new Class<?>[]{Exception.class},new ApiClient.Failure(403,"fixture"));check("revocation clears saved classifications",text("Warning")==null&&text("87.5% model confidence")==null);
            check("revocation gives explanation",find(root(),v->v instanceof TextView&&((TextView)v).getText().toString().startsWith("Access is no longer available."))!=null);
            secure();
        });
        idle();main(()->layout(root()));close();
    }
    private JSONObject learningData(boolean caregiver)throws Exception{
        JSONObject ranges=new JSONObject().put("heart_rate_bpm",new JSONArray().put(40).put(200)).put("spo2_percent",new JSONArray().put(70).put(100)).put("body_temp_c",new JSONArray().put(30).put(42));
        JSONObject item=new JSONObject().put("classification","Warning").put("confidence",.875).put("source","synthetic").put("educational_only",true).put("created_at","2026-10-27T00:00:00Z").put("model_id","fixture-model").put("features",new JSONObject().put("heart_rate_bpm",101).put("spo2_percent",96).put("body_temp_c",36.5));
        return new JSONObject().put("patient_id","fixture-patient").put("source","synthetic").put("educational_only",true).put("can_add",!caregiver).put("selected_model","random_forest").put("model_id","fixture-model").put("feature_ranges",ranges).put("examples",new JSONArray()).put("items",new JSONArray().put(item));
    }
    private void launch(Class<?> type,String patient)throws Exception{Intent intent=new Intent(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(patient!=null)intent.putExtra("patient_id",patient);activity=startActivitySync(intent);idle();}
    private void close()throws Exception{if(activity!=null){Activity closing=activity;main(()->closing.finish());activity=null;idle();}}
    private void font(float scale){Configuration configuration=new Configuration(activity.getResources().getConfiguration());configuration.fontScale=scale;activity.getResources().updateConfiguration(configuration,activity.getResources().getDisplayMetrics());}
    private void secure(){check("secure window stays enabled",(activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);}
    private void chartLayout()throws Exception{
        MetricHistoryChartView chart=(MetricHistoryChartView)find(root(),v->v instanceof MetricHistoryChartView);check("history chart remains mounted for layout",chart!=null);
        int width=Math.round(280*activity.getResources().getDisplayMetrics().density);
        chart.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));chart.layout(0,0,width,chart.getMeasuredHeight());
        Bitmap image=Bitmap.createBitmap(width,chart.getMeasuredHeight(),Bitmap.Config.ARGB_8888);try{chart.draw(new Canvas(image));}finally{image.recycle();}
        float left=(Float)get(chart,"plotLeft"),right=(Float)get(chart,"plotRight");Paint instruction=new Paint();instruction.setTextSize(11*activity.getResources().getDisplayMetrics().scaledDensity);
        check("large-font chart instruction fits its plot",instruction.measureText("Touch to inspect")<=right-left);
        check("chart plot has positive drawing area",right>left&&(Float)get(chart,"plotBottom")>(Float)get(chart,"plotTop"));
        chart.requestLayout();
    }
    private void layout(View view){if(view instanceof TextView){TextView text=(TextView)view;if(text.getVisibility()==View.VISIBLE&&text.getLayout()!=null&&text.getText().length()>0){String value=text.getText().toString().replace('\n',' ');String label="screen text ["+value.substring(0,Math.min(value.length(),80))+"]";check(label+" is not ellipsized",text.getEllipsize()==null||text.getLayout().getEllipsisCount(text.getLayout().getLineCount()-1)==0);if(!(text instanceof EditText))textFits(label,text,true);}}if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)layout(((ViewGroup)view).getChildAt(i));}
    private void targets(View view){
        String description=view.getContentDescription()==null?"":view.getContentDescription().toString();
        if(view instanceof ViewGroup&&(description.equals("Today")||description.equals("Updates")||description.equals("Care")||description.equals("Settings"))){
            TextView label=(TextView)find(view,item->item instanceof TextView&&description.contentEquals(((TextView)item).getText()));
            check("bottom navigation words remain intact",label!=null&&label.getLayout()!=null&&label.getLayout().getLineCount()==1);
        }
        if(view.isClickable()&&view.isEnabled()&&view.getVisibility()==View.VISIBLE){
            float minimum=56*activity.getResources().getDisplayMetrics().density;
            check("interactive target has a measured size",view.getWidth()>0&&view.getHeight()>0);
            check("interactive target meets 56dp height",view.getHeight()+1>=minimum);
            check("interactive target meets 56dp width",view.getWidth()+1>=minimum);
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)targets(((ViewGroup)view).getChildAt(i));
    }
    private View root(){return activity.getWindow().getDecorView();}
    private interface Predicate{boolean test(View view);}
    private View find(View view,Predicate test){if(test.test(view))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),test);if(found!=null)return found;}return null;}
    private int count(View view,Predicate test){int total=test.test(view)?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)total+=count(((ViewGroup)view).getChildAt(i),test);return total;}
    private boolean prototypeCopy(View view){return find(view,v->{if(!(v instanceof TextView))return false;String value=((TextView)v).getText().toString().toLowerCase(java.util.Locale.ENGLISH);return value.contains("educational prototype")||value.contains("clinically validated")||value.contains("academic demo");})!=null;}
    private TextView text(String value){return (TextView)find(root(),v->v instanceof TextView&&value.contentEquals(((TextView)v).getText()));}
    private View description(String value){return find(root(),v->v.getContentDescription()!=null&&value.contentEquals(v.getContentDescription()));}
    private View descriptionPrefix(String value){return find(root(),v->v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(value));}
    private void click(String label){TextView found=text(label);check("action exists: "+label,found!=null);found.performClick();}
    private void check(String label,boolean passed){checks++;if(!passed)throw new AssertionError(label);}
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static Object invoke(Object object,String name)throws Exception{return invoke(object,name,new Class<?>[0]);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...args)throws Exception{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,args);}
    private interface Work{void run()throws Exception;}
    private void main(Work work)throws Exception{mainFailure=null;CountDownLatch finished=new CountDownLatch(1);runOnMainSync(()->{try{work.run();}catch(Throwable failure){mainFailure=failure;}finally{finished.countDown();}});if(!finished.await(10,TimeUnit.SECONDS))throw new AssertionError("UI timeout");if(mainFailure!=null){if(mainFailure instanceof Exception)throw (Exception)mainFailure;if(mainFailure instanceof Error)throw (Error)mainFailure;throw new RuntimeException(mainFailure);}}
    private void idle(){waitForIdleSync();}
}
