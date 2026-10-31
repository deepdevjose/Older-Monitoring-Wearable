package org.caremonitor.demo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** A scoped history request. A zero end means the server's current clock. */
final class MetricHistoryQuery {
    static final int READ_TIMEOUT_MS=15000;
    static final String SOURCE="device_diagnostic";
    final String patient,metric,period;
    final long endMillis;

    MetricHistoryQuery(String patient,String metric,String period,long endMillis){
        if(patient==null||!patient.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Choose an approved person before opening history.");
        if(!knownMetric(metric))throw new IllegalArgumentException("This reading is not available in history.");
        if(!"day".equals(period)&&!"week".equals(period)&&!"month".equals(period))throw new IllegalArgumentException("Choose a day, week or month.");
        if(endMillis<0)throw new IllegalArgumentException("The history date is invalid.");
        this.patient=patient;this.metric=metric;this.period=period;this.endMillis=endMillis;
    }

    String path(){
        String path="/patients/"+patient+"/metrics/"+metric+"/history?period="+period+"&source="+SOURCE;
        if(endMillis>0){
            SimpleDateFormat utc=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            // This format has only colons that need escaping in a query value.
            path+="&end="+utc.format(new Date(endMillis)).replace(":","%3A");
        }
        return path;
    }

    boolean matches(String patient,String metric,String period,String source){
        return this.patient.equals(patient)&&this.metric.equals(metric)&&this.period.equals(period)&&SOURCE.equals(source);
    }

    private static boolean knownMetric(String value){
        return "heart_rate_bpm".equals(value)||"spo2_percent".equals(value)||"surface_temp_c".equals(value)
            ||"ambient_temp_c".equals(value)||"acceleration_g".equals(value)||"light_adc".equals(value);
    }
}
