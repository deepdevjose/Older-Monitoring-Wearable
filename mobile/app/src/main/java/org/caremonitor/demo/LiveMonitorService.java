package org.caremonitor.demo;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Owns a user-started live session across Activity recreation. Never loads demo assets. */
public final class LiveMonitorService extends Service {
    public static final String ACTION_CONNECT = "org.caremonitor.demo.CONNECT";
    public static final String ACTION_STOP = "org.caremonitor.demo.STOP";
    public static final String EXPECTED_DEVICE_ID = "elder-demo-01";
    private static final String CHANNEL = "care_monitor_connection";
    private static final int NOTIFICATION_ID = 60;
    private static final long FRESH_MS = 10000;
    private final LiveSampleFreshness freshness=new LiveSampleFreshness(FRESH_MS);
    public interface Listener { void onLiveChanged(); }
    public static final class Device {
        public final String address, name;
        public final int rssi;
        Device(String address, String name, int rssi) {
            this.address=address;this.name=name;this.rssi=rssi;
        }
    }
    public final class LocalBinder extends Binder {
        public LiveMonitorService service() { return LiveMonitorService.this; }
    }
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<Double>> histories = new LinkedHashMap<>();
    private final AlertInbox events = new AlertInbox();
    private final LinkedHashSet<String> sampleIds=new LinkedHashSet<>();
    private SensorTransport bluetooth;
    // Transport generations also protect the service from callbacks queued by a
    // closed client. A reconnect always receives a fresh client and JSON framer.
    private long transportGeneration;
    private String transport="spp";
    private ServerRelay relay;
    private JSONObject snapshot;
    private String connectionState="Not connected", error="", relayState="Not configured";
    private boolean running, lastFresh, destroyed;
    private String selectedAddress="", selectedName="";
    private int reconnectAttempts;
    private long lastReceivedAt, packetCount, lastUploadAt, lastNotifyAt;
    private int queued;

    @Override public void onCreate() {
        super.onCreate();
        relay=new ServerRelay(this,(status,count,lastSuccess) -> {
            relayState=status;queued=count;lastUploadAt=lastSuccess;changed();
        });
        replaceTransportClient();
        main.post(tick);
    }

    private void replaceTransportClient() {
        final long generation=++transportGeneration;
        SensorTransport previous=bluetooth;
        bluetooth=null; // Invalidate the previous client before closing its socket/GATT.
        if(previous!=null)previous.close();
        SensorTransport.Listener listener=new SensorTransport.Listener() {
            private boolean active() {return !destroyed&&transportGeneration==generation;}
            @Override public void onState(String state) {
                if(!active())return;
                if(state.equals("disconnected")||state.equals("connecting")||state.equals("closed"))invalidateLiveSample();
                connectionState=readableState(state);
                if(state.equals("ready")){reconnectAttempts=0;main.removeCallbacks(reconnect);}
                if(state.equals("disconnected")&&running&&!selectedAddress.isEmpty()) {
                    main.removeCallbacks(reconnect);main.postDelayed(reconnect,Math.min(30000,5000L*(1L<<Math.min(reconnectAttempts++,3))));
                }
                changed();
            }
            @Override public void onDevice(String address,String name,int rssi) {
                if(!active())return;
                devices.put(address,new Device(address,name,rssi));changed();
            }
            @Override public void onJsonLine(String line) {if(active())acceptLine(line);}
            @Override public void onError(String message) {if(active()){error=message;changed();}}
        };
        bluetooth="ble".equals(transport)?new BleSensorClient(this,listener):new SppSensorClient(this,listener);
    }

    private void invalidateLiveSample() {
        snapshot=null;histories.clear();sampleIds.clear();lastReceivedAt=0;
        freshness.invalidate();lastFresh=false;
    }

