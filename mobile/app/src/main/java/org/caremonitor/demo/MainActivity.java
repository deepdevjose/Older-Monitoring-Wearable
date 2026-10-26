package org.caremonitor.demo;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Accessible native companion. Accounts, authorisation and alert decisions belong to the server. */
public final class MainActivity extends Activity {
    private static final int BG=0xFFF5F5F7,INK=0xFF1D1D1F,MUTED=0xFF62636B,BLUE=0xFF0066CC,LINE=0xFFE4E5EA,SOFT=0xFFEAF2FF,RED=0xFFB52B33;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private ApiSession session;
    private LinearLayout root,content;
    private ScrollView scroll;
    private String page="home",patientId="",notice="",pendingAction="";
    private JSONArray patients=new JSONArray(),requests=new JSONArray(),caregivers=new JSONArray(),reminders=new JSONArray(),notifications=new JSONArray();
    private JSONObject overview=new JSONObject(),consents=new JSONObject();
    private boolean visible,bound,busy,registering,refreshing;
    private long epoch;
    private LiveMonitorService monitor;
    private TextView connectionLabel,readingLabel;
    private LinearLayout deviceList;
    private EditText emailInput,passwordInput,nameInput,serverInput;
    private Spinner roleInput;
    private CheckBox termsCheck;
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
        if(state!=null){page=state.getString("page","home");patientId=state.getString("patient","");}
        bound=bindService(new Intent(this,LiveMonitorService.class),serviceConnection,BIND_AUTO_CREATE);
        if(Build.VERSION.SDK_INT>=33){android.window.OnBackInvokedCallback callback=()->{page="home";render();};
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);backCallback=callback;}
        if(getIntent().getBooleanExtra("show_alerts",false)){page="alerts";patientId=getIntent().getStringExtra("patient_id")==null?patientId:getIntent().getStringExtra("patient_id");}
        render();handleSoundIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("show_alerts",false)){page="alerts";patientId=intent.getStringExtra("patient_id")==null?patientId:intent.getStringExtra("patient_id");epoch++;refreshing=false;render();refresh();}handleSoundIntent(intent);}
    private void handleSoundIntent(Intent intent){if(intent.getBooleanExtra("sound_check",false)&&session!=null&&session.elder()){
        intent.removeExtra("sound_check");new AlertDialog.Builder(this).setTitle("¿Te encuentras bien?")
            .setMessage("Se observó sonido fuerte en el teléfono. Esta señal no identifica caídas ni accidentes. Puedes enviar un aviso a tu círculo de cuidado.")
            .setPositiveButton("Enviar aviso",(d,w)->sendEvent("audio_impact")).setNegativeButton("Estoy bien",null).show();}}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("page",page);state.putString("patient",patientId);}
    @Override protected void onStart(){super.onStart();visible=true;main.post(poll);}
    @Override protected void onStop(){visible=false;main.removeCallbacks(poll);super.onStop();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);if(monitor!=null)monitor.removeListener(liveListener);if(bound)unbindService(serviceConnection);
        network.shutdownNow();if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)backCallback);super.onDestroy();}
    private final Runnable poll=new Runnable(){public void run(){if(visible&&session!=null){refresh();}if(visible)main.postDelayed(this,15000);}};
    private boolean elder(){return session!=null&&session.elder();}
    private JSONObject json(Object... entries){JSONObject object=new JSONObject();try{for(int i=0;i<entries.length;i+=2)object.put((String)entries[i],entries[i+1]);}catch(Exception failure){throw new IllegalArgumentException(failure);}return object;}
    private void render(){
        connectionLabel=null;readingLabel=null;deviceList=null;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=23)root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        else root.setFitsSystemWindows(true);
        setContentView(root);
        scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(20),dp(24),dp(24));scroll.addView(content);
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setPadding(0,0,0,dp(24));content.addView(brand);
        TextView mark=text("✚",24,BLUE,true);mark.setGravity(Gravity.CENTER);mark.setBackground(shape(SOFT,18));brand.addView(mark,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView word=text("CareMonitor",21,INK,true);LinearLayout.LayoutParams wordLp=new LinearLayout.LayoutParams(0,-2,1);wordLp.leftMargin=dp(12);brand.addView(word,wordLp);
        if(session==null){auth();return;}
        if(!notice.isEmpty()){TextView message=text(notice,16,MUTED,false);message.setPadding(0,0,0,dp(16));content.addView(message);}
        if(page.equals("home"))home();else if(page.equals("alerts"))alerts();else if(page.equals("care"))care();else if(page.equals("device"))device();else account();
        navigation();
    }
    private void auth(){
        label(registering?"Un cuidado más cercano.":"Tu círculo, conectado.",34,INK,true);
        paragraph("Sensores, recordatorios y compañía en un solo lugar.");
        LinearLayout card=card(content);labelIn(card,registering?"Crear cuenta":"Te damos la bienvenida",23,INK,true);
        serverInput=input(card,"Servidor HTTPS",ApiSession.savedBase(this),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        emailInput=input(card,"Correo electrónico","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        passwordInput=input(card,"Contraseña","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);passwordInput.setSaveEnabled(false);
        if(registering){
            nameInput=input(card,"Nombre para mostrar","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            labelIn(card,"¿Cómo usarás CareMonitor?",17,INK,true);
            roleInput=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Soy la persona mayor","Soy cuidador o cuidadora"});
            roleInput.setAdapter(adapter);roleInput.setMinimumHeight(dp(56));card.addView(roleInput,new LinearLayout.LayoutParams(-1,dp(60)));
            termsCheck=new CheckBox(this);termsCheck.setText("Entiendo las limitaciones de las lecturas. Usaré datos de prueba y no tomaré decisiones médicas con esta app.");
            termsCheck.setTextColor(INK);termsCheck.setTextSize(17);termsCheck.setMinHeight(dp(56));card.addView(termsCheck);
        }
        button(card,busy?"Conectando…":registering?"Crear mi cuenta":"Iniciar sesión",BLUE,()->authenticate());
        button(card,registering?"Ya tengo cuenta":"Crear una cuenta",SOFT,()->{registering=!registering;notice="";render();});
        paragraph("CareMonitor no sustituye atención médica ni servicios de emergencia. Las lecturas pueden ser incompletas.");
        if(!notice.isEmpty())label(notice,17,RED,false);
        paragraph("La dirección y el certificado deben coincidir. Tu contraseña y sesión viajan cifradas por HTTPS.");
    }
    private void authenticate(){
        if(busy)return;
        if(!ApiClient.lanAllowed(this)){pendingAction="auth";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);return;}
        final String base,email,password,name,role;
        try{base=ApiClient.base(serverInput.getText().toString().trim());email=emailInput.getText().toString().trim();password=passwordInput.getText().toString();
            name=registering?nameInput.getText().toString().trim():"";role=registering&&roleInput.getSelectedItemPosition()==1?"caregiver":"elder";
            if(!email.contains("@")||password.length()<12)throw new IllegalArgumentException("Usa un correo válido y una contraseña de al menos 12 caracteres.");
            if(registering&&(name.isEmpty()||!termsCheck.isChecked()))throw new IllegalArgumentException("Indica tu nombre y acepta las condiciones de uso.");
        }catch(Exception failure){toast(failure.getMessage());return;}
        busy=true;final boolean registration=registering;notice="Conectando por HTTPS…";
        passwordInput.setText("");final long capture=++epoch;
        network.execute(()->{try{
            JSONObject body=json("email",email,"password",password);
            if(registration){body.put("display_name",name);body.put("role",role);body.put("educational_ack",true);}
            JSONObject response=ApiClient.request(this,base,"","POST",registration?"/auth/register":"/auth/login",body);
            ApiSession saved=ApiSession.save(this,base,response);
            main.post(()->{if(capture!=epoch)return;busy=false;session=saved;page="home";patientId="";notice="";
                NotificationSync.schedule(this);notificationPermission();if(monitor!=null)monitor.refreshSession();render();refresh();});
        }catch(Exception failure){main.post(()->{if(capture!=epoch)return;busy=false;notice=failure.getMessage();render();});}});
    }
    private void navigation(){
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setPadding(dp(8),dp(8),dp(8),dp(8));nav.setBackgroundColor(Color.WHITE);root.addView(nav);
        String[] labels={"Hoy","Avisos","Cuidado","Cuenta"},pages={"home","alerts","care","account"};
        for(int i=0;i<labels.length;i++){final String target=pages[i];TextView item=text(labels[i],16,page.equals(target)?BLUE:MUTED,page.equals(target));item.setGravity(Gravity.CENTER);
            item.setMinHeight(dp(56));item.setBackground(shape(page.equals(target)?SOFT:Color.WHITE,16));item.setOnClickListener(v->{page=target;render();refresh();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(56),1);lp.leftMargin=dp(3);lp.rightMargin=dp(3);nav.addView(item,lp);}
    }
    private void home(){
        label(elder()?"Hola, "+session.name.split(" ")[0]+".":"Cuidar empieza aquí.",34,INK,true);
        paragraph(elder()?"Tu día, con tranquilidad y compañía.":"Una vista clara de las personas que acompañas.");
        if(!elder())patientChooser();
        LinearLayout hero=card(content);hero.setBackground(shape(0xFF172D48,28));
        labelIn(hero,elder()?"Mi bienestar":"Bienestar de "+patientName(),14,0xFFBFDBFF,true);
        String state=overview.optJSONObject("freshness")==null?"no_data":overview.optJSONObject("freshness").optString("state");
        labelIn(hero,state.equals("fresh")?"Datos recientes":state.equals("stale")?"Datos antiguos":"Esperando conexión",28,Color.WHITE,true);
        labelIn(hero,state.equals("fresh")?"Lecturas del wearable · no son una valoración médica.":"La ausencia de datos no significa que todo esté bien.",17,0xFFD4DFED,false);
        if(elder()){
            connectionLabel=labelIn(hero,monitor==null?"Wearable sin conexión":monitor.bluetoothState(),17,Color.WHITE,false);
            button(hero,"Conectar mi wearable",Color.WHITE,()->{page="device";render();});
        }else if(patientId.isEmpty())labelIn(hero,"Pide el código de vinculación a la persona mayor.",17,Color.WHITE,false);
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
        LinearLayout card=card(content);labelIn(card,"Lecturas del wearable",23,INK,true);
        if(raw==null){labelIn(card,"Sin lecturas disponibles",20,MUTED,false);return;}
        JSONObject optical=raw.optJSONObject("max30102"),temp=raw.optJSONObject("gy906"),light=raw.optJSONObject("photoresistor"),imu=raw.optJSONObject("mpu6050");
        boolean valid=optical!=null&&"ok".equals(optical.optString("status"))&&!Boolean.FALSE.equals(optical.opt("online"))&&!Boolean.FALSE.equals(optical.opt("finger_detected"));
        metric(card,"Pulso",valid&&optical.optBoolean("heart_rate_valid")?numeric(optical,"heart_rate_bpm"," lpm"):"Sin lectura válida");
        metric(card,"Oxígeno estimado",valid&&optical.optBoolean("spo2_valid")?numeric(optical,"spo2_percent"," %"):"Sin lectura válida");
        boolean tempValid=temp!=null&&!Boolean.FALSE.equals(temp.opt("online"))&&(!temp.has("status")||temp.optString("status").equals("ok"));
        metric(card,"Superficie",tempValid?numeric(temp,"object_temp_c"," °C"):"No disponible");
        metric(card,"Ambiente",tempValid?numeric(temp,"ambient_temp_c"," °C"):"No disponible");
        metric(card,"Luz / contacto",light!=null&&light.opt("adc_raw") instanceof Number?"ADC "+light.optInt("adc_raw")+" · sin calibrar":"No disponible");
        metric(card,"Movimiento",imu!=null&&!Boolean.FALSE.equals(imu.opt("online"))?"Señal disponible":"No disponible");
        labelIn(card,"Las lecturas inválidas se conservan como inválidas; nunca se convierten en valores normales.",16,MUTED,false);
    }
    private String numeric(JSONObject object,String key,String suffix){Object value=object.opt(key);if(!(value instanceof Number))return "No disponible";double number=((Number)value).doubleValue();return Double.isNaN(number)||Double.isInfinite(number)?"No disponible":String.format(Locale.getDefault(),"%.1f",number)+suffix;}
    private void metric(LinearLayout parent,String title,String value){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(14),0,dp(14));parent.addView(row);
        TextView titleView=text(title,18,MUTED,false);row.addView(titleView,new LinearLayout.LayoutParams(0,-2,1));TextView valueView=text(value,19,INK,true);valueView.setGravity(Gravity.END);valueView.setMaxWidth(dp(185));row.addView(valueView);
        View line=new View(this);line.setBackgroundColor(LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
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
        LinearLayout delivery=card(content);labelIn(delivery,"Cómo llegan los avisos",22,INK,true);
        labelIn(delivery,"Activa el canal local para mantener una conexión HTTPS con tu servidor y recibir nuevos avisos sin servicios externos. Su notificación permite detenerlo. Si está desactivado, la app consulta cada 15 segundos abierta y Android programa un respaldo de al menos 15 minutos en segundo plano. La red, batería y cierre forzado pueden interrumpirlo. No hay entrega garantizada. Todos los cuidadores tienen su propio buzón.",17,MUTED,false);
        labelIn(delivery,NotificationChannelService.status(),17,BLUE,true);
        button(delivery,"Activar avisos locales",BLUE,this::enableChannel);
        button(delivery,"Detener canal de avisos",SOFT,()->{stopService(new Intent(this,NotificationChannelService.class));toast("Canal detenido. El buzón permanece en el servidor.");});
        button(delivery,"Permitir notificaciones",SOFT,this::notificationPermission);button(content,"Actualizar avisos",BLUE,this::refresh);
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
        label("A tu manera",34,INK,true);paragraph("Permisos claros. Control en tus manos.");
        LinearLayout profile=card(content);labelIn(profile,session.name,26,INK,true);labelIn(profile,session.email+"\n"+(elder()?"Persona mayor":"Cuidador o cuidadora"),18,MUTED,false);
        labelIn(profile,"Servidor: "+session.base,15,MUTED,false);labelIn(profile,"Sesión hasta: "+formatTime(session.expires),15,MUTED,false);
        if(elder())privacy();
        LinearLayout security=card(content);labelIn(security,"Tu información, protegida",23,INK,true);
        labelIn(security,"La sesión y la cola de lecturas se cifran con una clave de AndroidKeyStore. HTTPS verifica certificado y nombre del servidor. Cada cuenta accede solo al círculo autorizado. Una cola pendiente nunca se enviará desde otra cuenta.",17,MUTED,false);
        labelIn(security,"No se capturan grabaciones ni se envía audio. El servidor recibe puntos GPS solo fuera del área de casa cuando tú activas la función.",17,MUTED,false);
        labelIn(security,NotificationChannelService.status(),17,BLUE,true);
        button(security,"Activar avisos locales",BLUE,this::enableChannel);
        button(security,"Detener canal de avisos",SOFT,()->{stopService(new Intent(this,NotificationChannelService.class));toast("Canal detenido.");});
        button(security,"Permitir red local",SOFT,()->{if(!ApiClient.lanAllowed(this)){pendingAction="network";requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},714);}else toast("El permiso de red local ya está concedido.");});
        button(security,"Permitir notificaciones",SOFT,this::notificationPermission);
        button(content,"Cerrar sesión",SOFT,()->confirm("¿Cerrar sesión?","Se detendrán el wearable, ubicación y audio. La cola cifrada queda vinculada a esta cuenta para un acceso posterior.",this::logout));
        paragraph("CareMonitor 1.0.0 · Android nativo\nSin validación clínica. No garantiza vigilancia continua ni asistencia de emergencia.");
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
        label("Tu wearable",34,INK,true);paragraph("Conecta el ESP32 a este teléfono. Las lecturas se envían mediante una conexión segura al servidor propio, por la red que elija Android, incluida tu VPN.");
        if(!elder()){paragraph("El wearable se conecta desde la cuenta de la persona mayor.");return;}
        LinearLayout card=card(content);connectionLabel=labelIn(card,"Cargando conexión…",22,INK,true);readingLabel=labelIn(card,"",17,MUTED,false);
        button(card,"Buscar dispositivos Bluetooth",BLUE,()->bluetooth("scan"));
        button(card,"Abrir ajustes de Bluetooth",SOFT,()->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        paragraph("Para Classic SPP, empareja primero tu ESP32 en los ajustes Bluetooth del teléfono. Comprueba su dirección y el código de emparejamiento. El cifrado HTTPS empieza en el teléfono; el ESP32 debe usar emparejamiento seguro.");
        deviceList=new LinearLayout(this);deviceList.setOrientation(LinearLayout.VERTICAL);content.addView(deviceList);
        button(content,"Detener wearable",SOFT,()->{if(monitor!=null)monitor.stopMonitoring();updateDevice();});
        button(content,"Borrar lecturas pendientes",SOFT,()->confirm("¿Borrar la cola pendiente?","Las muestras no entregadas se perderán de forma permanente en este teléfono.",()->{if(monitor!=null)monitor.clearUploadQueue();}));
        paragraph("La cola cifrada conserva hasta 200 muestras. Si se llena, se descartan las nuevas y se muestra el recuento. Una sesión vencida pausa el envío. No existe garantía de monitorización continua.");
        updateDevice();
    }
    private void bluetooth(String action){
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
        if(connectionLabel!=null)connectionLabel.setText(monitor==null?"Wearable sin conexión":monitor.bluetoothState());
        if(readingLabel!=null&&monitor!=null)readingLabel.setText(monitor.relayState()+"\n"+monitor.packetCount()+" muestras · "+monitor.queuedCount()+" pendientes"+(monitor.error().isEmpty()?"":"\n"+monitor.error()));
        if(deviceList!=null&&monitor!=null){deviceList.removeAllViews();for(LiveMonitorService.Device device:monitor.devices()){
            button(deviceList,"Wearable CareMonitor\n"+device.address,SOFT,()->{try{
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
        ApiSession old=session;epoch++;refreshing=false;session=null;notice="Sesión cerrada.";patientId="";overview=new JSONObject();patients=new JSONArray();requests=new JSONArray();caregivers=new JSONArray();reminders=new JSONArray();notifications=new JSONArray();consents=new JSONObject();
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
                if(!page.equals("device")&&!page.equals("account")){int y=scroll==null?0:scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));}
            });
        }catch(Exception failure){main.post(()->{if(capture!=epoch)return;refreshing=false;
            if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==401){logout();notice="Tu sesión venció o fue revocada. Inicia sesión otra vez.";render();}
            else{notice=failure.getMessage();if(visible)toast(notice);}});}});
    }
    private void confirm(String title,String body,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Confirmar",(d,w)->action.run()).setNegativeButton("Cancelar",null).show();}
    private void toast(String message){Toast.makeText(this,message==null?"No se pudo completar la acción.":message,Toast.LENGTH_LONG).show();}
    private void label(String title,int size,int color,boolean bold){labelIn(content,title,size,color,bold);}
    private void paragraph(String value){TextView view=labelIn(content,value,18,MUTED,false);view.setPadding(0,dp(8),0,dp(16));}
    private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));view.setLineSpacing(dp(3),1.04f);return view;}
    private TextView labelIn(LinearLayout parent,String value,int size,int color,boolean bold){TextView view=text(value,size,color,bold);view.setPadding(0,dp(4),0,dp(8));parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private LinearLayout card(LinearLayout parent){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(20),dp(20),dp(20),dp(20));card.setBackground(shape(Color.WHITE,26));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);lp.bottomMargin=dp(8);parent.addView(card,lp);return card;}
    private void button(LinearLayout parent,String label,int background,Runnable click){Button view=new Button(this);view.setText(label);view.setTextSize(18);view.setAllCaps(false);view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        view.setTextColor(background==BLUE||background==RED?Color.WHITE:BLUE);view.setBackground(shape(background,17));view.setMinHeight(dp(56));view.setMinimumHeight(dp(56));view.setPadding(dp(18),dp(14),dp(18),dp(14));
        view.setOnClickListener(v->click.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);parent.addView(view,lp);}
    private EditText input(LinearLayout parent,String label,String value,int type){labelIn(parent,label,16,MUTED,true);EditText view=new EditText(this);view.setText(value);view.setInputType(type);view.setTextSize(18);view.setTextColor(INK);view.setSingleLine(true);view.setPadding(dp(14),dp(12),dp(14),dp(12));view.setBackground(shape(BG,12));
        view.setMinHeight(dp(56));view.setContentDescription(label);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(view,lp);return view;}
    private GradientDrawable shape(int fill,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(fill);drawable.setCornerRadius(dp(radius));return drawable;}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private String formatTime(String value){long time=ApiSession.expiryMs(value);return time==0?value:android.text.format.DateFormat.getDateFormat(this).format(new Date(time))+" "+android.text.format.DateFormat.getTimeFormat(this).format(new Date(time));}
}
