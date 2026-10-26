package org.caremonitor.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Native Bluetooth monitor with an explicitly separate local demo; no clinical diagnosis. */
public final class MainActivity extends Activity {
    private static final String DEFAULT_SERVER = RelayPolicy.DEFAULT_ENDPOINT;
    private static final String[] TABS = {"Home", "Sensors", "Alerts", "Profile"};
    private static final String[] MODULES = {"Heart & oxygen", "Temperature", "Movement", "Light", "Buzzer"};
    private static final String[] CHIPS = {"MAX30102", "GY906", "MPU6050", "Photoresistor", "Actuator"};
    private static final int[] GLYPHS = {R.drawable.ic_heart, R.drawable.ic_temperature,
        R.drawable.ic_motion, R.drawable.ic_light, R.drawable.ic_buzzer};
    private CarePalette p;
    private boolean dark;
    private SharedPreferences preferences;
    private LinearLayout root, content;
    private String page = "login";
    private int tab, detailModule;
    private JSONObject snapshot, demoHistory;
    private long refreshedAt;
    private boolean refreshFailed, inboxInitialized, alertLoadFailed;
    private AlertInbox inbox = new AlertInbox();
    private String selectedAlertId = "", alertFilter = "all";
    private String savedServer = "";
    private String patientName = "Eleanor Chen", patientAge = "72", caregiverName = "Alex Morgan";
    private String draftName, draftAge, draftCaregiver;
    private EditText nameInput, ageInput, caregiverInput, bluetoothMacInput;
    private String bluetoothMacDraft="";
    private Object backCallback;
    private boolean liveMode, monitorBound, visible, waitingForBluetoothEnable;
    private boolean serverAlertMonitoringRequested;
    private final Handler liveUi=new Handler(Looper.getMainLooper());
    private long lastLiveUpdate;
    private boolean liveUpdatePending;
    private final List<Runnable> liveBindings=new ArrayList<>();
    private List<AlertInbox.Entry> displayedAlerts=new ArrayList<>();
    private List<RemoteAlertInbox.Entry> displayedServerAlerts=new ArrayList<>();
    private String displayedServerAlertState="";
    private long selectedServerAlertId;
    private boolean displayedAlertLoadFailed;
    private LinearLayout alertContentHost;
    private LiveMonitorService monitor;
    private String permissionAction="", permissionTransport="spp", lastRoute="", connectionsReturnPage="shell";
    private ScrollView pageScroll;
    private TextView bluetoothLabel, uploadLabel, receivedLabel, connectionError;
    private TextView uploadCredentialLabel, alertCredentialLabel, alertSyncLabel;
    private TextView settingsBluetoothLabel, settingsReceivedLabel, modeStrip;
    private LinearLayout deviceList;
    private final Handler setupUi=new Handler(Looper.getMainLooper());
    private int setupGeneration;
    private boolean setupBusy,pendingSetupReview;
    private String connectionSetupStatus="";
    private AlertDialog connectionSetupDialog;
    private final LiveMonitorService.Listener liveListener=this::liveChanged;
    private final ServiceConnection monitorConnection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            monitor=((LiveMonitorService.LocalBinder)binder).service();
            monitor.setAlertUiVisible(visible);
            if(liveMode&&serverAlertMonitoringRequested)monitor.startAlertMonitoring();
            if(visible)monitor.addListener(liveListener);
            if(monitor.isRunning()&&!liveMode&&page.equals("login")) {liveMode=true;page="shell";tab=0;}
            if(liveMode) {
                for(AlertInbox.Entry event:inbox.entries())monitor.events().add(event);
                inbox=monitor.events();syncLiveSnapshot();
            }
            if(isSessionPage()&&visible)render();
            resumePendingRadioAction();
            if(pendingSetupReview){pendingSetupReview=false;reviewConnectionSetup(null,true);}
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            monitor=null;if(isSessionPage()){if(liveMode)refreshFailed=true;liveChanged();}
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("care_monitor_local", MODE_PRIVATE);
        dark = preferences.getBoolean("dark", false);
        patientName = preferences.getString("patient_name", "Eleanor Chen");
        patientAge = preferences.getString("patient_age", "72");
        caregiverName = preferences.getString("caregiver_name", "Alex Morgan");
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        try { demoHistory = readAsset("sample_sensor_history.json"); }
        catch (Exception ignored) { demoHistory = new JSONObject(); }
        if (state != null && state.getBoolean("demo_session", false)) restoreSession(state);
        render();
        monitorBound=bindService(new Intent(this,LiveMonitorService.class),monitorConnection,BIND_AUTO_CREATE);
        handleConnectionSetupAction(getIntent());
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback callback = this::goBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backCallback = callback;
        }
    }

    @Override protected void onDestroy() {
        setupGeneration++;setupUi.removeCallbacksAndMessages(null);
        if(connectionSetupDialog!=null)connectionSetupDialog.dismiss();
        liveUi.removeCallbacksAndMessages(null);
        if(monitor!=null)monitor.removeListener(liveListener);
        if(monitorBound)unbindService(monitorConnection);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (android.window.OnBackInvokedCallback) backCallback);
        }
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);handleConnectionSetupAction(intent);
    }

    @Override protected void onStart() {
        super.onStart();visible=true;
        if(monitor!=null){monitor.setAlertUiVisible(true);if(liveMode&&serverAlertMonitoringRequested)monitor.startAlertMonitoring();monitor.addListener(liveListener);liveChanged();resumePendingRadioAction();}
    }
    @Override protected void onStop() {
        visible=false;liveUi.removeCallbacksAndMessages(null);liveUpdatePending=false;
        if(monitor!=null){monitor.setAlertUiVisible(false);monitor.removeListener(liveListener);}
        super.onStop();
    }

    private void restoreSession(Bundle state) {
        page = state.getString("page", "shell");
        if (!page.equals("shell") && !page.equals("sensor") && !page.equals("alert") && !page.equals("settings") && !page.equals("connections")) page = "shell";
        liveMode=state.getBoolean("live_mode",false);
        serverAlertMonitoringRequested=liveMode&&state.getBoolean("server_alert_monitoring",false);
        connectionsReturnPage="settings".equals(state.getString("connections_return_page"))?"settings":"shell";
        permissionAction=state.getString("bluetooth_pending_action","");
        permissionTransport="ble".equals(state.getString("bluetooth_pending_transport"))?"ble":"spp";
        waitingForBluetoothEnable=state.getBoolean("bluetooth_enable_dialog",false);
        bluetoothMacDraft=state.getString("bluetooth_mac_draft","");
        tab = Math.max(0, Math.min(3, state.getInt("tab", 0)));
        int storedModule=state.getInt("module",0),storedLayout=state.getInt("module_layout_version",1);
        detailModule=SensorModuleNavigation.restoredIndex(storedModule,storedLayout);
        if(page.equals("sensor")&&SensorModuleNavigation.wasRemoved(storedModule,storedLayout)){page="shell";tab=1;}
        refreshedAt = state.getLong("refreshed_at", 0);
        refreshFailed = state.getBoolean("refresh_failed", false);
        restoreInbox(state);
        savedServer = state.getString("server_draft", "");
        draftName = state.getString("draft_name");
        draftAge = state.getString("draft_age");
        draftCaregiver = state.getString("draft_caregiver");
        try { snapshot = new JSONObject(state.getString("snapshot", "{}")); if (snapshot.length() == 0) snapshot = null; }
        catch (Exception ignored) { snapshot = null; }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        boolean entered = isSessionPage();
        state.putBoolean("demo_session", entered);
        state.putBoolean("live_mode",liveMode);
        state.putBoolean("server_alert_monitoring",serverAlertMonitoringRequested);
        state.putString("connections_return_page",connectionsReturnPage);
        state.putString("bluetooth_pending_action",permissionAction);
        state.putString("bluetooth_pending_transport",permissionTransport);
        state.putBoolean("bluetooth_enable_dialog",waitingForBluetoothEnable);
        state.putString("bluetooth_mac_draft",bluetoothMacInput==null?bluetoothMacDraft:bluetoothMacInput.getText().toString());
        if (!entered) return;
        captureProfileDraft();
        state.putString("page", page); state.putInt("tab", tab); state.putInt("module", detailModule);
        state.putInt("module_layout_version",SensorModuleNavigation.LAYOUT_VERSION);
        state.putLong("refreshed_at", refreshedAt); state.putBoolean("refresh_failed", refreshFailed);
        state.putBoolean("inbox_initialized", inboxInitialized);
        state.putBoolean("alert_load_failed", alertLoadFailed);
        state.putString("alert_events", encodeInbox().toString());
        state.putString("selected_alert", selectedAlertId); state.putString("alert_filter", alertFilter);
        state.putLong("selected_server_alert",selectedServerAlertId);
        state.putString("server_draft", savedServer);
        state.putString("snapshot", snapshot == null ? "{}" : snapshot.toString());
        state.putString("draft_name", draftName); state.putString("draft_age", draftAge);
        state.putString("draft_caregiver", draftCaregiver);
        // Login and registration credentials are deliberately never saved.
    }

    private boolean isSessionPage() { return !page.equals("login") && !page.equals("signup"); }
    private boolean compact() {
        return getResources().getConfiguration().screenWidthDp < 360
            || getResources().getConfiguration().fontScale > 1.3f;
    }

    private void render() {
        liveUi.removeCallbacks(refreshLiveUi);liveUpdatePending=false;liveBindings.clear();alertContentHost=null;
        String route=page+":"+tab+":"+detailModule;
        int restoreY=route.equals(lastRoute)&&pageScroll!=null?pageScroll.getScrollY():0;
        lastRoute=route;
        captureProfileDraft(); nameInput = ageInput = caregiverInput = null;
        if(bluetoothMacInput!=null)bluetoothMacDraft=bluetoothMacInput.getText().toString();
        bluetoothMacInput=null;
        bluetoothLabel=uploadLabel=receivedLabel=connectionError=null;deviceList=null;
        uploadCredentialLabel=alertCredentialLabel=alertSyncLabel=null;
        settingsBluetoothLabel=settingsReceivedLabel=modeStrip=null;
        setTheme(dark ? R.style.CareMonitorDarkTheme : R.style.CareMonitorTheme);
        p = new CarePalette(dark);
        getWindow().setStatusBarColor(p.background); getWindow().setNavigationBarColor(p.surface);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!dark && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        root = vertical(); root.setBackgroundColor(p.background); setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return insets;
            }); root.requestApplyInsets();
        } else root.setFitsSystemWindows(true);
        if (!page.equals("login")) header();
        if (isSessionPage()) demoStrip();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        pageScroll=scroll;
        content = vertical(); content.setPadding(dp(16), dp(page.equals("login") ? 30 : 8), dp(16), dp(22));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page.equals("login")) login();
        else if (page.equals("signup")) signup();
        else if (page.equals("sensor")) sensorDetails();
        else if (page.equals("alert")) alertDetails();
        else if (page.equals("settings")) settings();
        else if (page.equals("connections")) connections();
        else if (tab == 0) overview();
        else if (tab == 1) sensors();
        else if (tab == 2) alerts();
        else profile();
        if (isSessionPage()) navigation();
        displayedAlerts=inbox.entries();displayedAlertLoadFailed=alertLoadFailed;
        displayedServerAlerts=remoteAlerts();displayedServerAlertState=remoteAlertState();
        if(restoreY>0)scroll.post(() -> scroll.scrollTo(0,restoreY));
    }

    private void header() {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(5), dp(10), dp(5));
        if (!page.equals("shell")) row.addView(iconButton(R.drawable.ic_back, "Back", v -> goBack()));
        String heading = page.equals("signup") ? "Server accounts" : page.equals("sensor") ? MODULES[detailModule]
            : page.equals("alert") ? "Alert details" : page.equals("settings") ? "Settings" : page.equals("connections")?"Connections":tab == 3 ? "Profile" : tab == 2 ? "Alerts" : "Care Monitor";
        TextView title = text(heading, 21, p.text, true); title.setPadding(dp(5), 0, dp(4), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (page.equals("shell") && tab == 0) row.addView(iconButton(R.drawable.ic_nav_profile, "Open patient profile", v -> navigate(3)));
        if (isSessionPage()) {
            View menu = iconButton(R.drawable.ic_more, "More options", this::showMenu);
            row.addView(menu);
        }
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showMenu(View anchor) {
        Context themed = new ContextThemeWrapper(this, dark ? android.R.style.Theme_Material_NoActionBar
            : android.R.style.Theme_Material_Light_NoActionBar);
        PopupMenu menu = new PopupMenu(themed, anchor);
        menu.getMenu().add(0, 1, 0, "Settings"); menu.getMenu().add(0, 2, 1, "About");
        menu.getMenu().add(0,3,2,"Bluetooth connection");
        menu.getMenu().add(0,4,3,"Server account");
        menu.setOnMenuItemClickListener(item -> {
            hideKeyboard();
            if (item.getItemId() == 1) { page = "settings"; render(); }
            else if(item.getItemId()==3)openConnections();
            else if(item.getItemId()==4)openAccount();
            else new AlertDialog.Builder(themed).setTitle("Care Monitor 0.8.0")
                .setMessage("Live Bluetooth reception and HTTPS forwarding, with a separate server account view for assigned readings, model results and alerts. The local demo remains separate. This is an educational monitoring application, not a diagnosis.")
                .setPositiveButton("OK", null).show();
            return true;
        }); menu.show();
    }

    private void demoStrip() {
        modeStrip = text("", 11, p.primary, false);updateModeStrip();
        modeStrip.setPadding(dp(10), dp(4), dp(10), dp(4)); modeStrip.setBackground(shape(p.soft, 12, Color.TRANSPARENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(16), 0, dp(16), dp(8)); root.addView(modeStrip, params);
    }

    private void updateModeStrip() {
        if(modeStrip!=null)modeStrip.setText(liveMode?(recent()?"Live "+("ble".equals(bluetoothTransport())?"BLE":"SPP")+" · receiving sensor readings":"Live mode · waiting for fresh readings"):"Demo data  ·  Simulated readings");
    }

    private void navigate(int destination) { hideKeyboard(); page = "shell"; tab = destination; render(); }

    private void navigation() {
        LinearLayout bar = horizontal(); bar.setPadding(dp(4), dp(5), dp(4), dp(5)); bar.setBackgroundColor(p.surface);
        int[] icons = {R.drawable.ic_nav_overview,R.drawable.ic_nav_sensors,R.drawable.ic_nav_alerts,R.drawable.ic_nav_profile};
        for (int i = 0; i < 4; i++) {
            final int destination = i;
            int active = page.equals("sensor") ? 1 : page.equals("alert") ? 2 : page.equals("settings")||page.equals("connections") ? -1 : tab;
            int pending = pendingAlertCount();
            boolean selected = active == i, newAlert = i == 2 && pending > 0;
            LinearLayout item = vertical(); item.setGravity(Gravity.CENTER); item.setPadding(dp(2),dp(4),dp(2),dp(4));
            item.setMinimumHeight(dp(64)); item.setFocusable(true); item.setSelected(selected);
            item.setContentDescription(TABS[i] + (newAlert ? ", " + pending + " alerts to review" : ""));
            buttonSemantics(item);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(35,18,108,103)),
                shape(selected?p.soft:p.surface,16,Color.TRANSPARENT),shape(p.surface,16,Color.TRANSPARENT)));
            FrameLayout slot = new FrameLayout(this);
            slot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            slot.addView(icon(icons[i],28,selected?p.primary:p.muted),new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));
            if (i==2) {
                TextView badge = text(pending > 99 ? "99+" : String.valueOf(pending),11,p.surface,true); badge.setGravity(Gravity.CENTER);
                badge.setIncludeFontPadding(false); badge.setLineSpacing(0,1); badge.setBackground(shape(p.danger,9,Color.TRANSPARENT));
                slot.addView(badge,new FrameLayout.LayoutParams(dp(pending > 9 ? 25 : 18),dp(18),Gravity.TOP|Gravity.RIGHT));
                bind(() -> {
                    int count=pendingAlertCount();setLiveText(badge,count>99?"99+":String.valueOf(count));
                    badge.setVisibility(count>0?View.VISIBLE:View.GONE);
                    FrameLayout.LayoutParams badgeParams=(FrameLayout.LayoutParams)badge.getLayoutParams();
                    int width=dp(count>9?25:18);if(badgeParams.width!=width){badgeParams.width=width;badge.setLayoutParams(badgeParams);}
                    String description="Alerts"+(count>0?", "+count+" alerts to review":"");
                    if(!description.contentEquals(item.getContentDescription()))item.setContentDescription(description);
                });
            }
            item.addView(slot,new LinearLayout.LayoutParams(dp(44),dp(32)));
            TextView caption = text(TABS[i],12,selected?p.primary:p.muted,selected);
            caption.setGravity(Gravity.CENTER); caption.setIncludeFontPadding(false); caption.setLineSpacing(0,1);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(caption,new LinearLayout.LayoutParams(-1,-2)); item.setOnClickListener(v -> navigate(destination));
            bar.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        }
        root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
    }

    private void login() {
        brand();
        title("Welcome to\nCare Monitor", "A clearer view of everyday care for older adults.");
        action(content,"Sign in to server account",true,v -> openAccount());
        action(content,"Connect a live ESP32",true,v -> enterLive());
        notice("Server accounts show readings and alerts for devices assigned by your server administrator. Bluetooth reception and uploads can continue independently.");
        action(content, "Explore local demo", false, v -> enterDemo());
        paragraph("The local demo uses simulated data and creates no account. Ask your server administrator to create a patient or caregiver account.");
    }

    private void openAccount() {
        hideKeyboard();
        startActivity(new Intent(this, AccountActivity.class));
    }

    private void signup() {
        brand();
        title("Server accounts", "Patient and caregiver access is assigned by your server administrator.");
        paragraph("Ask your administrator to create an account and assign the correct patient devices. This app does not create accounts or let users choose their own permissions.");
        action(content, "Sign in to server account", true, v -> openAccount());
        action(content, "Explore local demo", false, v -> enterDemo());
    }

    private void enterDemo() {
        permissionAction="";hideKeyboard();if(monitor!=null){monitor.stopAlertMonitoring();monitor.stopMonitoring();}
        liveMode=false;serverAlertMonitoringRequested=false;inbox=new AlertInbox();inboxInitialized=alertLoadFailed=false;alertFilter="all";selectedServerAlertId=0;
        page="shell";tab=0;updateSnapshot();render();
    }
    private void enterLive() {
        hideKeyboard();liveMode=true;snapshot=null;refreshFailed=true;inboxInitialized=true;alertLoadFailed=false;
        inbox=monitor==null?new AlertInbox():monitor.events();syncLiveSnapshot();
        if(!isSessionPage())tab=0;
        openConnections();
    }
    private void openConnections() {
        hideKeyboard();
        if(!page.equals("connections"))connectionsReturnPage=page.equals("settings")?"settings":"shell";
        page="connections";render();
    }
    private void signOut() {
        permissionAction="";if(monitor!=null){monitor.stopAlertMonitoring();monitor.stopMonitoring();}liveMode=false;serverAlertMonitoringRequested=false;inbox=new AlertInbox();
        hideKeyboard(); nameInput=ageInput=caregiverInput=null; draftName=draftAge=draftCaregiver=null;
        page="login";tab=0;detailModule=0;snapshot=null;refreshedAt=0;refreshFailed=false;
        inbox.clear();inboxInitialized=alertLoadFailed=false;selectedAlertId="";alertFilter="all";savedServer="";render();
    }

    private boolean recent() { return liveMode?monitor!=null&&monitor.isFresh():snapshot != null && !refreshFailed; }
    private int pendingAlertCount(){return inbox.pendingCount()+(liveMode&&monitor!=null?monitor.serverAlertPendingCount():0);}
    private int urgentAlertCount(){return inbox.urgentPendingCount()+(liveMode&&monitor!=null?monitor.serverAlertUrgentPendingCount():0);}
    private int unresolvedServerCount(){int n=0;for(RemoteAlertInbox.Entry event:remoteAlerts())if(!event.resolved())n++;return n;}
    private int unresolvedUrgentCount(){int n=inbox.urgentPendingCount();for(RemoteAlertInbox.Entry event:remoteAlerts())if(!event.resolved()&&("urgent".equals(event.severity)||"critical".equals(event.severity)))n++;return n;}
    private List<RemoteAlertInbox.Entry> remoteAlerts(){return liveMode&&monitor!=null?monitor.serverAlerts():new ArrayList<>();}
    private String remoteAlertState(){return monitor==null?"Server service is starting":monitor.serverAlertState();}
    private boolean needsReview() { Double oxygen=spo2();return !liveMode&&oxygen!=null&&oxygen<95 || pendingAlertCount()>0 || unresolvedServerCount()>0; }
    private ReadingSummary.State summaryState() {
        if(liveMode)return ReadingSummary.assessLive(recent(),heartRate(),spo2(),temperature("object_temp_c"),pendingAlertCount()>0,unresolvedServerCount()>0);
        if(alertLoadFailed&&inbox.pendingCount()==0) return ReadingSummary.State.UNAVAILABLE;
        return ReadingSummary.assess(recent(),heartRate(),spo2(),temperature("object_temp_c"),needsReview());
    }

    private void overview() {
        LinearLayout patient = horizontal(); patient.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = vertical(); label(identity,patientName, dark?25:20,p.text,true);
        label(identity,"Patient 001 · "+patientAge+" years",12,p.muted,false);
        patient.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        patient.addView(iconButton(R.drawable.ic_nav_profile,"View patient profile",v -> navigate(3)));
        content.addView(patient); space(content,10);
        statusCard();
        sectionHeading("Vital signs","All sensors",v -> navigate(1));
        if (dark) {
            LinearLayout heart = vitalCard("Heart rate",this::heartRate,"BPM",0,p.heart,"heart_rate_bpm");
            attach(content,heart); attach(content,vitalCard("Blood oxygen",this::spo2,"%",0,p.oxygen,"spo2_percent"));
        } else if (!compact()) {
            LinearLayout row = horizontal();
            LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-2,1);a.setMargins(0,0,dp(6),dp(12));
            LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,-2,1);b.setMargins(dp(6),0,0,dp(12));
            row.addView(vitalCard("Heart rate",this::heartRate,"BPM",0,p.heart,"heart_rate_bpm"),a);
            row.addView(vitalCard("Blood oxygen",this::spo2,"%",0,p.oxygen,"spo2_percent"),b);content.addView(row);
        } else {
            attach(content,vitalCard("Heart rate",this::heartRate,"BPM",0,p.heart,"heart_rate_bpm"));
            attach(content,vitalCard("Blood oxygen",this::spo2,"%",0,p.oxygen,"spo2_percent"));
        }
        LinearLayout temp=card(content); addCardHeading(temp,R.drawable.ic_temperature,"Surface temperature",p.surfaceTemp);
        bindLabel(temp,() -> measurement(display(temperature("object_temp_c")),"°C",1),27,p.text,true);
        bindLabel(temp,() -> "Ambient: "+measurement(display(temperature("ambient_temp_c")),"°C",1),12,p.ambientTemp,false);
        clickable(temp,"View surface and ambient temperature charts",v -> openModule(1));
        LinearLayout device=card(content);TextView deviceHeading=addCardHeading(device,R.drawable.ic_motion,"",p.primary);
        bind(() -> setLiveText(deviceHeading,recent()?(liveMode?"Device connected · "+("ble".equals(bluetoothTransport())?"BLE":"SPP"):"Device connected · demo"):"Device needs checking"));
        bindLabel(device,() -> "Device ID: "+deviceId(),13,p.text,true);
        bindLabel(device,() -> recent()?"4 sensors + 1 actuator":"Latest readings unavailable",12,p.muted,false);
        action(device,"View sensors",false,v -> navigate(1));
        if(liveMode) {
            bindLabel(device,() -> "Bluetooth: "+(monitor==null?"Starting":monitor.bluetoothState()),12,p.muted,false);
            bindLabel(device,() -> "Server: "+(monitor==null?"Not connected":monitor.relayState()),12,p.muted,false);
            action(device,"Bluetooth connection",false,v -> openConnections());
        }
        paragraph(liveMode?"Recent readings from your device · no simulated replacement":"Illustrative trends · simulated data only");
    }

    private void statusCard() {
        LinearLayout card=card(content);
        ImageView mark=icon(R.drawable.ic_status_unknown,dark?48:23,p.primary);
        if(dark) {LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(58),dp(58));iconParams.gravity=Gravity.CENTER_HORIZONTAL;card.addView(mark,iconParams);}
        else {
            LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.addView(mark,new LinearLayout.LayoutParams(dp(27),dp(27)));
            TextView caption=text(liveMode?"Device reading status":"Patient status · demo",14,p.text,true);caption.setPadding(dp(7),0,0,0);
            row.addView(caption,new LinearLayout.LayoutParams(0,-2,1));card.addView(row,new LinearLayout.LayoutParams(-1,-2));space(card,5);
        }
        TextView heading=text("",dark?27:23,p.text,true);
        if(dark) heading.setGravity(Gravity.CENTER); card.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        TextView description=label(card,"",13,p.muted,false);
        TextView incomplete=label(card,"Some alert history could not load.",12,p.muted,false);
        label(card,liveMode?"Live sensor values · not a diagnosis":"Based on simulated readings · not a diagnosis",11,p.muted,false);
        if(liveMode)label(card,"Server rules use the approved policy. ML predictions are experimental shadow results.",12,p.muted,false);
        if(liveMode)bindLabel(card,this::remoteAlertState,11,p.muted,false);
        Button review=button("Review alert",false,v -> navigate(2));LinearLayout.LayoutParams reviewParams=new LinearLayout.LayoutParams(-1,-2);reviewParams.setMargins(0,dp(6),0,dp(6));card.addView(review,reviewParams);
        final int[] previousStyle={-1};
        bind(() -> {
            ReadingSummary.State state=summaryState();boolean attention=state==ReadingSummary.State.ATTENTION,unknown=state==ReadingSummary.State.UNAVAILABLE;
            boolean urgent=attention&&unresolvedUrgentCount()>0;int style=unknown?0:urgent?3:attention?2:1;
            if(previousStyle[0]!=style) {
                previousStyle[0]=style;card.setBackground(shape(attention?p.warningSoft:unknown?p.surface:p.soft,24,Color.TRANSPARENT));
                mark.setImageResource(unknown?R.drawable.ic_status_unknown:attention?R.drawable.ic_status_attention:R.drawable.ic_status_ok);
                mark.setImageTintList(ColorStateList.valueOf(urgent?p.danger:attention?p.warning:p.primary));heading.setTextColor(urgent?p.danger:p.text);
            }
            setLiveText(heading,unknown?(alertLoadFailed?"Alert history incomplete":recent()?"Some readings unavailable":"No recent readings"):urgent?"Urgent alert":attention?"Needs attention":liveMode?"Receiving readings":"Readings stable");
            String detail=unknown?(alertLoadFailed?"Refresh the demo event history before assessing the alert status":recent()?"Check sensor contact and the available readings":"Check the device connection"):
                attention?(liveMode&&unresolvedServerCount()>0?unresolvedServerCount()+" server alert episodes remain unresolved. "+(pendingAlertCount()>0?pendingAlertCount()+" events await review.":"Review recorded; awaiting recovery."):
                    pendingAlertCount()>0?pendingAlertCount()+" alerts to review":"A simulated reading needs review"):
                liveMode?"Latest valid readings received over Bluetooth":"Current demo readings look consistent";
            setLiveText(description,detail);
            incomplete.setVisibility(alertLoadFailed&&!unknown?View.VISIBLE:View.GONE);review.setVisibility(attention?View.VISIBLE:View.GONE);
        });
    }

    private LinearLayout vitalCard(String name,Supplier<Double> value,String unit,int decimals,int color,String historyKey) {
        LinearLayout card=styledCard();addCardHeading(card,name.equals("Heart rate")?R.drawable.ic_heart:R.drawable.ic_oxygen,name,color);
        bindLabel(card,() -> measurement(display(value.get()),unit,decimals),27,p.text,true);
        if(dark&&unit.equals("%")) {
            SensorChartView chart=SensorChartView.ring(this,null,100,"SpO₂ %",color,p.text,p.muted,p.border);addVisual(card,chart);bind(() -> chart.updateRing(display(value.get()),100));
        } else {
            SensorChartView chart=SensorChartView.spark(this,null,color,unit,p.text,p.muted,p.border);addVisual(card,chart);bind(() -> chart.updateSeries(history(historyKey,value.get())));
        }
        label(card,trendNote(),11,p.muted,false);
        clickable(card,"View "+name.toLowerCase(Locale.US)+" charts",v -> openModule(0));return card;
    }

    private void sensors() {
        title("Sensor modules",liveMode?"Live readings received over Bluetooth":"A visual view of each module · demo data");
        for(int first=0;first<MODULES.length;first+=compact()?1:2) {
            if(compact()||first+1==MODULES.length) attach(content,moduleCard(first));
            else {
                LinearLayout row=horizontal();
                LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-2,1);a.setMargins(0,0,dp(6),dp(12));
                LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,-2,1);b.setMargins(dp(6),0,0,dp(12));
                row.addView(moduleCard(first),a);row.addView(moduleCard(first+1),b);content.addView(row);
            }
        }
        paragraph("4 sensor inputs + 1 buzzer actuator. Tap a module to view its charts.");
    }

    private LinearLayout moduleCard(int index) {
        LinearLayout card=styledCard();int color=moduleColor(index);addCardHeading(card,GLYPHS[index],MODULES[index],color);
        bindLabel(card,()->moduleCardValue(index),21,p.text,true);
        if(index==2) label(card,"X, Y and Z charts in details",12,p.muted,false);
        else if(index!=4) {
            String indexUnit=index==0?"BPM":index==1?"°C":"ADC";
            SensorChartView chart=SensorChartView.spark(this,null,color,indexUnit,p.text,p.muted,p.border);
            addVisual(card,chart);
            bind(()->chart.updateSeries(moduleHistory(index,moduleReading(index))));
        }
        else label(card,"Reported state",12,p.muted,false);
        label(card,CHIPS[index]+"  →",11,p.muted,false);
        clickable(card,"View "+MODULES[index]+" details",v -> openModule(index));return card;
    }

    private Double moduleReading(int index) {
        switch(index) {
            case 0:return display(heartRate());
            case 1:return display(temperature("object_temp_c"));
            case 2:return display(axis("accel_g","z"));
            case 3:return display(lightRaw());
            default:return null;
        }
    }

    private String moduleCardValue(int index) {
        Double reading=moduleReading(index);
        switch(index) {
            case 0:return measurement(reading,"BPM",0);
            case 1:return measurement(reading,"°C surface",1);
            case 2:return measurement(reading,"g · Z",2);
            case 3:return measurement(reading,"raw ADC",0);
            default:return buzzerState();
        }
    }

    private void openModule(int index) { hideKeyboard();detailModule=index;page="sensor";tab=1;render(); }
    private int moduleColor(int index) { return index==0?p.heart:index==1?p.surfaceTemp:index==3?p.warning:p.primary; }
    private double[] moduleHistory(int index,Double value) {
        return history(index==0?"heart_rate_bpm":index==1?"surface_temp_c":index==3?"light_adc":"",value);
    }
    private Double display(Double value) { return recent()?value:null; }
    private double[] history(String key,Double value) {
        if(liveMode)return recent()&&value!=null&&monitor!=null?monitor.history(key):null;
        if(!recent()||value==null||demoHistory==null||key.isEmpty()) return null;
        JSONArray points=demoHistory.optJSONArray(key);if(points==null||points.length()<2) return null;
        double[] result=new double[points.length()];for(int i=0;i<result.length;i++) result[i]=points.optDouble(i,Double.NaN);
        // Synthetic history ends at the displayed demo snapshot, never at an unrelated cached value.
        result[result.length-1]=value;return result;
    }

    private void sensorDetails() {
        bindLabel(content,()->"Device ID: "+deviceId(),12,p.muted,false);
        label(content,liveMode?"Recent Bluetooth readings":"Latest hour · illustrative trends",12,p.muted,false);space(content,8);
        switch(detailModule) {
            case 0:
                trendCard("Heart rate",this::heartRate,"BPM",0,p.heart,"heart_rate_bpm","Estimated heart rate",false);
                trendCard("Blood oxygen",this::spo2,"%",0,p.oxygen,"spo2_percent","Estimated SpO₂",false);
                LinearLayout optical=card(content);addCardHeading(optical,R.drawable.ic_heart,"Optical signal",p.heart);
                SensorChartView opticalChart=SensorChartView.bars(this,null,new String[]{"Red","Infrared"},p.heart,"raw counts",p.text,p.muted,p.border);
                addVisual(optical,opticalChart);
                bind(()->opticalChart.updateBars(new double[]{toNumber(display(opticalReadable()?finite(module("max30102"),"red_raw"):null)),toNumber(display(opticalReadable()?finite(module("max30102"),"ir_raw"):null))},new String[]{"Red","Infrared"}));
                bindLabel(optical,()->"Sensor status: "+opticalStatus(),12,p.muted,false);
                label(optical,"Raw optical counts are not BPM or oxygen percentages.",12,p.muted,false);break;
            case 1:
                trendCard("Surface temperature",()->temperature("object_temp_c"),"°C",1,p.surfaceTemp,"surface_temp_c","Surface temperature, separate from core body temperature",false);
                trendCard("Ambient temperature",()->temperature("ambient_temp_c"),"°C",1,p.ambientTemp,"ambient_temp_c","Surrounding air temperature",false);break;
            case 2:
                axisCard("Acceleration","accel_g","g");axisCard("Angular velocity","gyro_dps","°/s");
                paragraph(liveMode?"Latest motion samples · no fall classification":"Latest simulated motion samples · no fall classification");break;
            case 3:
                trendCard("Relative light",this::lightRaw,"ADC",0,p.warning,"light_adc","Raw ADC counts · not calibrated lux",false);
                LinearLayout light=card(content);addCardHeading(light,R.drawable.ic_light,"ADC range",p.warning);
                SensorChartView lightChart=SensorChartView.ring(this,null,100,"% of ADC",p.warning,p.text,p.muted,p.border);
                addVisual(light,lightChart);
                bind(()->lightChart.updateRing(display(lightPercent()),100));
                label(light,liveMode?"Relative light reading · live ADC":"Relative light reading · simulated data",12,p.muted,false);break;
            default:
                LinearLayout buzzer=card(content);ImageView mark=icon(R.drawable.ic_buzzer,64,p.muted);
                LinearLayout.LayoutParams markParams=new LinearLayout.LayoutParams(dp(76),dp(76));markParams.gravity=Gravity.CENTER_HORIZONTAL;buzzer.addView(mark,markParams);
                bindLabel(buzzer,this::buzzerState,24,p.text,true);
                bindLabel(buzzer,()->"GPIO "+number(finite(module("buzzer"),"pin"),0)+" · actuator",12,p.muted,false);
                label(buzzer,"The firmware reports its pin. No on/off state or activity history is available.",13,p.muted,false);
                label(buzzer,"Remote buzzer control is not connected.",12,p.muted,false);
        }
    }

    private void trendCard(String name,Supplier<Double> value,String unit,int decimals,int color,String key,String explanation,boolean bars) {
        LinearLayout card=card(content);addCardHeading(card,unit.equals("BPM")?R.drawable.ic_heart:unit.equals("%")?R.drawable.ic_oxygen:unit.equals("°C")?R.drawable.ic_temperature:R.drawable.ic_light,name,color);
        bindLabel(card,()->measurement(display(value.get()),unit,decimals),32,p.text,true);
        SensorChartView chart=bars?SensorChartView.bars(this,null,new String[0],color,unit,p.text,p.muted,p.border)
                :SensorChartView.line(this,null,color,unit,p.text,p.muted,p.border);
        chart.updateMessage("No recent readings");addVisual(card,chart);
        bind(()-> {
            double[] points=history(key,value.get());
            if(bars)chart.updateBars(points,new String[0]);else chart.updateSeries(points);
        });
        label(card,explanation,12,p.muted,false);label(card,trendNote(),11,p.muted,false);
    }

    private void axisCard(String name,String group,String unit) {
        LinearLayout card=card(content);addCardHeading(card,R.drawable.ic_motion,name,p.primary);
        SensorChartView chart=SensorChartView.bars(this,null,new String[]{"X","Y","Z"},p.primary,unit,p.text,p.muted,p.border);
        addVisual(card,chart);
        bind(()->chart.updateBars(new double[]{toNumber(display(axis(group,"x"))),toNumber(display(axis(group,"y"))),toNumber(display(axis(group,"z")))},new String[]{"X","Y","Z"}));
        label(card,liveMode?"Latest sample · Bluetooth":"Latest sample · simulated data",11,p.muted,false);
    }
    private static double toNumber(Double value) { return value==null?Double.NaN:value; }
    private String trendNote(){return liveMode?"Recent readings received on this phone":"Illustrative trend · simulated data";}
    private void addVisual(LinearLayout parent,View chart) {
        if(liveMode&&chart instanceof SensorChartView)((SensorChartView)chart).timeline("Earlier","","Latest");
        parent.addView(chart,new LinearLayout.LayoutParams(-1,-2));
    }

    private void alerts() {
        alertContentHost=vertical();content.addView(alertContentHost,new LinearLayout.LayoutParams(-1,-2));
        rebuildAlertContent();
    }

    /** Only the event area changes when actual event content changes. */
    private void rebuildAlertContent() {
        if(alertContentHost==null)return;
        LinearLayout parent=content;content=alertContentHost;alertContentHost.removeAllViews();
        try {if(page.equals("alert"))buildAlertDetails();else buildAlerts();}
        finally {content=parent;}
    }

    private void buildAlerts() {
        title("Care updates",patientName+" · Device ID: "+deviceId());
        LinearLayout summary=card(content);
        int pending=pendingAlertCount(),urgent=urgentAlertCount();
        addCardHeading(summary,alertLoadFailed?R.drawable.ic_status_unknown:pending>0?R.drawable.ic_nav_alerts:R.drawable.ic_status_ok,
            alertLoadFailed?"Alert history incomplete":pending>0?pending+" alerts to review":"No alerts to review",alertLoadFailed?p.muted:urgent>0?p.danger:pending>0?p.warning:p.primary);
        label(summary,urgent>0?urgent+" urgent · check the most recent events":alertLoadFailed?"Some events could not load":"Health readings and device updates",13,p.muted,false);
        label(summary,liveMode?"Authorised server events and separately labelled device events":"Simulated event history · not live detections",12,p.muted,false);
        if(liveMode) {
            label(summary,remoteAlertState(),12,p.muted,false);
            label(summary,"Last successful sync: "+date(monitor==null?0:monitor.serverAlertLastSyncAt()),11,p.muted,false);
            label(summary,"ML shadow mode · predictions are recorded for evaluation and do not trigger care alarms.",12,p.muted,false);
            action(summary,"Refresh server alerts",false,v -> {if(monitor!=null)monitor.refreshServerAlerts();});
            label(summary,"Updates run while this app is open or a live Bluetooth session is monitoring. Review the sync status before relying on cached events.",11,p.muted,false);
        }
        if(alertLoadFailed) notice("Some alert events could not load. Refresh demo readings in Settings to retry.");
        alertFilters();
        int visible=0;
        for(AlertInbox.Entry event:inbox.entries()) {
            if(alertFilter.equals("resolved") || alertFilter.equals("pending")&&!event.pending() || alertFilter.equals("reviewed")&&!event.reviewed()) continue;
            alertCard(event);visible++;
        }
        for(RemoteAlertInbox.Entry event:remoteAlerts()) {
            if(alertFilter.equals("pending")&&!event.pending() || alertFilter.equals("reviewed")&&!event.reviewed()
                    || alertFilter.equals("resolved")&&!event.resolved())continue;
            remoteAlertCard(event);visible++;
        }
        if(visible==0) {
            LinearLayout empty=card(content);addCardHeading(empty,alertLoadFailed?R.drawable.ic_status_unknown:R.drawable.ic_status_ok,alertLoadFailed?"History unavailable in this view":"Nothing here yet",alertLoadFailed?p.muted:p.primary);
            label(empty,alertLoadFailed?"Refresh demo readings in Settings to retry.":alertFilter.equals("pending")?"No listed alerts currently need review.":"No events in this view.",13,p.muted,false);
        }
        paragraph("Acknowledging records review; it does not confirm recovery. Device updates do not add to the alert count.");
        paragraph(liveMode?"Server rules require an explicitly approved policy. Fall, blood pressure, core body temperature, hydration and wear-state ML remain unavailable with the current inputs. Device events are not server ML results.":"Demo events only. Switch to live mode to receive your ESP32 readings.");
    }

    private void alertFilters() {
        LinearLayout filters=compact()?vertical():horizontal();
        int remoteReviewed=0,remoteResolved=0;for(RemoteAlertInbox.Entry item:remoteAlerts()){if(item.reviewed())remoteReviewed++;if(item.resolved())remoteResolved++;}
        String[] keys=liveMode?new String[]{"all","pending","reviewed","resolved"}:new String[]{"all","pending","reviewed"};
        String[] names={"All ("+(inbox.size()+remoteAlerts().size())+")","To review ("+pendingAlertCount()+")","Reviewed ("+(inbox.reviewedCount()+remoteReviewed)+")","Resolved ("+remoteResolved+")"};
        for(int i=0;i<keys.length;i++) {
            final String value=keys[i];Button control=button(names[i],alertFilter.equals(value),v -> {alertFilter=value;render();});
            control.setTextSize(12);control.setSelected(alertFilter.equals(value));
            LinearLayout.LayoutParams params=compact()?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1);
            params.setMargins(0,0,compact()?0:dp(5),dp(8));filters.addView(control,params);
        }
        attach(content,filters);
    }

    private void alertCard(AlertInbox.Entry event) {
        LinearLayout card=card(content);int color=alertColor(event.type);
        addCardHeading(card,alertIcon(event.type),event.type.title,color);
        label(card,severityLabel(event.type)+" · "+reviewLabel(event),12,color,true);
        label(card,event.reading,15,p.text,true);
        label(card,date(event.occurredAt)+(liveMode?" · device event":" · simulated event"),12,p.muted,false);
        if(event.type==AlertCatalog.Type.BLOOD_PRESSURE_HIGH || event.type==AlertCatalog.Type.HYDRATION_CHECK
                || event.type==AlertCatalog.Type.WATCH_NOT_WORN || event.type==AlertCatalog.Type.WATCH_WORN) {
            label(card,event.type.capability,12,p.muted,false);
        }
        label(card,"View details →",12,p.primary,true);
        clickable(card,event.type.title+", "+reviewLabel(event)+", "+event.reading+", view details",v -> {
            selectedAlertId=event.id;selectedServerAlertId=0;page="alert";tab=2;render();
        });
    }

    private AlertCatalog.Type remoteType(RemoteAlertInbox.Entry event) {
        String type=event.type;
        if("surface_temp_high".equals(type))return AlertCatalog.Type.BODY_TEMP_HIGH;
        if("surface_temp_low".equals(type))return AlertCatalog.Type.BODY_TEMP_LOW;
        return AlertCatalog.find(type);
    }
    private String remoteTitle(RemoteAlertInbox.Entry event) {
        AlertCatalog.Type type=remoteType(event);
        return type==null?"Sensor reading unavailable":type.title;
    }
    private int remoteColor(RemoteAlertInbox.Entry event) {
        return "urgent".equals(event.severity)||"critical".equals(event.severity)?p.danger:
            "warning".equals(event.severity)||"attention".equals(event.severity)?p.warning:p.primary;
    }
    private String remoteOrigin(RemoteAlertInbox.Entry event) {
        return "quality".equals(event.source)?"Server · signal quality":"Server · configured rule";
    }
    private String remoteStatus(RemoteAlertInbox.Entry event) {
        return event.resolved()?"Resolved":event.reviewed()?"Acknowledged":"Active · to review";
    }
    private void remoteAlertCard(RemoteAlertInbox.Entry event) {
        LinearLayout card=card(content);AlertCatalog.Type type=remoteType(event);int color=remoteColor(event);
        addCardHeading(card,type==null?R.drawable.ic_status_unknown:alertIcon(type),remoteTitle(event),color);
        label(card,remoteOrigin(event)+" · "+remoteStatus(event),12,color,true);
        label(card,(event.resolved()?"Recovery: ":"Trigger: ")+event.reading,15,p.text,true);
        label(card,"Device ID: "+event.deviceId+" · "+date(event.occurredAt),12,p.muted,false);
        label(card,"View details →",12,p.primary,true);
        clickable(card,remoteTitle(event)+", "+remoteStatus(event)+", view server event",v -> {
            selectedServerAlertId=event.id;selectedAlertId="";page="alert";tab=2;render();
        });
    }
    private void buildRemoteAlertDetails() {
        RemoteAlertInbox.Entry selected=null;
        for(RemoteAlertInbox.Entry item:remoteAlerts())if(item.id==selectedServerAlertId){selected=item;break;}
        if(selected==null){title("Server alert unavailable","Refresh the authorised alert inbox to retrieve this event.");action(content,"Back to alerts",true,v -> navigate(2));return;}
        final RemoteAlertInbox.Entry event=selected;
        title(remoteTitle(event),"Device ID: "+event.deviceId);
        LinearLayout hero=card(content);AlertCatalog.Type type=remoteType(event);
        addCardHeading(hero,type==null?R.drawable.ic_status_unknown:alertIcon(type),remoteOrigin(event),remoteColor(event));
        label(hero,(event.resolved()?"Recovery: ":"Trigger: ")+event.reading,24,p.text,true);
        if(type!=null)label(hero,type.message,15,p.text,false);
        label(hero,"Occurred: "+date(event.occurredAt),12,p.muted,false);
        label(hero,"Status updated: "+date(event.updatedAt),12,p.muted,false);
        label(hero,(event.resolved()?"The supporting value was recorded when this episode resolved.":"The supporting value was recorded when this episode opened.")+" Current readings are shown on Home and Sensors.",12,p.muted,false);
        LinearLayout status=card(content);addCardHeading(status,event.resolved()?R.drawable.ic_status_ok:R.drawable.ic_nav_alerts,remoteStatus(event),remoteColor(event));
        label(status,remoteAlertState(),12,p.muted,false);
        label(status,"Last successful sync: "+date(monitor==null?0:monitor.serverAlertLastSyncAt()),11,p.muted,false);
        if(event.acknowledgedAt>0)label(status,"Acknowledged: "+date(event.acknowledgedAt),13,p.muted,false);
        if(event.resolvedAt>0)label(status,"Resolved: "+date(event.resolvedAt),13,p.muted,false);
        if(event.pending()) {
            if(monitor!=null&&monitor.canAcknowledgeServerAlerts())action(status,"Acknowledge on server",true,v -> monitor.acknowledgeServerAlert(event.id));
            else label(status,"An alerts:ack credential is required to acknowledge this event.",12,p.muted,false);
        }
        label(status,"The state updates after server confirmation. Acknowledgement records review; recovery is evaluated separately.",12,p.muted,false);
        LinearLayout provenance=card(content);label(provenance,"Decision details",17,p.text,true);
        label(provenance,"Source: "+event.source+" · Event ID: "+event.id,13,p.text,false);
        label(provenance,"Rule version: "+event.ruleVersion,12,p.muted,false);
        label(provenance,"ML is in shadow mode. Experimental predictions do not create these care alerts.",12,p.muted,false);
        action(content,"Refresh server alerts",false,v -> {if(monitor!=null)monitor.refreshServerAlerts();});
        action(content,"Back to alerts",false,v -> navigate(2));
    }

    private void alertDetails() {
        alertContentHost=vertical();content.addView(alertContentHost,new LinearLayout.LayoutParams(-1,-2));
        rebuildAlertContent();
    }

    private void buildAlertDetails() {
        if(liveMode&&selectedServerAlertId>0){buildRemoteAlertDetails();return;}
        AlertInbox.Entry event=inbox.find(selectedAlertId);
        if(event==null) {
            title("Alert unavailable","Return to the inbox to select an event.");
            action(content,"Back to alerts",true,v -> navigate(2));return;
        }
        title(event.type.title,patientName+" · Patient 001");
        LinearLayout hero=card(content);
        addCardHeading(hero,alertIcon(event.type),severityLabel(event.type)+(liveMode?" · device event":" · simulated event"),alertColor(event.type));
        label(hero,event.reading,24,p.text,true);
        label(hero,event.type.message,16,p.text,false);
        label(hero,"Reported: "+date(event.occurredAt),12,p.muted,false);
        LinearLayout status=card(content);addCardHeading(status,event.reviewed()?R.drawable.ic_status_ok:
            event.pending()?R.drawable.ic_status_attention:R.drawable.ic_watch,reviewLabel(event),p.primary);
        if(event.reviewed()) label(status,"Reviewed: "+date(event.acknowledgedAt),13,p.muted,false);
        else label(status,event.pending()?"This event is waiting for a caregiver to review it.":"Informational device update. No acknowledgement is required.",13,p.muted,false);
        if(event.pending()) action(status,"Acknowledge alert",true,v -> {
            if(inbox.acknowledge(event.id,System.currentTimeMillis())) {
                render();toast("Alert reviewed locally. This does not confirm recovery.");
            } else toast("Unable to update this alert. Please reopen it.");
        });
        LinearLayout source=card(content);label(source,"Event source",17,p.text,true);
        label(source,event.type.source,14,p.text,false);
        label(source,"Device ID: "+event.deviceId,13,p.muted,false);
        label(source,event.type.capability,13,p.muted,false);
        label(source,liveMode?"Received from the linked device. Review of this device event is local; authorised server events are listed separately.":"This is a bundled simulated event. No live device notification was received.",12,p.muted,false);
        action(content,"Back to alerts",false,v -> navigate(2));
        paragraph("An acknowledgement records review. Server rule settings and recovery decisions are managed separately from this local event.");
    }

    private String reviewLabel(AlertInbox.Entry event) {
        return event.reviewed()?"Reviewed":event.pending()?"To review":"Device update";
    }

    private String severityLabel(AlertCatalog.Type type) {
        switch(type.severity) {
            case URGENT:return "Urgent";
            case ATTENTION:return "Attention";
            case NOTICE:return "Notice";
            default:return "Info";
        }
    }

    private int alertColor(AlertCatalog.Type type) {
        return type.severity==AlertCatalog.Severity.URGENT?p.danger:
            type.severity==AlertCatalog.Severity.ATTENTION?p.warning:p.primary;
    }

    private int alertIcon(AlertCatalog.Type type) {
        switch(type) {
            case POSSIBLE_FALL:return R.drawable.ic_fall;
            case BLOOD_PRESSURE_HIGH:return R.drawable.ic_pressure;
            case HEART_RATE_HIGH:case HEART_RATE_LOW:return R.drawable.ic_heart;
            case OXYGEN_HIGH:case OXYGEN_LOW:return R.drawable.ic_oxygen;
            case BODY_TEMP_HIGH:case BODY_TEMP_LOW:case AMBIENT_TEMP_HIGH:case AMBIENT_TEMP_LOW:return R.drawable.ic_temperature;
            case WATCH_WORN:case WATCH_NOT_WORN:return R.drawable.ic_watch;
            default:return R.drawable.ic_water;
        }
    }

    private void captureProfileDraft() {
        if(nameInput!=null) {draftName=nameInput.getText().toString();draftAge=ageInput.getText().toString();draftCaregiver=caregiverInput.getText().toString();}
    }
    private void profile() {
        ImageView avatar=icon(R.drawable.ic_nav_profile,52,p.primary);avatar.setBackground(shape(p.soft,38,Color.TRANSPARENT));avatar.setPadding(dp(15),dp(15),dp(15),dp(15));
        LinearLayout.LayoutParams avatarParams=new LinearLayout.LayoutParams(dp(82),dp(82));avatarParams.gravity=Gravity.CENTER_HORIZONTAL;avatarParams.setMargins(0,dp(10),0,dp(8));content.addView(avatar,avatarParams);
        TextView name=text(patientName,24,p.text,true);name.setGravity(Gravity.CENTER);content.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView caption=text(liveMode?"Patient profile · local":"Patient profile · demo",12,p.muted,false);caption.setGravity(Gravity.CENTER);content.addView(caption,new LinearLayout.LayoutParams(-1,-2));space(content,20);
        LinearLayout card=card(content);label(card,"Personal details",17,p.text,true);
        nameInput=field(card,"Patient name","Example patient name",false,false);nameInput.setText(draftName==null?patientName:draftName);
        ageInput=field(card,"Age","Years",false,false);ageInput.setInputType(InputType.TYPE_CLASS_NUMBER);ageInput.setText(draftAge==null?patientAge:draftAge);
        label(card,"Patient ID: Patient 001",12,p.muted,false);
        caregiverInput=field(card,"Caregiver name (optional)","Example caregiver name",false,false);caregiverInput.setText(draftCaregiver==null?caregiverName:draftCaregiver);
        LinearLayout device=card(content);addCardHeading(device,R.drawable.ic_motion,"Linked device",p.primary);
        label(device,"Device ID: "+deviceId(),14,p.text,true);label(device,"4 sensors + 1 buzzer actuator",12,p.muted,false);
        action(content,"Save profile",true,v -> saveProfile());
        paragraph("Saved only on this device. This local profile is not an online account or medical record.");
    }

    private void saveProfile() {
        String name=nameInput.getText().toString().trim(),age=ageInput.getText().toString().trim(),carer=caregiverInput.getText().toString().trim();
        boolean valid=true;
        if(name.length()<2||name.length()>40){nameInput.setError("Use 2–40 characters.");valid=false;}
        int ageNumber=0;try{ageNumber=Integer.parseInt(age);}catch(Exception ignored){}
        if(ageNumber<1||ageNumber>120){ageInput.setError("Enter an age from 1 to 120.");valid=false;}
        if(carer.length()>40){caregiverInput.setError("Use no more than 40 characters.");valid=false;}
        if(!valid)return;
        patientName=name;patientAge=String.valueOf(ageNumber);caregiverName=carer;
        preferences.edit().putString("patient_name",patientName).putString("patient_age",patientAge).putString("caregiver_name",caregiverName).apply();
        draftName=patientName;draftAge=patientAge;draftCaregiver=caregiverName;nameInput=ageInput=caregiverInput=null;
        hideKeyboard();render();toast("Profile saved on this device.");
    }

    private void settings() {
        title("Settings","Connection and application information");
        LinearLayout account=card(content);addCardHeading(account,R.drawable.ic_nav_profile,"Server account",p.primary);
        label(account,"Sign in to view your assigned readings, model classification and server alerts.",13,p.muted,false);
        action(account,"Open server account",true,v -> openAccount());
        LinearLayout connection=card(content);addCardHeading(connection,R.drawable.ic_bluetooth,"Bluetooth connection",p.primary);
        settingsBluetoothLabel=text("",14,p.text,true);connection.addView(settingsBluetoothLabel);
        settingsReceivedLabel=text("",12,p.muted,false);connection.addView(settingsReceivedLabel);
        action(connection,"Connect / manage ESP32",true,v -> openConnections());
        label(connection,"Connect using your team's Classic Bluetooth firmware, or select BLE for the earlier firmware.",12,p.muted,false);
        if(liveMode) {
            action(connection,"Stop live monitoring",false,v -> {permissionAction="";if(monitor!=null)monitor.stopMonitoring();syncLiveSnapshot();render();});
            action(connection,"Switch to demo",false,v -> enterDemo());
        } else {
            action(connection,"Refresh demo readings",false,v -> {updateSnapshot();render();toast(refreshFailed?"Demo snapshot unavailable":"Demo snapshot reloaded.");});
        }
        updateSettingsConnectionViews();
        LinearLayout appearance=card(content);label(appearance,"Appearance",17,p.text,true);
        label(appearance,dark?"Health focus · deep green":"Clear care · light",13,p.muted,false);
        action(appearance,"Clear care · light",!dark,v -> setAppearance(false));
        action(appearance,"Health focus · deep green",dark,v -> setAppearance(true));
        LinearLayout about=card(content);label(about,"Care Monitor 0.8.0",17,p.text,true);
        label(about,"ESP32 → Bluetooth SPP or BLE → phone → Wi-Fi server. Live mode never injects simulated readings or demo alerts.",12,p.muted,false);
        label(about,"Per-phone credentials authorise uploads and server alerts separately. Rule alerts need an approved server policy; ML records shadow predictions only.",12,p.muted,false);
        action(content,"End session",false,v -> signOut());
        paragraph("Ending the session stops the connection and pauses uploads. Saved profile, appearance, encrypted server credentials and queued uploads remain on this device.");
    }

    private void updateSettingsConnectionViews() {
        updateModeStrip();
        if(settingsBluetoothLabel==null)return;
        settingsBluetoothLabel.setText(bluetoothTransportLabel()+"\n"+(monitor==null?"Starting service":monitor.bluetoothState()));
        settingsReceivedLabel.setText(!liveMode?"Demo mode · connect an ESP32 for real sensor readings":
            monitor==null||monitor.receivedAt()==0?"Live mode · no sensor messages received yet":
            "Device ID: "+deviceId()+"\n"+monitor.packetCount()+" messages received"+(monitor.isFresh()?" · recent":" · stale"));
    }

    private void connections() {
        title("Connect your device","ESP32 → Bluetooth → phone → Wi-Fi → server");
        if(!liveMode)notice("Demo mode does not upload data. Scanning or connecting switches to live mode.");
        LinearLayout radio=card(content);addCardHeading(radio,R.drawable.ic_bluetooth,"Bluetooth device",p.primary);
        label(radio,"Bluetooth type",13,p.muted,false);
        action(radio,"Classic Bluetooth (SPP)","spp".equals(bluetoothTransport()),v -> selectBluetoothTransport("spp"));
        action(radio,"Bluetooth Low Energy (BLE)","ble".equals(bluetoothTransport()),v -> selectBluetoothTransport("ble"));
        label(radio,"spp".equals(bluetoothTransport())?"For your team's firmware: ElderSensors-elder-demo-01":"For the earlier BLE firmware: CareMonitor-elder-demo-01",12,p.muted,false);
        bluetoothLabel=text("",14,p.text,true);radio.addView(bluetoothLabel);
        receivedLabel=text("",12,p.muted,false);radio.addView(receivedLabel);
        connectionError=text("",12,p.danger,false);radio.addView(connectionError);
        action(radio,"Scan for ESP32",true,v -> radioAction("scan"));
        action(radio,"Stop scan",false,v -> {if(monitor!=null)monitor.stopScan();});
        deviceList=vertical();radio.addView(deviceList,new LinearLayout.LayoutParams(-1,-2));
        if("spp".equals(bluetoothTransport())) {
            label(radio,"First pairing: compare the code on your phone with the ESP32 Serial Monitor, then type Y there within 30 seconds. Allow the pairing request on the phone.",12,p.muted,false);
            bluetoothMacInput=field(radio,"Bluetooth MAC address","AA:BB:CC:DD:EE:FF",false,false);
            bluetoothMacInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            bluetoothMacInput.setText(bluetoothMacDraft);
            action(radio,"Connect by MAC",false,v -> {
                String address=bluetoothMacInput.getText().toString().trim().toUpperCase(Locale.US);
                if(!BluetoothAdapter.checkBluetoothAddress(address)){bluetoothMacInput.setError("Enter the Bluetooth MAC printed by your ESP32.");return;}
                bluetoothMacDraft=address;radioAction("connect:"+address);
            });
        }
        label(radio,"Android 7–11 also requires system Location to be enabled for scanning.",12,p.muted,false);
        if(liveMode)action(radio,"Stop live monitoring",false,v -> {permissionAction="";if(monitor!=null)monitor.stopMonitoring();syncLiveSnapshot();updateConnectionViews();});
        LinearLayout server=card(content);addCardHeading(server,R.drawable.ic_nav_settings,"Wi-Fi server",p.primary);
        label(server,"Requires the companion Care Monitor server 0.7.0 and individually issued keys.",12,p.muted,false);
        uploadLabel=text("",13,p.text,true);server.addView(uploadLabel);
        uploadCredentialLabel=label(server,"Upload key: loading",12,p.muted,false);
        EditText endpoint=field(server,"Server endpoint",DEFAULT_SERVER,false,false);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        String current=monitor==null?"":monitor.endpoint();endpoint.setText(current.isEmpty()?DEFAULT_SERVER:current);
        action(server,"Use team server address",false,v -> endpoint.setText(DEFAULT_SERVER));
        EditText apiKey=field(server,"Server API key","Enter the server's X-API-Key",true,false);
        label(server,"The key is encrypted on this phone and never shown in logs. No key is included in the APK.",12,p.muted,false);
        action(server,"Save server settings",true,v -> {
            if(monitor==null){toast("Connection service is starting. Try again shortly.");return;}
            try {monitor.configureServer(endpoint.getText().toString().trim(),apiKey.getText().toString().trim());
                apiKey.setText("");hideKeyboard();toast("Server configuration requested. Check the upload status below.");}
            catch(IllegalArgumentException error){toast(error.getMessage());}
        });
        action(server,"Verify upload connection",false,v -> {if(monitor!=null)monitor.verifyUploadServer();});
        action(server,"Prepare encrypted connection setup",false,v -> prepareConnectionSetup());
        action(server,"Import encrypted connection setup",false,v -> {
            if(setupBusy){toast("Connection setup is already in progress.");return;}
            Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {startActivityForResult(picker,603);}catch(RuntimeException error){toast("No file picker is available.");}
        });
        if(!connectionSetupStatus.isEmpty())label(server,connectionSetupStatus,12,p.muted,false);
        label(server,"Saved means encrypted on this phone. Verified means this server confirmed the key, device and sensor:write permission.",12,p.muted,false);
        label(server,"Only real Bluetooth messages are queued. Uploads use Wi-Fi and HTTPS with server certificate and hostname verification.",12,p.muted,false);
        label(server,"For this computer, use "+DEFAULT_SERVER+". Connect the phone and computer to the same Wi-Fi network.",12,p.muted,false);
        action(server,"Discard queued uploads",false,v -> {
            if(monitor==null)return;
            new AlertDialog.Builder(new ContextThemeWrapper(this,dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar))
                .setTitle("Discard queued uploads?")
                .setMessage("This removes unsent samples from this phone. Uploaded server records are unchanged.")
                .setNegativeButton("Cancel",null).setPositiveButton("Discard",(dialog,which)->monitor.clearUploadQueue()).show();
        });
        LinearLayout alertServer=card(content);addCardHeading(alertServer,R.drawable.ic_nav_alerts,"Server alerts",p.primary);
        label(alertServer,"Use a separate caregiver key with alerts:read. Add alerts:ack when this phone may acknowledge events.",12,p.muted,false);
        label(alertServer,"This connection uses the same HTTPS receiver address shown above. A changed address requires explicit configuration.",12,p.muted,false);
        alertCredentialLabel=label(alertServer,"Alert key: loading",12,p.muted,false);
        alertSyncLabel=label(alertServer,"",12,p.muted,false);
        EditText caregiverKey=field(alertServer,"Caregiver alert key","Enter the separate alerts key",true,false);
        action(alertServer,"Save alert key securely",true,v -> {
            if(monitor==null){toast("Connection service is starting. Try again shortly.");return;}
            try {monitor.configureAlertServer(endpoint.getText().toString().trim(),caregiverKey.getText().toString().trim());
                caregiverKey.setText("");hideKeyboard();toast("Saving alert key. Check the status above.");}
            catch(IllegalArgumentException error){toast(error.getMessage());}
        });
        action(alertServer,"Verify alert access",false,v -> {if(monitor!=null)monitor.verifyAlertServer();});
        action(alertServer,"View live server alerts",false,v -> {
            if(monitor==null){toast("Connection service is starting. Try again shortly.");return;}
            if(!liveMode)enterLive();serverAlertMonitoringRequested=true;monitor.startAlertMonitoring();navigate(2);
        });
        label(alertServer,"ML shadow results do not trigger alarms. Live numeric rules need an approved server policy. Alert polling continues while the app is open or live Bluetooth monitoring runs.",12,p.muted,false);
        action(content,"View live readings",false,v -> {if(!liveMode)enterLive();navigate(0);});
        paragraph("Keep this phone within Bluetooth range and connect it to a Wi-Fi network that can reach the computer server. The ongoing notification provides a Stop action.");
        updateConnectionViews();
    }

    private String bluetoothTransport() {return monitor==null?"spp":monitor.transport();}
    private String bluetoothTransportLabel() {return "ble".equals(bluetoothTransport())?"Bluetooth Low Energy (BLE)":"Classic Bluetooth (SPP)";}
    private void selectBluetoothTransport(String mode) {
        if(monitor==null){toast("Connection service is starting. Try again shortly.");return;}
        if(mode.equals(monitor.transport()))return;
        Runnable change=() -> {
            permissionAction="";permissionTransport=mode;hideKeyboard();
            if(monitor!=null&&monitor.setTransport(mode)){syncLiveSnapshot();render();}
        };
        if(monitor.isRunning()) {
            new AlertDialog.Builder(new ContextThemeWrapper(this,dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar))
                .setTitle("Switch Bluetooth type?")
                .setMessage("This stops the current connection. Queued server uploads are kept. Select a device to start a new connection.")
                .setNegativeButton("Cancel",null).setPositiveButton("Switch",(dialog,which)->change.run()).show();
        } else change.run();
    }

    private void syncLiveSnapshot() {
        if(liveMode&&monitor!=null){snapshot=monitor.snapshot();refreshedAt=monitor.receivedAt();refreshFailed=!monitor.isFresh();inbox=monitor.events();}
    }

    private void liveChanged() {
        if(!visible)return;
        syncLiveSnapshot();
        if(isSessionPage()&&!liveUpdatePending) {
            liveUpdatePending=true;
            liveUi.postDelayed(refreshLiveUi,Math.max(0,1000-(SystemClock.elapsedRealtime()-lastLiveUpdate)));
        }
    }
    private final Runnable refreshLiveUi=() -> {
        liveUpdatePending=false;
        if(!visible||!isSessionPage())return;
        lastLiveUpdate=SystemClock.elapsedRealtime();syncLiveSnapshot();updateModeStrip();
        if(page.equals("connections"))updateConnectionViews();
        else if(page.equals("settings"))updateSettingsConnectionViews();
        for(Runnable update:new ArrayList<>(liveBindings))update.run();
        List<AlertInbox.Entry> events=inbox.entries();
        List<RemoteAlertInbox.Entry> serverEvents=remoteAlerts();String serverState=remoteAlertState();
        if((!events.equals(displayedAlerts)||displayedAlertLoadFailed!=alertLoadFailed
                ||!serverEvents.equals(displayedServerAlerts)||!serverState.equals(displayedServerAlertState))&&alertContentHost!=null)rebuildAlertContent();
        displayedAlerts=events;displayedAlertLoadFailed=alertLoadFailed;
        displayedServerAlerts=serverEvents;displayedServerAlertState=serverState;
    };

    private void updateConnectionViews() {
        updateModeStrip();
        if(bluetoothLabel==null)return;
        bluetoothLabel.setText(bluetoothTransportLabel()+": "+(monitor==null?"Starting service":monitor.bluetoothState()));
        receivedLabel.setText(monitor==null||monitor.receivedAt()==0?"No sensor messages received":
            "Last message: "+date(monitor.receivedAt())+" · "+monitor.packetCount()+" received"+(monitor.isFresh()?"":" · stale"));
        connectionError.setText(monitor==null?"":monitor.error());
        uploadLabel.setText(monitor==null?"Server: starting service":"Server: "+monitor.relayState()+"\nQueued: "+monitor.queuedCount()+" · Last upload: "+date(monitor.uploadedAt()));
        if(uploadCredentialLabel!=null)setLiveText(uploadCredentialLabel,"Upload key: "+(monitor==null?"Loading":credentialDescription(monitor.uploadCredentialState())));
        if(alertCredentialLabel!=null)setLiveText(alertCredentialLabel,"Alert key: "+(monitor==null?"Loading":credentialDescription(monitor.alertCredentialState())));
        if(alertSyncLabel!=null)setLiveText(alertSyncLabel,remoteAlertState()+"\nLast successful sync: "+date(monitor==null?0:monitor.serverAlertLastSyncAt()));
        deviceList.removeAllViews();
        if(monitor!=null)for(LiveMonitorService.Device device:monitor.devices()) {
            action(deviceList,(device.name==null||device.name.isEmpty()?"ESP32 device":device.name)+"\n"+device.address+(device.rssi==Integer.MIN_VALUE?"":" · "+device.rssi+" dBm"),false,v -> radioAction("connect:"+device.address));
        }
    }

    private String credentialDescription(ServerCredentialState state) {
        String result=state.status;
        if(!state.keyId.isEmpty())result+="\nKey ID: "+state.keyId;
        if(!state.expiresAt.isEmpty())result+=" · Expires: "+state.expiresAt;
        return result;
    }

    private void radioAction(String action) {
        if(monitor==null){toast("Connection service is starting. Try again shortly.");return;}
        if(!liveMode)enterLive();
        permissionAction=action;permissionTransport=monitor.transport();
        java.util.ArrayList<String> missing=new java.util.ArrayList<>();
        if(Build.VERSION.SDK_INT>=31) {
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),601);return;}
        performRadioAction();
    }

    private void performRadioAction() {
        if(permissionAction.isEmpty()||waitingForBluetoothEnable)return;
        if(monitor==null)return; // Resume after binding if a permission result arrived first.
        if(!permissionTransport.equals(monitor.transport())){permissionAction="";toast("Bluetooth type changed. Select your device again.");return;}
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null){permissionAction="";toast("This device does not support Bluetooth.");return;}
        try {
            if(!adapter.isEnabled()){waitingForBluetoothEnable=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),602);return;}
            String action=permissionAction;permissionAction="";
            if(action.equals("scan"))monitor.scan();
            else if(action.startsWith("connect:")) {
                Intent connect=new Intent(this,LiveMonitorService.class).setAction(LiveMonitorService.ACTION_CONNECT)
                    .putExtra("address",action.substring(8)).putExtra("transport",permissionTransport);
                if(Build.VERSION.SDK_INT>=26)startForegroundService(connect);else startService(connect);
            }
        } catch(RuntimeException failure){permissionAction="";waitingForBluetoothEnable=false;toast("Unable to use Bluetooth. Check Nearby devices permission and try again.");}
    }

    private void resumePendingRadioAction() {
        if(!visible||!liveMode||monitor==null||permissionAction.isEmpty()||waitingForBluetoothEnable)return;
        boolean granted=Build.VERSION.SDK_INT>=31?
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if(!granted)return;
        // A retained pending action follows an explicit user request. The saved
        // waiting flag prevents duplicate enable dialogs across recreation, and
        // cancellation clears the pending action before it can be resumed again.
        performRadioAction();
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==601) {
            boolean granted=Build.VERSION.SDK_INT>=31?
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
            if(granted)performRadioAction();else {permissionAction="";toast("Bluetooth permission was not granted. Enable it in Android app permissions to connect.");}
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==602){waitingForBluetoothEnable=false;if(result==RESULT_OK)performRadioAction();else permissionAction="";}
        if(request==603&&result==RESULT_OK&&data!=null&&data.getData()!=null)reviewConnectionSetup(data.getData(),false);
    }

    private void handleConnectionSetupAction(Intent intent) {
        if(intent==null)return;
        String action=intent.getAction();
        if(SecureConnectionSetup.PREPARE_ACTION.equals(action)) {
            page="connections";render();prepareConnectionSetup();
        } else if(SecureConnectionSetup.REVIEW_ACTION.equals(action)) {
            page="connections";render();
            if(monitor==null)pendingSetupReview=true;else reviewConnectionSetup(null,true);
        }
    }

    private void prepareConnectionSetup() {
        if(setupBusy){toast("Connection setup is already in progress.");return;}
        setupBusy=true;final int generation=++setupGeneration;
        new Thread(()->{
            boolean okay=false;try{SecureConnectionSetup.prepare(getApplicationContext());okay=true;}catch(Exception ignored){}
            final boolean success=okay;
            setupUi.post(()->{
                if(generation!=setupGeneration||isFinishing())return;
                setupBusy=false;connectionSetupStatus=success?"Setup public key ready. The computer can prepare an encrypted setup for this phone.":"Could not prepare secure setup. Check phone storage and try again.";
                toast(connectionSetupStatus);if(page.equals("connections"))render();
            });
        },"care-monitor-setup-public").start();
    }

    private void reviewConnectionSetup(android.net.Uri uri,boolean fixed) {
        if(setupBusy){toast("Connection setup is already in progress.");return;}
        if(monitor==null){toast("Connection service is starting. Try importing again shortly.");return;}
        setupBusy=true;final int generation=++setupGeneration;
        connectionSetupStatus="Checking encrypted setup and both server permissions…";
        if(page.equals("connections"))render();
        new Thread(()->{
            SecureConnectionSetup.Verified result=null;
            try(InputStream input=fixed?new java.io.FileInputStream(SecureConnectionSetup.fixedFile(getApplicationContext())):getContentResolver().openInputStream(uri)) {
                if(input==null)throw new java.io.IOException();
                SecureConnectionSetup.Draft draft=SecureConnectionSetup.decrypt(getApplicationContext(),input);
                result=SecureConnectionSetup.verify(getApplicationContext(),draft);
            } catch(Exception ignored){}
            final SecureConnectionSetup.Verified verified=result;
            setupUi.post(()->{
                if(generation!=setupGeneration||isFinishing())return;
                if(verified==null){failConnectionSetup("Setup not applied. Check its expiry, recipient, Wi-Fi connection and server permissions.");return;}
                showConnectionSetupReview(verified,uri,fixed,generation);
            });
        },"care-monitor-setup-check").start();
    }

    private void showConnectionSetupReview(SecureConnectionSetup.Verified verified,android.net.Uri uri,boolean fixed,int generation) {
        if(monitor==null){failConnectionSetup("Connection service unavailable. Import again shortly.");return;}
        SecureConnectionSetup.Draft draft=verified.draft;
        int queued=monitor.queuedCount();
        boolean discard=queued>0&&!draft.endpoint.equals(monitor.queuedUploadEndpoint());
        LinearLayout review=new LinearLayout(this);review.setOrientation(LinearLayout.VERTICAL);review.setPadding(dp(24),dp(8),dp(24),dp(8));
        TextView explanation=new TextView(this);explanation.setTextColor(p.text);explanation.setTextSize(15);
        explanation.setText("Server: "+draft.endpoint+"\nDevice: "+draft.deviceId+
            "\n\nBoth new keys passed HTTPS verification.\nUpload: sensor:write\nAlerts: alerts:read, alerts:ack\n\nPending uploads: "+queued+
            (discard?"\nThese samples belong to the previous server. Applying this setup must stop Bluetooth and discard those pending uploads.":"\nPending samples for this server are kept."));
        review.addView(explanation);
        CheckBox confirmDiscard=new CheckBox(this);confirmDiscard.setText("I approve stopping Bluetooth and discarding the previous server's pending uploads.");
        confirmDiscard.setTextColor(p.text);if(discard)review.addView(confirmDiscard);
        connectionSetupDialog=new AlertDialog.Builder(new ContextThemeWrapper(this,dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar))
            .setTitle("Apply encrypted connection setup?").setView(review).setNegativeButton("Cancel",(dialog,which)->{
                setupBusy=false;connectionSetupStatus="Setup cancelled. Existing keys and pending uploads are unchanged.";
            }).setPositiveButton("Apply setup",null).setOnCancelListener(dialog->{setupBusy=false;connectionSetupStatus="Setup cancelled. Existing keys and pending uploads are unchanged.";}).create();
        connectionSetupDialog.setOnShowListener(dialog->{
            Button apply=connectionSetupDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if(discard){apply.setEnabled(false);confirmDiscard.setOnCheckedChangeListener((button,checked)->apply.setEnabled(checked));}
            apply.setOnClickListener(view->{
                if(discard&&!confirmDiscard.isChecked())return;
                connectionSetupDialog.dismiss();connectionSetupDialog=null;
                applyConnectionSetup(verified,uri,fixed,generation,discard);
            });
        });
        connectionSetupDialog.show();
    }

    private void applyConnectionSetup(SecureConnectionSetup.Verified verified,android.net.Uri uri,boolean fixed,int generation,boolean discard) {
        if(generation!=setupGeneration||monitor==null)return;
        SecureConnectionSetup.Draft draft=verified.draft;
        try {SecureSetupPolicy.metadata(draft.endpoint,draft.deviceId,draft.expiresAtMs,System.currentTimeMillis(),draft.setupId,draft.serverId);}
        catch(Exception ignored){failConnectionSetup("Setup expired before confirmation. Generate a fresh encrypted file.");return;}
        String resumeAddress=monitor.isRunning()?monitor.selectedBluetoothAddress():"";
        String resumeTransport=monitor.transport();
        if(discard) {
            monitor.stopMonitoring();monitor.clearUploadQueue();
            waitForSetupQueue(verified,uri,fixed,generation,resumeAddress,resumeTransport,SystemClock.elapsedRealtime()+10000);
        } else persistConnectionSetup(verified,uri,fixed,generation,"",resumeTransport);
    }

    private void waitForSetupQueue(SecureConnectionSetup.Verified verified,android.net.Uri uri,boolean fixed,int generation,String resumeAddress,String resumeTransport,long deadline) {
        if(generation!=setupGeneration||isFinishing())return;
        if(monitor==null||SystemClock.elapsedRealtime()>deadline){failConnectionSetup("Could not finish clearing pending storage. Setup was not applied; reconnect Bluetooth when ready.");return;}
        if(monitor.queuedCount()==0)persistConnectionSetup(verified,uri,fixed,generation,resumeAddress,resumeTransport);
        else setupUi.postDelayed(()->waitForSetupQueue(verified,uri,fixed,generation,resumeAddress,resumeTransport,deadline),150);
    }

    private void persistConnectionSetup(SecureConnectionSetup.Verified verified,android.net.Uri uri,boolean fixed,int generation,String resumeAddress,String resumeTransport) {
        if(monitor==null){failConnectionSetup("Connection service unavailable. Import again shortly.");return;}
        try {
            monitor.configureServer(verified.draft.endpoint,verified.draft.uploadKey);
            monitor.configureAlertServer(verified.draft.endpoint,verified.draft.alertsKey);
            connectionSetupStatus="Saving encrypted keys and confirming server access…";
            awaitConnectionSetupSaved(verified,uri,fixed,generation,resumeAddress,resumeTransport,SystemClock.elapsedRealtime()+25000,false);
        } catch(Exception ignored){failConnectionSetup("Setup could not be saved. Check pending uploads and connection settings before retrying.");}
    }

    private void awaitConnectionSetupSaved(SecureConnectionSetup.Verified verified,android.net.Uri uri,boolean fixed,int generation,String resumeAddress,String resumeTransport,long deadline,boolean verificationRequested) {
        if(generation!=setupGeneration||isFinishing())return;
        if(monitor==null||SystemClock.elapsedRealtime()>deadline){failConnectionSetup("Setup is not fully verified. Open Connections to check both saved key statuses; retry if needed.");return;}
        boolean requested=verificationRequested;
        if(monitor.isServerConfigured()&&monitor.isAlertServerConfigured()&&verified.draft.endpoint.equals(monitor.endpoint())&&verified.draft.endpoint.equals(monitor.serverAlertEndpoint())) {
            if(!requested){monitor.verifyUploadServer();monitor.verifyAlertServer();requested=true;}
            ServerCredentialState upload=monitor.uploadCredentialState(),alerts=monitor.alertCredentialState();
            if(upload.verified&&alerts.verified&&verified.uploadKeyId.equals(upload.keyId)&&verified.alertsKeyId.equals(alerts.keyId)
                    &&verified.draft.serverId.equals(upload.serverId)&&verified.draft.serverId.equals(alerts.serverId)) {
                if(!SecureConnectionSetup.rememberApplied(getApplicationContext(),verified.draft.setupId)){failConnectionSetup("Keys are saved, but setup completion could not be recorded. Check phone storage.");return;}
                if(fixed)try{SecureConnectionSetup.fixedFile(getApplicationContext()).delete();}catch(Exception ignored){}
                else if(uri!=null)try{android.provider.DocumentsContract.deleteDocument(getContentResolver(),uri);}catch(Exception ignored){}
                setupBusy=false;connectionSetupStatus="Both keys are saved securely and verified. Alert synchronisation requested.";
                if(!liveMode)enterLive();serverAlertMonitoringRequested=true;monitor.startAlertMonitoring();monitor.refreshServerAlerts();
                if(!resumeAddress.isEmpty()&&resumeTransport.equals(monitor.transport())&&BluetoothAdapter.checkBluetoothAddress(resumeAddress))radioAction("connect:"+resumeAddress);
                toast(connectionSetupStatus);if(page.equals("connections"))render();return;
            }
        }
        final boolean nextRequested=requested;
        setupUi.postDelayed(()->awaitConnectionSetupSaved(verified,uri,fixed,generation,resumeAddress,resumeTransport,deadline,nextRequested),200);
    }

    private void failConnectionSetup(String message) {
        setupBusy=false;connectionSetupStatus=message;toast(message);if(page.equals("connections"))render();
    }
    private void setAppearance(boolean value) { hideKeyboard();dark=value;preferences.edit().putBoolean("dark",dark).apply();render(); }

    private JSONObject readAsset(String name) throws Exception {
        try(InputStream input=getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[2048];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            return new JSONObject(new String(output.toByteArray(),"UTF-8"));
        }
    }
    private void updateSnapshot() {
        if(liveMode){syncLiveSnapshot();return;}
        try {
            snapshot=readAsset("sample_sensor_data.json");refreshedAt=System.currentTimeMillis();refreshFailed=false;
        } catch(Exception ignored){refreshFailed=true;}
        if(!inboxInitialized||alertLoadFailed) loadDemoAlerts();
        Double oxygen=spo2();
        if(recent()&&oxygen!=null&&oxygen<95) inbox.add(new AlertInbox.Entry("snapshot-oxygen-low",
            AlertCatalog.Type.OXYGEN_LOW,refreshedAt,deviceId(),measurement(oxygen,"% SpO₂ · simulated snapshot",0),0));
    }

    private void loadDemoAlerts() {
        inboxInitialized=true;
        try {
            JSONArray events=readAsset("sample_alerts.json").optJSONArray("events");
            alertLoadFailed=!decodeEvents(events,true,System.currentTimeMillis());
        } catch(Exception ignored){alertLoadFailed=true;}
    }

    private void restoreInbox(Bundle state) {
        inboxInitialized=state.getBoolean("inbox_initialized",false);
        alertLoadFailed=state.getBoolean("alert_load_failed",false);
        selectedAlertId=state.getString("selected_alert","");
        selectedServerAlertId=state.getLong("selected_server_alert",0);
        alertFilter=state.getString("alert_filter","all");
        if(!alertFilter.equals("all")&&!alertFilter.equals("pending")&&!alertFilter.equals("reviewed")&&!alertFilter.equals("resolved")) alertFilter="all";
        if(inboxInitialized) {
            try {if(!decodeEvents(new JSONArray(state.getString("alert_events","[]")),false,0)) alertLoadFailed=true;}
            catch(Exception ignored){alertLoadFailed=true;}
        }
    }

    /** Asset events and saved UI state share stable IDs; replay never clears review state. */
    private boolean decodeEvents(JSONArray events,boolean asset,long now) {
        if(events==null) return false;
        boolean complete=true;
        for(int i=0;i<events.length();i++) {
            try {
                JSONObject event=events.getJSONObject(i);
                AlertCatalog.Type type=AlertCatalog.find(event.optString("type",""));
                long at,ack;
                if(asset) {
                    long minutes=event.getLong("minutes_ago");
                    if(minutes<0||minutes>525600) throw new IllegalArgumentException("Invalid event age");
                    at=now-minutes*60000L;
                    ack=event.optBoolean("reviewed",false)?Math.min(now,at+60000L):0;
                } else {at=event.getLong("occurred_at");ack=event.optLong("acknowledged_at",0);}
                String eventDevice=asset?deviceId():event.optString("device_id","");
                inbox.add(new AlertInbox.Entry(event.optString("id",""),type,at,eventDevice,event.optString("reading",""),ack));
            } catch(Exception ignored){complete=false;}
        }
        return complete;
    }

    private JSONArray encodeInbox() {
        JSONArray events=new JSONArray();
        for(AlertInbox.Entry event:inbox.entries()) {
            try {
                JSONObject item=new JSONObject();item.put("id",event.id);item.put("type",event.type.id);
                item.put("occurred_at",event.occurredAt);item.put("acknowledged_at",event.acknowledgedAt);
                item.put("device_id",event.deviceId);item.put("reading",event.reading);events.put(item);
            } catch(Exception ignored){alertLoadFailed=true;}
        }
        return events;
    }

    private JSONObject module(String key) { return snapshot == null ? null : snapshot.optJSONObject(key); }

    private static Double finite(JSONObject object, String key) {
        if (object == null) return null;
        Object value = object.opt(key);
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        return Double.isNaN(number) || Double.isInfinite(number) ? null : number;
    }

    private static Double adc(Double value) { return value != null && value >= 0 && value <= 4095 ? value : null; }

    private static boolean available(JSONObject object) {
        if (object == null || object.length() == 0 || Boolean.FALSE.equals(object.opt("online"))) return false;
        if (object.has("status")) return "ok".equals(object.opt("status")) || "sampled".equals(object.opt("status"));
        return Boolean.TRUE.equals(object.opt("online")) || object.has("adc_raw");
    }

    private String deviceId() {
        return snapshot != null && snapshot.opt("device_id") instanceof String ? snapshot.optString("device_id") : "Unavailable";
    }

    private String opticalStatus() {
        JSONObject optical = module("max30102");
        return optical != null && optical.opt("status") instanceof String ? optical.optString("status") : "unknown";
    }

    private boolean opticalReadable() {
        JSONObject optical = module("max30102");
        String status = opticalStatus();
        return optical != null && optical.length() > 0 && !Boolean.FALSE.equals(optical.opt("online"))
                && !status.equals("not_found") && !status.equals("read_error") && !status.equals("timeout") && !status.equals("fifo_reset_error");
    }

    private Double heartRate() {
        JSONObject optical = module("max30102");
        return optical == null ? null : OpticalEstimatePolicy.heartRate(optical.opt("status"),
                optical.opt("online"), optical.opt("finger_detected"),
                optical.opt("heart_rate_valid"), optical.opt("heart_rate_bpm"),
                optical.has("sample_age_ms"), optical.opt("sample_age_ms"),
                optical.has("result_age_ms"), optical.opt("result_age_ms"));
    }

    private Double spo2() {
        JSONObject optical = module("max30102");
        return optical == null ? null : OpticalEstimatePolicy.oxygen(optical.opt("status"),
                optical.opt("online"), optical.opt("finger_detected"),
                optical.opt("spo2_valid"), optical.opt("spo2_percent"),
                optical.has("sample_age_ms"), optical.opt("sample_age_ms"),
                optical.has("result_age_ms"), optical.opt("result_age_ms"));
    }

    private Double temperature(String field) { return available(module("gy906")) ? finite(module("gy906"), field) : null; }

    private Double axis(String group, String axis) {
        JSONObject motion = module("mpu6050");
        return available(motion) ? finite(motion.optJSONObject(group), axis) : null;
    }

    private Double lightRaw() { return available(module("photoresistor")) ? adc(finite(module("photoresistor"), "adc_raw")) : null; }

    private Double lightPercent() {
        JSONObject light = module("photoresistor");
        if (!available(light)) return null;
        Double value = finite(light, "percent");
        if (value == null) value = finite(light, "adc_percent");
        if (value != null) return value >= 0 && value <= 100 ? value : null;
        Double raw = lightRaw(); return raw == null ? null : raw / 4095 * 100;
    }

    private String buzzerState() {
        Object on=module("buzzer")==null?null:module("buzzer").opt("on");
        if(!recent()||!(on instanceof Boolean)) return "State unknown";
        return Boolean.TRUE.equals(on)?"Commanded on":"Commanded off";
    }

    private boolean validEmail(EditText view) {
        String email = view.getText().toString().trim();
        boolean valid = email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
        if (!valid) view.setError("Enter a valid sample email address.");
        return valid;
    }

    private boolean validPassword(EditText view) {
        boolean valid = view.length() >= 8;
        if (!valid) view.setError("Use at least 8 characters for this demo.");
        return valid;
    }

    private static boolean validServer(String candidate) {
        try {
            URI uri = new URI(candidate);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535);
        } catch (Exception ignored) { return false; }
    }

    private void brand() {
        TextView mark=text("CM",22,p.primaryText,true);mark.setGravity(Gravity.CENTER);mark.setBackground(shape(p.primary,18,Color.TRANSPARENT));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(62),dp(62));params.setMargins(0,0,0,dp(22));content.addView(mark,params);
    }
    private void title(String heading,String description) {
        label(content,heading,page.equals("login")||page.equals("signup")?30:24,p.text,true);
        if(!description.isEmpty())label(content,description,13,p.muted,false);space(content,10);
    }
    private void paragraph(String message){label(content,message,12,p.muted,false);space(content,8);}
    private void notice(String message){LinearLayout card=card(content);card.setBackground(shape(p.soft,14,Color.TRANSPARENT));label(card,message,12,p.muted,false);}
    private LinearLayout styledCard(){LinearLayout card=vertical();card.setPadding(dp(16),dp(15),dp(16),dp(15));card.setBackground(shape(p.surface,22,p.border));return card;}
    private LinearLayout card(LinearLayout parent){LinearLayout card=styledCard();attach(parent,card);return card;}
    private void attach(LinearLayout parent,View child){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,0,0,dp(12));parent.addView(child,params);}
    private TextView addCardHeading(LinearLayout parent,int drawable,String title,int color){
        LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.addView(icon(drawable,23,color),new LinearLayout.LayoutParams(dp(27),dp(27)));
        TextView caption=text(title,14,p.text,true);caption.setPadding(dp(7),0,0,0);row.addView(caption,new LinearLayout.LayoutParams(0,-2,1));parent.addView(row,new LinearLayout.LayoutParams(-1,-2));space(parent,5);return caption;
    }
    private void sectionHeading(String heading,String action,View.OnClickListener listener){
        LinearLayout row=horizontal();row.setGravity(Gravity.CENTER_VERTICAL);TextView text=text(heading,17,p.text,true);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        Button button=button(action,false,listener);button.setTextSize(12);row.addView(button,new LinearLayout.LayoutParams(-2,-2));attach(content,row);
    }
    private void clickable(LinearLayout card,String description,View.OnClickListener listener){
        card.setOnClickListener(listener);card.setFocusable(true);buttonSemantics(card);
        // Visible values and chart summaries remain available to screen readers.
        card.setContentDescription(description);
    }
    private void buttonSemantics(View view){view.setAccessibilityDelegate(new View.AccessibilityDelegate(){
        @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}
    });}
    private ImageView icon(int drawable,int size,int color){ImageView image=new ImageView(this);image.setImageResource(drawable);image.setImageTintList(ColorStateList.valueOf(color));image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setMinimumWidth(dp(size));image.setMinimumHeight(dp(size));return image;}
    private View iconButton(int drawable,String description,View.OnClickListener listener){
        FrameLayout frame=new FrameLayout(this);frame.setMinimumWidth(dp(48));frame.setMinimumHeight(dp(48));frame.setFocusable(true);frame.setContentDescription(description);buttonSemantics(frame);
        frame.addView(icon(drawable,23,p.primary),new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
        frame.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(32,18,108,103)),shape(p.background,14,Color.TRANSPARENT),shape(p.surface,14,Color.TRANSPARENT)));
        frame.setOnClickListener(listener);frame.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return frame;
    }
    private EditText field(LinearLayout parent,String name,String hint,boolean password,boolean email){
        label(parent,name,13,p.text,true);EditText input=new EditText(this);input.setTextColor(p.text);input.setHintTextColor(p.muted);input.setTextSize(16);input.setHint(hint);
        input.setPadding(dp(13),dp(12),dp(13),dp(12));input.setSingleLine(true);input.setBackground(shape(p.surface,12,p.border));
        input.setSaveEnabled(false);input.setFreezesText(false);if(Build.VERSION.SDK_INT>=26)input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setInputType(InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:email?InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:InputType.TYPE_TEXT_FLAG_CAP_WORDS));
        input.setContentDescription(name);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(4),0,dp(14));parent.addView(input,params);return input;
    }
    private Button button(String label,boolean filled,View.OnClickListener listener){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(filled?p.primaryText:p.primary);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(9),dp(12),dp(9));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(35,18,108,103)),shape(filled?p.primary:p.surface,14,filled?Color.TRANSPARENT:p.border),shape(p.surface,14,Color.TRANSPARENT)));b.setOnClickListener(listener);return b;
    }
    private void action(LinearLayout parent,String label,boolean filled,View.OnClickListener listener){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(6),0,dp(6));parent.addView(button(label,filled,listener),params);}
    private TextView text(String value,float size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(2),1);if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
    private TextView label(LinearLayout parent,String value,float size,int color,boolean bold){TextView view=text(value,size,color,bold);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(4),0,dp(5));parent.addView(view,params);return view;}
    private void bind(Runnable update){update.run();if(liveMode)liveBindings.add(update);}
    private TextView bindLabel(LinearLayout parent,Supplier<String> value,float size,int color,boolean bold){
        TextView view=label(parent,"",size,color,bold);bind(() -> setLiveText(view,value.get()));return view;
    }
    private static void setLiveText(TextView view,String value){
        String next=value==null?"":value;if(!next.contentEquals(view.getText()))view.setText(next);
    }
    private void space(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout vertical(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout horizontal(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.HORIZONTAL);return view;}
    private GradientDrawable shape(int fill,int radius,int stroke){GradientDrawable value=new GradientDrawable();value.setColor(fill);value.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT)value.setStroke(dp(1),stroke);return value;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static String number(Double value,int decimals){return value==null?"Unavailable":String.format(Locale.US,"%."+decimals+"f",value);}
    private static String measurement(Double value,String unit,int decimals){return value==null?"Unavailable":number(value,decimals)+" "+unit;}
    private static String date(long timestamp){return timestamp==0?"Unavailable":new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date(timestamp));}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void hideKeyboard(){android.view.inputmethod.InputMethodManager manager=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);View focus=getCurrentFocus();if(manager!=null&&focus!=null)manager.hideSoftInputFromWindow(focus.getWindowToken(),0);}
    private void goBack(){hideKeyboard();if(page.equals("signup")){page="login";render();}else if(page.equals("connections")){page=connectionsReturnPage;render();}else if(!page.equals("shell")&&isSessionPage()){page="shell";render();}else if(page.equals("shell")&&tab!=0){navigate(0);}else finish();}
    @Override public void onBackPressed(){if(Build.VERSION.SDK_INT<33)goBack();else super.onBackPressed();}
}
