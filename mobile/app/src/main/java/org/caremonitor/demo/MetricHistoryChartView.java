package org.caremonitor.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Time-positioned history. Null buckets remain gaps and are never zero-filled. */
public final class MetricHistoryChartView extends View {
    private static final int TEXT=0xff202124, MUTED=0xff666970, GRID=0xffe6e7eb;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final ArrayList<Point> points=new ArrayList<>();
    private long start,end;
    private String unit="",period="day",source="device_diagnostic";
    private int accent=0xff007aff,selected=-1;
    private double low,high;
    private float plotLeft,plotRight,plotTop,plotBottom;
    private static final class Point {
        long at; Double value; int count;
        Point(long at,Double value,int count){this.at=at;this.value=value;this.count=count;}
    }

    public MetricHistoryChartView(Context context){
        super(context);setFocusable(true);setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumHeight(dp(260));setContentDescription("History chart. No valid observations.");
    }

    public void setHistory(JSONObject data){
        points.clear();selected=-1;
        unit=data.optString("unit","");period=data.optString("period","day");source=data.optString("source","device_diagnostic");
        start=time(data.optString("start",""));end=time(data.optString("end",""));
        String metric=data.optString("metric","");
        accent=metric.equals("heart_rate_bpm")?0xffff375f:metric.equals("spo2_percent")?0xff007aff:
            metric.contains("temp")?0xffff9500:metric.equals("light_adc")?0xff9666dc:0xff008d7b;
        JSONArray array=data.optJSONArray("points");
        if(array!=null)for(int i=0;i<Math.min(array.length(),500);i++){
            JSONObject item=array.optJSONObject(i);if(item==null)continue;
            double value=item.optDouble("value",Double.NaN);int count=item.optInt("count",0);
            long at=time(item.optString("at",""));
            if(at<start||at>end)continue;
            points.add(new Point(at,count>0&&finite(value)?value:null,count));
        }
        low=Double.POSITIVE_INFINITY;high=Double.NEGATIVE_INFINITY;
        for(Point point:points)if(point.value!=null){low=Math.min(low,point.value);high=Math.max(high,point.value);}
        if(!finite(low)){low=0;high=1;}else{double padding=Math.max((high-low)*.15,Math.max(.1,Math.abs(high)*.02));low-=padding;high+=padding;}
        updateDescription();invalidate();
    }

