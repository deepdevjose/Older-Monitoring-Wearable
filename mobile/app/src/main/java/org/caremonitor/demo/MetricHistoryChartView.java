package org.caremonitor.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
    private static final int TEXT=0xff1c1c1e, MUTED=0xff636366, GRID=0xffececf1;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final ArrayList<Point> points=new ArrayList<>();
    private long start,end;
    private String unit="",period="day",source="device_diagnostic",intervalLabel="Interval averages";
    private int accent=0xff0066cc,selected=-1;
    private double low,high,valueScale=1;
    private float plotLeft,plotRight,plotTop,plotBottom;
    private float touchX,touchY;
    private boolean scrubbing;
    private final int touchSlop;
    private static final class Point {
        long at; Double value; int count;
        Point(long at,Double value,int count){this.at=at;this.value=value;this.count=count;}
    }

    public MetricHistoryChartView(Context context){
        super(context);setFocusable(true);setClickable(true);
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumHeight(dp(282));setContentDescription("History chart. No valid observations.");
    }

    public void setHistory(JSONObject data){
        points.clear();selected=-1;
        unit=data.optString("unit","");period=data.optString("period","day");source=data.optString("source","device_diagnostic");
        int bucket=data.optInt("bucket_seconds",0);
        intervalLabel=bucket==3600?"Hourly averages":bucket==21600?"6-hour averages":bucket==86400?"Daily averages":"Interval averages";
        start=time(data.optString("start",""));end=time(data.optString("end",""));
        String metric=data.optString("metric","");
        accent=metric.equals("heart_rate_bpm")?0xffb52b55:metric.equals("spo2_percent")?0xff0066cc:
            metric.contains("temp")?0xff98631d:0xff6554a4;
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
        if(!finite(low)){low=0;high=1;valueScale=1;}else{
            valueScale=Math.max(1,Math.max(Math.abs(low),Math.abs(high)));
            low/=valueScale;high/=valueScale;
            double padding=Math.max((high-low)*.15,Math.max(.1/valueScale,Math.abs(high)*.02));
            double limit=Double.MAX_VALUE/valueScale;
            low=Math.max(-limit,low-padding);high=Math.min(limit,high+padding);
        }
        updateDescription();invalidate();
    }

    @Override protected void onMeasure(int width,int height){
        // Keep the plot usable when the header and axis text grow with the system font size.
        int desired=dp(196)+Math.round(sp(86));
        setMeasuredDimension(resolveSize(dp(300),width),resolveSize(Math.max(getSuggestedMinimumHeight(),desired),height));
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        text(MUTED,11);float labels=Math.max(paint.measureText(format(low*valueScale)),paint.measureText(format(high*valueScale)));
        labels=Math.max(labels,paint.measureText(unit));
        plotLeft=getPaddingLeft()+dp(4);plotRight=getWidth()-getPaddingRight()-labels-dp(16);
        plotTop=getPaddingTop()+sp(62)+dp(8);plotBottom=getHeight()-getPaddingBottom()-sp(28)-dp(10);
        if(plotRight<=plotLeft||plotBottom<=plotTop)return;
        drawHeader(canvas);
        for(int i=0;i<4;i++){
            float y=plotTop+(plotBottom-plotTop)*i/3;
            stroke(GRID,.8f);canvas.drawLine(plotLeft,y,plotRight,y,paint);
            text(MUTED,11);canvas.drawText(format((high-(high-low)*i/3)*valueScale),plotRight+dp(8),y+sp(4),paint);
        }
        for(int i=0;i<3;i++){
            float at=plotLeft+(plotRight-plotLeft)*i/2;
            stroke(GRID,.8f);canvas.drawLine(at,plotTop,at,plotBottom,paint);
        }
        text(MUTED,11);canvas.drawText(unit,plotRight+dp(8),plotTop-sp(10),paint);
        path.reset();boolean connected=false;int valid=0;
        for(Point point:points){
            if(point.value==null){connected=false;continue;}valid++;
            float x=x(point.at),y=y(point.value);
            if(connected)path.lineTo(x,y);else path.moveTo(x,y);connected=true;
        }
        // Straight segments show the returned interval means without inventing intermediate values.
        stroke(accent,2.5f);canvas.drawPath(path,paint);
        for(Point point:points)if(point.value!=null){paint.setStyle(Paint.Style.FILL);paint.setColor(accent);canvas.drawCircle(x(point.at),y(point.value),dp(2.8f),paint);}
        text(MUTED,11);String first=axis(start),last=axis(end);
        canvas.drawText(first,plotLeft,plotBottom+sp(23),paint);
        canvas.drawText(last,plotRight-paint.measureText(last),plotBottom+sp(23),paint);
        String middle=axis(start+(end-start)/2);float middleWidth=paint.measureText(middle);
        if(plotRight-plotLeft>paint.measureText(first)+paint.measureText(last)+middleWidth+dp(32))
            canvas.drawText(middle,(plotLeft+plotRight-middleWidth)/2,plotBottom+sp(23),paint);
        if(valid==0){text(MUTED,14);drawFitted(canvas,"No readings",plotLeft,(plotTop+plotBottom)/2,plotRight-plotLeft);}
        if(selected>=0&&selected<points.size()){
            Point point=points.get(selected);stroke(0xffb8b8c0,1);canvas.drawLine(x(point.at),plotTop,x(point.at),plotBottom,paint);
            if(point.value!=null){
                paint.setStyle(Paint.Style.FILL);paint.setColor((accent&0x00ffffff)|0x22000000);canvas.drawCircle(x(point.at),y(point.value),dp(10),paint);
                paint.setColor(0xffffffff);canvas.drawCircle(x(point.at),y(point.value),dp(6),paint);
                paint.setColor(accent);canvas.drawCircle(x(point.at),y(point.value),dp(4),paint);
            }
        }
    }

    private void drawHeader(Canvas canvas){
        Point point=selected>=0&&selected<points.size()?points.get(selected):null;
        float width=getWidth()-getPaddingRight()-plotLeft;
        text(MUTED,12);drawFitted(canvas,point==null?"Touch to inspect":shortDate(point.at),plotLeft,getPaddingTop()+sp(14),width);
        text(point==null?TEXT:accent,point==null?17:22);
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        String value=point==null?intervalLabel:point.value==null?"No reading":format(point.value)+" "+unit;
        drawFitted(canvas,value,plotLeft,getPaddingTop()+sp(43),width);
    }

    private void drawFitted(Canvas canvas,String value,float left,float baseline,float width){
        if(paint.measureText(value)>width){int length=paint.breakText(value,true,Math.max(0,width-paint.measureText("…")),null);value=value.substring(0,length)+"…";}
        canvas.drawText(value,left,baseline,paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(points.isEmpty())return super.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                touchX=event.getX();touchY=event.getY();scrubbing=false;
                choose(event.getX());return true;
            case MotionEvent.ACTION_MOVE:
                float horizontal=Math.abs(event.getX()-touchX),vertical=Math.abs(event.getY()-touchY);
                if(!scrubbing&&vertical>touchSlop&&vertical>horizontal){
                    if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return false;
                }
                if(horizontal>touchSlop)scrubbing=true;
                if(scrubbing&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
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
        if(!points.isEmpty()){info.setScrollable(true);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if(points.size()>1)info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,points.size()-1,Math.max(0,selected)));}
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
    private String shortDate(long at){return new SimpleDateFormat("MMM d · HH:mm",Locale.US).format(new Date(at));}
    private String axis(long at){return new SimpleDateFormat(period.equals("day")?"HH:mm":"MMM d",Locale.US).format(new Date(at));}
    private long time(String value){
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));format.setLenient(false);
        ParsePosition position=new ParsePosition(0);Date parsed=format.parse(value,position);
        return parsed!=null&&position.getIndex()==value.length()?parsed.getTime():0;
    }
    private float x(long at){return plotLeft+(plotRight-plotLeft)*(float)((double)(at-start)/Math.max(1,end-start));}
    private float y(double value){return (float)(plotBottom-(value/valueScale-low)/(high-low)*(plotBottom-plotTop));}
    private void text(int color,float size){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(sp(size));paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));}
    private void stroke(int color,float width){paint.setStyle(Paint.Style.STROKE);paint.setColor(color);paint.setStrokeWidth(dp(width));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private float sp(float value){return value*getResources().getDisplayMetrics().scaledDensity;}
    private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
    private String format(double value){return String.format(Locale.US,Math.abs(value)>=100000?"%.3g":unit.equals("ADC")?"%.0f":unit.equals("g")?"%.2f":"%.1f",value);}
}
