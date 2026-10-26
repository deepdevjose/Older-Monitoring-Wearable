package org.caremonitor.demo;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.media.*;
import android.os.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Explicitly user-started foreground GPS or local RMS. Audio buffers never leave RAM. */
public final class PrivacySignalService extends Service implements LocationListener {
    public static final String START_LOCATION="location",START_AUDIO="audio",STOP="stop";
    private static final String CHANNEL="caremonitor_privacy";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService audioWorker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean locationPending=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean audioRunning;private AudioRecord recorder;private LocationManager locations;
    private volatile boolean gpsRunning;private volatile ApiSession owner;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile long lastUpload,lastImpact,lastConsentCheck,lastConsentVerified;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        ApiSession requested=ApiSession.load(this);
        if(requested==null||!requested.elder()||(owner!=null&&(!owner.id.equals(requested.id)||!owner.base.equals(requested.base)))){stopSelf();return START_NOT_STICKY;}
        owner=requested;lastConsentVerified=SystemClock.elapsedRealtime();
        String action=intent.getAction();
        try{
            if(START_LOCATION.equals(action)&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                JSONObject policy=new JSONObject(SecureStore.read(this,"privacy_"+owner.id+".enc",4096));
                if(!policy.optBoolean("location_consent")||!policy.has("home_lat")||!policy.has("home_lon")){stopSelf();return START_NOT_STICKY;}
                locations=(LocationManager)getSystemService(LOCATION_SERVICE);
                if(!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)){stopSelf();return START_NOT_STICKY;}
                gpsRunning=true;notification();locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,30000,15,this);
                handler.postDelayed(watchdog,10000);
            }else if(START_AUDIO.equals(action)&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
                JSONObject policy=new JSONObject(SecureStore.read(this,"privacy_"+owner.id+".enc",4096));
                if(!policy.optBoolean("audio_consent")){stopSelf();return START_NOT_STICKY;}
                if(audioRunning)return START_NOT_STICKY;audioRunning=true;notification();audioWorker.execute(this::measureAudio);handler.postDelayed(watchdog,10000);
            }else stopSelf();
        }catch(Exception failure){stopSelf();}
        return START_NOT_STICKY;
    }
    private boolean sameOwner(){ApiSession current=ApiSession.load(this);return current!=null&&owner!=null&&current.id.equals(owner.id)&&current.base.equals(owner.base);}
    private final Runnable watchdog=new Runnable(){public void run(){
        if(!sameOwner()||SystemClock.elapsedRealtime()-lastConsentVerified>300000){stopSelf();return;}
        if(SystemClock.elapsedRealtime()-lastConsentCheck>60000){lastConsentCheck=SystemClock.elapsedRealtime();worker.execute(()->{try{
            JSONObject consent=ApiClient.call(PrivacySignalService.this,owner,"GET","/consents",null);
            if((gpsRunning&&!consent.optBoolean("location"))||(audioRunning&&!consent.optBoolean("audio")))handler.post(()->stopSelf());
            else lastConsentVerified=SystemClock.elapsedRealtime();
        }catch(Exception ignored){}});}
        handler.postDelayed(this,10000);
    }};
    @Override public void onLocationChanged(Location point){
        if(!sameOwner()){stopSelf();return;}
        if(point.isFromMockProvider()||!point.hasAccuracy()||point.getAccuracy()>100||System.currentTimeMillis()-point.getTime()>120000)return;
        if(SystemClock.elapsedRealtime()-lastUpload<60000||!locationPending.compareAndSet(false,true))return;
        worker.execute(()->{try{
            JSONObject policy=new JSONObject(SecureStore.read(this,"privacy_"+owner.id+".enc",4096));
            if(!policy.optBoolean("location_consent"))return;
            float[] distance=new float[1];Location.distanceBetween(policy.getDouble("home_lat"),policy.getDouble("home_lon"),point.getLatitude(),point.getLongitude(),distance);
            double radius=Math.max(100,policy.optDouble("radius_m",200));
            // Uncertainty must lie entirely beyond the home boundary; never upload points within home.
            if(!PrivacyBoundary.mayShare(distance[0],point.getAccuracy(),radius,System.currentTimeMillis()-point.getTime(),point.isFromMockProvider()))return;
            if(!sameOwner())return;
            JSONObject body=new JSONObject().put("outside_home",true).put("latitude",point.getLatitude()).put("longitude",point.getLongitude())
                .put("accuracy_m",point.getAccuracy()).put("recorded_at",ApiSession.utc(point.getTime()));
            ApiClient.call(this,owner,"POST","/location",body);lastUpload=SystemClock.elapsedRealtime();
        }catch(Exception ignored){}finally{locationPending.set(false);}});
    }
    private void measureAudio(){
        short[] samples=new short[2048];
        try{
            int buffer=Math.max(samples.length*2,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buffer);
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException();recorder.startRecording();
            while(audioRunning&&sameOwner()){
                int count=recorder.read(samples,0,samples.length);if(count<=0)break;
                double sum=0;for(int i=0;i<count;i++){sum+=(double)samples[i]*samples[i];samples[i]=0;}
                double rms=Math.sqrt(sum/count)/32768.0;
                // A loudness cue only. No classifier, no raw sound storage, no automatic server event.
                if(rms>.65&&SystemClock.elapsedRealtime()-lastImpact>60000){lastImpact=SystemClock.elapsedRealtime();loudnessPrompt();}
            }
        }catch(Exception ignored){}finally{java.util.Arrays.fill(samples,(short)0);AudioRecord device=recorder;recorder=null;
            if(device!=null){try{device.stop();}catch(Exception ignored){}device.release();}
            audioRunning=false;if(!gpsRunning)stopSelf();}
    }
    private void loudnessPrompt(){
        Intent open=new Intent(this,MainActivity.class).putExtra("sound_check",true);
        PendingIntent pending=PendingIntent.getActivity(this,903,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(903,builder.setSmallIcon(R.drawable.ic_microphone)
            .setContentTitle("Señal de sonido fuerte").setContentText("¿Estás bien? Abre la app para confirmar. No identifica accidentes.")
            .setContentIntent(pending).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());}catch(SecurityException ignored){}
    }
    private void notification(){
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Privacidad y señales locales",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,902,new Intent(this,PrivacySignalService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,901,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Notification item=builder.setSmallIcon(R.drawable.ic_watch).setContentTitle("CareMonitor · funciones consentidas activas")
            .setContentText((gpsRunning?"Ubicación fuera de casa. ":"")+(audioRunning?"Sonido local, sin grabaciones.":""))
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null,"Detener",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(902,item,(gpsRunning?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0)|(audioRunning?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:0));
        else startForeground(902,item);
    }
    @Override public void onProviderEnabled(String provider){}
    @Override public void onProviderDisabled(String provider){}
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);audioRunning=false;
        if(locations!=null)locations.removeUpdates(this);AudioRecord device=recorder;if(device!=null)try{device.stop();}catch(Exception ignored){}
        worker.shutdownNow();audioWorker.shutdownNow();super.onDestroy();}
}