    @Override public IBinder onBind(Intent intent) {return binder;}

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || ACTION_STOP.equals(intent.getAction())) {stopMonitoring();return START_NOT_STICKY;}
        if(ACTION_CONNECT.equals(intent.getAction())) {
            ApiSession account=ApiSession.load(this);
            if(account==null||!account.elder()){error="Inicia sesión como persona mayor antes de conectar el wearable.";stopMonitoring();return START_NOT_STICKY;}
            if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
                error="Nearby devices permission is required.";stopMonitoring();return START_NOT_STICKY;
            }
            try {
                String requestedTransport=intent.getStringExtra("transport");
                if(requestedTransport!=null&&!selectTransport(requestedTransport,false)) {
                    if(!running)stopSelf();return START_NOT_STICKY;
                }
                String address=intent.getStringExtra("address");
                if(address==null||address.isEmpty()) {
                    error="Choose an ESP32 from the Bluetooth device list.";
                    if(!running)stopSelf();changed();return START_NOT_STICKY;
                }
                Device selected=devices.get(address);
                selectedAddress=address;
                selectedName=selected==null?("spp".equals(transport)?"ElderSensors-elder-demo-01":"CareMonitor-elder-demo-01"):selected.name;
                main.removeCallbacks(reconnect);reconnectAttempts=0;
                invalidateLiveSample();packetCount=0;error="";
                replaceTransportClient();
                connectionState=readableState("connecting");
                running=true;startConnectionNotification();
                bluetooth.connect(selectedAddress);
                relay.start();
            } catch(RuntimeException failure) {
                running=false;error="Unable to start live monitoring. Check Bluetooth permissions and try again.";
                main.removeCallbacks(reconnect);invalidateLiveSample();replaceTransportClient();
                connectionState="Not connected";relay.pause();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            }
            changed();
        }
        return START_NOT_STICKY;
    }

    public void scan() {
        ApiSession account=ApiSession.load(this);if(account==null||!account.elder()){error="Cuenta de persona mayor requerida.";changed();return;}
        if(running){error="Stop monitoring before scanning for another ESP32.";changed();return;}
        devices.clear();error="";bluetooth.startScan();changed();
    }
    public void stopScan() {bluetooth.stopScan();}
    public String transport() {return transport;}
    /** A mode change stops the current session; selecting it never connects a device. */
    public boolean setTransport(String mode) {return selectTransport(mode,true);}
    private boolean selectTransport(String mode,boolean stopStartedService) {
        if(!"spp".equals(mode)&&!"ble".equals(mode)) {
            error="Choose Classic Bluetooth (SPP) or Bluetooth LE (BLE).";changed();return false;
        }
        if(transport.equals(mode))return true;
        running=false;main.removeCallbacks(reconnect);reconnectAttempts=0;
        selectedAddress="";selectedName="";invalidateLiveSample();packetCount=0;
        transport=mode;devices.clear();error="";
        replaceTransportClient();relay.pause();connectionState="Not connected";
        stopForeground(STOP_FOREGROUND_REMOVE);if(stopStartedService)stopSelf();changed();return true;
    }
    public void addListener(Listener listener) {if(!listeners.contains(listener)) listeners.add(listener);}
    public void removeListener(Listener listener) {listeners.remove(listener);}
    public List<Device> devices() {return new ArrayList<>(devices.values());}
    public boolean isRunning() {return running;}
    public boolean isFresh() {return freshness.isFresh(running,bluetooth!=null&&bluetooth.isReady(),SystemClock.elapsedRealtime());}
    public String bluetoothState() {return connectionState;}
    public String relayState() {return relayState;}
    public String error() {return error;}
    public long receivedAt() {return lastReceivedAt;}
    public long packetCount() {return packetCount;}
    public int queuedCount() {return queued;}
    public long uploadedAt() {return lastUploadAt;}
    public String endpoint() {return relay.endpoint();}
    public boolean isServerConfigured() {return relay.isConfigured();}
    public AlertInbox events() {return events;}
    public JSONObject snapshot() {return snapshot;}
    public void refreshSession() { relay.refreshSession(); if(running)relay.start(); }
    public void clearUploadQueue() {relay.clearQueue();}

    public void stopMonitoring() {
        running=false;main.removeCallbacks(reconnect);reconnectAttempts=0;
        selectedAddress="";selectedName="";invalidateLiveSample();
        replaceTransportClient();connectionState="Disconnected";relay.pause();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();changed();
    }

    public double[] history(String key) {
        ArrayDeque<Double> values=histories.get(key);if(values==null)return null;
        double[] result=new double[values.size()];int index=0;
        for(Double value:values)result[index++]=value;
        return result;
    }

    private void acceptLine(String line) {
        if(!running||bluetooth==null||!bluetooth.isReady()) return;
        try {
            if(line.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException();
            JSONTokener tokens=new JSONTokener(line);Object decoded=tokens.nextValue();
            if(!(decoded instanceof JSONObject)||tokens.nextClean()!=0)throw new IllegalArgumentException();
            JSONObject incoming=(JSONObject)decoded;
            if(!EXPECTED_DEVICE_ID.equals(incoming.opt("device_id"))) {
                error="El dispositivo no corresponde al wearable vinculado.";changed();return;
            }
            String identity=incoming.has("sample_id")?incoming.optString("boot_nonce","")+":"+incoming.optString("sample_id",""):"";
            if(!identity.isEmpty()&&sampleIds.contains(identity))return;
            if(!identity.isEmpty()) {
                if(sampleIds.size()>=128)sampleIds.remove(sampleIds.iterator().next());
                sampleIds.add(identity);
            }
            // The firmware sends readings, not a diagnosis. Preserve all original validity flags.
            lastReceivedAt=System.currentTimeMillis();
            SimpleDateFormat utc=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            String received=utc.format(new Date(lastReceivedAt));incoming.put("phone_received_at",received);
            if(!(incoming.opt("timestamp") instanceof String)||incoming.optString("timestamp").isEmpty())incoming.put("timestamp",received);
            snapshot=incoming;freshness.received(SystemClock.elapsedRealtime());
            packetCount++;error="";recordHistory(incoming);
            relay.enqueue(incoming.toString());changed();
        } catch(Exception ignored) {error="Invalid sensor JSON was discarded. Waiting for a complete valid message.";changed();}
    }

    private void recordEvents(JSONArray incoming) {
        if(incoming==null)return;
        for(int i=0;i<incoming.length();i++) {
            try {
                JSONObject item=incoming.getJSONObject(i);
                AlertCatalog.Type type=AlertCatalog.find(item.optString("type",""));
                long at=item.optLong("occurred_at",lastReceivedAt);
                if(at<=0 || at>lastReceivedAt+60000) at=lastReceivedAt;
                events.add(new AlertInbox.Entry(item.getString("id"),type,at,EXPECTED_DEVICE_ID,item.optString("reading","Reading unavailable"),0));
            } catch(Exception ignored) {error="An unsupported alert event was skipped; sensor readings remain available.";}
        }
    }

    private void recordHistory(JSONObject data) {
        JSONObject optical=data.optJSONObject("max30102"),temp=data.optJSONObject("gy906"),mic=data.optJSONObject("microphone"),light=data.optJSONObject("photoresistor");
        String state=optical==null?"":optical.optString("status","");
        boolean estimates=optical!=null && !Boolean.FALSE.equals(optical.opt("online"))
            && !Boolean.FALSE.equals(optical.opt("finger_detected")) && state.equals("ok");
        addPoint("heart_rate_bpm",estimates&&Boolean.TRUE.equals(optical.opt("heart_rate_valid"))?number(optical,"heart_rate_bpm",Double.MIN_VALUE,Double.MAX_VALUE):Double.NaN);
        addPoint("spo2_percent",estimates&&Boolean.TRUE.equals(optical.opt("spo2_valid"))?number(optical,"spo2_percent",0,100):Double.NaN);
        boolean temperature=available(temp);
        addPoint("surface_temp_c",temperature?number(temp,"object_temp_c",-273.15,Double.MAX_VALUE):Double.NaN);
        addPoint("ambient_temp_c",temperature?number(temp,"ambient_temp_c",-273.15,Double.MAX_VALUE):Double.NaN);
        addPoint("microphone_adc",available(mic)?number(mic,"adc_raw",0,4095):Double.NaN);
        addPoint("light_adc",available(light)?number(light,"adc_raw",0,4095):Double.NaN);
    }

    private static boolean available(JSONObject module) {
        if(module==null||module.length()==0||Boolean.FALSE.equals(module.opt("online")))return false;
        if(module.has("status"))return "ok".equals(module.opt("status"))||"sampled".equals(module.opt("status"));
        return Boolean.TRUE.equals(module.opt("online"))||module.has("adc_raw");
    }
    private String readableState(String state) {
        if(state.equals("ready"))return "spp".equals(transport)?"Connected · receiving serial data":"Connected · receiving notifications";
        if(state.equals("scan_stopped"))return "Scan stopped";
        if(state.equals("subscribing"))return "Enabling sensor notifications";
        if(state.equals("discovering"))return "Discovering sensor service";
        if(state.equals("scanning"))return "spp".equals(transport)?"Scanning for classic Bluetooth devices":"Scanning for Care Monitor BLE devices";
        if(state.equals("connecting"))return "Conectando con tu wearable";
        if(state.equals("disconnected"))return "Disconnected";
        if(state.equals("closed"))return "Disconnected";
        return state;
    }

    private static double number(JSONObject object,String key,double low,double high) {
        if(object==null||!(object.opt(key) instanceof Number))return Double.NaN;
        double value=((Number)object.opt(key)).doubleValue();
        return Double.isNaN(value)||Double.isInfinite(value)||value<low||value>high?Double.NaN:value;
    }

    private void addPoint(String key,double value) {
        ArrayDeque<Double> values=histories.get(key);
        if(values==null){values=new ArrayDeque<>();histories.put(key,values);}
        if(values.size()>=60)values.removeFirst();values.addLast(value);
    }

    private final Runnable tick=new Runnable() {
        @Override public void run() {
            boolean fresh=isFresh();if(fresh!=lastFresh){lastFresh=fresh;changed();}
            main.postDelayed(this,1000);
        }
    };
    private final Runnable reconnect=new Runnable() {
        @Override public void run() {
            if(running&&!destroyed&&!selectedAddress.isEmpty()) {
                error="Reconnecting to the selected ESP32…";invalidateLiveSample();replaceTransportClient();
                connectionState=readableState("connecting");bluetooth.connect(selectedAddress);changed();
            }
        }
    };

    private void changed() {
        if(destroyed)return;
        for(Listener listener:new ArrayList<>(listeners))listener.onLiveChanged();
        if(running && SystemClock.elapsedRealtime()-lastNotifyAt>3000) {
            lastNotifyAt=SystemClock.elapsedRealtime();
            try {((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification());}
            catch(SecurityException ignored){}
        }
    }

    private void startConnectionNotification() {
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Device connection",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID,notification());
    }

    private Notification notification() {
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,LiveMonitorService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        String mode="spp".equals(transport)?"Classic SPP":"BLE";
        String device="Wearable CareMonitor";
        return builder.setSmallIcon(R.drawable.ic_watch).setContentTitle("Care Monitor · "+mode+" · "+device)
            .setContentText(connectionState+" · "+queued+" uploads queued").setContentIntent(content)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    }

    @Override public void onDestroy() {
        destroyed=true;running=false;transportGeneration++;
        main.removeCallbacksAndMessages(null);listeners.clear();
        bluetooth.close();relay.close();super.onDestroy();
    }
}
