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
    private ImageView setupStatusIcon;
    private LinearLayout deviceList;
    private EditText emailInput,passwordInput,nameInput,serverInput;
    private CheckBox termsCheck;
    private AccountSetup setup=new AccountSetup();
    private String loginEmail="";
    private boolean acknowledged;
    private int deviceStep;
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
    private Object backCallback;
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){monitor=((LiveMonitorService.LocalBinder)binder).service();monitor.addListener(liveListener);updateDevice();}
        public void onServiceDisconnected(ComponentName name){monitor=null;updateDevice();}
    };
    private final LiveMonitorService.Listener liveListener=this::updateDevice;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        session=ApiSession.load(this);
        if(state!=null){page=state.getString("page","home");patientId=state.getString("patient","");
            registering=state.getBoolean("registering",false);setup.step=Math.max(0,Math.min(3,state.getInt("setup_step",0)));
            setup.role=state.getString("setup_role","");setup.name=state.getString("setup_name","");setup.email=state.getString("setup_email","");
            loginEmail=state.getString("login_email","");acknowledged=state.getBoolean("acknowledged",false);deviceStep=Math.max(0,Math.min(3,state.getInt("device_step",0)));}
        Object retained=getLastNonConfigurationInstance();if(retained instanceof AuthAttempt){authentication=(AuthAttempt)retained;busy=true;authentication.attach(this);}
        else if(state!=null&&state.getBoolean("auth_in_flight",false))notice="La solicitud se interrumpió. Si tu cuenta ya se creó, elige Ya tengo una cuenta para iniciar sesión.";
        bound=bindService(new Intent(this,LiveMonitorService.class),serviceConnection,BIND_AUTO_CREATE);
        if(Build.VERSION.SDK_INT>=33){android.window.OnBackInvokedCallback callback=this::goBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);backCallback=callback;}
        if(getIntent().getBooleanExtra("show_alerts",false)){page="alerts";patientId=getIntent().getStringExtra("patient_id")==null?patientId:getIntent().getStringExtra("patient_id");}
        render();handleSoundIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("show_alerts",false)){page="alerts";patientId=intent.getStringExtra("patient_id")==null?patientId:intent.getStringExtra("patient_id");epoch++;refreshing=false;render();refresh();}handleSoundIntent(intent);}
    private void handleSoundIntent(Intent intent){if(intent.getBooleanExtra("sound_check",false)&&session!=null&&session.elder()){
        intent.removeExtra("sound_check");new AlertDialog.Builder(this).setTitle("¿Te encuentras bien?")
            .setMessage("Se observó sonido fuerte en el teléfono. Esta señal no identifica caídas ni accidentes. Puedes enviar un aviso a tu círculo de cuidado.")
            .setPositiveButton("Enviar aviso",(d,w)->sendEvent("audio_impact")).setNegativeButton("Estoy bien",null).show();}}
    @Override protected void onSaveInstanceState(Bundle state){rememberAuth();super.onSaveInstanceState(state);state.putString("page",page);state.putString("patient",patientId);
        state.putBoolean("registering",registering);state.putInt("setup_step",setup.step);state.putString("setup_role",setup.role);state.putString("setup_name",setup.name);
        state.putString("setup_email",setup.email);state.putString("login_email",loginEmail);state.putBoolean("acknowledged",acknowledged);state.putInt("device_step",deviceStep);state.putBoolean("auth_in_flight",busy);}
    @Override public Object onRetainNonConfigurationInstance(){return authentication;}
    @Override public void onBackPressed(){goBack();}
    private void goBack(){
        if(busy){toast("Espera a que termine la solicitud.");return;}
        if(session==null){rememberAuth();notice="";if(registering&&setup.step>0)setup.back();else if(registering)registering=false;else{moveTaskToBack(true);return;}}
        else if(page.equals("device")&&deviceStep>0)deviceStep--;
        else if(isSettingsDetail())page="account";else page="home";
        hideKeyboard();
        render();
    }
    @Override protected void onStart(){super.onStart();visible=true;main.post(poll);}
    @Override protected void onStop(){visible=false;main.removeCallbacks(poll);super.onStop();}
    @Override protected void onDestroy(){epoch++;main.removeCallbacksAndMessages(null);if(monitor!=null)monitor.removeListener(liveListener);if(bound)unbindService(serviceConnection);
        if(authentication!=null)synchronized(authentication){if(authentication.owner==this)authentication.owner=null;}
        if(isChangingConfigurations())network.shutdown();else network.shutdownNow();
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)backCallback);super.onDestroy();}
    private final Runnable poll=new Runnable(){public void run(){if(visible&&session!=null){refresh();}if(visible)main.postDelayed(this,15000);}};
    private boolean elder(){return session!=null&&session.elder();}
    private JSONObject json(Object... entries){JSONObject object=new JSONObject();try{for(int i=0;i<entries.length;i+=2)object.put((String)entries[i],entries[i+1]);}catch(Exception failure){throw new IllegalArgumentException(failure);}return object;}
    private void render(){
        connectionLabel=null;readingLabel=null;deviceList=null;deviceHeading=null;setupStatusIcon=null;
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
        if(page.equals("home"))home();else if(page.equals("alerts"))alerts();else if(page.equals("care"))care();else if(page.equals("device"))device();
        else if(page.equals("connection")){settingsBack();label("Conexión",34,INK,true);paragraph("La conexión de tu equipo, en un solo lugar.");serverSettings();}
        else if(page.equals("privacy")){settingsBack();label("Tu privacidad",34,INK,true);paragraph("Tú decides qué compartir y cuándo.");if(elder())privacy();}
        else if(page.equals("notification_settings")){settingsBack();label("Notificaciones",34,INK,true);notificationSettings();}
        else if(page.equals("security")){settingsBack();label("Tu información",34,INK,true);securityDetails();}
        else account();
        navigation();
    }
    private void brand(){
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setPadding(0,0,0,dp(session==null?20:8));content.addView(brand);
        ImageView mark=icon(R.drawable.ic_heart,BLUE,22);FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(SOFT,12));tile.addView(mark,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));brand.addView(tile,new LinearLayout.LayoutParams(dp(36),dp(36)));
        TextView word=text("CareMonitor",17,INK,true);LinearLayout.LayoutParams wordLp=new LinearLayout.LayoutParams(0,-2,1);wordLp.leftMargin=dp(10);brand.addView(word,wordLp);
        if(session!=null){TextView profile=text(initials(session.name),16,BLUE,true);profile.setGravity(Gravity.CENTER);profile.setMinHeight(dp(56));profile.setBackground(interactive(SOFT,28));profile.setContentDescription("Abrir ajustes");profile.setOnClickListener(v->openPage("account"));brand.addView(profile,new LinearLayout.LayoutParams(dp(56),dp(56)));}
    }
    private boolean isSettingsDetail(){return page.equals("connection")||page.equals("privacy")||page.equals("notification_settings")||page.equals("security");}
    private void settingsBack(){textAction(content,"Ajustes",()->openPage("account"),false);}
    private void openPage(String target){hideKeyboard();page=target;notice="";render();refresh();}
    private void hideKeyboard(){View focus=getCurrentFocus();if(focus!=null)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);}
    private String initials(String name){String[] parts=name.trim().split("\\s+");if(parts.length==0||parts[0].isEmpty())return "CM";return (parts[0].substring(0,1)+(parts.length>1?parts[parts.length-1].substring(0,1):"")).toUpperCase(Locale.getDefault());}
    private void auth(){
        if(registering){registration();return;}
        label("Cuidar, más cerca.",34,INK,true);
        paragraph("Tu bienestar y tu círculo de cuidado, en un mismo lugar.");
        LinearLayout card=card(content);
        emailInput=input(card,"Correo electrónico",loginEmail,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        passwordInput=input(card,"Contraseña","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);passwordInput.setSaveEnabled(false);
        passwordVisibility(card,passwordInput);
        emailInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);emailInput.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_NEXT){passwordInput.requestFocus();return true;}return false;});
        passwordInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);passwordInput.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){authenticate();return true;}return false;});
        authNotice();
        reassurance("Un espacio privado para ti", "CareMonitor no sustituye atención médica ni servicios de emergencia.");
        LinearLayout actions=authFooter();button(actions,busy?"Iniciando sesión…":"Iniciar sesión",BLUE,this::authenticate);
        textAction(actions,"Crear una cuenta",()->{rememberAuth();hideKeyboard();registering=true;setup=new AccountSetup();acknowledged=false;notice="";render();},true);
        if(!getSharedPreferences("caremonitor_endpoint",0).getString("previous_base","").isEmpty())button(content,"Volver a mi conexión anterior",SOFT,()->{
            String previous=getSharedPreferences("caremonitor_endpoint",0).getString("previous_base","");
            if(getSharedPreferences("caremonitor_endpoint",0).edit().putString("base",previous).remove("previous_base").commit()){
                rememberAuth();notice="Conexión anterior restaurada.";render();}
            else toast("No se pudo restaurar la conexión.");
        });
    }
    private void registration(){
        progress(setup.step,4,"Crear tu cuenta");
        String[] titles={"¿Cómo usarás CareMonitor?","¿Cómo te llamas?","¿Cuál es tu correo?","Protege tu cuenta"};
        String[] hints={"Elige la opción que mejor te describa.","Usaremos este nombre para saludarte.","Lo usarás para iniciar sesión.","Este es el último paso. Tu contraseña debe tener entre 12 y 128 caracteres."};
        label(titles[setup.step],32,INK,true);paragraph(hints[setup.step]);
        if(setup.step==0){
            roleChoice("elder","Soy la persona mayor","Mi wearable, mi bienestar y las personas que elijo.",R.drawable.ic_person);
            roleChoice("caregiver","Soy cuidador o cuidadora","Acompaño a alguien de mi círculo de cuidado.",R.drawable.ic_people);
        }else{
            LinearLayout card=card(content);
            if(setup.step==1)nameInput=input(card,"Tu nombre",setup.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            else if(setup.step==2)emailInput=input(card,"Correo electrónico",setup.email,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            else{
                labelIn(card,setup.name,23,INK,true);labelIn(card,setup.email,17,MUTED,false);
                labelIn(card,setup.elder()?"Persona mayor":"Cuidador o cuidadora",17,BLUE,true);
                passwordInput=input(card,"Elige una contraseña","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);passwordInput.setSaveEnabled(false);
                passwordVisibility(card,passwordInput);
                termsCheck=new CheckBox(this);termsCheck.setText("Entiendo las limitaciones. Usaré datos de prueba y no tomaré decisiones médicas con esta app.");
                termsCheck.setTextColor(INK);termsCheck.setTextSize(17);termsCheck.setMinHeight(dp(56));termsCheck.setChecked(acknowledged);termsCheck.setEnabled(!busy);card.addView(termsCheck);
            }
        }
        if(nameInput!=null||emailInput!=null){EditText field=nameInput!=null?nameInput:emailInput;field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
            field.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_NEXT){advanceSetup();return true;}return false;});}
        authNotice();
        LinearLayout actions=authFooter();button(actions,busy?"Creando tu cuenta…":setup.step==3?"Crear mi cuenta":"Continuar",BLUE,this::advanceSetup);
        textAction(actions,setup.step==0?"Ya tengo una cuenta":"Atrás",this::goBack,true);
        if(setup.step>0)textAction(content,"Ya tengo una cuenta",()->{rememberAuth();hideKeyboard();loginEmail=setup.email;registering=false;notice="";render();},true);
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
        CheckBox show=new CheckBox(this);show.setText("Mostrar contraseña");show.setContentDescription("Mostrar contraseña");show.setTextSize(16);show.setTextColor(MUTED);show.setMinHeight(dp(56));show.setButtonTintList(ColorStateList.valueOf(BLUE));show.setEnabled(!busy);parent.addView(show,new LinearLayout.LayoutParams(-1,-2));
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
        label(title+" · Paso "+(step+1)+" de "+count,15,MUTED,true);
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
        option.setFocusable(true);option.setClickable(true);option.setSelected(selected);option.setContentDescription(title+". "+subtitle+(selected?". Seleccionado":""));
        option.setOnClickListener(v->{if(busy)return;setup.role=role;notice="";render();});
    }
    private void ready(){
        illustration(R.drawable.ic_care_monitor,"Tu círculo de cuidado");label("Tu cuenta está lista",32,INK,true);
        paragraph("Ahora puedes pedir acceso a la persona que acompañas. Ella revisará y aprobará tu solicitud.");
        button(content,"Vincular a una persona",BLUE,()->{page="care";render();refresh();});
        button(content,"Ir a mi inicio",SOFT,()->{page="home";render();refresh();});
    }
    private void authenticate(){
        if(busy)return;
        if(!ApiClient.lanAllowed(this)){pendingAction="auth";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);return;}
        rememberAuth();final String base,email,password,name,role;
        try{base=ApiClient.base(ApiSession.savedBase(this));email=registering?setup.email:loginEmail;password=passwordInput.getText().toString();
            name=registering?setup.name:"";role=registering?setup.role:"";
            if(!email.contains("@")||password.length()<12||password.length()>128)throw new IllegalArgumentException("Usa un correo válido y una contraseña de entre 12 y 128 caracteres.");
            if(registering&&(!setup.problem().isEmpty()||!acknowledged))throw new IllegalArgumentException("Revisa tus datos y acepta las condiciones de uso.");
        }catch(Exception failure){toast(failure.getMessage());return;}
        busy=true;final boolean registration=registering;notice="Conectando por HTTPS…";
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
        if(attempt.error!=null){notice=attempt.error+(attempt.registration?" Si ya tienes cuenta, inicia sesión.":"");render();return;}
        try{ApiSession saved=ApiSession.save(this,attempt.base,attempt.response);session=saved;page=attempt.registration?(saved.elder()?"device":"ready"):"home";deviceStep=0;patientId="";notice="";
            getSharedPreferences("caremonitor_endpoint",0).edit().remove("previous_base").apply();
            loginEmail=attempt.email;registering=false;setup=new AccountSetup();acknowledged=false;
            NotificationSync.schedule(this);if(monitor!=null)monitor.refreshSession();render();refresh();
        }catch(Exception failure){notice="No se pudo guardar la sesión. Vuelve a iniciar sesión.";render();}
    }
    private void navigation(){
        View divider=new View(this);divider.setBackgroundColor(LINE);root.addView(divider,new LinearLayout.LayoutParams(-1,Math.max(1,dp(.5f))));
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(dp(8),dp(6),dp(8),dp(6));nav.setBackgroundColor(Color.WHITE);root.addView(nav);
        String[] labels={"Hoy","Avisos","Cuidado","Ajustes"},pages={"home","alerts","care","account"};
        int[] symbols={R.drawable.ic_nav_overview,R.drawable.ic_nav_alerts,R.drawable.ic_people,R.drawable.ic_nav_settings};
        for(int i=0;i<labels.length;i++){final String target=pages[i];boolean selected=page.equals(target)||(target.equals("account")&&isSettingsDetail())||(target.equals("home")&&page.equals("device"));int tint=selected?BLUE:MUTED;
            LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setPadding(dp(2),dp(4),dp(2),dp(4));item.setMinimumHeight(dp(60));item.setBackground(interactive(Color.WHITE,14));
            item.addView(icon(symbols[i],tint,24),new LinearLayout.LayoutParams(dp(24),dp(24)));
            TextView label=text(labels[i],12,tint,selected);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(4),0,0);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label,new LinearLayout.LayoutParams(-1,-2));
            item.setSelected(selected);item.setContentDescription(labels[i]);item.setFocusable(true);item.setOnClickListener(v->openPage(target));nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));}
    }
    private void home(){
        label(elder()?"Hola, "+session.name.split(" ")[0]+".":"Cuidar empieza aquí.",34,INK,true);
        paragraph(elder()?"Tu día, con tranquilidad y compañía.":"Una vista clara de las personas que acompañas.");
        if(!elder())patientChooser();
        LinearLayout hero=card(content);hero.setBackground(shape(Color.WHITE,24));
        LinearLayout statusTitle=new LinearLayout(this);statusTitle.setGravity(Gravity.CENTER_VERTICAL);hero.addView(statusTitle);
        statusTitle.addView(icon(R.drawable.ic_watch,BLUE,22),new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView eyebrow=text(elder()?"MI BIENESTAR":"BIENESTAR DE "+patientName().toUpperCase(Locale.getDefault()),13,MUTED,true);eyebrow.setLetterSpacing(.06f);LinearLayout.LayoutParams eyebrowLp=new LinearLayout.LayoutParams(0,-2,1);eyebrowLp.leftMargin=dp(10);statusTitle.addView(eyebrow,eyebrowLp);
        String state=overview.optJSONObject("freshness")==null?"no_data":overview.optJSONObject("freshness").optString("state");
        labelIn(hero,state.equals("fresh")?"Datos recientes":state.equals("stale")?"Datos antiguos":"Esperando conexión",28,INK,true);
        labelIn(hero,state.equals("fresh")?"Lecturas del wearable · no son una valoración médica.":"La ausencia de datos no significa que todo esté bien.",17,MUTED,false);
        if(elder()){
            connectionLabel=labelIn(hero,monitor==null?"Wearable sin conexión":monitor.bluetoothState(),16,MUTED,false);
            button(hero,"Conectar mi wearable",BLUE,()->{deviceStep=0;page="device";render();});
        }else if(patientId.isEmpty())labelIn(hero,"Pide el código de vinculación a la persona mayor.",17,MUTED,false);
        JSONObject reading=overview.optJSONObject("reading");JSONObject raw=reading==null?null:reading.optJSONObject("payload");
        if(elder()&&monitor!=null&&monitor.isFresh())raw=monitor.snapshot();
        metrics(raw);
        if(reading!=null)paragraph("Última muestra del servidor: "+formatTime(reading.optString("captured_at"))+" · origen "+sourceLabel(reading.optString("source","")));
        if(elder()){
            LinearLayout help=card(content);labelIn(help,"Estoy aquí para ayudarte",23,INK,true);
            labelIn(help,"Envía un aviso a tu círculo de cuidado. Necesita conexión con el servidor.",18,MUTED,false);
            button(help,"Pedir ayuda",RED,()->confirm("¿Enviar un aviso de ayuda?","Llegará como aviso a tus cuidadores vinculados. Este sistema no llama a emergencias.",()->sendEvent("sos")));
            button(help,"Quiero avisar de una caída",SOFT,()->confirm("¿Confirmas que quieres avisar?","La señal de movimiento no confirma por sí sola una caída. Tú decides enviar el aviso.",()->sendEvent("possible_fall")));
        }
        medicationCards(false);
        JSONObject location=overview.optJSONObject("location");
        if(!elder()&&location!=null){LinearLayout card=card(content);labelIn(card,"Fuera de casa",23,INK,true);
            labelIn(card,"Última ubicación compartida: "+formatTime(location.optString("recorded_at"))+" · precisión ±"+(int)location.optDouble("accuracy_m")+" m",17,MUTED,false);
            button(card,"Ver coordenadas",SOFT,()->new AlertDialog.Builder(this).setTitle("Ubicación GPS compartida").setMessage("Latitud: "+location.optDouble("latitude")+"\nLongitud: "+location.optDouble("longitude")+"\nPrecisión: ±"+(int)location.optDouble("accuracy_m")+" m\n\nSe muestra en el teléfono sin abrir servicios de mapas externos.").setPositiveButton("Entendido",null).show());}
        LinearLayout limits=card(content);labelIn(limits,"Una lectura con contexto",22,INK,true);
        labelIn(limits,"El GY906 mide temperatura de superficie, no temperatura corporal central. La luz puede sugerir contacto, pero requiere calibración. La presión arterial y la deshidratación no se pueden determinar con estos sensores.",17,MUTED,false);
        button(content,"Actualizar",SOFT,this::refresh);
    }
    private String sourceLabel(String source){
        if("device_diagnostic".equals(source))return "Wearable";
        if("synthetic".equals(source))return "Datos de prueba";
        if("dataset_replay".equals(source))return "Registro de prueba";
        return "Origen no disponible";
    }
    private String patientName(){JSONObject patient=overview.optJSONObject("patient");return patient==null?"tu círculo":patient.optString("display_name","tu círculo");}
    private void patientChooser(){
        if(patients.length()==0){paragraph("Aún no tienes personas vinculadas. Abre Cuidado para solicitar acceso con su código.");return;}
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);content.addView(row);
        for(int i=0;i<patients.length();i++){JSONObject patient=patients.optJSONObject(i);if(patient==null)continue;
            String id=patient.optString("id");button(row,(id.equals(patientId)?"✓  ":"")+patient.optString("display_name","Persona"),id.equals(patientId)?BLUE:SOFT,
                ()->{epoch++;refreshing=false;patientId=id;overview=new JSONObject();reminders=new JSONArray();render();refresh();});}
    }
    private void metrics(JSONObject raw){
        label("Lecturas del wearable",23,INK,true);
        if(raw==null){LinearLayout empty=card(content);empty.addView(icon(R.drawable.ic_nav_sensors,MUTED,28),new LinearLayout.LayoutParams(dp(28),dp(28)));labelIn(empty,"Sin lecturas disponibles",21,INK,true);labelIn(empty,"Tus lecturas aparecerán aquí cuando lleguen muestras del wearable.",17,MUTED,false);return;}
        JSONObject optical=raw.optJSONObject("max30102"),temp=raw.optJSONObject("gy906"),light=raw.optJSONObject("photoresistor"),imu=raw.optJSONObject("mpu6050");
        boolean valid=optical!=null&&"ok".equals(optical.optString("status"))&&!Boolean.FALSE.equals(optical.opt("online"))&&!Boolean.FALSE.equals(optical.opt("finger_detected"));
        LinearLayout row=metricRow();metricTile(row,"Pulso",valid&&optical.optBoolean("heart_rate_valid")?numeric(optical,"heart_rate_bpm"," lpm"):"Sin lectura válida",R.drawable.ic_heart,0xFFB52B55);
        metricTile(row,"Oxígeno estimado",valid&&optical.optBoolean("spo2_valid")?numeric(optical,"spo2_percent"," %"):"Sin lectura válida",R.drawable.ic_oxygen,BLUE);
        boolean tempValid=temp!=null&&!Boolean.FALSE.equals(temp.opt("online"))&&(!temp.has("status")||temp.optString("status").equals("ok"));
        row=metricRow();metricTile(row,"Superficie",tempValid?numeric(temp,"object_temp_c"," °C"):"No disponible",R.drawable.ic_temperature,0xFF98631D);
        metricTile(row,"Ambiente",tempValid?numeric(temp,"ambient_temp_c"," °C"):"No disponible",R.drawable.ic_temperature,BLUE);
        row=metricRow();metricTile(row,"Luz / contacto",light!=null&&light.opt("adc_raw") instanceof Number?"ADC "+light.optInt("adc_raw")+" · sin calibrar":"No disponible",R.drawable.ic_light,0xFF98631D);
        metricTile(row,"Movimiento",imu!=null&&!Boolean.FALSE.equals(imu.opt("online"))?"Señal disponible":"No disponible",R.drawable.ic_motion,0xFF6554A4);
        paragraph("Las lecturas inválidas se conservan como inválidas; nunca se convierten en valores normales.");
    }
    private String numeric(JSONObject object,String key,String suffix){Object value=object.opt(key);if(!(value instanceof Number))return "No disponible";double number=((Number)value).doubleValue();return Double.isNaN(number)||Double.isInfinite(number)?"No disponible":String.format(Locale.getDefault(),"%.1f",number)+suffix;}
    private LinearLayout metricRow(){LinearLayout row=new LinearLayout(this);row.setOrientation(getResources().getConfiguration().fontScale>=1.3f?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);content.addView(row);return row;}
    private void metricTile(LinearLayout row,String title,String value,int symbol,int tint){
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(18),dp(18),dp(18),dp(18));tile.setBackground(shape(Color.WHITE,22));
        boolean stacked=row.getOrientation()==LinearLayout.VERTICAL;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1);lp.topMargin=dp(8);lp.bottomMargin=dp(4);if(!stacked&&row.getChildCount()==0)lp.rightMargin=dp(12);row.addView(tile,lp);
        tile.addView(icon(symbol,tint,24),new LinearLayout.LayoutParams(dp(24),dp(24)));labelIn(tile,title,16,MUTED,false);labelIn(tile,value,22,INK,true);
    }
    private void alerts(){
        label("Avisos de cuidado",34,INK,true);paragraph("Consulta el origen y el detalle de cada aviso.");
        if(!elder())patientChooser();
        JSONArray alerts=overview.optJSONArray("alerts");if(alerts==null||alerts.length()==0){LinearLayout empty=card(content);labelIn(empty,"Sin avisos disponibles",25,INK,true);labelIn(empty,"Esto no confirma un estado de salud ni una conexión activa.",18,MUTED,false);}
        else for(int i=0;i<alerts.length();i++){JSONObject alert=alerts.optJSONObject(i);if(alert==null)continue;LinearLayout card=card(content);
            labelIn(card,alert.optBoolean("acknowledged")?"REVISADO":"PENDIENTE DE REVISIÓN",12,BLUE,true);
            labelIn(card,alert.optString("title",alert.optString("type","Aviso")),23,INK,true);
            labelIn(card,alert.optString("message","Revisa la señal y el contexto de este aviso."),18,MUTED,false);
            labelIn(card,formatTime(alert.optString("created_at"))+" · "+sourceLabel(alert.optString("source")),15,MUTED,false);
            if(!elder()&&!alert.optBoolean("acknowledged")){String id=alert.optString("id");button(card,"Marcar como revisado",SOFT,()->mutate("POST","/alerts/"+id+"/acknowledge",json(),"Aviso marcado como revisado."));}}
        label("Buzón de mi cuenta",23,INK,true);
        int shown=0;for(int i=0;i<notifications.length();i++){JSONObject item=notifications.optJSONObject(i);if(item==null||item.optBoolean("acknowledged"))continue;
            JSONObject data=item.optJSONObject("data");if(data==null)data=new JSONObject();LinearLayout card=card(content);labelIn(card,data.optString("title","Aviso de cuidado"),21,INK,true);
            labelIn(card,data.optString("message",data.optString("instructions","Abre Cuidado para ver el recordatorio.")),17,MUTED,false);
            int id=item.optInt("id");button(card,"Ya lo leí",SOFT,()->mutate("POST","/notifications/"+id+"/ack",json(),"Aviso leído."));shown++;}
        if(shown==0)paragraph("No hay mensajes pendientes.");
        LinearLayout delivery=card(content);labelIn(delivery,"Tus notificaciones",22,INK,true);
        labelIn(delivery,"Elige cómo recibir avisos de tu círculo. La entrega depende de la conexión y los permisos del teléfono.",17,MUTED,false);
        labelIn(delivery,NotificationChannelService.status(),17,BLUE,true);
        button(delivery,"Configurar notificaciones",SOFT,()->openPage("notification_settings"));button(content,"Actualizar avisos",SOFT,this::refresh);
    }
    private void care(){
        label(elder()?"Mi círculo de cuidado":"Cuidamos en equipo",34,INK,true);paragraph(elder()?"Tú eliges quién puede acompañarte y acceder a tus datos.":"El acceso comienza con una invitación y la aprobación de la persona mayor.");
        if(elder()){
            LinearLayout invite=card(content);labelIn(invite,"Invita a alguien de confianza",24,INK,true);
            labelIn(invite,"Genera un código temporal de 4 dígitos. Compartirlo solo solicita acceso: tú debes aprobar a cada persona.",18,MUTED,false);
            button(invite,"Generar código",BLUE,()->run(session,(account)->ApiClient.call(this,account,"POST","/invitations",json()),result->{
                new AlertDialog.Builder(this).setTitle("Tu código: "+result.optString("code")).setMessage("Vence: "+formatTime(result.optString("expires_at"))+". Revisa el nombre de cada solicitante antes de aprobarlo.").setPositiveButton("Entendido",null).show();}));
            label("Solicitudes de acceso",23,INK,true);
            int pending=0;for(int i=0;i<requests.length();i++){JSONObject request=requests.optJSONObject(i);if(request==null||!"pending".equals(request.optString("status","pending")))continue;
                JSONObject user=request.optJSONObject("caregiver");String name=user==null?request.optString("display_name",request.optString("caregiver_name","Solicitante")):user.optString("display_name","Solicitante");
                String email=user==null?request.optString("email",""):user.optString("email","");String id=request.optString("id");LinearLayout card=card(content);labelIn(card,name,22,INK,true);if(!email.isEmpty())labelIn(card,email,17,MUTED,false);
                button(card,"Aprobar acceso",BLUE,()->confirm("¿Reconoces a "+name+"?","Podrá ver lecturas, alertas y ubicación consentida, y asignar recordatorios. Puedes revocar el acceso.",()->mutate("POST","/link-requests/"+id+"/approve",json(),"Cuidador vinculado.")));pending++;}
            if(pending==0)paragraph("No tienes solicitudes pendientes.");
            label("Personas vinculadas",23,INK,true);if(caregivers.length()==0)paragraph("Aún no has aprobado cuidadores.");
            for(int i=0;i<caregivers.length();i++){JSONObject user=caregivers.optJSONObject(i);if(user==null)continue;String id=user.optString("id"),name=user.optString("display_name","Cuidador");LinearLayout card=card(content);labelIn(card,name,22,INK,true);
                button(card,"Revocar acceso",SOFT,()->confirm("¿Revocar acceso a "+name+"?","Dejará de consultar tus datos y de recibir nuevos avisos. Las notificaciones ya entregadas no pueden retirarse de su memoria.",()->mutate("DELETE","/caregivers/"+id,null,"Acceso revocado.")));}
        }else{
            LinearLayout invite=card(content);labelIn(invite,"Vincular a una persona",24,INK,true);EditText code=input(invite,"Código de 4 dígitos","",InputType.TYPE_CLASS_NUMBER);code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
            button(invite,"Solicitar acceso",BLUE,()->{String value=code.getText().toString();if(!value.matches("[0-9]{4}")){toast("Introduce los 4 dígitos.");return;}mutate("POST","/link-requests",json("code",value),"Solicitud enviada. La persona mayor debe aprobarla.");});
            patientChooser();if(!patientId.isEmpty()){button(content,"Crear recordatorio de medicación",BLUE,this::reminderDialog);button(content,"Recordatorio para beber agua",SOFT,()->confirm("¿Crear un recordatorio de agua?","Un aviso en 5 minutos y cada 2 horas para recordar una pauta de hidratación ya acordada. No detecta deshidratación ni recomienda cantidades.",()->mutate("POST","/patients/"+patientId+"/reminders",json("title","Recordatorio de agua","dose","Según la pauta acordada","instructions","Recuerda la hidratación acordada. Este aviso no diagnostica deshidratación.","due_at",ApiSession.utc(System.currentTimeMillis()+300000),"repeat_hours",2),"Recordatorio creado.")));}
        }
        medicationCards(true);
    }
    private void medicationCards(boolean all){
        if(reminders.length()==0){if(all)paragraph("No hay recordatorios asignados.");return;}
        label("Recordatorios",23,INK,true);
        for(int i=0;i<reminders.length();i++){JSONObject reminder=reminders.optJSONObject(i);if(reminder==null)continue;
            LinearLayout card=card(content);labelIn(card,reminder.optString("title","Medicación"),24,INK,true);
            boolean cancelled=reminder.opt("cancelled_at") instanceof String;if(cancelled)labelIn(card,"CANCELADO",14,MUTED,true);
            labelIn(card,reminder.optString("dose","")+"  "+reminder.optString("instructions",""),19,INK,false);
            labelIn(card,(reminder.optBoolean("active")?"Próximo: ":"Programado: ")+formatTime(reminder.optString("due_at")),17,MUTED,false);
            if(reminder.opt("last_due_at") instanceof String)labelIn(card,"Último aviso: "+formatTime(reminder.optString("last_due_at")),17,BLUE,true);
            if(reminder.opt("taken_at") instanceof String)labelIn(card,"Última confirmación: "+formatTime(reminder.optString("taken_at")),16,MUTED,false);
            if(!cancelled&&elder()&&reminder.opt("last_due_at") instanceof String&&!(reminder.opt("taken_at") instanceof String)){String id=reminder.optString("id");button(card,"Ya lo tomé",BLUE,()->confirm("¿Confirmar la toma?","Solo registra lo que tú confirmas. El sistema no verifica ingestión ni prescribe dosis.",()->mutate("POST","/reminders/"+id+"/taken",json(),"Toma registrada.")));}
            if(!cancelled){String id=reminder.optString("id");button(card,"Cancelar recordatorio",SOFT,()->confirm("¿Cancelar este recordatorio?","No se enviarán nuevas ocurrencias. No modifica ninguna prescripción. Las notificaciones que ya llegaron pueden permanecer en otros teléfonos.",()->mutate("DELETE","/reminders/"+id,null,"Recordatorio cancelado.")));}
        }
    }
    private void reminderDialog(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),dp(8));
        EditText title=input(form,"Nombre del recordatorio","",InputType.TYPE_CLASS_TEXT),dose=input(form,"Dosis indicada por su profesional","",InputType.TYPE_CLASS_TEXT),
            instructions=input(form,"Instrucciones acordadas","",InputType.TYPE_CLASS_TEXT),minutes=input(form,"Primera vez, dentro de cuántos minutos","5",InputType.TYPE_CLASS_NUMBER),repeat=input(form,"Repetir cada cuántas horas (vacío = una vez)","",InputType.TYPE_CLASS_NUMBER);
        labelIn(form,"No prescribe ni verifica interacciones. Usa una pauta ya indicada y acordada con la persona mayor.",16,MUTED,false);
        ScrollView wrap=new ScrollView(this);wrap.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nuevo recordatorio").setView(wrap).setPositiveButton("Crear",null).setNegativeButton("Cancelar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            int delay=Integer.parseInt(minutes.getText().toString());if(delay<1||delay>525600||title.getText().toString().trim().isEmpty())throw new IllegalArgumentException();
            JSONObject body=json("title",title.getText().toString().trim(),"dose",dose.getText().toString().trim(),"instructions",instructions.getText().toString().trim(),"due_at",ApiSession.utc(System.currentTimeMillis()+delay*60000L));
            if(!repeat.getText().toString().isEmpty()){int hours=Integer.parseInt(repeat.getText().toString());if(hours<1||hours>720)throw new IllegalArgumentException();body.put("repeat_hours",hours);}
            dialog.dismiss();mutate("POST","/patients/"+patientId+"/reminders",body,"Recordatorio asignado.");
        }catch(Exception failure){toast("Revisa el nombre, los minutos y la repetición.");}}));dialog.show();
    }
    private void account(){
        label("Ajustes",34,INK,true);paragraph("A tu manera. Control en tus manos.");
        LinearLayout profile=card(content);LinearLayout identity=new LinearLayout(this);identity.setGravity(Gravity.CENTER_VERTICAL);profile.addView(identity);
        TextView avatar=text(initials(session.name),23,BLUE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(shape(SOFT,28));identity.addView(avatar,new LinearLayout.LayoutParams(dp(56),dp(56)));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams copyLp=new LinearLayout.LayoutParams(0,-2,1);copyLp.leftMargin=dp(16);identity.addView(copy,copyLp);
        labelIn(copy,session.name,22,INK,true);labelIn(copy,elder()?"Persona mayor":"Cuidador o cuidadora",16,MUTED,false);labelIn(profile,session.email,16,MUTED,false);
        sectionTitle("TU EXPERIENCIA");LinearLayout preferences=group(content);
        if(elder())settingsRow(preferences,"Configurar mi wearable","Conexión guiada, a tu ritmo",R.drawable.ic_watch,()->{deviceStep=0;openPage("device");});
        settingsRow(preferences,"Notificaciones","Avisos y permisos del teléfono",R.drawable.ic_nav_alerts,()->openPage("notification_settings"));
        if(elder())settingsRow(preferences,"Privacidad","Ubicación y sonido, si tú lo decides",R.drawable.ic_shield,()->openPage("privacy"));
        sectionTitle("CONEXIÓN Y CUENTA");LinearLayout account=group(content);
        settingsRow(account,"Conexión con tu servidor","Administrar la conexión de tu equipo",R.drawable.ic_bluetooth,()->openPage("connection"));
        settingsRow(account,"Tu información, protegida","Sesión, almacenamiento y seguridad",R.drawable.ic_lock,()->openPage("security"));
        textAction(content,"Cerrar sesión",()->confirm("¿Cerrar sesión?","Se detendrán el wearable, ubicación y audio. La cola cifrada queda vinculada a esta cuenta para un acceso posterior.",this::logout),true).setTextColor(RED);
        labelIn(content,"CareMonitor 1.0.0\nSin validación clínica. No garantiza vigilancia continua ni asistencia de emergencia.",15,MUTED,false);
    }
    private void notificationSettings(){
        paragraph("Los avisos de tu círculo, como tú prefieras.");
        LinearLayout delivery=card(content);labelIn(delivery,"Canal de avisos",23,INK,true);labelIn(delivery,NotificationChannelService.status(),17,BLUE,true);
        labelIn(delivery,"Al activarlo se mantiene una conexión segura con tu servidor y una notificación visible para detenerlo.",17,MUTED,false);
        button(delivery,"Activar avisos locales",BLUE,this::enableChannel);
        button(delivery,"Detener canal de avisos",SOFT,()->{stopService(new Intent(this,NotificationChannelService.class));toast("Canal detenido. El buzón permanece en el servidor.");});
        LinearLayout permissions=card(content);labelIn(permissions,"Permisos del teléfono",23,INK,true);
        button(permissions,"Permitir notificaciones",SOFT,this::notificationPermission);
        button(permissions,"Permitir red local",SOFT,()->{if(!ApiClient.lanAllowed(this)){pendingAction="network";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);}else toast("El permiso de red local ya está concedido.");});
        paragraph("Si el canal está desactivado, la app consulta cada 15 segundos abierta y Android programa un respaldo de al menos 15 minutos en segundo plano. La red, batería y cierre forzado pueden interrumpirlo. No hay entrega garantizada. Cada cuidador tiene su propio buzón.");
    }
    private void securityDetails(){
        paragraph("Información clara sobre cómo se protegen tus datos.");LinearLayout security=card(content);labelIn(security,"Tu información, protegida",23,INK,true);
        labelIn(security,"La sesión y la cola de lecturas se cifran con una clave de AndroidKeyStore. HTTPS verifica certificado y nombre del servidor. Cada cuenta accede solo al círculo autorizado. Una cola pendiente nunca se enviará desde otra cuenta.",17,MUTED,false);
        labelIn(security,"No se capturan grabaciones ni se envía audio. El servidor recibe puntos GPS solo fuera del área de casa cuando tú activas la función.",17,MUTED,false);
        labelIn(security,"Sesión hasta: "+formatTime(session.expires),16,MUTED,false);
    }
    private void serverSettings(){
        LinearLayout connection=card(content);labelIn(connection,"Conexión con tu servidor",23,INK,true);
        labelIn(connection,"La app usa la conexión preparada para tu equipo. Cambia esta dirección solo si administras el servidor.",17,MUTED,false);
        serverInput=input(connection,"Servidor HTTPS",session.base,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        labelIn(connection,"La dirección debe terminar en /api/v1 y coincidir con su certificado. Al cambiarla volverás a iniciar sesión; las lecturas pendientes seguirán ligadas al servidor anterior.",16,MUTED,false);
        button(connection,"Guardar conexión",BLUE,()->{
            final String base;try{base=ApiClient.base(serverInput.getText().toString().trim());}catch(Exception failure){toast(failure.getMessage());return;}
            if(base.equals(session.base)){toast("Ya estás usando esta conexión.");return;}
            confirm("¿Cambiar de servidor?","Se cerrará tu sesión y se detendrán el wearable y las funciones locales. No se enviarán tus credenciales ni lecturas al nuevo servidor hasta que inicies sesión allí.",()->{
                if(!getSharedPreferences("caremonitor_endpoint",0).edit().putString("previous_base",session.base).putString("base",base).commit()){toast("No se pudo guardar la conexión.");return;}
                logout();notice="Conexión guardada. Inicia sesión en tu servidor.";render();
            });
        });
    }
    private JSONObject privacyPolicy(){return privacyPolicy(session);}
    private JSONObject privacyPolicy(ApiSession account){try{String saved=SecureStore.read(this,"privacy_"+account.id+".enc",4096);return saved.isEmpty()?new JSONObject():new JSONObject(saved);}catch(Exception failure){return new JSONObject();}}
    private void privacy(){
        JSONObject policy=privacyPolicy();
        LinearLayout location=card(content);labelIn(location,"Ubicación fuera de casa",24,INK,true);
        labelIn(location,"Solo si lo decides. Configura el centro de casa y un radio de privacidad de al menos 100 m. Se usa la ubicación del teléfono y su precisión: no representa la ubicación exacta de tu cuerpo.",18,MUTED,false);
        labelIn(location,"Consentimiento del servidor: "+(consents.optBoolean("location")?"activo":"desactivado"),16,MUTED,false);
        if(policy.has("home_lat"))labelIn(location,"Casa configurada · radio "+policy.optInt("radius_m",200)+" m",17,BLUE,true);
        button(location,"Configurar casa",SOFT,this::homeDialog);
        button(location,"Activar ubicación fuera de casa",BLUE,()->{
            if(!policy.has("home_lat")){toast("Configura primero el área de casa.");return;}
            confirm("¿Compartir ubicación fuera de casa?","Tus cuidadores verán los puntos enviados cuando el teléfono esté fuera del radio. No se envían puntos dentro de casa. Puedes detenerlo en la notificación.",()->setConsent("location",true,()->requestFeature("location")));});
        button(location,"Revocar ubicación",SOFT,()->setConsent("location",false,null));
        LinearLayout audio=card(content);labelIn(audio,"Sonido local, opcional",24,INK,true);
        labelIn(audio,"Mide intensidad de sonido en memoria para preguntarte si estás bien ante un sonido fuerte. No reconoce accidentes, voces ni caídas. No graba, no guarda y no transmite audio. Mantiene una notificación visible.",18,MUTED,false);
        labelIn(audio,"Consentimiento del servidor: "+(consents.optBoolean("audio")?"activo":"desactivado"),16,MUTED,false);
        button(audio,"Activar señal local de sonido",BLUE,()->confirm("¿Activar el micrófono local?","Se procesarán breves muestras solo en RAM. Un sonido fuerte mostrará una pregunta; tú decides si envías un aviso. La escucha termina al pulsar Detener.",()->setConsent("audio",true,()->requestFeature("audio"))));
        button(audio,"Revocar micrófono",SOFT,()->setConsent("audio",false,null));
        button(content,"Detener ubicación y micrófono",SOFT,()->{stopService(new Intent(this,PrivacySignalService.class));toast("Funciones locales detenidas.");});
    }
    private void homeDialog(){
        JSONObject policy=privacyPolicy();LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),0,dp(24),0);
        EditText lat=input(form,"Latitud de casa",policy.has("home_lat")?String.valueOf(policy.optDouble("home_lat")):"",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED),
            lon=input(form,"Longitud de casa",policy.has("home_lon")?String.valueOf(policy.optDouble("home_lon")):"",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED),
            radius=input(form,"Radio de privacidad en metros",String.valueOf(policy.optInt("radius_m",200)),InputType.TYPE_CLASS_NUMBER);
        labelIn(form,"Estas coordenadas permanecen cifradas en tu teléfono.",16,MUTED,false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Tu área de casa").setView(form).setPositiveButton("Guardar",null).setNegativeButton("Cancelar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{double a=Double.parseDouble(lat.getText().toString()),b=Double.parseDouble(lon.getText().toString());int r=Integer.parseInt(radius.getText().toString());
            if(!Double.isFinite(a)||!Double.isFinite(b)||a< -90||a>90||b< -180||b>180||r<100||r>10000)throw new IllegalArgumentException();
            policy.put("home_lat",a).put("home_lon",b).put("radius_m",r);SecureStore.write(this,"privacy_"+session.id+".enc",policy.toString());dialog.dismiss();render();
        }catch(Exception failure){toast("Usa coordenadas válidas y un radio de 100 a 10 000 m.");}}));dialog.show();
    }
    private void setConsent(String feature,boolean enabled,Runnable after){
        if(!enabled)stopService(new Intent(this,PrivacySignalService.class));
        ApiSession account=session;
        run(account,a->{JSONObject server=ApiClient.call(this,a,"GET","/consents",null);JSONObject body=json("location",server.optBoolean("location"),"audio",server.optBoolean("audio"));body.put(feature,enabled);
            JSONObject result=ApiClient.call(this,a,"POST","/consents",body);JSONObject policy=privacyPolicy(a);policy.put(feature+"_consent",enabled);SecureStore.write(this,"privacy_"+a.id+".enc",policy.toString());return result;
        },result->{consents=result;notice=enabled?"Consentimiento registrado.":"Consentimiento revocado; funciones locales detenidas.";render();if(after!=null)after.run();});
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
        pendingAction="";try{Intent intent=new Intent(this,PrivacySignalService.class).setAction(feature);if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);toast("Función iniciada. Puedes detenerla en la notificación.");}
        catch(RuntimeException failure){toast("No se pudo iniciar. Revisa los permisos y mantén la app abierta al activarla.");}
    }
    private void device(){
        if(!elder()){label("Conexión del wearable",32,INK,true);paragraph("Solo la persona mayor conecta el wearable desde su propia cuenta.");return;}
        progress(deviceStep,4,"Conectar mi wearable");
        if(deviceStep==0){
            label("Vamos paso a paso",32,INK,true);paragraph("Prepara tu wearable y mantén el teléfono cerca.");
            illustration(R.drawable.ic_watch,"Wearable junto al teléfono");
            LinearLayout tips=card(content);labelIn(tips,"1. Enciende el wearable",22,INK,true);labelIn(tips,"2. Comprueba que tenga batería",22,INK,true);labelIn(tips,"3. Déjalo cerca de tu teléfono",22,INK,true);
            button(content,"Ya lo tengo listo",BLUE,()->{deviceStep=1;render();});
        }else if(deviceStep==1){
            label("Activa la conexión",32,INK,true);paragraph("Abre Bluetooth y empareja tu wearable. Comprueba que la dirección y el código coincidan con tu dispositivo.");
            illustration(R.drawable.ic_bluetooth,"Emparejamiento Bluetooth");
            button(content,"Abrir Bluetooth",BLUE,()->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
            paragraph("Cuando termines, vuelve aquí. Si ya estaba emparejado, puedes continuar.");
            button(content,"Continuar",BLUE,()->{deviceStep=2;render();});
        }else if(deviceStep==2){
            label("Elige tu wearable",32,INK,true);paragraph("Pulsa Buscar y selecciona tu dispositivo en la lista. El permiso de dispositivos cercanos se pide ahora.");
            LinearLayout status=card(content);connectionLabel=labelIn(status,"Sin conexión",22,INK,true);readingLabel=labelIn(status,"",17,MUTED,false);
            button(content,"Buscar mi wearable",BLUE,()->bluetooth("scan"));
            button(content,"Detener y volver a buscar",SOFT,()->{if(monitor!=null)monitor.stopMonitoring();updateDevice();});
            deviceList=new LinearLayout(this);deviceList.setOrientation(LinearLayout.VERTICAL);content.addView(deviceList);
            button(content,"Comprobar conexión",SOFT,()->{if(monitor==null||!monitor.isFresh()){toast("Aún no llegan lecturas recientes. Revisa que el wearable esté encendido y seleccionado.");return;}deviceStep=3;render();});
        }else{
            boolean fresh=monitor!=null&&monitor.isFresh();
            setupStatusIcon=illustration(fresh?R.drawable.ic_status_ok:R.drawable.ic_status_unknown,"Estado de la conexión");
            deviceHeading=labelIn(content,fresh?"Tu wearable está conectado":"Esperando nuevas lecturas",32,INK,true);
            paragraph("Aquí puedes comprobar si llegan muestras recientes y volver a tu inicio.");
            LinearLayout status=card(content);connectionLabel=labelIn(status,"",22,INK,true);readingLabel=labelIn(status,"",17,MUTED,false);
            paragraph("Recibir muestras en el teléfono no confirma su entrega al servidor ni que los valores sean válidos.");
            button(content,"Ir a mi inicio",BLUE,()->{page="home";render();refresh();});
            button(content,"Detener wearable",SOFT,()->{if(monitor!=null)monitor.stopMonitoring();deviceStep=2;render();});
            button(content,"Borrar lecturas pendientes",SOFT,()->confirm("¿Borrar la cola pendiente?","Las muestras no entregadas se perderán de forma permanente en este teléfono.",()->{if(monitor!=null)monitor.clearUploadQueue();}));
        }
        if(deviceStep>0)button(content,"Atrás",SOFT,this::goBack);
        if(deviceStep<3)button(content,"Lo haré más tarde",SOFT,()->{page="home";render();refresh();});
        updateDevice();
    }
    private void bluetooth(String action){
        if(!elder()){pendingAction="";toast("El wearable se conecta desde la cuenta de la persona mayor.");return;}
        pendingAction=action;
        if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},711);return;}
        if(Build.VERSION.SDK_INT<31&&Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},711);return;}
        try{BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager.getAdapter();
            if(adapter==null){toast("Este teléfono no tiene Bluetooth disponible.");return;}if(!adapter.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),712);return;}
            pendingAction="";if(monitor==null){toast("La conexión se está preparando. Inténtalo de nuevo.");return;}monitor.setTransport("spp");monitor.scan();updateDevice();
        }catch(SecurityException failure){toast("Permite dispositivos cercanos para continuar.");}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);
        boolean granted=results.length>0;for(int result:results)granted&=result==PackageManager.PERMISSION_GRANTED;
        if(code==710){String feature=pendingAction;pendingAction="";if(granted)startFeature(feature);else toast("Permiso no concedido. La función permanece detenida.");}
        if(code==711){if(granted)bluetooth(pendingAction);else{pendingAction="";toast("Bluetooth necesita permiso de dispositivos cercanos.");}}
        if(code==713){String action=pendingAction;pendingAction="";if(granted&&"channel".equals(action))enableChannel();else if(!granted)toast("Sin permiso de avisos. Puedes leer el buzón en la app.");}
        if(code==714){String action=pendingAction;pendingAction="";if(granted){if(action.equals("auth"))authenticate();else if(action.equals("channel"))enableChannel();else refresh();}
            else{notice="El servidor local necesita permiso de dispositivos cercanos/red local. Puedes concederlo en los ajustes de la app.";toast(notice);}}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==712&&result==RESULT_OK)bluetooth(pendingAction);}
    private void updateDevice(){
        if(!visible&&connectionLabel==null)return;
        if(deviceHeading!=null){boolean fresh=monitor!=null&&monitor.isFresh();deviceHeading.setText(fresh?"Tu wearable está conectado":"Esperando nuevas lecturas");
            if(setupStatusIcon!=null)setupStatusIcon.setImageResource(fresh?R.drawable.ic_status_ok:R.drawable.ic_status_unknown);}
        if(connectionLabel!=null)connectionLabel.setText(monitor==null?"Wearable sin conexión":monitor.bluetoothState());
        if(readingLabel!=null&&monitor!=null)readingLabel.setText(monitor.relayState()+"\n"+monitor.packetCount()+" muestras · "+monitor.queuedCount()+" pendientes"+(monitor.error().isEmpty()?"":"\n"+monitor.error()));
        if(deviceList!=null&&monitor!=null&&elder()){deviceList.removeAllViews();List<LiveMonitorService.Device> devices=monitor.devices();
            if(devices.isEmpty())labelIn(deviceList,"Cuando busques, tus dispositivos emparejados aparecerán aquí.",17,MUTED,false);
            for(LiveMonitorService.Device device:devices){
            button(deviceList,"Wearable CareMonitor\n"+device.address,SOFT,()->{try{
                if(!elder())return;
                Intent intent=new Intent(this,LiveMonitorService.class).setAction(LiveMonitorService.ACTION_CONNECT).putExtra("address",device.address).putExtra("transport","spp");
                if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            }catch(RuntimeException failure){toast("No se pudo iniciar Bluetooth. Revisa permisos.");}});}}
    }
    private void enableChannel(){
        if(session==null){toast("Inicia sesión primero.");return;}
        if(!ApiClient.lanAllowed(this)){pendingAction="channel";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){pendingAction="channel";requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},713);return;}
        if(!((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled()){
            toast("Activa las notificaciones en los ajustes para usar el canal local.");
            startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));return;
        }
        try{Intent intent=new Intent(this,NotificationChannelService.class).setAction("start");if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            toast("Canal local iniciado. Puedes detenerlo en la notificación.");}
        catch(RuntimeException failure){toast("Android no permitió iniciar el canal. Abre la app y revisa sus permisos.");}
    }
    private void notificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},713);else toast("Puedes revisar los avisos en los ajustes del sistema.");}
    private void sendEvent(String type){mutate("POST","/events",json("type",type,"confirmed",true,"occurred_at",ApiSession.utc(System.currentTimeMillis()),"source","device_diagnostic"),"Solicitud registrada.");}
    private void logout(){
        ApiSession old=session;epoch++;refreshing=false;session=null;busy=false;registering=false;setup=new AccountSetup();acknowledged=false;pendingAction="";deviceStep=0;page="home";notice="Sesión cerrada.";patientId="";overview=new JSONObject();patients=new JSONArray();requests=new JSONArray();caregivers=new JSONArray();reminders=new JSONArray();notifications=new JSONArray();consents=new JSONObject();
        if(monitor!=null)monitor.stopMonitoring();stopService(new Intent(this,PrivacySignalService.class));ApiSession.clear(this);render();
        if(old!=null)network.execute(()->{try{ApiClient.call(this,old,"POST","/auth/logout",json());}catch(Exception ignored){}});
    }
    private interface Request{JSONObject call(ApiSession account)throws Exception;}
    private interface Success{void accept(JSONObject result);}
    private void run(ApiSession account,Request request,Success success){
        if(account==null)return;final long capture=epoch;
        network.execute(()->{try{JSONObject result=request.call(account);main.post(()->{if(capture==epoch&&session!=null&&session.id.equals(account.id))success.accept(result);});}
            catch(Exception failure){main.post(()->{if(capture!=epoch)return;if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==401){logout();notice="Tu sesión venció. Vuelve a iniciar sesión.";render();}
                else{notice=failure.getMessage();toast(notice);}});}});
    }
    private void mutate(String method,String path,JSONObject body,String message){run(session,account->ApiClient.call(this,account,method,path,body),result->{notice=message;refresh();render();});}
    private void refresh(){
        if(refreshing||session==null||!ApiClient.lanAllowed(this))return;if(!session.valid()){logout();notice="Tu sesión venció. Vuelve a iniciar sesión.";render();return;}
        refreshing=true;ApiSession account=session;long capture=epoch;String chosen=patientId;
        network.execute(()->{try{
            JSONObject p=ApiClient.call(this,account,"GET","/patients",null);JSONArray list=p.optJSONArray("patients");if(list==null)list=new JSONArray();
            String selected=chosen;boolean exists=false;for(int i=0;i<list.length();i++)if(selected.equals(list.optJSONObject(i).optString("id")))exists=true;
            if(!exists)selected=list.length()>0?list.getJSONObject(0).getString("id"):"";
            JSONObject view=selected.isEmpty()?new JSONObject():ApiClient.call(this,account,"GET","/patients/"+selected+"/overview",null);
            JSONArray meds=selected.isEmpty()?new JSONArray():ApiClient.call(this,account,"GET","/patients/"+selected+"/reminders",null).optJSONArray("reminders");
            JSONObject c=account.elder()?ApiClient.call(this,account,"GET","/consents",null):new JSONObject();
            JSONArray req=account.elder()?ApiClient.call(this,account,"GET","/link-requests",null).optJSONArray("requests"):new JSONArray();
            JSONArray care=account.elder()?ApiClient.call(this,account,"GET","/caregivers",null).optJSONArray("caregivers"):new JSONArray();
            JSONArray inbox=ApiClient.call(this,account,"GET","/notifications?after=0",null).optJSONArray("items");
            try{NotificationSync.sync(this);}catch(Exception ignored){}
            final JSONArray patientList=list,medicine=meds,pending=req,carers=care,messages=inbox;final String id=selected;
            main.post(()->{if(capture!=epoch||session==null||!session.id.equals(account.id))return;refreshing=false;
                patients=patientList;patientId=id;overview=view;reminders=medicine==null?new JSONArray():medicine;requests=pending==null?new JSONArray():pending;
                caregivers=carers==null?new JSONArray():carers;notifications=messages==null?new JSONArray():messages;consents=c;
                // Preserve forms and scroll position; connection labels update independently of sensor cadence.
                if(!page.equals("device")&&!page.equals("account")&&!page.equals("ready")&&!isSettingsDetail()){int y=scroll==null?0:scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));}
            });
        }catch(Exception failure){main.post(()->{if(capture!=epoch)return;refreshing=false;
            if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==401){logout();notice="Tu sesión venció o fue revocada. Inicia sesión otra vez.";render();}
            else{notice=failure.getMessage();if(visible)toast(notice);}});}});
    }
    private void confirm(String title,String body,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Confirmar",(d,w)->action.run()).setNegativeButton("Cancelar",null).show();}
    private void toast(String message){Toast.makeText(this,message==null?"No se pudo completar la acción.":message,Toast.LENGTH_LONG).show();}
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
    private String formatTime(String value){long time=ApiSession.expiryMs(value);return time==0?value:android.text.format.DateFormat.getDateFormat(this).format(new Date(time))+" "+android.text.format.DateFormat.getTimeFormat(this).format(new Date(time));}
}
