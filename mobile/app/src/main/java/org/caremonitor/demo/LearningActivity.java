package org.caremonitor.demo;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Authenticated synthetic exercises. Never converts wearable surface temperature to body temperature. */
public final class LearningActivity extends Activity {
    private static final int BLUE=0xff007aff, INK=0xff1c1c1e, SECONDARY=0xff636366, BG=0xfff2f2f7;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ApiSession session;
    private String patientId, notice="", pulse="68", oxygen="97", temperature="36.5", pendingKey="", pendingId="";
    private JSONObject data;
    private LinearLayout content;
    private boolean visible, busy;
    private long generation;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        session=ApiSession.load(this);patientId=getIntent().getStringExtra("patient_id");
        if(session==null||!session.valid()||patientId==null||!patientId.matches("[A-Za-z0-9_-]{1,128}")
                ||(session.elder()&&!session.id.equals(patientId))){finish();return;}
        if(state!=null){pulse=state.getString("pulse","68");oxygen=state.getString("oxygen","97");temperature=state.getString("temperature","36.5");
            pendingKey=state.getString("pending_key","");pendingId=state.getString("pending_id","");}
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("pulse",pulse);state.putString("oxygen",oxygen);state.putString("temperature",temperature);state.putString("pending_key",pendingKey);state.putString("pending_id",pendingId);}
    @Override public void onResume(){super.onResume();visible=true;
        ApiSession active=ApiSession.load(this);if(session==null||active==null||!active.valid()||!active.id.equals(session.id)||!active.base.equals(session.base)||!active.token.equals(session.token)){data=null;finish();return;}load();}
    @Override public void onPause(){visible=false;generation++;busy=false;super.onPause();}
    @Override public void onDestroy(){worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
    private boolean current(long request){
        ApiSession active=ApiSession.load(this);
        return visible&&!isFinishing()&&request==generation&&active!=null&&active.id.equals(session.id)
            &&active.base.equals(session.base)&&active.token.equals(session.token);
    }
    private String path(){return "/patients/"+patientId+"/learning/readings";}
    private void load(){
        if(busy)return;busy=true;notice="";long request=++generation;render();
        worker.execute(()->{try{
            JSONObject result=ApiClient.call(this,session,"GET",path(),null);
            main.post(()->{if(!current(request))return;data=result;busy=false;render();});
        }catch(Exception failure){main.post(()->{if(!current(request))return;failed(failure);});}});
    }
    private void classify(){
        if(busy||!session.elder()||data==null||!data.optBoolean("can_add"))return;
        try{
            JSONObject features=new JSONObject().put("heart_rate_bpm",finite(pulse))
                .put("spo2_percent",finite(oxygen)).put("body_temp_c",finite(temperature)).put("sample_age_seconds",0.0);
            JSONObject ranges=data.optJSONObject("feature_ranges");
            if(ranges==null)throw new IllegalArgumentException();
            for(String name:new String[]{"heart_rate_bpm","spo2_percent","body_temp_c"}){
                JSONArray range=ranges.getJSONArray(name);double value=features.getDouble(name);
                if(value<range.getDouble(0)||value>range.getDouble(1))throw new IllegalArgumentException();
            }
            String key=features.toString();if(!key.equals(pendingKey)){pendingId=UUID.randomUUID().toString();pendingKey=key;}
            JSONObject body=new JSONObject().put("source","synthetic").put("sample_id",pendingId).put("features",features);
            busy=true;notice="";long request=++generation;render();
            worker.execute(()->{try{
                ApiClient.call(this,session,"POST",path(),body);
                main.post(()->{if(!current(request))return;busy=false;pendingKey="";pendingId="";load();});
            }catch(Exception failure){main.post(()->{if(!current(request))return;failed(failure);});}});
        }catch(Exception invalid){notice="Enter all three numbers within the ranges shown below.";render();}
    }
    private static double finite(String text){double value=Double.parseDouble(text.trim());if(Double.isNaN(value)||Double.isInfinite(value))throw new IllegalArgumentException();return value;}
    private String message(Exception failure){return failure instanceof ApiClient.Failure?failure.getMessage():"The exercise could not be loaded. Try again.";}
    private void failed(Exception failure){
        busy=false;data=null;
        if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==403)notice="Access is no longer available. The older adult must approve your care link to share learning history.";
        else if(failure instanceof ApiClient.Failure&&((ApiClient.Failure)failure).status==401)notice="Your session expired or was revoked. Return to Settings and sign in again.";
        else notice=message(failure);render();
    }
    private void render(){
        if(session==null)return;
        LinearLayout screen=new LinearLayout(this);screen.setOrientation(1);screen.setBackgroundColor(BG);screen.setFitsSystemWindows(true);
        Button back=button("‹  Settings",()->finish());back.setEnabled(true);back.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);screen.addView(back);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(dp(20),dp(8),dp(20),dp(32));scroll.addView(content);
        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(screen);
        TextView heading=text("Learning insights",32,INK);heading.setTypeface(null,Typeface.BOLD);if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);content.addView(heading);
        content.addView(text("Synthetic model exercises",17,SECONDARY));
        LinearLayout explanation=card();explanation.addView(text("Educational data only",18,INK));
        explanation.addView(text("Use synthetic examples to explore Normal, Warning and Critical classifications. Confidence is the model's score, not medical certainty. These results do not diagnose a condition or trigger care notifications.",16,SECONDARY));
        explanation.addView(text("Wearable readings stay separate. Surface temperature from the GY906 is not body temperature and is never sent to this model.",16,SECONDARY));
        if(!notice.isEmpty()){TextView status=text(notice,17,0xffa12c22);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status);}
        if(busy){ProgressBar loading=new ProgressBar(this);loading.setContentDescription("Loading learning insights");content.addView(loading);}
        if(data==null){content.addView(button("Try again",this::load));return;}
        if(!patientId.equals(data.optString("patient_id"))||!"synthetic".equals(data.optString("source"))||!data.optBoolean("educational_only")){
            content.addView(text("This response does not match the synthetic learning workspace.",17,INK));return;
        }
        LinearLayout model=card();model.addView(text("Local model",17,SECONDARY));
        String selectedModel=data.optString("selected_model");model.addView(text("random_forest".equals(selectedModel)?"Random forest":"logistic_regression".equals(selectedModel)?"Logistic regression":"Model details unavailable",21,INK));
        model.addView(text(data.optString("model_id"),15,SECONDARY));
        if(session.elder()&&data.optBoolean("can_add")){
            LinearLayout entry=card();entry.addView(text("Explore a synthetic sample",22,INK));
            JSONArray examples=data.optJSONArray("examples");
            if(examples!=null)for(int i=0;i<examples.length();i++){
                JSONObject example=examples.optJSONObject(i);if(example==null)continue;
                entry.addView(button(example.optString("label")+" example · CSV row "+example.optInt("csv_row"),()->{
                    JSONObject f=example.optJSONObject("features");if(f==null)return;
                    pulse=number(f.optDouble("heart_rate_bpm"));oxygen=number(f.optDouble("spo2_percent"));temperature=number(f.optDouble("body_temp_c"));notice="";render();
                }));
            }
            entry.addView(text("The examples come from the repository's synthetic CSV. You may edit the values to explore the model.",15,SECONDARY));
            field(entry,"Heart rate (bpm)",pulse,range("heart_rate_bpm"),value->pulse=value);
            field(entry,"Oxygen saturation (%)",oxygen,range("spo2_percent"),value->oxygen=value);
            field(entry,"Body temperature (°C)",temperature,range("body_temp_c"),value->temperature=value);
            entry.addView(button("Classify and save",this::classify));
        }else{
            LinearLayout access=card();access.addView(text("Shared learning history",21,INK));
            access.addView(text("You can review this person's saved synthetic exercises while your care link is approved. The older adult adds exercises from their account.",16,SECONDARY));
        }
        JSONArray items=data.optJSONArray("items");
        TextView history=text("Recent exercises",24,INK);history.setTypeface(null,Typeface.BOLD);if(Build.VERSION.SDK_INT>=28)history.setAccessibilityHeading(true);content.addView(history);
        content.addView(text("Latest 30 saved samples · newest first · retained for 30 days",15,SECONDARY));
        if(items==null||items.length()==0){LinearLayout empty=card();empty.addView(text("No saved exercises yet",20,INK));empty.addView(text("Saved synthetic results will appear here for the older adult and their approved caregivers.",16,SECONDARY));}
        else for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null)record(item,i==0);}
        content.addView(button("Refresh history",this::load));
    }
    private String range(String key){JSONObject ranges=data.optJSONObject("feature_ranges");JSONArray range=ranges==null?null:ranges.optJSONArray(key);return range==null?"Synthetic input only":"Model range: "+number(range.optDouble(0))+"–"+number(range.optDouble(1));}
    private void record(JSONObject item,boolean latest){
        String label=item.optString("classification","");double confidence=item.optDouble("confidence",Double.NaN);
        if(!"synthetic".equals(item.optString("source"))||!item.optBoolean("educational_only")
            ||!(label.equals("Normal")||label.equals("Warning")||label.equals("Critical"))||Double.isNaN(confidence)||confidence<0||confidence>1)return;
        LinearLayout row=card();if(latest)row.addView(text("LATEST RESULT",13,SECONDARY));
        row.addView(text(label,latest?32:23,INK));
        String score=String.format(Locale.US,"%.1f%% model confidence",confidence*100);row.addView(text(score,18,BLUE));
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgress((int)Math.round(confidence*1000));bar.setContentDescription(label+", "+score);row.addView(bar,new LinearLayout.LayoutParams(-1,dp(12)));
        long time=ApiSession.expiryMs(item.optString("created_at"));row.addView(text(time>0?DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT,Locale.ENGLISH).format(new Date(time)):"Saved exercise",15,SECONDARY));
        JSONObject f=item.optJSONObject("features");if(f!=null)row.addView(text(number(f.optDouble("heart_rate_bpm"))+" bpm  ·  "+number(f.optDouble("spo2_percent"))+"% oxygen  ·  "+number(f.optDouble("body_temp_c"))+" °C body temperature",16,INK));
        row.addView(text("Synthetic · "+item.optString("model_id"),13,SECONDARY));
    }
    private interface Changed{void set(String value);}
    private void field(LinearLayout parent,String label,String value,String hint,Changed changed){
        TextView caption=text(label,17,INK);parent.addView(caption);EditText input=new EditText(this);input.setTextSize(22);input.setText(value);input.setSingleLine(true);input.setSaveEnabled(false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setId(View.generateViewId());caption.setLabelFor(input.getId());input.setContentDescription(label);input.setEnabled(!busy);input.setMinHeight(dp(56));parent.addView(input,new LinearLayout.LayoutParams(-1,-2));
        parent.addView(text(hint,14,SECONDARY));input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){changed.set(s.toString());}public void afterTextChanged(Editable s){}});
    }
    private LinearLayout card(){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(18),dp(16),dp(18),dp(16));GradientDrawable background=new GradientDrawable();background.setColor(Color.WHITE);background.setCornerRadius(dp(20));card.setBackground(background);LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.setMargins(0,dp(14),0,dp(8));content.addView(card,layout);return card;}
    private TextView text(String value,int size,int color){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setPadding(0,dp(4),0,dp(6));return text;}
    private Button button(String label,Runnable action){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(17);button.setTextColor(BLUE);button.setMinHeight(dp(52));button.setEnabled(!busy);button.setOnClickListener(v->action.run());return button;}
    private static String number(double value){return Double.isNaN(value)||Double.isInfinite(value)?"—":String.format(Locale.US,value==Math.rint(value)?"%.0f":"%.1f",value);}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
}