    @Override protected void onMeasure(int width,int height){
        int desired=dp(260)+Math.round(Math.max(0,getResources().getConfiguration().fontScale-1)*dp(70));
        setMeasuredDimension(resolveSize(dp(300),width),resolveSize(Math.max(getSuggestedMinimumHeight(),desired),height));
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        text(MUTED,11);float labels=Math.max(paint.measureText(format(low)),paint.measureText(format(high)));
        plotLeft=getPaddingLeft()+dp(5);plotRight=getWidth()-getPaddingRight()-labels-dp(14);
        plotTop=getPaddingTop()+sp(35);plotBottom=getHeight()-getPaddingBottom()-sp(30);
        if(plotRight<=plotLeft||plotBottom<=plotTop)return;
        for(int i=0;i<4;i++){
            float y=plotTop+(plotBottom-plotTop)*i/3;
            stroke(GRID,1);canvas.drawLine(plotLeft,y,plotRight,y,paint);
            text(MUTED,11);canvas.drawText(format(high-(high-low)*i/3),plotRight+dp(7),y+sp(4),paint);
        }
        path.reset();boolean connected=false;int valid=0;
        for(Point point:points){
            if(point.value==null){connected=false;continue;}valid++;
            float x=x(point.at),y=y(point.value);
            if(connected)path.lineTo(x,y);else path.moveTo(x,y);connected=true;
        }
        stroke(accent,2.8f);canvas.drawPath(path,paint);
        for(Point point:points)if(point.value!=null){paint.setStyle(Paint.Style.FILL);paint.setColor(accent);canvas.drawCircle(x(point.at),y(point.value),dp(3),paint);}
        text(MUTED,11);String first=axis(start),last=axis(end);
        canvas.drawText(first,plotLeft,plotBottom+sp(23),paint);
        canvas.drawText(last,plotRight-paint.measureText(last),plotBottom+sp(23),paint);
        if(valid==0){text(TEXT,14);String empty="No valid observations";canvas.drawText(empty,Math.max(plotLeft,(plotLeft+plotRight-paint.measureText(empty))/2),(plotTop+plotBottom)/2,paint);}
        if(selected>=0&&selected<points.size()){
            Point point=points.get(selected);stroke(MUTED,1);canvas.drawLine(x(point.at),plotTop,x(point.at),plotBottom,paint);
            if(point.value!=null){paint.setStyle(Paint.Style.FILL);paint.setColor(accent);canvas.drawCircle(x(point.at),y(point.value),dp(5),paint);}
            text(TEXT,12);String shown=selection(point);float width=plotRight-plotLeft;
            if(paint.measureText(shown)>width){int n=paint.breakText(shown,true,Math.max(0,width-paint.measureText("…")),null);shown=shown.substring(0,n)+"…";}
            canvas.drawText(shown,plotLeft,plotTop-sp(14),paint);
        }else{text(MUTED,11);canvas.drawText("Touch to inspect",plotLeft,plotTop-sp(14),paint);}
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(points.isEmpty())return super.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:case MotionEvent.ACTION_MOVE:
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                choose(event.getX());return true;
            case MotionEvent.ACTION_UP:
                choose(event.getX());if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick(){super.performClick();if(selected>=0)announceForAccessibility(selection(points.get(selected)));return true;}
    private void choose(float coordinate){
        int nearest=0;float distance=Float.MAX_VALUE;
        for(int i=0;i<points.size();i++){float candidate=Math.abs(x(points.get(i).at)-coordinate);if(candidate<distance){distance=candidate;nearest=i;}}
        selected=nearest;updateDescription();invalidate();
    }
    private boolean move(int direction){
        if(points.isEmpty())return false;
        selected=Math.max(0,Math.min(points.size()-1,selected<0?(direction>0?0:points.size()-1):selected+direction));
        updateDescription();invalidate();announceForAccessibility(selection(points.get(selected)));return true;
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){
        if(code==KeyEvent.KEYCODE_DPAD_LEFT)return move(-1);
        if(code==KeyEvent.KEYCODE_DPAD_RIGHT)return move(1);
        return super.onKeyDown(code,event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");
        if(!points.isEmpty()){info.setScrollable(true);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    }
    @Override public boolean performAccessibilityAction(int action,Bundle args){
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)return move(1);
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)return move(-1);
        return super.performAccessibilityAction(action,args);
    }
    private void updateDescription(){
        int available=0;for(Point point:points)if(point.value!=null)available++;
        String description="History chart. "+(source.equals("synthetic")?"Synthetic observations. ":"Wearable sensor diagnostics. ")+available+" time intervals with valid averages. Missing intervals are gaps. "+date(start)+" to "+date(end)+". Swipe forward or backward to inspect intervals.";
        if(selected>=0)description+=" Selected: "+selection(points.get(selected));
        setContentDescription(description);
    }
    private String selection(Point point){return date(point.at)+": "+(point.value==null?"No valid observations":format(point.value)+" "+unit+" average, "+point.count+" observations");}
    private String date(long at){return new SimpleDateFormat("MMM d, yyyy HH:mm",Locale.US).format(new Date(at));}
    private String axis(long at){return new SimpleDateFormat(period.equals("day")?"HH:mm":"MMM d",Locale.US).format(new Date(at));}
    private long time(String value){
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));format.setLenient(false);
        ParsePosition position=new ParsePosition(0);Date parsed=format.parse(value,position);
        return parsed!=null&&position.getIndex()==value.length()?parsed.getTime():0;
    }
    private float x(long at){return plotLeft+(plotRight-plotLeft)*(float)((double)(at-start)/Math.max(1,end-start));}
    private float y(double value){return (float)(plotBottom-(value-low)/(high-low)*(plotBottom-plotTop));}
    private void text(int color,float size){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(sp(size));}
    private void stroke(int color,float width){paint.setStyle(Paint.Style.STROKE);paint.setColor(color);paint.setStrokeWidth(dp(width));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private float sp(float value){return value*getResources().getDisplayMetrics().scaledDensity;}
    private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
    private static String format(double value){return String.format(Locale.US,Math.abs(value)>=100?"%.0f":Math.abs(value)>=10?"%.1f":"%.2f",value);}
}
