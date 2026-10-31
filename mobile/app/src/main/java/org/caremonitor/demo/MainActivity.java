package org.caremonitor.demo;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Accessible native companion. Accounts, authorisation and alert decisions belong to the server. */
public final class MainActivity extends Activity {
    private static final int BG=0xFFF2F2F7,INK=0xFF1C1C1E,MUTED=0xFF636366,BLUE=0xFF0066CC,LINE=0xFFE5E5EA,SOFT=0xFFEAF2FF,RED=0xFFB52B33;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private ApiSession session;
    private LinearLayout root,content,authActions;
    private ScrollView scroll;
    private String page="home",patientId="",notice="",pendingAction="";
    private JSONArray patients=new JSONArray(),requests=new JSONArray(),caregivers=new JSONArray(),reminders=new JSONArray(),notifications=new JSONArray();
    private JSONObject overview=new JSONObject(),consents=new JSONObject();
    private boolean visible,bound,busy,registering,refreshing;
    private long epoch;
    private LiveMonitorService monitor;
    private TextView connectionLabel,readingLabel,deviceHeading;
    private TextView homeConnection,homeReadingContext,detailFreshness,detailStored,detailSource,emptyReadingNote,emptyReadingHint,syncLabel,helpStatus;
    private Button showUpdatesButton,helpButton;
    private String syncProblem="",builtPageData="",helpOutcome="";
    private long overviewReceivedAt=SystemClock.elapsedRealtime();
    private boolean manualRefresh;
    private HelpAttempt helpAttempt;
    private Button homeWearableButton;
    private CareStatusView homeStatus;
    private boolean animateNextPage;
    private String homeBuiltKey="";
    private final Map<String,MetricViews> metricViews=new LinkedHashMap<>();
    private static final class MetricViews {
        final LinearLayout tile; final TextView value,availability,time; final String title;
        MetricViews(LinearLayout tile,TextView value,TextView availability,TextView time,String title){this.tile=tile;this.value=value;this.availability=availability;this.time=time;this.title=title;}
    }
    private ImageView setupStatusIcon;
    private LinearLayout deviceList;
    private EditText emailInput,passwordInput,nameInput,serverInput;
    private CheckBox termsCheck;
    private AccountSetup setup=new AccountSetup();
    private String loginEmail="";
    private boolean acknowledged;
    private int deviceStep;
    private String historyMetric="heart_rate_bpm",historyPeriod="day",historyError="",historyOrigin="home";
    private JSONObject historyData;
    private long historyEndMillis,historyRequest;
    private boolean historyLoading;
    private AuthAttempt authentication;
    /** Retained only across configuration changes; never writes passwords or responses to a Bundle. */
    private static final class AuthAttempt{
        final boolean registration;final String base,email;
        MainActivity owner;JSONObject response;String error;boolean finished;
        AuthAttempt(boolean registration,String base,String email){this.registration=registration;this.base=base;this.email=email;}
        synchronized void complete(JSONObject response,String error){this.response=response;this.error=error;finished=true;notifyOwner();}
        synchronized void attach(MainActivity activity){owner=activity;if(finished)notifyOwner();}
        private void notifyOwner(){final MainActivity activity=owner;if(activity!=null)activity.main.post(()->activity.finishAuthentication(this));}
    }
    private static final class HelpAttempt {
        final String accountId,base; MainActivity owner; boolean finished; String outcome;
        HelpAttempt(ApiSession account){accountId=account.id;base=account.base;}
        synchronized void attach(MainActivity activity){owner=activity;if(finished)notifyOwner();}
        synchronized void complete(String value){outcome=value;finished=true;notifyOwner();}
        private void notifyOwner(){MainActivity activity=owner;if(activity!=null)activity.main.post(()->activity.finishHelp(this));}
    }
    private static final class RetainedRequests {
        final AuthAttempt auth; final HelpAttempt help;
        RetainedRequests(AuthAttempt auth,HelpAttempt help){this.auth=auth;this.help=help;}
    }
    private Object backCallback;
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){monitor=((LiveMonitorService.LocalBinder)binder).service();monitor.addListener(liveListener);updateDevice();}
        public void onServiceDisconnected(ComponentName name){monitor=null;updateDevice();}
    };
    private final LiveMonitorService.Listener liveListener=()->{updateDevice();if(page.equals("home")||page.equals("reading_details"))updateReadingViews();};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        session=ApiSession.load(this);
        if(state!=null){page=state.getString("page","home");patientId=state.getString("patient","");
            registering=state.getBoolean("registering",false);setup.step=Math.max(0,Math.min(3,state.getInt("setup_step",0)));
            setup.role=state.getString("setup_role","");setup.name=state.getString("setup_name","");setup.email=state.getString("setup_email","");
            loginEmail=state.getString("login_email","");acknowledged=state.getBoolean("acknowledged",false);deviceStep=Math.max(0,Math.min(3,state.getInt("device_step",0)));
            helpOutcome=state.getString("help_outcome","");historyOrigin=state.getString("history_origin","home");
            historyMetric=state.getString("history_metric","heart_rate_bpm");historyPeriod=state.getString("history_period","day");historyEndMillis=state.getLong("history_end",0);}
        Object retained=getLastNonConfigurationInstance();
        if(retained instanceof RetainedRequests){RetainedRequests requests=(RetainedRequests)retained;helpAttempt=requests.help;if(helpAttempt!=null)helpAttempt.attach(this);retained=requests.auth;}
        if(retained instanceof AuthAttempt){authentication=(AuthAttempt)retained;busy=true;authentication.attach(this);}
        else if(state!=null&&state.getBoolean("auth_in_flight",false))notice="The request was interrupted. If your account was created, choose I already have an account to sign in.";
        bound=bindService(new Intent(this,LiveMonitorService.class),serviceConnection,BIND_AUTO_CREATE);
        if(Build.VERSION.SDK_INT>=33){android.window.OnBackInvokedCallback callback=this::goBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);backCallback=callback;}
        if(getIntent().getBooleanExtra("show_alerts",false)){page="alerts";patientId=getIntent().getStringExtra("patient_id")==null?patientId:getIntent().getStringExtra("patient_id");}
        render();handleSoundIntent(getIntent());if(session!=null&&page.equals("history"))loadHistory();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("show_alerts",false)){clearHistory();page="alerts";patientId=intent.getStringExtra("patient_id")==null?patientId:intent.getStringExtra("patient_id");epoch++;refreshing=false;render();refresh();}handleSoundIntent(intent);}
    private void handleSoundIntent(Intent intent){if(intent.getBooleanExtra("sound_check",false)&&session!=null&&session.elder()){
        intent.removeExtra("sound_check");new AlertDialog.Builder(this).setTitle("Are you okay?")
            .setMessage("Your phone detected a loud sound. This does not identify a fall or accident. You can notify your care circle.")
            .setPositiveButton("Send a notice",(d,w)->sendEvent("audio_impact")).setNegativeButton("I'm okay",null).show();}}
    @Override protected void onSaveInstanceState(Bundle state){rememberAuth();super.onSaveInstanceState(state);state.putString("page",page);state.putString("patient",patientId);
        state.putBoolean("registering",registering);state.putInt("setup_step",setup.step);state.putString("setup_role",setup.role);state.putString("setup_name",setup.name);
        state.putString("setup_email",setup.email);state.putString("login_email",loginEmail);state.putBoolean("acknowledged",acknowledged);state.putInt("device_step",deviceStep);state.putBoolean("auth_in_flight",busy);
        state.putString("help_outcome",helpOutcome);state.putString("history_origin",historyOrigin);state.putString("history_metric",historyMetric);state.putString("history_period",historyPeriod);state.putLong("history_end",historyEndMillis);}
    @Override public Object onRetainNonConfigurationInstance(){return new RetainedRequests(authentication,helpAttempt);}
    @Override public void onBackPressed(){goBack();}
    private void goBack(){
        if(busy){toast("Please wait for the request to finish.");return;}
        if(session==null){rememberAuth();notice="";if(registering&&setup.step>0)setup.back();else if(registering)registering=false;else{moveTaskToBack(true);return;}}
        else if(page.equals("device")&&deviceStep>0)deviceStep--;
        else if(page.equals("history"))page=historyOrigin;
        else if(isSettingsDetail())page="account";else page="home";
        if(!page.equals("history"))clearHistory();
        hideKeyboard();
        render();
    }
    @Override protected void onStart(){super.onStart();visible=true;if(homeStatus!=null)homeStatus.setActive(true);main.post(poll);main.post(presentationTick);}
    @Override protected void onStop(){visible=false;main.removeCallbacks(poll);main.removeCallbacks(presentationTick);stopPageMotion();super.onStop();}
    private void stopPageMotion(){if(homeStatus!=null)homeStatus.setActive(false);if(content!=null)CareMotion.cancel(content);for(MetricViews metric:metricViews.values())CareMotion.cancel(metric.value);}
    @Override protected void onDestroy(){epoch++;main.removeCallbacksAndMessages(null);if(monitor!=null)monitor.removeListener(liveListener);if(bound)unbindService(serviceConnection);
        if(authentication!=null)synchronized(authentication){if(authentication.owner==this)authentication.owner=null;}
        if(helpAttempt!=null)synchronized(helpAttempt){if(helpAttempt.owner==this)helpAttempt.owner=null;}
        if(isChangingConfigurations())network.shutdown();else network.shutdownNow();
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)backCallback);super.onDestroy();}
    private final Runnable poll=new Runnable(){public void run(){if(visible&&session!=null){refresh();}if(visible)main.postDelayed(this,15000);}};
    private final Runnable presentationTick=new Runnable(){public void run(){
        if(visible){if(page.equals("home")||page.equals("reading_details"))updateReadingViews();main.postDelayed(this,1000);}
    }};
    private void setLabel(TextView label,String value){if(label!=null&&!value.contentEquals(label.getText()))label.setText(value);}
    private void updateSyncLabel(){if(syncLabel!=null){setLabel(syncLabel,syncProblem);syncLabel.setVisibility(syncProblem.isEmpty()?View.GONE:View.VISIBLE);}}
    private String pageDataKey(){return page+"|"+patientId+"|"+patients+"|"+requests+"|"+caregivers+"|"+reminders+"|"+notifications+"|"+overview.optJSONArray("alerts");}
    private void refreshFromUser(){manualRefresh=true;refresh();}
    private boolean elder(){return session!=null&&session.elder();}
    private JSONObject json(Object... entries){JSONObject object=new JSONObject();try{for(int i=0;i<entries.length;i+=2)object.put((String)entries[i],entries[i+1]);}catch(Exception failure){throw new IllegalArgumentException(failure);}return object;}
    private void render(){
        stopPageMotion();metricViews.clear();
        connectionLabel=null;readingLabel=null;deviceList=null;deviceHeading=null;setupStatusIcon=null;
        syncLabel=null;showUpdatesButton=null;helpStatus=null;helpButton=null;
        homeConnection=null;homeReadingContext=null;homeStatus=null;detailFreshness=null;detailStored=null;detailSource=null;emptyReadingNote=null;emptyReadingHint=null;homeWearableButton=null;
        emailInput=null;passwordInput=null;nameInput=null;serverInput=null;termsCheck=null;
        authActions=null;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=23)root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        else root.setFitsSystemWindows(true);
        setContentView(root);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(12),dp(24),dp(24));scroll.addView(content);
        brand();
        if(session==null){auth();return;}
        if(!notice.isEmpty()){TextView message=text(notice,16,MUTED,false);message.setPadding(0,0,0,dp(16));content.addView(message);}
        if(page.equals("ready")){ready();return;}
        if(page.equals("home"))home();else if(page.equals("history"))history();else if(page.equals("alerts"))alerts();else if(page.equals("care"))care();else if(page.equals("device"))device();
        else if(page.equals("connection")){settingsBack();label("Connection",34,INK,true);paragraph("Your connection, in one place.");serverSettings();}
        else if(page.equals("privacy")){settingsBack();label("Your privacy",34,INK,true);paragraph("You choose what to share and when.");if(elder())privacy();}
        else if(page.equals("notification_settings")){settingsBack();label("Notifications",34,INK,true);notificationSettings();}
        else if(page.equals("security")){settingsBack();label("Your information",34,INK,true);securityDetails();}
        else if(page.equals("about")){settingsBack();aboutCareMonitor();}
        else if(page.equals("reading_details")){settingsBack();readingDetails();}
        else account();
        syncLabel=text("",16,MUTED,false);syncLabel.setPadding(0,dp(10),0,dp(12));syncLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(syncLabel,1);updateSyncLabel();
        builtPageData=pageDataKey();
        if(page.equals("care")||page.equals("alerts")){
            showUpdatesButton=textAction(content,"Show new updates",()->{int y=scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));},true);showUpdatesButton.setVisibility(View.GONE);
            content.removeView(showUpdatesButton);content.addView(showUpdatesButton,2);
        }
        navigation();
        if(animateNextPage){animateNextPage=false;final View target=content;content.post(()->{if(visible&&content==target)CareMotion.enter(target);});}
    }
    private void brand(){
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setPadding(0,0,0,dp(session==null?20:8));content.addView(brand);
        boolean summary=session!=null&&page.equals("home");
        if(!summary){ImageView mark=icon(R.drawable.ic_heart,BLUE,22);FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(SOFT,12));tile.addView(mark,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));brand.addView(tile,new LinearLayout.LayoutParams(dp(36),dp(36)));}
        TextView word=text(summary?"Today":"CareMonitor",summary?34:17,INK,true);LinearLayout.LayoutParams wordLp=new LinearLayout.LayoutParams(0,-2,1);wordLp.leftMargin=summary?0:dp(10);brand.addView(word,wordLp);
        if(session!=null){TextView profile=text(initials(session.name),16,BLUE,true);profile.setGravity(Gravity.CENTER);profile.setMinHeight(dp(56));profile.setBackground(interactive(SOFT,28));profile.setContentDescription("Open settings");profile.setOnClickListener(v->openPage("account"));brand.addView(profile,new LinearLayout.LayoutParams(dp(56),dp(56)));}
    }
    private boolean isSettingsDetail(){return page.equals("connection")||page.equals("privacy")||page.equals("notification_settings")||page.equals("security")||page.equals("reading_details")||page.equals("about");}
    private void settingsBack(){textAction(content,"Settings",()->openPage("account"),false);}
    private void openPage(String target){hideKeyboard();if(!target.equals("history"))clearHistory();animateNextPage=!page.equals(target);page=target;notice="";render();refresh();}
    private void hideKeyboard(){View focus=getCurrentFocus();if(focus!=null)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);}
    private String initials(String name){String[] parts=name.trim().split("\\s+");if(parts.length==0||parts[0].isEmpty())return "CM";return (parts[0].substring(0,1)+(parts.length>1?parts[parts.length-1].substring(0,1):"")).toUpperCase(Locale.getDefault());}
    private void auth(){
        if(registering){registration();return;}
        label("Care, closer.",34,INK,true);
        paragraph("Your wellbeing and care circle, together.");
        LinearLayout card=card(content);
        emailInput=input(card,"Email address",loginEmail,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        passwordInput=input(card,"Password","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);passwordInput.setSaveEnabled(false);
        passwordVisibility(card,passwordInput);
        emailInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);emailInput.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_NEXT){passwordInput.requestFocus();return true;}return false;});
        passwordInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);passwordInput.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){authenticate();return true;}return false;});
        authNotice();
        textAction(content,"About CareMonitor",this::showAboutCareMonitor,false);
        LinearLayout actions=authFooter();button(actions,busy?"Signing in…":"Sign in",BLUE,this::authenticate);
        textAction(actions,"Create an account",()->{rememberAuth();hideKeyboard();registering=true;setup=new AccountSetup();acknowledged=false;notice="";render();},true);
        if(!getSharedPreferences("caremonitor_endpoint",0).getString("previous_base","").isEmpty())button(content,"Restore my previous connection",SOFT,()->{
            if(busy||session!=null)return;
            String previous=getSharedPreferences("caremonitor_endpoint",0).getString("previous_base","");
            if(previous.isEmpty())return;
            String password=passwordInput.getText().toString();
            if(getSharedPreferences("caremonitor_endpoint",0).edit().putString("base",previous).remove("previous_base").commit()){
                rememberAuth();notice="Previous connection restored.";render();passwordInput.setText(password);}
            else toast("Could not restore your connection.");
        });
        if(!ApiSession.savedBase(this).equals(ApiSession.DEFAULT_BASE))button(content,"Update my connection",SOFT,()->{
            if(busy||session!=null)return;
            String previous=ApiSession.savedBase(this);
            if(previous.equals(ApiSession.DEFAULT_BASE))return;
            String password=passwordInput.getText().toString();
            if(getSharedPreferences("caremonitor_endpoint",0).edit().putString("previous_base",previous).putString("base",ApiSession.DEFAULT_BASE).commit()){
                rememberAuth();notice="Connection updated. Sign in to continue.";render();passwordInput.setText(password);}
            else toast("Could not update your connection.");
        });
    }
    private void registration(){
        progress(setup.step,4,"Create your account");
        String[] titles={"How will you use CareMonitor?","What's your name?","What's your email?","Protect your account"};
        String[] hints={"Choose the option that describes you.","We'll use this name to greet you.","You'll use it to sign in.","One last step. Your password needs 12 to 128 characters."};
        label(titles[setup.step],32,INK,true);paragraph(hints[setup.step]);
        if(setup.step==0){
            roleChoice("elder","I'm the older adult","My wearable, my wellbeing and the people I choose.",R.drawable.ic_person);
            roleChoice("caregiver","I'm a caregiver","I support someone in my care circle.",R.drawable.ic_people);
        }else{
            LinearLayout card=card(content);
            if(setup.step==1)nameInput=input(card,"Your name",setup.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            else if(setup.step==2)emailInput=input(card,"Email address",setup.email,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            else{
                labelIn(card,setup.name,23,INK,true);labelIn(card,setup.email,17,MUTED,false);
                labelIn(card,setup.elder()?"Older adult":"Caregiver",17,BLUE,true);
                passwordInput=input(card,"Choose a password","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);passwordInput.setSaveEnabled(false);
                passwordVisibility(card,passwordInput);
                termsCheck=new CheckBox(this);termsCheck.setText("I have read About CareMonitor and understand its use and limitations.");
                termsCheck.setTextColor(INK);termsCheck.setTextSize(17);termsCheck.setMinHeight(dp(56));termsCheck.setChecked(acknowledged);termsCheck.setEnabled(!busy);card.addView(termsCheck);
                textAction(card,"About CareMonitor",this::showAboutCareMonitor,false);
            }
        }
        if(nameInput!=null||emailInput!=null){EditText field=nameInput!=null?nameInput:emailInput;field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
            field.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_NEXT){advanceSetup();return true;}return false;});}
        authNotice();
        LinearLayout actions=authFooter();button(actions,busy?"Creating your account…":setup.step==3?"Create my account":"Continue",BLUE,this::advanceSetup);
        textAction(actions,setup.step==0?"I already have an account":"Back",this::goBack,true);
        if(setup.step>0)textAction(content,"I already have an account",()->{rememberAuth();hideKeyboard();loginEmail=setup.email;registering=false;notice="";render();},true);
    }
    private void advanceSetup(){
        if(busy)return;rememberAuth();String problem=setup.problem();if(!problem.isEmpty()){notice=problem;render();return;}
        if(setup.step==3)authenticate();else{hideKeyboard();setup.next();notice="";render();}
    }
    private LinearLayout authFooter(){
        authActions=new LinearLayout(this);authActions.setOrientation(LinearLayout.VERTICAL);authActions.setPadding(dp(24),dp(6),dp(24),dp(8));authActions.setBackgroundColor(BG);
        root.addView(authActions,new LinearLayout.LayoutParams(-1,-2));return authActions;
    }
    private void passwordVisibility(LinearLayout parent,EditText password){
        secretVisibility(parent,password,"Show password");
    }
    private void secretVisibility(LinearLayout parent,EditText password,String title){
        CheckBox show=new CheckBox(this);show.setText(title);show.setContentDescription(title);show.setTextSize(16);show.setTextColor(MUTED);show.setMinHeight(dp(56));show.setButtonTintList(ColorStateList.valueOf(BLUE));show.setEnabled(!busy);parent.addView(show,new LinearLayout.LayoutParams(-1,-2));
        show.setOnCheckedChangeListener((view,checked)->{int cursor=password.getSelectionStart();password.setTransformationMethod(checked?null:android.text.method.PasswordTransformationMethod.getInstance());password.setSelection(Math.max(0,Math.min(cursor,password.length())));});
    }
    private void reassurance(String title,String detail){
        LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.TOP);line.setPadding(dp(4),dp(20),dp(4),0);content.addView(line);
        ImageView shield=icon(R.drawable.ic_shield,MUTED,20);LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(dp(20),dp(20));image.topMargin=dp(6);image.rightMargin=dp(10);line.addView(shield,image);
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));labelIn(copy,title,16,INK,true);labelIn(copy,detail,15,MUTED,false);
    }
    private void rememberAuth(){
        if(session!=null)return;
        if(nameInput!=null)setup.name=nameInput.getText().toString().trim();
        if(emailInput!=null){if(registering)setup.email=emailInput.getText().toString().trim();else loginEmail=emailInput.getText().toString().trim();}
        if(termsCheck!=null)acknowledged=termsCheck.isChecked();
    }
    private void authNotice(){if(!notice.isEmpty()){TextView message=labelIn(content,notice,17,RED,false);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);}}
    private void progress(int step,int count,String title){
        label(title+" · Step "+(step+1)+" of "+count,15,MUTED,true);
        LinearLayout track=new LinearLayout(this);track.setPadding(0,dp(8),0,dp(16));content.addView(track);
        for(int i=0;i<count;i++){View segment=new View(this);segment.setBackground(shape(i<=step?BLUE:LINE,5));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(5),1);lp.rightMargin=i<count-1?dp(6):0;track.addView(segment,lp);}
    }
    private ImageView illustration(int resource,String description){
        FrameLayout panel=new FrameLayout(this);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(140));lp.topMargin=dp(4);lp.bottomMargin=dp(16);content.addView(panel,lp);
        View halo=new View(this);halo.setBackground(shape(SOFT,60));panel.addView(halo,new FrameLayout.LayoutParams(dp(120),dp(120),Gravity.CENTER));
        FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(Color.WHITE,24));tile.setElevation(dp(2));panel.addView(tile,new FrameLayout.LayoutParams(dp(88),dp(88),Gravity.CENTER));
        ImageView icon=icon(resource,BLUE,48);icon.setContentDescription(description);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        tile.addView(icon,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));return icon;
    }
    private void roleChoice(String role,String title,String subtitle,int resource){
        boolean selected=role.equals(setup.role);LinearLayout option=card(content);option.setPadding(dp(20),dp(20),dp(20),dp(20));GradientDrawable background=shape(selected?SOFT:Color.WHITE,22);background.setStroke(dp(selected?2:1),selected?BLUE:LINE);option.setBackground(new RippleDrawable(ColorStateList.valueOf(0x110066CC),background,null));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);option.addView(row);
        ImageView icon=new ImageView(this);icon.setImageResource(resource);icon.setImageTintList(ColorStateList.valueOf(BLUE));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView heading=text(title,20,INK,true);LinearLayout.LayoutParams headingLp=new LinearLayout.LayoutParams(0,-2,1);headingLp.leftMargin=dp(12);headingLp.rightMargin=dp(12);row.addView(heading,headingLp);
        ImageView check=icon(selected?R.drawable.ic_check_circle:R.drawable.ic_chevron_right,selected?BLUE:MUTED,24);row.addView(check,new LinearLayout.LayoutParams(dp(24),dp(24)));labelIn(option,subtitle,17,MUTED,false);
        option.setFocusable(true);option.setClickable(true);option.setSelected(selected);option.setContentDescription(title+". "+subtitle+(selected?". Selected":""));
        option.setOnClickListener(v->{if(busy)return;setup.role=role;notice="";render();});
    }
    private void ready(){
        illustration(R.drawable.ic_care_monitor,"Your care circle");label("Your account is ready",32,INK,true);
        paragraph("You can now ask the person you support for access. They will review and approve your request.");
        button(content,"Link to a person",BLUE,()->{page="care";render();refresh();});
        button(content,"Go to Today",SOFT,()->{page="home";render();refresh();});
    }
    private void authenticate(){
        if(busy)return;
        if(!ApiClient.lanAllowed(this)){pendingAction="auth";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);return;}
        rememberAuth();final String base,email,password,name,role;
        try{base=ApiClient.base(ApiSession.savedBase(this));email=registering?setup.email:loginEmail;password=passwordInput.getText().toString();
            name=registering?setup.name:"";role=registering?setup.role:"";
            if(!email.contains("@")||password.length()<12||password.length()>128)throw new IllegalArgumentException("Use a valid email and a password with 12 to 128 characters.");
            if(registering&&(!setup.problem().isEmpty()||!acknowledged))throw new IllegalArgumentException("Review your details and accept the terms of use.");
        }catch(Exception failure){toast(failure.getMessage());return;}
        busy=true;final boolean registration=registering;notice="Connecting securely…";
        passwordInput.setText("");epoch++;AuthAttempt attempt=new AuthAttempt(registration,base,email);authentication=attempt;attempt.attach(this);render();
        network.execute(()->{try{
            JSONObject body=json("email",email,"password",password);
            if(registration){body.put("display_name",name);body.put("role",role);body.put("educational_ack",true);}
            JSONObject response=ApiClient.request(this,base,"","POST",registration?"/auth/register":"/auth/login",body);
            attempt.complete(response,null);
        }catch(Exception failure){attempt.complete(null,failure.getMessage());}});
    }
    private void finishAuthentication(AuthAttempt attempt){
        synchronized(attempt){if(attempt.owner!=this||authentication!=attempt||!attempt.finished||isFinishing()||isDestroyed())return;attempt.owner=null;}
        authentication=null;busy=false;
        if(attempt.error!=null){notice=attempt.error+(attempt.registration?" If you already have an account, sign in.":"");render();return;}
        try{ApiSession saved=ApiSession.save(this,attempt.base,attempt.response);session=saved;page=attempt.registration?(saved.elder()?"device":"ready"):"home";deviceStep=0;patientId="";notice="";
            getSharedPreferences("caremonitor_endpoint",0).edit().remove("previous_base").apply();
            loginEmail=attempt.email;registering=false;setup=new AccountSetup();acknowledged=false;
            NotificationSync.schedule(this);if(monitor!=null)monitor.refreshSession();render();refresh();
        }catch(Exception failure){notice="Could not save your session. Please sign in again.";render();}
    }
    private void navigation(){
        View divider=new View(this);divider.setBackgroundColor(LINE);root.addView(divider,new LinearLayout.LayoutParams(-1,Math.max(1,dp(.5f))));
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(dp(8),dp(6),dp(8),dp(6));nav.setBackgroundColor(Color.WHITE);root.addView(nav);
        String[] labels={"Today","Updates","Care","Settings"},pages={"home","alerts","care","account"};
        int[] symbols={R.drawable.ic_nav_overview,R.drawable.ic_nav_alerts,R.drawable.ic_people,R.drawable.ic_nav_settings};
        for(int i=0;i<labels.length;i++){final String target=pages[i];boolean selected=page.equals(target)||(target.equals("account")&&(isSettingsDetail()||(page.equals("history")&&historyOrigin.equals("reading_details"))))||(target.equals("home")&&(page.equals("device")||(page.equals("history")&&!historyOrigin.equals("reading_details"))));int tint=selected?BLUE:MUTED;
            LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setPadding(dp(2),dp(4),dp(2),dp(4));item.setMinimumHeight(dp(60));item.setBackground(interactive(Color.WHITE,14));
            item.addView(icon(symbols[i],tint,24),new LinearLayout.LayoutParams(dp(24),dp(24)));
            int navTextSize=(int)Math.min(12f,20f/getResources().getConfiguration().fontScale);
            TextView label=text(labels[i],navTextSize,tint,selected);label.setSingleLine(true);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(4),0,0);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label,new LinearLayout.LayoutParams(-1,-2));
            item.setSelected(selected);item.setContentDescription(labels[i]);item.setFocusable(true);item.setOnClickListener(v->openPage(target));nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));}
    }
    private void home(){
        TextView date=labelIn(content,new java.text.SimpleDateFormat("EEEE, d MMMM",Locale.ENGLISH).format(new Date()),16,MUTED,false);
        date.setPadding(0,0,0,dp(8));
        if(!elder()){
            patientChooser();
            if(!approvedPatient(patientId)){
                LinearLayout welcome=card(content);welcome.addView(icon(R.drawable.ic_people,BLUE,32),new LinearLayout.LayoutParams(dp(32),dp(32)));
                labelIn(welcome,"Bring your care circle together",26,INK,true);
                labelIn(welcome,"Ask the older adult for an invitation code. Their approval keeps them in control.",18,MUTED,false);
                button(welcome,"Link to a person",BLUE,()->openPage("care"));homeBuiltKey=homeStructureKey();return;
            }
        }
        boolean largeCaregiver=!elder()&&getResources().getConfiguration().fontScale>=1.3f;
        if(!largeCaregiver)connectionStrip();
        if(elder()){
            button(content,"Ask for help",RED,()->confirm("Send a request for help?",
                (caregivers.length()==0?"No caregiver is linked yet, so nobody in your circle can receive this request. ":"Your approved caregivers will receive a notice. ")+"This does not call emergency services.",this::sendHelp));
            helpButton=(Button)content.getChildAt(content.getChildCount()-1);helpStatus=labelIn(content,"",17,INK,false);helpStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);updateHelp();
        }else{
            int pending=pendingReviewCount();LinearLayout updates=group(content);
            settingsRow(updates,pending>0?"Needs your review":"Care updates",pending>0?pending+(pending==1?" notice is waiting":" notices are waiting"):"You're all caught up",R.drawable.ic_nav_alerts,()->openPage("alerts"));
        }
        metrics(readingsPayload());
        if(largeCaregiver)connectionStrip();
        nextReminder();
        if(!elder())sharedLocation();
        textAction(content,"Refresh",this::refreshFromUser,true);
        homeBuiltKey=homeStructureKey();
    }
    private int pendingReviewCount(){JSONArray alerts=overview.optJSONArray("alerts");int count=0;if(alerts!=null)for(int i=0;i<alerts.length();i++){JSONObject alert=alerts.optJSONObject(i);if(alert!=null&&!alert.optBoolean("acknowledged"))count++;}return count;}
    private void connectionStrip(){
        LinearLayout strip=new LinearLayout(this);strip.setGravity(Gravity.CENTER_VERTICAL);strip.setMinimumHeight(dp(64));strip.setPadding(0,dp(4),0,dp(12));content.addView(strip,new LinearLayout.LayoutParams(-1,-2));
        homeStatus=new CareStatusView(this);strip.addView(homeStatus,new LinearLayout.LayoutParams(dp(40),dp(40)));homeStatus.setActive(visible);
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(10);strip.addView(copy,cp);
        homeConnection=labelIn(copy,"",17,INK,true);homeConnection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        homeReadingContext=labelIn(copy,"",15,MUTED,false);
        strip.addView(icon(R.drawable.ic_chevron_right,MUTED,18),new LinearLayout.LayoutParams(dp(18),dp(18)));
        strip.setBackground(interactive(Color.TRANSPARENT,16));strip.setFocusable(true);strip.setContentDescription(elder()?"Manage my wearable":"View shared reading history");
        strip.setOnClickListener(v->{if(elder()){deviceStep=monitor!=null&&monitor.isRunning()?3:0;openPage("device");}else openHistory("heart_rate_bpm");});
        updateConnectionStrip();
    }
    private void updateConnectionStrip(){
        if(homeConnection==null||homeStatus==null)return;
        boolean live=elder()&&monitor!=null&&monitor.isFresh();
        String freshness=readingFreshness();
        String title=live?"Wearable connected":elder()&&monitor!=null&&monitor.isRunning()?"Connecting to your wearable":elder()?"Connect my wearable":"Shared readings";
        String subtitle=live?"Updating from your wearable":readingsPayload()==null?"No readings available yet":ReadingAge.description(savedReadingAge());
        if(!live&&readingsPayload()!=null&&!freshness.equals("fresh"))subtitle+=" · saved";
        setLabel(homeConnection,title);setLabel(homeReadingContext,subtitle);
        if(homeWearableButton!=null)homeWearableButton.setVisibility(monitor!=null&&monitor.isRunning()?View.GONE:View.VISIBLE);
        homeStatus.setState(live?CareStatusView.State.LIVE:elder()&&monitor!=null&&monitor.isRunning()?CareStatusView.State.CONNECTING:!elder()&&readingsPayload()!=null?CareStatusView.State.WAITING:CareStatusView.State.OFFLINE);
    }
    private double savedReadingAge(){JSONObject freshness=overview.optJSONObject("freshness");Object age=freshness==null?null:freshness.opt("age_seconds");return ReadingAge.seconds(age instanceof Number?((Number)age).doubleValue():Double.NaN,overviewReceivedAt,SystemClock.elapsedRealtime());}
    private String readingFreshness(){return storedReading()==null?"no_data":ReadingAge.fresh(savedReadingAge())?"fresh":"stale";}
    private JSONObject storedReading(){
        if(!elder()&&!approvedPatient(patientId))return null;
        JSONObject person=overview.optJSONObject("patient");
        if(!elder()&&(person==null||!patientId.equals(person.optString("id"))))return null;
        return overview.optJSONObject("reading");
    }
    private JSONObject readingsPayload(){
        if(elder()&&monitor!=null&&monitor.isFresh())return monitor.snapshot();
        JSONObject reading=storedReading();
        return reading!=null&&"device_diagnostic".equals(reading.optString("source"))?reading.optJSONObject("payload"):null;
    }
    private String homeStructureKey(){
        return selectedPatient()+"|"+patients.toString()+"|"+reminders.toString()+"|"+pendingReviewCount()+"|"+(monitor!=null&&monitor.isRunning())+"|"+String.valueOf(overview.optJSONObject("location"))+"|"+new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ENGLISH).format(new Date());
    }
    private void nextReminder(){
        label(elder()?"For today":"Care plan",23,INK,true);
        JSONObject next=null;long earliest=Long.MAX_VALUE;
        for(int i=0;i<reminders.length();i++){
            JSONObject reminder=reminders.optJSONObject(i);if(reminder==null||reminder.opt("cancelled_at") instanceof String)continue;
            boolean waiting=reminder.opt("last_due_at") instanceof String&&!(reminder.opt("taken_at") instanceof String);
            if(!waiting&&!reminder.optBoolean("active"))continue;
            long at=waiting?0:ApiSession.expiryMs(reminder.optString("due_at"));
            if(next==null||at<earliest){next=reminder;earliest=at;}
        }
        LinearLayout plan=card(content);
        if(next==null){labelIn(plan,"No reminders scheduled",23,INK,true);labelIn(plan,elder()?"Your care circle can add an agreed reminder to your plan.":"Add an agreed reminder from Care.",17,MUTED,false);}
        else{
            boolean waiting=next.opt("last_due_at") instanceof String&&!(next.opt("taken_at") instanceof String);
            labelIn(plan,waiting?"Reminder to confirm":"Next reminder",14,BLUE,true);labelIn(plan,next.optString("title","Reminder"),25,INK,true);
            String instructions=(next.optString("dose","")+" "+next.optString("instructions","")).trim();if(!instructions.isEmpty())labelIn(plan,instructions,18,INK,false);
            labelIn(plan,waiting?(elder()?"Have you completed this reminder?":"Waiting for their confirmation."):formatTime(next.optString("due_at")),17,MUTED,false);
            if(waiting&&elder()){final String id=next.optString("id");button(plan,"I've taken it",BLUE,()->confirm("Confirm you took it?","This records your confirmation only. It does not verify ingestion or change your prescribed plan.",()->mutate("POST","/reminders/"+id+"/taken",json(),"Confirmation recorded.")));}
        }
        textAction(plan,elder()?"View my care plan":"View care plan",()->openPage("care"),false);
    }
    private void sharedLocation(){JSONObject location=overview.optJSONObject("location");if(location==null)return;
        LinearLayout shared=card(content);labelIn(shared,"Shared location",23,INK,true);labelIn(shared,"Last shared: "+formatTime(location.optString("recorded_at")),17,MUTED,false);
        button(shared,"View shared location",SOFT,()->new AlertDialog.Builder(this).setTitle("Shared GPS location").setMessage("Latitude: "+location.optDouble("latitude")+"\nLongitude: "+location.optDouble("longitude")+"\nAccuracy: ±"+(int)location.optDouble("accuracy_m")+" m\n\nShared by the older adult while outside their chosen home area.").setPositiveButton("Got it",null).show());
    }
    private void readingDetails(){
        label("Reading details",34,INK,true);paragraph(elder()?"Your wearable and saved readings.":"Readings shared with you.");
        if(!elder()){patientChooser();if(!approvedPatient(patientId)){paragraph("Choose an approved person from Today to view their readings.");return;}}
        LinearLayout details=card(content);detailFreshness=labelIn(details,"",27,INK,true);
        detailStored=labelIn(details,"",17,MUTED,false);detailSource=labelIn(details,"",16,MUTED,false);
        if(elder()){
            connectionLabel=labelIn(details,"",17,INK,true);readingLabel=labelIn(details,"",16,MUTED,false);
            button(details,monitor!=null&&monitor.isRunning()?"Manage my wearable":"Set up my wearable",SOFT,()->{deviceStep=monitor!=null&&monitor.isRunning()?3:0;openPage("device");});
            button(details,"Sync saved readings",SOFT,()->{final ApiSession expected=session;confirm("Send saved readings?","Your pending readings will be sent only if they belong to this account and server. Readings from another account or server stay paused.",()->{
                if(monitor==null||!monitor.resumeSavedUploads(expected)){toast("Sign in as the older adult and try again.");return;}
                toast("Checking your saved readings. Their status appears here.");updateDevice();
            });});
        }
        metrics(readingsPayload());
        LinearLayout limits=card(content);labelIn(limits,"Understanding your readings",23,INK,true);
        labelIn(limits,"Surface temperature is not core body temperature. Missing readings can mean poor contact, movement or a connection problem; they do not prove the wearable is off. These sensors do not measure blood pressure or dehydration.",17,MUTED,false);
        labelIn(limits,"A connection or a normal-looking value is not a medical assessment. Missing or older readings do not confirm that someone is okay.",17,MUTED,false);
        LinearLayout saved=card(content);labelIn(saved,"How history works",23,INK,true);
        labelIn(saved,"Saved wearable readings are kept for 30 days. Day, Week and Month show averages of valid measurements in each interval. Missing measurements leave gaps; your phone may have a newer live reading than the saved chart.",17,MUTED,false);
        labelIn(saved,"Heart rate and estimated oxygen show sensor estimates even outside the alert limits or when signal quality is low. Quality flags are retained with each sample. No estimate is shown when the sensor cannot calculate one. Movement history shows acceleration including gravity, not a step count or a confirmed fall.",17,MUTED,false);
        textAction(content,"Refresh saved readings",this::refresh,true);updateDevice();updateReadingViews();
    }
    private void updateReadingViews(){
        if(!page.equals("home")&&!page.equals("reading_details"))return;
        updateConnectionStrip();JSONObject raw=readingsPayload();
        if(emptyReadingNote!=null)emptyReadingNote.setVisibility(raw==null?View.VISIBLE:View.GONE);if(emptyReadingHint!=null)emptyReadingHint.setVisibility(raw==null?View.VISIBLE:View.GONE);
        for(Map.Entry<String,MetricViews> item:metricViews.entrySet()){
            String human=metricValue(raw,item.getKey(),page.equals("reading_details"));MetricViews views=item.getValue();
            boolean unavailable=human.equals("Unavailable")||human.equals("No valid reading");String number=unavailable?"—":human;
            if(visible)CareMotion.fadeText(views.value,metricDisplay(number));else views.value.setText(metricDisplay(number));
            setLabel(views.availability,unavailable?human:"");views.availability.setVisibility(unavailable?View.VISIBLE:View.GONE);
            setLabel(views.time,metricTimestamp());
            views.tile.setContentDescription(views.title+", "+human+". View history");
        }
        if(detailFreshness!=null){
            boolean live=elder()&&monitor!=null&&monitor.isFresh();setLabel(detailFreshness,live||readingFreshness().equals("fresh")?"Recent readings":readingsPayload()==null?"Waiting for readings":"Older readings");
            JSONObject reading=storedReading();detailStored.setText(reading==null?"No reading has been stored yet.":"Last stored reading: "+formatTime(reading.optString("captured_at")));
            detailSource.setText(reading==null?"Measurements appear after your wearable connects.":"Source: "+sourceLabel(reading.optString("source")));
        }
    }
    private String sourceLabel(String source){
        if("device_diagnostic".equals(source))return "Wearable";
        if("synthetic".equals(source))return "Test data";
        if("dataset_replay".equals(source))return "Test recording";
        return "Source unavailable";
    }
    private String patientName(){JSONObject patient=overview.optJSONObject("patient");return patient==null?"your care circle":patient.optString("display_name","your care circle");}
    private void patientChooser(){
        if(patients.length()==0){return;}
        JSONObject selected=null;for(int i=0;i<patients.length();i++){JSONObject person=patients.optJSONObject(i);if(person!=null&&patientId.equals(person.optString("id")))selected=person;}
        LinearLayout card=card(content);labelIn(card,"VIEWING",12,MUTED,true);labelIn(card,selected==null?"Choose a person":selected.optString("display_name","Person"),26,INK,true);
        if(patients.length()>1||selected==null)textAction(card,"Change person",this::choosePatient,false);
    }
    private void choosePatient(){
        if(patients.length()==0)return;
        String[] names=new String[patients.length()];String[] ids=new String[patients.length()];int checked=-1;
        for(int i=0;i<patients.length();i++){JSONObject person=patients.optJSONObject(i);names[i]=person==null?"Person":person.optString("display_name","Person");ids[i]=person==null?"":person.optString("id");if(patientId.equals(ids[i]))checked=i;}
        new AlertDialog.Builder(this).setTitle("Whose information?").setSingleChoiceItems(names,checked,(dialog,index)->{
            dialog.dismiss();if(ids[index].equals(patientId))return;epoch++;refreshing=false;clearHistory();patientId=ids[index];overview=new JSONObject();reminders=new JSONArray();render();refresh();
        }).setNegativeButton("Cancel",null).show();
    }
    private void metrics(JSONObject raw){
        label(elder()?"Your readings":"Their readings",23,INK,true);
        emptyReadingNote=labelIn(content,elder()?"Waiting for your wearable":"Waiting for new readings",17,MUTED,false);emptyReadingHint=labelIn(content,"Tap any card to look through saved history.",16,MUTED,false);emptyReadingNote.setVisibility(raw==null?View.VISIBLE:View.GONE);emptyReadingHint.setVisibility(raw==null?View.VISIBLE:View.GONE);
        LinearLayout row=metricRow();metricTile(row,"Heart rate",metricValue(raw,"heart_rate_bpm",false),R.drawable.ic_heart,0xFFB52B55,"heart_rate_bpm");
        metricTile(row,"Estimated oxygen",metricValue(raw,"spo2_percent",false),R.drawable.ic_oxygen,BLUE,"spo2_percent");
        label(elder()?"Around you":"Their surroundings",23,INK,true);
        row=metricRow();metricTile(row,"Surface temperature",metricValue(raw,"surface_temp_c",false),R.drawable.ic_temperature,0xFF98631D,"surface_temp_c");
        metricTile(row,"Ambient temperature",metricValue(raw,"ambient_temp_c",false),R.drawable.ic_temperature,BLUE,"ambient_temp_c");
        row=metricRow();row.setOrientation(LinearLayout.VERTICAL);
        metricTile(row,"Movement",metricValue(raw,"acceleration_g",false),R.drawable.ic_motion,0xFF6554A4,"acceleration_g");
    }
    private String metricValue(JSONObject raw,String metric,boolean details){
        if(raw==null)return metric.equals("heart_rate_bpm")||metric.equals("spo2_percent")?"No valid reading":"Unavailable";
        JSONObject optical=raw.optJSONObject("max30102"),temp=raw.optJSONObject("gy906"),light=raw.optJSONObject("photoresistor"),imu=raw.optJSONObject("mpu6050");
        if(metric.equals("heart_rate_bpm")||metric.equals("spo2_percent")){
            Double estimate=optical==null?null:OpticalReading.value(optical.opt(metric));
            return estimate==null?"No valid reading":String.format(Locale.US,Math.abs(estimate)>=100000?"%.3g":"%.1f",estimate)+(metric.equals("heart_rate_bpm")?" bpm":" %");
        }
        boolean tempValid=temp!=null&&temp.optBoolean("online",false)&&(!temp.has("status")||temp.optString("status").equals("ok"));
        if(metric.equals("surface_temp_c"))return tempValid?numeric(temp,"object_temp_c"," °C"):"Unavailable";
        if(metric.equals("ambient_temp_c"))return tempValid?numeric(temp,"ambient_temp_c"," °C"):"Unavailable";
        if(metric.equals("light_adc"))return light!=null&&light.opt("adc_raw") instanceof Number?(details?"ADC "+light.optInt("adc_raw"):"Signal available"):"Unavailable";
        return imu!=null&&imu.optBoolean("online",false)&&(!imu.has("status")||"ok".equals(imu.optString("status")))?"Signal available":"Unavailable";
    }
    private String numeric(JSONObject object,String key,String suffix){Object value=object.opt(key);if(!(value instanceof Number))return "Unavailable";double number=((Number)value).doubleValue();return Double.isNaN(number)||Double.isInfinite(number)?"Unavailable":String.format(Locale.US,"%.1f",number)+suffix;}
    private LinearLayout metricRow(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);content.addView(row);return row;}
    private void metricTile(LinearLayout row,String title,String value,int symbol,int tint,String metric){
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setMinimumHeight(dp(122));tile.setPadding(dp(18),dp(16),dp(18),dp(12));tile.setBackground(interactive(Color.WHITE,22));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);lp.bottomMargin=dp(4);row.addView(tile,lp);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);tile.addView(heading,new LinearLayout.LayoutParams(-1,-2));heading.addView(icon(symbol,tint,20),new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView name=text(title,17,tint,true);LinearLayout.LayoutParams nameLp=new LinearLayout.LayoutParams(0,-2,1);nameLp.leftMargin=dp(8);nameLp.rightMargin=dp(10);heading.addView(name,nameLp);
        heading.addView(icon(R.drawable.ic_chevron_right,MUTED,16),new LinearLayout.LayoutParams(dp(16),dp(16)));
        boolean unavailable=value.equals("Unavailable")||value.equals("No valid reading");
        TextView number=labelIn(tile,unavailable?"—":value,value.equals("Signal available")?22:34,INK,true);number.setText(metricDisplay(unavailable?"—":value));number.setPadding(0,dp(10),0,dp(2));
        TextView availability=labelIn(tile,unavailable?value:"",15,MUTED,false);availability.setVisibility(unavailable?View.VISIBLE:View.GONE);
        TextView time=labelIn(tile,metricTimestamp(),14,MUTED,false);time.setPadding(0,dp(2),0,0);metricViews.put(metric,new MetricViews(tile,number,availability,time,title));
        tile.setFocusable(true);tile.setContentDescription(title+", "+value+". View history");tile.setOnClickListener(v->openHistory(metric));
    }
    private CharSequence metricDisplay(String value){
        android.text.SpannableString styled=new android.text.SpannableString(value);int split=value.lastIndexOf(' ');
        if(split>0&&Character.isDigit(value.charAt(0))){styled.setSpan(new android.text.style.RelativeSizeSpan(.52f),split,value.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);styled.setSpan(new android.text.style.ForegroundColorSpan(MUTED),split,value.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        return styled;
    }
    private String metricTimestamp(){
        if(elder()&&monitor!=null&&monitor.isFresh())return "Live from your wearable";
        JSONObject reading=storedReading();if(reading==null)return "Tap to view history";
        long time=ApiSession.expiryMs(reading.optString("captured_at"));if(time==0)return "Saved reading";
        boolean today=new java.text.SimpleDateFormat("yyyyMMdd",Locale.ENGLISH).format(new Date(time)).equals(new java.text.SimpleDateFormat("yyyyMMdd",Locale.ENGLISH).format(new Date()));
        return new java.text.SimpleDateFormat(today?"'Today at' HH:mm":"d MMM 'at' HH:mm",Locale.ENGLISH).format(new Date(time));
    }
    private String metricTitle(){
        switch(historyMetric){case "spo2_percent":return "Estimated oxygen";case "surface_temp_c":return "Surface temperature";case "ambient_temp_c":return "Ambient temperature";case "light_adc":return "Light";case "acceleration_g":return "Movement";default:return "Heart rate";}
    }
    private String metricUnit(){
        switch(historyMetric){case "spo2_percent":return "%";case "surface_temp_c":case "ambient_temp_c":return "°C";case "light_adc":return "ADC";case "acceleration_g":return "g";default:return "bpm";}
    }
    private long historyDuration(){return (historyPeriod.equals("month")?30L:historyPeriod.equals("week")?7L:1L)*86400000L;}
    private String selectedPatient(){return session==null?"":elder()?session.id:patientId;}
    private boolean approvedPatient(String id){
        if(session==null||id.isEmpty())return false;if(elder())return id.equals(session.id);
        for(int i=0;i<patients.length();i++){JSONObject person=patients.optJSONObject(i);if(person!=null&&id.equals(person.optString("id")))return true;}return false;
    }
    private void clearHistory(){historyRequest++;historyLoading=false;historyData=null;historyError="";}
    private void openHistory(String metric){
        historyOrigin=page.equals("reading_details")?"reading_details":"home";
        if(!approvedPatient(selectedPatient())){toast("Link to an older adult and select them before opening history.");return;}
        hideKeyboard();clearHistory();historyMetric=metric;historyPeriod="day";historyEndMillis=0;page="history";notice="";render();loadHistory();
    }
    private void openLearning(){
        String selected=selectedPatient();if(!approvedPatient(selected)){toast("Open Care to request access, then select an approved person.");openPage("care");return;}
        startActivity(new Intent(this,LearningActivity.class).putExtra("patient_id",selected));
    }
    private void history(){
        textAction(content,historyOrigin.equals("reading_details")?"Reading details":"Today",()->openPage(historyOrigin),false);
        label(metricTitle(),34,INK,true);if(!elder())labelIn(content,"For "+patientName(),17,MUTED,false);
        LinearLayout selector=new LinearLayout(this);selector.setOrientation(getResources().getConfiguration().fontScale>=1.6f?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        selector.setPadding(dp(4),dp(4),dp(4),dp(4));selector.setBackground(shape(LINE,16));content.addView(selector,new LinearLayout.LayoutParams(-1,-2));
        String[] periods={"day","week","month"},names={"Day","Week","Month"};
        for(int i=0;i<periods.length;i++){
            String period=periods[i];boolean selected=historyPeriod.equals(period);Button choice=new Button(this);choice.setText(names[i]);choice.setAllCaps(false);choice.setTextSize(16);choice.setTextColor(selected?INK:MUTED);choice.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            choice.setBackground(interactive(selected?Color.WHITE:Color.TRANSPARENT,12));choice.setMinHeight(dp(56));choice.setMinimumHeight(dp(56));choice.setStateListAnimator(null);choice.setSelected(selected);choice.setContentDescription(names[i]+" history"+(selected?", selected":""));
            boolean stacked=selector.getOrientation()==LinearLayout.VERTICAL;selector.addView(choice,new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1));
            choice.setOnClickListener(v->{if(historyPeriod.equals(period))return;clearHistory();historyPeriod=period;historyEndMillis=0;render();loadHistory();});
        }
        JSONObject summary=historyData==null?null:historyData.optJSONObject("summary");long count=summary==null?0:summary.optLong("count");
        JSONObject coverage=historyData==null?null:historyData.optJSONObject("coverage");boolean complete=coverage==null||coverage.optBoolean("complete",true);
        LinearLayout graph=card(content);
        labelIn(graph,"AVERAGE",13,MUTED,true);String average=summary==null?"—":historyValue(summary.opt("average"));
        labelIn(graph,average+" "+metricUnit(),42,INK,true).setText(metricDisplay(average+" "+metricUnit()));
        String interval=historyPeriod.equals("month")?"30 days":historyPeriod.equals("week")?"7 days":"24 hours";
        if(historyData!=null)labelIn(graph,historyDate(historyData.optString("start"))+" – "+historyDate(historyData.optString("end")),15,MUTED,false);
        else labelIn(graph,"Past "+interval,15,MUTED,false);
        if(historyLoading){ProgressBar progress=new ProgressBar(this);progress.setContentDescription("Loading stored readings");graph.addView(progress,new LinearLayout.LayoutParams(dp(40),dp(40)));labelIn(graph,"Loading your history…",17,MUTED,false);}
        else if(!historyError.isEmpty()){
            labelIn(graph,"History could not be loaded",22,INK,true);labelIn(graph,historyError,17,MUTED,false);
            if(!ApiClient.lanAllowed(this))button(graph,"Allow local network",BLUE,()->{pendingAction="history";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);});
            else button(graph,"Try again",BLUE,this::loadHistory);
        }else if(historyData==null||count==0){
            JSONObject available=historyData==null?null:historyData.optJSONObject("available_readings");long latest=available==null?0:ApiSession.expiryMs(available.optString("latest_at"));
            boolean noSaved=complete&&available!=null&&available.optLong("count")==0;
            labelIn(graph,!complete?"Getting your readings ready":noSaved?"No saved readings yet":"No readings for this period",22,INK,true);
            labelIn(graph,!complete?"Some older readings are still being prepared. Try again in a moment.":noSaved?(elder()?"Connect your wearable to start your history. Readings are saved to the account you're using now.":"Readings will appear after this person's phone connects their wearable and saves them."):"Choose another date or a longer period to see your saved readings.",17,MUTED,false);
            if(noSaved&&elder())button(graph,"Connect my wearable",BLUE,()->{deviceStep=monitor!=null&&monitor.isRunning()?3:0;openPage("device");});
            long rangeStart=historyData==null?0:ApiSession.expiryMs(historyData.optString("start")),rangeEnd=historyData==null?0:ApiSession.expiryMs(historyData.optString("end"));
            if(latest>0&&(latest<rangeStart||latest>=rangeEnd))button(graph,"View latest saved day",BLUE,()->openLatestHistory(latest));
            else if(complete&&available!=null&&available.optLong("count")>0)labelIn(graph,"Saved readings are available, but this measurement has no valid values in the selected period.",16,MUTED,false);
        }else{
            MetricHistoryChartView chart=new MetricHistoryChartView(this);chart.setHistory(historyData);graph.addView(chart,new LinearLayout.LayoutParams(-1,-2));
            labelIn(graph,"Touch the chart to inspect a stored interval.",15,MUTED,false);
            labelIn(graph,count+" "+(count==1?"reading":"readings"),15,MUTED,false);
        }
        LinearLayout periodNavigation=new LinearLayout(this);periodNavigation.setOrientation(LinearLayout.HORIZONTAL);content.addView(periodNavigation,new LinearLayout.LayoutParams(-1,-2));
        Button earlier=textAction(periodNavigation,"Earlier",()->shiftHistory(-1),true);earlier.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));earlier.setContentDescription("View previous "+interval);
        Button later=textAction(periodNavigation,"Later",()->shiftHistory(1),true);later.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));later.setEnabled(historyEndMillis>0&&historyEndMillis<System.currentTimeMillis()-1000);later.setContentDescription("View next "+interval);
        if(historyEndMillis>0)textAction(content,"Back to the present",()->{clearHistory();historyEndMillis=0;render();loadHistory();},true);
        textAction(content,"Choose date",this::chooseHistoryDate,true);
        if(summary!=null&&count>0){
            LinearLayout details=card(content);labelIn(details,elder()?"Your readings":"Saved readings",23,INK,true);
            historySummary(details,"Latest",historyValue(summary.opt("latest"))+" "+metricUnit());
            labelIn(details,formatTime(summary.optString("latest_at")),15,MUTED,false);
            historySummary(details,"Range",historyValue(summary.opt("min"))+"–"+historyValue(summary.opt("max"))+" "+metricUnit());
        }
        if(!complete){
            LinearLayout indexing=card(content);labelIn(indexing,"More readings are on their way",22,INK,true);labelIn(indexing,"Your chart will update as older readings become available.",17,MUTED,false);button(indexing,"Refresh history",SOFT,this::loadHistory);
        }
        button(content,"Refresh history",SOFT,this::loadHistory);
        textAction(content,"Reading details",()->openPage("reading_details"),true);
    }
    private void historySummary(LinearLayout parent,String title,String value){
        boolean stacked=getResources().getConfiguration().fontScale>=1.3f;
        LinearLayout row=new LinearLayout(this);row.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(8),0,dp(8));parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
        TextView name=text(title,17,MUTED,false);row.addView(name,new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1));
        TextView number=text(value,19,INK,true);number.setGravity(stacked?Gravity.START:Gravity.END);if(stacked)number.setPadding(0,dp(4),0,0);row.addView(number,new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:2));
    }
    private String historyValue(Object value){if(!(value instanceof Number))return "—";double number=((Number)value).doubleValue();return Double.isFinite(number)?String.format(Locale.ENGLISH,historyMetric.equals("light_adc")?"%.0f":historyMetric.equals("acceleration_g")?"%.2f":"%.1f",number):"—";}
    private String historyDate(String value){long time=ApiSession.expiryMs(value);return time==0?"Date unavailable":new java.text.SimpleDateFormat("d MMM, HH:mm",Locale.ENGLISH).format(new Date(time));}
    private void shiftHistory(int direction){long end=historyEndMillis==0?System.currentTimeMillis():historyEndMillis;long target=end+direction*historyDuration();clearHistory();historyEndMillis=target>=System.currentTimeMillis()-1000?0:Math.max(historyDuration(),target);render();loadHistory();}
    private void openLatestHistory(long latest){clearHistory();historyPeriod="day";historyEndMillis=Math.min(System.currentTimeMillis(),latest+1000);render();loadHistory();}
    private void chooseHistoryDate(){
        final ApiSession account=session;final String person=selectedPatient(),metric=historyMetric;Calendar chosen=Calendar.getInstance();if(historyEndMillis>0)chosen.setTimeInMillis(historyEndMillis-1);
        DatePickerDialog picker=new DatePickerDialog(this,(view,year,month,day)->{
            if(session==null||account==null||!session.id.equals(account.id)||!session.base.equals(account.base)||!page.equals("history")||!person.equals(selectedPatient())||!metric.equals(historyMetric))return;
            Calendar end=Calendar.getInstance();end.set(year,month,day,23,59,59);end.set(Calendar.MILLISECOND,999);long now=System.currentTimeMillis();
            clearHistory();historyEndMillis=end.getTimeInMillis()>=now?0:end.getTimeInMillis();render();loadHistory();
        },chosen.get(Calendar.YEAR),chosen.get(Calendar.MONTH),chosen.get(Calendar.DAY_OF_MONTH));picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();
    }
    private void loadHistory(){
        if(session==null||!page.equals("history")||historyLoading)return;
        if(!session.valid()){logout();notice="Your session expired. Please sign in again.";render();return;}
        if(!ApiClient.lanAllowed(this)){historyData=null;historyError="Your server needs local network access. Allow it to view stored history.";render();return;}
        final ApiSession account=session;final long capture=epoch,request=++historyRequest;final String person=selectedPatient(),metric=historyMetric,period=historyPeriod;final long end=historyEndMillis;
        if(person.isEmpty()){historyData=null;historyError="Choose an approved person from Today before opening history.";render();return;}
        historyLoading=true;historyError="";historyData=null;render();
        network.execute(()->{try{
            JSONObject result=ApiClient.history(this,account,person,metric,period,end);
            main.post(()->{if(!historyCurrent(account,capture,request,person,metric,period,end))return;historyLoading=false;historyData=result;historyError="";int y=scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));});
        }catch(Exception failure){main.post(()->{
            if(!historyCurrent(account,capture,request,person,metric,period,end))return;historyLoading=false;historyData=null;
            if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==401){logout();notice="Your session expired or was revoked. Please sign in again.";render();return;}
            if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==403)historyError="Access is no longer available. The older adult must approve your access before you can view their history.";
            else if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==404)historyError="Saved readings are unavailable. Return to Today and check your care access and connection.";
            else historyError=failure.getMessage()==null?"Check your connection, then try again.":failure.getMessage();render();
        });}});
    }
    private boolean historyCurrent(ApiSession account,long capture,long request,String person,String metric,String period,long end){
        return capture==epoch&&request==historyRequest&&session!=null&&session.id.equals(account.id)&&session.base.equals(account.base)&&page.equals("history")&&person.equals(selectedPatient())&&metric.equals(historyMetric)&&period.equals(historyPeriod)&&end==historyEndMillis;
    }
    private void alerts(){
        label("Updates",34,INK,true);paragraph(elder()?"Notices and reminders from your care circle.":"Review notices for the person you support.");
        if(!elder())patientChooser();
        JSONArray alerts=overview.optJSONArray("alerts");if(alerts==null||alerts.length()==0){LinearLayout empty=card(content);empty.addView(icon(R.drawable.ic_nav_alerts,BLUE,30),new LinearLayout.LayoutParams(dp(30),dp(30)));labelIn(empty,"You're all caught up",25,INK,true);labelIn(empty,"New care updates will appear here.",18,MUTED,false);}
        else for(int i=0;i<alerts.length();i++){JSONObject alert=alerts.optJSONObject(i);if(alert==null)continue;LinearLayout card=card(content);
            labelIn(card,alert.optBoolean("acknowledged")?"REVIEWED":"AWAITING REVIEW",12,BLUE,true);
            labelIn(card,alert.optString("title",alert.optString("type","Notice")),23,INK,true);
            labelIn(card,alert.optString("message","Review the signal and context of this notice."),18,MUTED,false);
            labelIn(card,formatTime(alert.optString("created_at"))+" · "+sourceLabel(alert.optString("source")),15,MUTED,false);
            if(!elder()&&!alert.optBoolean("acknowledged")){String id=alert.optString("id");button(card,"Mark as reviewed",SOFT,()->mutate("POST","/alerts/"+id+"/acknowledge",json(),"Notice marked as reviewed."));}}
        label("My inbox",23,INK,true);
        int shown=0;for(int i=0;i<notifications.length();i++){JSONObject item=notifications.optJSONObject(i);if(item==null||item.optBoolean("acknowledged"))continue;
            JSONObject data=item.optJSONObject("data");if(data==null)data=new JSONObject();LinearLayout card=card(content);labelIn(card,data.optString("title","Care notice"),21,INK,true);
            labelIn(card,data.optString("message",data.optString("instructions","Open Care to see the reminder.")),17,MUTED,false);
            int id=item.optInt("id");button(card,"I've read this",SOFT,()->mutate("POST","/notifications/"+id+"/ack",json(),"Notice read."));shown++;}
        if(shown==0)paragraph("You are all caught up.");
        textAction(content,"Set up notifications",()->openPage("notification_settings"),true);button(content,"Refresh notifications",SOFT,this::refresh);
    }
    private void care(){
        label(elder()?"My care circle":"Care together",34,INK,true);paragraph(elder()?"You choose who supports you and can access your information.":"Access starts with an invitation and the older adult's approval.");
        if(elder()){
            LinearLayout invite=card(content);labelIn(invite,"Invite someone you trust",24,INK,true);
            labelIn(invite,"Create a temporary four-digit code. Sharing it lets someone request access; you still approve each person.",18,MUTED,false);
            button(invite,"Create an invitation code",BLUE,()->run(session,(account)->ApiClient.call(this,account,"POST","/invitations",json()),result->{
                new AlertDialog.Builder(this).setTitle("Your code: "+result.optString("code")).setMessage("Expires: "+formatTime(result.optString("expires_at"))+". Check each person before approving their request.").setPositiveButton("Got it",null).show();}));
            label("Access requests",23,INK,true);
            int pending=0;for(int i=0;i<requests.length();i++){JSONObject request=requests.optJSONObject(i);if(request==null||!"pending".equals(request.optString("status","pending")))continue;
                JSONObject user=request.optJSONObject("caregiver");String name=user==null?request.optString("display_name",request.optString("caregiver_name","Applicant")):user.optString("display_name","Applicant");
                String email=user==null?request.optString("email",""):user.optString("email","");String id=request.optString("id");LinearLayout card=card(content);labelIn(card,name,22,INK,true);if(!email.isEmpty())labelIn(card,email,17,MUTED,false);
                button(card,"Approve access",BLUE,()->confirm("Do you know "+name+"?","They can view readings, alerts and shared location, and assign reminders. You can revoke access.",()->mutate("POST","/link-requests/"+id+"/approve",json(),"Caregiver linked.")));pending++;}
            if(pending==0)paragraph("No pending requests.");
            label("Linked people",23,INK,true);if(caregivers.length()==0)paragraph("You have not approved any caregivers yet.");
            for(int i=0;i<caregivers.length();i++){JSONObject user=caregivers.optJSONObject(i);if(user==null)continue;String id=user.optString("id"),name=user.optString("display_name","Caregiver");LinearLayout card=card(content);labelIn(card,name,22,INK,true);
                button(card,"Revoke access",SOFT,()->confirm("Revoke access for "+name+"?","They will no longer access your information or receive new notices. Notices already delivered cannot be removed from their device.",()->mutate("DELETE","/caregivers/"+id,null,"Access revoked.")));}
        }else{
            LinearLayout invite=card(content);labelIn(invite,"Link to a person",24,INK,true);EditText code=input(invite,"Four-digit code","",InputType.TYPE_CLASS_NUMBER);code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
            button(invite,"Request access",BLUE,()->{String value=code.getText().toString();if(!value.matches("[0-9]{4}")){toast("Enter all four digits.");return;}mutate("POST","/link-requests",json("code",value),"Request sent. The older adult needs to approve it.");});
            patientChooser();if(!patientId.isEmpty()){button(content,"Create a medication reminder",BLUE,this::reminderDialog);button(content,"Create a water reminder",SOFT,this::waterReminder);}
        }
        if(elder()){LinearLayout support=card(content);labelIn(support,"Let your circle know",23,INK,true);button(support,"Report a fall",SOFT,()->confirm("Send a fall notice?","This sends your report to your approved caregivers. The wearable does not confirm a fall or call emergency services.",()->sendEvent("possible_fall")));}
        medicationCards(true);
    }
    private void medicationCards(boolean all){
        if(reminders.length()==0){if(all)paragraph("No reminders assigned.");return;}
        label("Reminders",23,INK,true);
        for(int i=0;i<reminders.length();i++){JSONObject reminder=reminders.optJSONObject(i);if(reminder==null)continue;
            LinearLayout card=card(content);labelIn(card,reminder.optString("title","Medication"),24,INK,true);
            boolean cancelled=reminder.opt("cancelled_at") instanceof String;if(cancelled)labelIn(card,"CANCELLED",14,MUTED,true);
            labelIn(card,reminder.optString("dose","")+"  "+reminder.optString("instructions",""),19,INK,false);
            labelIn(card,(reminder.optBoolean("active")?"Next: ":"Scheduled: ")+formatTime(reminder.optString("due_at")),17,MUTED,false);
            if(reminder.opt("last_due_at") instanceof String)labelIn(card,"Last reminder: "+formatTime(reminder.optString("last_due_at")),17,BLUE,true);
            if(reminder.opt("taken_at") instanceof String)labelIn(card,"Last confirmation: "+formatTime(reminder.optString("taken_at")),16,MUTED,false);
            if(!cancelled&&elder()&&reminder.opt("last_due_at") instanceof String&&!(reminder.opt("taken_at") instanceof String)){String id=reminder.optString("id");button(card,"I've taken it",BLUE,()->confirm("Confirm you took it?","This records your confirmation only. The system does not verify ingestion or prescribe doses.",()->mutate("POST","/reminders/"+id+"/taken",json(),"Confirmation recorded.")));}
            if(!cancelled){String id=reminder.optString("id");button(card,"Cancel reminder",SOFT,()->confirm("Cancel this reminder?","No further occurrences will be sent. This does not change a prescription. Delivered notices may remain on other phones.",()->mutate("DELETE","/reminders/"+id,null,"Reminder cancelled.")));}
        }
    }
    private boolean careSelectionCurrent(ApiSession expected,String person){
        return expected!=null&&session!=null&&!elder()&&session.id.equals(expected.id)&&session.base.equals(expected.base)&&session.token.equals(expected.token)&&person.equals(patientId)&&approvedPatient(person);
    }
    private void waterReminder(){
        final ApiSession expected=session;final String person=patientId;
        if(!careSelectionCurrent(expected,person)){toast("Choose an approved person first.");return;}
        confirm("Water reminder for "+patientName()+"?","A reminder in 5 minutes and every 2 hours for an agreed hydration plan. It does not detect dehydration or recommend amounts.",()->{
            if(!careSelectionCurrent(expected,person)){toast("The selected person changed. Open the reminder again.");return;}
            mutate("POST","/patients/"+person+"/reminders",json("title","Water reminder","dose","Follow the agreed plan","instructions","Remember your agreed hydration plan. This notice does not diagnose dehydration.","due_at",ApiSession.utc(System.currentTimeMillis()+300000),"repeat_hours",2),"Reminder created.");
        });
    }
    private void reminderDialog(){
        final ApiSession expected=session;final String person=patientId;
        if(!careSelectionCurrent(expected,person)){toast("Choose an approved person first.");return;}
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),dp(8));
        EditText title=input(form,"Reminder name","",InputType.TYPE_CLASS_TEXT),dose=input(form,"Dose prescribed by a professional","",InputType.TYPE_CLASS_TEXT),
            instructions=input(form,"Agreed instructions","",InputType.TYPE_CLASS_TEXT),minutes=input(form,"First reminder, minutes from now","5",InputType.TYPE_CLASS_NUMBER),repeat=input(form,"Repeat every how many hours (blank = once)","",InputType.TYPE_CLASS_NUMBER);
        labelIn(form,"This does not prescribe or check interactions. Use a plan already prescribed and agreed with the older adult.",16,MUTED,false);
        ScrollView wrap=new ScrollView(this);wrap.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Reminder for "+patientName()).setView(wrap).setPositiveButton("Create",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!careSelectionCurrent(expected,person)){dialog.dismiss();toast("The selected person changed. Open the reminder again.");return;}
            try{
            int delay=Integer.parseInt(minutes.getText().toString());if(delay<1||delay>525600||title.getText().toString().trim().isEmpty())throw new IllegalArgumentException();
            JSONObject body=json("title",title.getText().toString().trim(),"dose",dose.getText().toString().trim(),"instructions",instructions.getText().toString().trim(),"due_at",ApiSession.utc(System.currentTimeMillis()+delay*60000L));
            if(!repeat.getText().toString().isEmpty()){int hours=Integer.parseInt(repeat.getText().toString());if(hours<1||hours>720)throw new IllegalArgumentException();body.put("repeat_hours",hours);}
            dialog.dismiss();mutate("POST","/patients/"+person+"/reminders",body,"Reminder assigned.");
        }catch(Exception failure){toast("Check the name, minutes and repeat interval.");}}));dialog.show();
    }
    private void account(){
        label("Settings",34,INK,true);paragraph("Your preferences. Your control.");
        boolean largeProfile=getResources().getConfiguration().fontScale>=1.3f;
        LinearLayout profile=card(content);LinearLayout identity=new LinearLayout(this);identity.setOrientation(largeProfile?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);identity.setGravity(Gravity.CENTER_VERTICAL);profile.addView(identity);
        TextView avatar=text(initials(session.name),23,BLUE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(shape(SOFT,28));int avatarSize=dp(Math.max(56,38*getResources().getConfiguration().fontScale));identity.addView(avatar,new LinearLayout.LayoutParams(avatarSize,avatarSize));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams copyLp=new LinearLayout.LayoutParams(largeProfile?-1:0,-2,largeProfile?0:1);if(largeProfile)copyLp.topMargin=dp(12);else copyLp.leftMargin=dp(16);identity.addView(copy,copyLp);
        labelIn(copy,session.name,22,INK,true);labelIn(copy,elder()?"Older adult":"Caregiver",16,MUTED,false);labelIn(profile,session.email,16,MUTED,false);
        sectionTitle("YOUR EXPERIENCE");LinearLayout preferences=group(content);
        settingsRow(preferences,"Reading details","Saved readings, source and wearable status",R.drawable.ic_nav_sensors,()->openPage("reading_details"));
        if(elder())settingsRow(preferences,"Set up my wearable","Guided connection, at your pace",R.drawable.ic_watch,()->{deviceStep=0;openPage("device");});
        settingsRow(preferences,"Notifications","Notices and phone permissions",R.drawable.ic_nav_alerts,()->openPage("notification_settings"));
        if(elder())settingsRow(preferences,"Privacy","Location and sound, only if you choose",R.drawable.ic_shield,()->openPage("privacy"));
        settingsRow(preferences,"Learning insights","Explore model classifications with synthetic data",R.drawable.ic_nav_sensors,this::openLearning);
        sectionTitle("DISPLAY");LinearLayout display=group(content);Switch reduce=new Switch(this);reduce.setText("Reduce motion");reduce.setTextSize(18);reduce.setTextColor(INK);reduce.setPadding(dp(16),dp(14),dp(16),dp(14));reduce.setMinHeight(dp(64));reduce.setSwitchPadding(dp(16));reduce.setChecked(getSharedPreferences("caremonitor_appearance",0).getBoolean("reduce_motion",false));reduce.setContentDescription("Reduce motion");display.addView(reduce,new LinearLayout.LayoutParams(-1,-2));
        reduce.setOnCheckedChangeListener((button,checked)->{getSharedPreferences("caremonitor_appearance",0).edit().putBoolean("reduce_motion",checked).apply();if(checked)stopPageMotion();});
        sectionTitle("CONNECTION & ACCOUNT");LinearLayout account=group(content);
        settingsRow(account,"Server connection","Manage your team's connection",R.drawable.ic_bluetooth,()->openPage("connection"));
        settingsRow(account,"Your information, protected","Session, storage and security",R.drawable.ic_lock,()->openPage("security"));
        settingsRow(account,"About CareMonitor","How it works, safety and reading limitations",R.drawable.ic_heart,()->openPage("about"));
        textAction(content,"Sign out",()->confirm("Sign out?","Wearable monitoring, location and audio will stop. The encrypted queue stays linked to this account for a later sign-in.",this::logout),true).setTextColor(RED);
        labelIn(content,"CareMonitor 1.0.0",15,MUTED,false);
    }
    private String careMonitorInformation(){return "CareMonitor helps you keep track of wearable readings, reminders and updates from your care circle. It is an educational prototype, not a clinically validated medical device.\n\nReadings do not diagnose a condition, measure dehydration or replace professional care. Surface temperature is not core body temperature. Missing values can result from contact, movement or connection issues.\n\nHelp notices depend on your connection, phone permissions and approved caregivers. They do not call emergency services or guarantee that someone has responded.\n\nLearning insights is a separate workspace using synthetic data; its model scores do not assess your health.";}
    private void showAboutCareMonitor(){new AlertDialog.Builder(this).setTitle("About CareMonitor").setMessage(careMonitorInformation()).setPositiveButton("Done",null).show();}
    private void aboutCareMonitor(){
        label("About CareMonitor",34,INK,true);paragraph("Your care, with clear information.");
        LinearLayout about=card(content);labelIn(about,"CareMonitor 1.0.0",24,INK,true);labelIn(about,careMonitorInformation(),17,MUTED,false);
        textAction(content,"Reading details",()->openPage("reading_details"),false);
        textAction(content,"Your information, protected",()->openPage("security"),false);
    }
    private void notificationSettings(){
        paragraph("Notices from your circle, your way.");
        LinearLayout delivery=card(content);labelIn(delivery,"Notification channel",23,INK,true);labelIn(delivery,NotificationChannelService.status(),17,BLUE,true);
        labelIn(delivery,"Keeps a secure connection to your server and a visible notification with a Stop control.",17,MUTED,false);
        button(delivery,"Enable local notifications",BLUE,this::enableChannel);
        button(delivery,"Stop notification channel",SOFT,()->{stopService(new Intent(this,NotificationChannelService.class));toast("Channel stopped. Your inbox stays on the server.");});
        LinearLayout permissions=card(content);labelIn(permissions,"Phone permissions",23,INK,true);
        button(permissions,"Allow notifications",SOFT,this::notificationPermission);
        button(permissions,"Allow local network",SOFT,()->{if(!ApiClient.lanAllowed(this)){pendingAction="network";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);}else toast("Local network access is already allowed.");});
        paragraph("When the channel is off, the open app checks every 15 seconds. Android schedules a background backup at intervals of at least 15 minutes. Network, battery settings and force stop may interrupt delivery. Each caregiver has a separate inbox.");
    }
    private void securityDetails(){
        paragraph("Clear information about how your data is protected.");LinearLayout security=card(content);labelIn(security,"Your information, protected",23,INK,true);
        labelIn(security,"Your session and queued readings are encrypted with an AndroidKeyStore key. HTTPS checks the certificate and server name. Each account can access only its approved circle. A pending queue is never sent from another account.",17,MUTED,false);
        labelIn(security,"No recordings are captured and no audio is sent. The server receives GPS points outside your home area only when you enable that feature.",17,MUTED,false);
        labelIn(security,"Session expires: "+formatTime(session.expires),16,MUTED,false);
    }
    private void serverSettings(){
        LinearLayout connection=card(content);labelIn(connection,"Server connection",23,INK,true);
        labelIn(connection,"The app uses the connection prepared for your team. Change this address only if you manage the server.",17,MUTED,false);
        serverInput=input(connection,"HTTPS server",session.base,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        labelIn(connection,"The address must end in /api/v1 and match its certificate. Changing it requires signing in again; pending readings stay linked to the previous server.",16,MUTED,false);
        button(connection,"Save connection",BLUE,()->{
            final String base;try{base=ApiClient.base(serverInput.getText().toString().trim());}catch(Exception failure){toast(failure.getMessage());return;}
            if(base.equals(session.base)){toast("You are already using this connection.");return;}
            confirm("Change server?","You will be signed out and wearable monitoring and local features will stop. Your credentials and readings will not be sent to the new server until you sign in there.",()->{
                if(!getSharedPreferences("caremonitor_endpoint",0).edit().putString("previous_base",session.base).putString("base",base).commit()){toast("Could not save your connection.");return;}
                logout();notice="Connection saved. Sign in to your server.";render();
            });
        });
    }
    private JSONObject privacyPolicy(){return privacyPolicy(session);}
    private JSONObject privacyPolicy(ApiSession account){try{String saved=SecureStore.read(this,"privacy_"+account.id+".enc",4096);return saved.isEmpty()?new JSONObject():new JSONObject(saved);}catch(Exception failure){return new JSONObject();}}
    private void privacy(){
        JSONObject policy=privacyPolicy();
        LinearLayout location=card(content);labelIn(location,"Location away from home",24,INK,true);
        labelIn(location,"Only if you choose. Set your home centre and a privacy radius of at least 100 m. This uses your phone's location and accuracy, not your exact body location.",18,MUTED,false);
        labelIn(location,"Server consent: "+(consents.optBoolean("location")?"enabled":"disabled"),16,MUTED,false);
        if(policy.has("home_lat"))labelIn(location,"Home area set · radius "+policy.optInt("radius_m",200)+" m",17,BLUE,true);
        button(location,"Set home area",SOFT,this::homeDialog);
        button(location,"Enable location away from home",BLUE,()->{
            if(!policy.has("home_lat")){toast("Set your home area first.");return;}
            confirm("Share your location away from home?","Your caregivers can see points sent when your phone is outside the radius. Points inside your home area are not sent. You can stop sharing from the notification.",()->setConsent("location",true,()->requestFeature("location")));});
        button(location,"Revoke location consent",SOFT,()->setConsent("location",false,null));
        LinearLayout audio=card(content);labelIn(audio,"Optional local sound",24,INK,true);
        labelIn(audio,"Measures sound intensity in memory to ask if you are okay after a loud sound. It does not recognise accidents, voices or falls. Audio is not recorded, stored or sent. A visible notification stays active.",18,MUTED,false);
        labelIn(audio,"Server consent: "+(consents.optBoolean("audio")?"enabled":"disabled"),16,MUTED,false);
        button(audio,"Enable local sound check",BLUE,()->confirm("Enable the local microphone?","Brief samples are processed only in memory. A loud sound shows a question; you decide whether to send a notice. Listening ends when you tap Stop.",()->setConsent("audio",true,()->requestFeature("audio"))));
        button(audio,"Revoke microphone consent",SOFT,()->setConsent("audio",false,null));
        button(content,"Stop location and microphone",SOFT,()->{stopService(new Intent(this,PrivacySignalService.class));toast("Local features stopped.");});
    }
    private void homeDialog(){
        JSONObject policy=privacyPolicy();LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),0,dp(24),0);
        EditText lat=input(form,"Home latitude",policy.has("home_lat")?String.valueOf(policy.optDouble("home_lat")):"",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED),
            lon=input(form,"Home longitude",policy.has("home_lon")?String.valueOf(policy.optDouble("home_lon")):"",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED),
            radius=input(form,"Privacy radius in metres",String.valueOf(policy.optInt("radius_m",200)),InputType.TYPE_CLASS_NUMBER);
        labelIn(form,"These coordinates stay encrypted on your phone.",16,MUTED,false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Your home area").setView(form).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{double a=Double.parseDouble(lat.getText().toString()),b=Double.parseDouble(lon.getText().toString());int r=Integer.parseInt(radius.getText().toString());
            if(!Double.isFinite(a)||!Double.isFinite(b)||a< -90||a>90||b< -180||b>180||r<100||r>10000)throw new IllegalArgumentException();
            policy.put("home_lat",a).put("home_lon",b).put("radius_m",r);SecureStore.write(this,"privacy_"+session.id+".enc",policy.toString());dialog.dismiss();render();
        }catch(Exception failure){toast("Use valid coordinates and a radius from 100 to 10,000 m.");}}));dialog.show();
    }
    private void setConsent(String feature,boolean enabled,Runnable after){
        if(!enabled)stopService(new Intent(this,PrivacySignalService.class));
        ApiSession account=session;
        run(account,a->{JSONObject server=ApiClient.call(this,a,"GET","/consents",null);JSONObject body=json("location",server.optBoolean("location"),"audio",server.optBoolean("audio"));body.put(feature,enabled);
            JSONObject result=ApiClient.call(this,a,"POST","/consents",body);JSONObject policy=privacyPolicy(a);policy.put(feature+"_consent",enabled);SecureStore.write(this,"privacy_"+a.id+".enc",policy.toString());return result;
        },result->{consents=result;notice=enabled?"Consent saved.":"Consent revoked; local features stopped.";render();if(after!=null)after.run();});
    }
    private void requestFeature(String feature){
        pendingAction=feature;
        String permission=feature.equals("audio")?Manifest.permission.RECORD_AUDIO:Manifest.permission.ACCESS_FINE_LOCATION;
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){
            if(feature.equals("location")&&Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},710);
            else requestPermissions(new String[]{permission},710);
        }else startFeature(feature);
    }
    private void startFeature(String feature){
        pendingAction="";try{Intent intent=new Intent(this,PrivacySignalService.class).setAction(feature);if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);toast("Feature started. You can stop it from the notification.");}
        catch(RuntimeException failure){toast("Could not start. Check permissions and keep the app open when enabling it.");}
    }
    private void device(){
        if(!elder()){label("Wearable connection",32,INK,true);paragraph("Only the older adult connects the wearable from their own account.");return;}
        progress(deviceStep,4,"Connect my wearable");
        if(deviceStep==0){
            label("One step at a time",32,INK,true);paragraph("Get your wearable ready and keep your phone nearby.");
            illustration(R.drawable.ic_watch,"Wearable beside the phone");
            LinearLayout tips=card(content);labelIn(tips,"1. Turn on your wearable",22,INK,true);labelIn(tips,"2. Check its power",22,INK,true);labelIn(tips,"3. Keep it near your phone",22,INK,true);
            button(content,"I'm ready",BLUE,()->{deviceStep=1;render();});
        }else if(deviceStep==1){
            label("Keep your wearable nearby",32,INK,true);paragraph("Choose it in the next step. Your phone will ask you to confirm Bluetooth pairing if needed.");
            illustration(R.drawable.ic_bluetooth,"Bluetooth pairing");
            textAction(content,"Need help with Bluetooth?",()->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)),true);
            paragraph("First time? Keep the private setup card that came with your wearable nearby. Someone you trust can help you with this step. Only one phone can use the wearable at a time.");
            button(content,"Continue",BLUE,()->{deviceStep=2;render();});
        }else if(deviceStep==2){
            label("Choose your wearable",32,INK,true);paragraph("Tap Search and select your wearable. Enter its setup key once when asked; it stays encrypted for your account on this phone.");
            LinearLayout status=card(content);connectionLabel=labelIn(status,"Not connected",22,INK,true);readingLabel=labelIn(status,"",17,MUTED,false);
            button(content,"Search for my wearable",BLUE,()->bluetooth("scan"));
            button(content,"Stop and search again",SOFT,()->{if(monitor!=null)monitor.stopMonitoring();bluetooth("scan");});
            deviceList=new LinearLayout(this);deviceList.setOrientation(LinearLayout.VERTICAL);content.addView(deviceList);
            button(content,"Check connection",SOFT,()->{if(monitor==null||!monitor.isFresh()){toast("No recent readings yet. Check that your wearable is on and selected.");return;}deviceStep=3;render();});
        }else{
            boolean fresh=monitor!=null&&monitor.isFresh();
            setupStatusIcon=illustration(fresh?R.drawable.ic_status_ok:R.drawable.ic_status_unknown,"Connection status");
            deviceHeading=labelIn(content,fresh?"Your wearable is connected":"Waiting for new readings",32,INK,true);
            paragraph("Check for recent readings here, then return to Today.");
            LinearLayout status=card(content);connectionLabel=labelIn(status,"",22,INK,true);readingLabel=labelIn(status,"",17,MUTED,false);
            paragraph("Receiving samples on your phone does not confirm server delivery or valid measurements.");
            button(content,"Go to Today",BLUE,()->{page="home";render();refresh();});
            button(content,"Stop wearable monitoring",SOFT,()->{if(monitor!=null)monitor.stopMonitoring();deviceStep=2;render();});
            button(content,"Delete pending readings",SOFT,()->confirm("Delete pending readings?","Undelivered samples will be permanently deleted from this phone.",()->{if(monitor!=null)monitor.clearUploadQueue();}));
        }
        if(deviceStep>0)button(content,"Back",SOFT,this::goBack);
        if(deviceStep<3)button(content,"I'll do this later",SOFT,()->{page="home";render();refresh();});
        updateDevice();
    }
    private void bluetooth(String action){
        if(!elder()){pendingAction="";toast("Connect the wearable from the older adult's account.");return;}
        pendingAction=action;
        if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},711);return;}
        if(Build.VERSION.SDK_INT<31&&Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},711);return;}
        try{BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager.getAdapter();
            if(adapter==null){toast("Bluetooth is unavailable on this phone.");return;}if(!adapter.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),712);return;}
            pendingAction="";if(monitor==null){toast("The connection is getting ready. Please try again.");return;}monitor.setTransport("spp");monitor.scan();updateDevice();
        }catch(SecurityException failure){toast("Allow Nearby devices to continue.");}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);
        boolean granted=results.length>0;for(int result:results)granted&=result==PackageManager.PERMISSION_GRANTED;
        if(code==710){String feature=pendingAction;pendingAction="";if(granted)startFeature(feature);else toast("Permission was not granted. The feature stays stopped.");}
        if(code==711){if(granted)bluetooth(pendingAction);else{pendingAction="";toast("Bluetooth needs Nearby devices permission.");}}
        if(code==713){String action=pendingAction;pendingAction="";if(granted&&"channel".equals(action))enableChannel();else if(!granted)toast("Notifications are not allowed. You can still read your inbox in the app.");}
        if(code==714){String action=pendingAction;pendingAction="";if(granted){if(action.equals("auth"))authenticate();else if(action.equals("channel"))enableChannel();else if(action.equals("history"))loadHistory();else refresh();}
            else{notice="Your server needs local network access. You can allow it in the app settings.";toast(notice);}}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==712&&result==RESULT_OK)bluetooth(pendingAction);}
    private void updateDevice(){
        if(!visible&&connectionLabel==null)return;
        if(deviceHeading!=null){boolean fresh=monitor!=null&&monitor.isFresh();deviceHeading.setText(fresh?"Your wearable is connected":"Waiting for new readings");
            if(setupStatusIcon!=null)setupStatusIcon.setImageResource(fresh?R.drawable.ic_status_ok:R.drawable.ic_status_unknown);}
        if(connectionLabel!=null)connectionLabel.setText(monitor==null?"Wearable disconnected":monitor.bluetoothState());
        if(readingLabel!=null&&monitor!=null)readingLabel.setText(monitor.relayState()+"\n"+monitor.packetCount()+" samples · "+monitor.queuedCount()+" pending"+(monitor.retainedOtherCount()>0?"\n"+monitor.retainedOtherCount()+" readings retained for another account or server":"")+(monitor.error().isEmpty()?"":"\n"+monitor.error()));
        if(deviceList!=null&&monitor!=null&&elder()){deviceList.removeAllViews();List<LiveMonitorService.Device> devices=monitor.devices();
            if(devices.isEmpty())labelIn(deviceList,"Your paired devices will appear here when you search.",17,MUTED,false);
            for(LiveMonitorService.Device device:devices){
            button(deviceList,"CareMonitor wearable\n"+device.address,SOFT,()->{
                if(!elder())return;
                if(WearableKeys.has(this,device.address))connectWearable(device.address);
                else authoriseWearable(device.address);
            });
            if(WearableKeys.has(this,device.address))textAction(deviceList,"Update setup code · "+device.address,()->authoriseWearable(device.address),false);
        }}}
    private void authoriseWearable(String address){
        if(!elder())return;
        final ApiSession expected=session;
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),dp(8),dp(24),0);
        labelIn(fields,"Use the code on your private setup card.",17,MUTED,false);
        fields.setFocusableInTouchMode(true);fields.requestFocus();
        EditText keyInput=input(fields,"Setup code from your card","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setSaveEnabled(false);keyInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        keyInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if(Build.VERSION.SDK_INT>=26)keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        secretVisibility(fields,keyInput,"Show setup key");
        ScrollView body=new ScrollView(this);body.setFillViewport(false);body.addView(fields,new ScrollView.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Your private setup code").setView(body)
            .setPositiveButton("Connect",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored->{
            if(dialog.getWindow()!=null){dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setSingleLine(true);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setSingleLine(true);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{
                    WearableKeys.save(this,expected,address,keyInput.getText().toString());keyInput.setText("");hideKeyboard();dialog.dismiss();connectWearable(address);
                }catch(IllegalArgumentException failure){keyInput.setError(failure.getMessage());}
                catch(Exception failure){toast("Your wearable key could not be saved. Check that you are signed in as the older adult.");}
            });
        });
        dialog.setOnDismissListener(ignored->keyInput.setText(""));dialog.show();
    }
    private void connectWearable(String address){
        if(!elder())return;
        try{
            Intent intent=new Intent(this,LiveMonitorService.class).setAction(LiveMonitorService.ACTION_CONNECT).putExtra("address",address).putExtra("transport","spp");
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
        }catch(RuntimeException failure){toast("Could not start Bluetooth. Check permissions.");}
    }
    private void enableChannel(){
        if(session==null){toast("Sign in first.");return;}
        if(!ApiClient.lanAllowed(this)){pendingAction="channel";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){pendingAction="channel";requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},713);return;}
        if(!((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled()){
            toast("Allow notifications in settings to use the local channel.");
            startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));return;
        }
        try{Intent intent=new Intent(this,NotificationChannelService.class).setAction("start");if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            toast("Local channel started. You can stop it from the notification.");}
        catch(RuntimeException failure){toast("Android did not allow the channel to start. Open the app and check permissions.");}
    }
    private void notificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},713);else toast("You can review notifications in system settings.");}
    private void updateHelp(){
        boolean pending=helpAttempt!=null&&!helpAttempt.finished;
        if(helpButton!=null){helpButton.setEnabled(!pending);helpButton.setText(pending?"Sending request…":"Ask for help");}
        if(helpStatus!=null){setLabel(helpStatus,pending?"Saving your request…":helpOutcome);helpStatus.setVisibility(pending||!helpOutcome.isEmpty()?View.VISIBLE:View.GONE);}
    }
    private void sendHelp(){
        if(!elder()||(helpAttempt!=null&&!helpAttempt.finished))return;
        final ApiSession account=session;final HelpAttempt attempt=new HelpAttempt(account);helpAttempt=attempt;helpOutcome="";attempt.attach(this);updateHelp();
        network.execute(()->{try{
            ApiClient.call(this,account,"POST","/events",json("type","sos","confirmed",true,"occurred_at",ApiSession.utc(System.currentTimeMillis()),"source","device_diagnostic"));
            attempt.complete("Request saved. Your approved care circle can review it. This does not confirm they have seen it.");
        }catch(Exception failure){attempt.complete("We could not confirm your request. Check Updates before trying again. No caregiver response is confirmed.");}});
    }
    private void finishHelp(HelpAttempt attempt){
        if(helpAttempt!=attempt||session==null||!session.id.equals(attempt.accountId)||!session.base.equals(attempt.base))return;
        helpOutcome=attempt.outcome;updateHelp();
    }
    private void sendEvent(String type){mutate("POST","/events",json("type",type,"confirmed",true,"occurred_at",ApiSession.utc(System.currentTimeMillis()),"source","device_diagnostic"),"Request recorded.");}
    private void logout(){
        ApiSession old=session;helpAttempt=null;helpOutcome="";syncProblem="";epoch++;clearHistory();refreshing=false;session=null;busy=false;registering=false;setup=new AccountSetup();acknowledged=false;pendingAction="";deviceStep=0;page="home";notice="Signed out.";patientId="";overview=new JSONObject();patients=new JSONArray();requests=new JSONArray();caregivers=new JSONArray();reminders=new JSONArray();notifications=new JSONArray();consents=new JSONObject();
        if(monitor!=null)monitor.stopMonitoring();stopService(new Intent(this,PrivacySignalService.class));ApiSession.clear(this);render();
        if(old!=null)network.execute(()->{try{ApiClient.call(this,old,"POST","/auth/logout",json());}catch(Exception ignored){}});
    }
    private interface Request{JSONObject call(ApiSession account)throws Exception;}
    private interface Success{void accept(JSONObject result);}
    private void run(ApiSession account,Request request,Success success){
        if(account==null)return;final long capture=epoch;
        network.execute(()->{try{JSONObject result=request.call(account);main.post(()->{if(capture==epoch&&session!=null&&session.id.equals(account.id))success.accept(result);});}
            catch(Exception failure){main.post(()->{if(capture!=epoch)return;if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==401){logout();notice="Your session expired. Please sign in again.";render();}
                else{notice=failure.getMessage();toast(notice);}});}});
    }
    private void mutate(String method,String path,JSONObject body,String message){run(session,account->ApiClient.call(this,account,method,path,body),result->{notice=message;refresh();render();});}
    private void refresh(){
        if(refreshing||session==null||!ApiClient.lanAllowed(this))return;if(!session.valid()){logout();notice="Your session expired. Please sign in again.";render();return;}
        refreshing=true;final boolean requested=manualRefresh;manualRefresh=false;ApiSession account=session;long capture=epoch;String chosen=patientId;
        network.execute(()->{try{
            JSONObject p=ApiClient.call(this,account,"GET","/patients",null);JSONArray list=p.optJSONArray("patients");if(list==null)list=new JSONArray();
            String selected=chosen;boolean exists=false;for(int i=0;i<list.length();i++)if(selected.equals(list.optJSONObject(i).optString("id")))exists=true;
            if(!exists)selected=list.length()>0?list.getJSONObject(0).getString("id"):"";
            final long viewRequestedAt=SystemClock.elapsedRealtime();
            JSONObject view=selected.isEmpty()?new JSONObject():ApiClient.call(this,account,"GET","/patients/"+selected+"/overview",null);
            JSONArray meds=selected.isEmpty()?new JSONArray():ApiClient.call(this,account,"GET","/patients/"+selected+"/reminders",null).optJSONArray("reminders");
            JSONObject c=account.elder()?ApiClient.call(this,account,"GET","/consents",null):new JSONObject();
            JSONArray req=account.elder()?ApiClient.call(this,account,"GET","/link-requests",null).optJSONArray("requests"):new JSONArray();
            JSONArray care=account.elder()?ApiClient.call(this,account,"GET","/caregivers",null).optJSONArray("caregivers"):new JSONArray();
            JSONArray inbox=ApiClient.call(this,account,"GET","/notifications?after=0",null).optJSONArray("items");
            try{NotificationSync.sync(this);}catch(Exception ignored){}
            final JSONArray patientList=list,medicine=meds,pending=req,carers=care,messages=inbox;final String id=selected;
            main.post(()->{if(capture!=epoch||session==null||!session.id.equals(account.id))return;refreshing=false;
                boolean changedSelection=!selectedPatient().equals(id);
                if(changedSelection){clearHistory();if(page.equals("history")){page="home";notice="Your care selection changed. Choose a person to open their history.";}}
                patients=patientList;patientId=id;overview=view;overviewReceivedAt=viewRequestedAt;syncProblem="";updateSyncLabel();reminders=medicine==null?new JSONArray():medicine;requests=pending==null?new JSONArray():pending;
                caregivers=carers==null?new JSONArray():carers;notifications=messages==null?new JSONArray():messages;consents=c;
                // Preserve forms and scroll position; connection labels update independently of sensor cadence.
                if(changedSelection&&!elder()&&(page.equals("home")||page.equals("reading_details")||page.equals("care")||page.equals("alerts")))render();
                else if(page.equals("home")&&homeBuiltKey.equals(homeStructureKey()))updateReadingViews();
                else if(page.equals("reading_details"))updateReadingViews();
                else if(page.equals("care")||page.equals("alerts")){
                    if(!builtPageData.equals(pageDataKey())){if(requested){int y=scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));}else if(showUpdatesButton!=null)showUpdatesButton.setVisibility(View.VISIBLE);}
                }
                else if(!page.equals("device")&&!page.equals("account")&&!page.equals("ready")&&!page.equals("history")&&!isSettingsDetail()&&!(getCurrentFocus() instanceof EditText)){int y=scroll==null?0:scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));}
            });
        }catch(Exception failure){main.post(()->{if(capture!=epoch)return;refreshing=false;
            if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==401){logout();notice="Your session expired or was revoked. Please sign in again.";render();}
            else if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==403&&page.equals("history")){clearHistory();historyError="Access is no longer available. Return to Today and check your care circle.";render();}
            else{syncProblem="Connection unavailable. Showing saved information. Try Refresh when you are connected.";updateSyncLabel();updateReadingViews();}});}});
    }
    private void confirm(String title,String body,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Confirm",(d,w)->action.run()).setNegativeButton("Cancel",null).show();}
    private void toast(String message){Toast.makeText(this,message==null?"Could not complete the action.":message,Toast.LENGTH_LONG).show();}
    private void label(String title,int size,int color,boolean bold){labelIn(content,title,size,color,bold);}
    private void paragraph(String value){TextView view=labelIn(content,value,18,MUTED,false);view.setPadding(0,dp(8),0,dp(16));}
    private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));view.setLineSpacing(dp(2),1.04f);if(size>=28)view.setLetterSpacing(-.025f);return view;}
    private TextView labelIn(LinearLayout parent,String value,int size,int color,boolean bold){TextView view=text(value,size,color,bold);view.setPadding(0,dp(4),0,dp(8));parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private LinearLayout card(LinearLayout parent){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(20),dp(20),dp(20),dp(20));card.setBackground(shape(Color.WHITE,22));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);lp.bottomMargin=dp(8);parent.addView(card,lp);return card;}
    private void button(LinearLayout parent,String label,int background,Runnable click){Button view=new Button(this);view.setText(label);view.setTextSize(18);view.setAllCaps(false);view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,background==BLUE||background==RED?Color.WHITE:BLUE}));
        StateListDrawable backgrounds=new StateListDrawable();backgrounds.addState(new int[]{-android.R.attr.state_enabled},shape(LINE,16));backgrounds.addState(new int[]{},interactive(background,16));view.setBackground(backgrounds);
        view.setMinHeight(dp(56));view.setMinimumHeight(dp(56));view.setPadding(dp(18),dp(14),dp(18),dp(14));view.setStateListAnimator(null);view.setElevation(0);view.setEnabled(!busy);
        view.setOnClickListener(v->click.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);parent.addView(view,lp);}
    private Button textAction(LinearLayout parent,String label,Runnable click,boolean centered){
        Button view=new Button(this);view.setText(label);view.setTextSize(17);view.setAllCaps(false);view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));view.setTextColor(BLUE);
        view.setBackground(interactive(Color.TRANSPARENT,14));view.setGravity((centered?Gravity.CENTER:Gravity.START)|Gravity.CENTER_VERTICAL);view.setPadding(centered?dp(12):0,dp(12),dp(12),dp(12));view.setMinHeight(dp(56));view.setMinimumHeight(dp(56));view.setStateListAnimator(null);view.setElevation(0);view.setEnabled(!busy);
        view.setOnClickListener(v->click.run());parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;
    }
    private EditText input(LinearLayout parent,String label,String value,int type){labelIn(parent,label,15,MUTED,true);EditText view=new EditText(this);view.setText(value);view.setInputType(type);view.setTextSize(18);view.setTextColor(INK);view.setSingleLine(true);view.setPadding(dp(14),dp(14),dp(14),dp(14));
        if((type&InputType.TYPE_MASK_VARIATION)==InputType.TYPE_TEXT_VARIATION_PASSWORD){view.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());view.setSaveEnabled(false);}
        GradientDrawable normal=shape(0xFFF5F5F9,12);normal.setStroke(Math.max(1,dp(.5f)),LINE);GradientDrawable focused=shape(Color.WHITE,12);focused.setStroke(dp(2),BLUE);
        StateListDrawable background=new StateListDrawable();background.addState(new int[]{android.R.attr.state_focused},focused);background.addState(new int[]{},normal);view.setBackground(background);view.setSelectAllOnFocus(false);
        view.setMinHeight(dp(56));view.setEnabled(!busy);view.setContentDescription(label);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(view,lp);return view;}
    private ImageView icon(int resource,int tint,int size){ImageView icon=new ImageView(this);icon.setImageResource(resource);icon.setImageTintList(ColorStateList.valueOf(tint));icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return icon;}
    private Drawable interactive(int fill,int radius){return new RippleDrawable(ColorStateList.valueOf(fill==BLUE||fill==RED?0x30FFFFFF:0x140066CC),shape(fill,radius),shape(Color.WHITE,radius));}
    private void sectionTitle(String title){TextView label=text(title,13,MUTED,true);label.setLetterSpacing(.07f);label.setPadding(dp(16),dp(24),dp(12),dp(8));content.addView(label);}
    private LinearLayout group(LinearLayout parent){LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);group.setBackground(shape(Color.WHITE,22));parent.addView(group,new LinearLayout.LayoutParams(-1,-2));return group;}
    private void settingsRow(LinearLayout parent,String title,String subtitle,int symbol,Runnable action){
        if(parent.getChildCount()>0){View line=new View(this);line.setBackgroundColor(LINE);LinearLayout.LayoutParams lineLp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(.5f)));lineLp.leftMargin=dp(68);parent.addView(line,lineLp);}
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(14),dp(16),dp(14));row.setMinimumHeight(dp(76));row.setBackground(interactive(Color.TRANSPARENT,18));
        FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(SOFT,10));tile.addView(icon(symbol,BLUE,22),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));row.addView(tile,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams copyLp=new LinearLayout.LayoutParams(0,-2,1);copyLp.leftMargin=dp(14);copyLp.rightMargin=dp(8);row.addView(copy,copyLp);
        labelIn(copy,title,17,INK,true);labelIn(copy,subtitle,15,MUTED,false);row.addView(icon(R.drawable.ic_chevron_right,MUTED,18),new LinearLayout.LayoutParams(dp(18),dp(18)));
        row.setFocusable(true);row.setContentDescription(title);row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private GradientDrawable shape(int fill,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(fill);drawable.setCornerRadius(dp(radius));return drawable;}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private String formatTime(String value){long time=ApiSession.expiryMs(value);return time==0?value:new java.text.SimpleDateFormat("d MMM yyyy, HH:mm",Locale.ENGLISH).format(new Date(time));}
}
