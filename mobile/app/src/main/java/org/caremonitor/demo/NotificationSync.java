package org.caremonitor.demo;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** Self-hosted durable inbox -> private native notifications. No external push service or SDK. */
final class NotificationSync {
    private static final int JOB=8720;
    private static final String CHANNEL="caremonitor_care",TAG="caremonitor-events";
    static void schedule(Context context){
        ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(
            new JobInfo.Builder(JOB,new ComponentName(context,NotificationJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000).setPersisted(true).build());
    }
    static void cancel(Context context){
        context.stopService(new Intent(context,NotificationChannelService.class));
        ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(JOB);
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        for(StatusBarNotification notification:manager.getActiveNotifications())if(TAG.equals(notification.getTag()))manager.cancel(TAG,notification.getId());
    }
    static boolean permitted(Context context){
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel=Build.VERSION.SDK_INT>=26?manager.getNotificationChannel(CHANNEL):null;
        return ApiClient.lanAllowed(context)&&(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            &&manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);
    }
    private static String key(ApiSession session)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((session.base+"|"+session.id).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte part:digest)out.append(String.format("%02x",part&255));return "inbox_"+out+".enc";
    }
    private static long cursor(Context context,String file)throws Exception{
        String saved=SecureStore.read(context,file,1024);return saved.isEmpty()?0:new JSONObject(saved).optLong("cursor",0);
    }
    static void sync(Context context)throws Exception{sync(context,false,()->true);}
    static void waitAndSync(Context context,BooleanSupplier active)throws Exception{sync(context,true,active);}
    private static void sync(Context context,boolean wait,BooleanSupplier active)throws Exception{
        ApiSession session=ApiSession.load(context);if(session==null)return;
        if(!permitted(context))throw new ApiClient.Failure(403,"Notifications or local network access are disabled.");
        String file=key(session);long start=cursor(context,file);
        JSONObject result=ApiClient.call(context,session,"GET",wait?"/notifications/wait?after="+start+"&timeout=20":"/notifications?after="+start,null,wait?30000:6000);
        deliver(context,session,file,result,active);
    }
    private static synchronized void deliver(Context context,ApiSession session,String file,JSONObject result,BooleanSupplier active)throws Exception{
        long last=cursor(context,file);JSONArray items=result.optJSONArray("items");if(items==null||!active.getAsBoolean()||!permitted(context))return;
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,"Care alerts",NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Care alerts and reminders from your server");channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(channel);}
        for(int i=0;i<items.length();i++){
            ApiSession current=ApiSession.load(context);
            if(!active.getAsBoolean()||current==null||!current.token.equals(session.token)||!current.id.equals(session.id)||!current.base.equals(session.base))return;
            JSONObject item=items.getJSONObject(i);int id=item.getInt("id");if(!NotificationCursor.shouldDeliver(id,last))continue;
            JSONObject data=item.optJSONObject("data");if(data==null)data=new JSONObject();
            Intent open=new Intent(context,MainActivity.class).putExtra("show_alerts",true).putExtra("patient_id",data.optString("patient_id","")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending=PendingIntent.getActivity(context,id,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,CHANNEL):new Notification.Builder(context);
            String title=data.optString("title","Care alert"),text=data.optString("message",data.optString("instructions","Open CareMonitor to review the alert."));
            Notification.Builder publicVersion=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,CHANNEL):new Notification.Builder(context);
            manager.notify(TAG,id,builder.setSmallIcon(R.drawable.ic_care_monitor).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion.setSmallIcon(R.drawable.ic_care_monitor)
                    .setContentTitle("CareMonitor").setContentText("You have a care alert").build()).build());
        }
        ApiSession current=ApiSession.load(context);
        if(active.getAsBoolean()&&current!=null&&current.token.equals(session.token)&&current.id.equals(session.id)&&current.base.equals(session.base))
            SecureStore.write(context,file,new JSONObject().put("cursor",NotificationCursor.advance(last,result.optLong("cursor",last))).toString());
    }
}
