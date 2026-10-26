package org.caremonitor.demo;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.concurrent.*;

/** User-enabled connection to the user's own HTTPS inbox. Not a cloud push SDK. */
public final class NotificationChannelService extends Service {
    private static final String CHANNEL="caremonitor_local_connection";
    private static final int ID=8740;
    public static final String STOP="stop";
    private static volatile boolean running;
    private static volatile String status="Canal de avisos detenido";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;private ApiSession owner;private int failures;
    public static String status(){return status;}public static boolean running(){return running;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||STOP.equals(intent.getAction())){status="Canal detenido por el usuario";stopSelf();return START_NOT_STICKY;}
        ApiSession account=ApiSession.load(this);
        if(account==null||!NotificationSync.permitted(this)){status="Activa permisos y sesión para recibir avisos";stopSelf();return START_NOT_STICKY;}
        if(running&&owner!=null){if(!owner.token.equals(account.token)){status="La sesión cambió. Activa de nuevo el canal.";stopSelf();}return START_NOT_STICKY;}
        owner=account;
        try{NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Canal de avisos del servidor propio",NotificationManager.IMPORTANCE_LOW));
            status="Conectando con tu servidor…";
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID,notification());
            running=true;worker.execute(this::listen);
        }catch(RuntimeException failure){status="Android no permitió iniciar el canal. Abre la app y revisa permisos.";stopSelf();}
        return START_NOT_STICKY;
    }
    private boolean active(){ApiSession current=ApiSession.load(this);return !closed&&running&&current!=null&&owner!=null&&owner.token.equals(current.token)&&owner.id.equals(current.id)&&owner.base.equals(current.base);}
    private void listen(){
        if(!active()||!NotificationSync.permitted(this)){status="Canal pausado por sesión o permisos";main.post(this::stopSelf);return;}
        try{NotificationSync.waitAndSync(this,this::active);failures=0;publish("Canal conectado a tu servidor");
            if(active())worker.schedule(this::listen,250,TimeUnit.MILLISECONDS);else{status="Canal pausado por cierre o vencimiento de sesión";main.post(this::stopSelf);}
        }catch(Exception failure){if(!active()){status="Canal pausado por cambio de sesión";main.post(this::stopSelf);return;}
            if(failure instanceof ApiClient.Failure&&(((ApiClient.Failure)failure).status==401||((ApiClient.Failure)failure).status==403)){
                status="Canal pausado. Revisa sesión y permisos.";main.post(this::stopSelf);return;}
            long delay=RelayPolicy.retryDelayMs(++failures);publish("Sin conexión · reintento en "+delay/1000+" s");
            if(active())worker.schedule(this::listen,delay,TimeUnit.MILLISECONDS);
        }
    }
    private void publish(String value){status=value;main.post(()->{if(!closed)try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ID,notification());}catch(SecurityException ignored){}});}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,ID,new Intent(this,MainActivity.class).putExtra("show_alerts",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,ID,new Intent(this,NotificationChannelService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_care_monitor).setContentTitle("CareMonitor · avisos locales activos").setContentText(status)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null,"Detener",stop).build()).build();
    }
    @Override public void onDestroy(){closed=true;running=false;if(status.startsWith("Canal conectado")||status.startsWith("Conectando")||status.startsWith("Sin conexión"))status="Canal de avisos detenido";main.removeCallbacksAndMessages(null);worker.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
