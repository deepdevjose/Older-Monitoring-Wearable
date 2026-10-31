package org.caremonitor.demo;

/** History scope and query-date checks without a phone, clock, or network. */
public final class MetricHistoryQueryTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("History request boundary "+checks);}
    private static void rejects(String patient,String metric,String period,long end){
        try{new MetricHistoryQuery(patient,metric,period,end);throw new AssertionError("Unsafe history request accepted");}
        catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args){
        String[] metrics={"heart_rate_bpm","spo2_percent","surface_temp_c","ambient_temp_c","acceleration_g","light_adc"};
        for(String metric:metrics)for(String period:new String[]{"day","week","month"}){
            MetricHistoryQuery query=new MetricHistoryQuery("patient_A-123",metric,period,0);
            check(query.path().equals("/patients/patient_A-123/metrics/"+metric+"/history?period="+period+"&source=device_diagnostic"));
            check(query.matches("patient_A-123",metric,period,"device_diagnostic"));
            check(!query.matches("other-person",metric,period,"device_diagnostic"));
            check(!query.matches("patient_A-123",metric,period,"synthetic"));
        }
        MetricHistoryQuery dated=new MetricHistoryQuery("patient","heart_rate_bpm","day",1609459200123L);
        check(dated.path().endsWith("&end=2021-01-01T00%3A00%3A00.123Z"));
        check(!dated.matches("patient","spo2_percent","day","device_diagnostic"));
        check(!dated.matches("patient","heart_rate_bpm","week","device_diagnostic"));
        check(MetricHistoryQuery.READ_TIMEOUT_MS==15000);
        for(String patient:new String[]{"", "../other", "person?source=synthetic", "person/metrics", null})rejects(patient,"heart_rate_bpm","day",0);
        rejects("patient","unknown","day",0);rejects("patient",null,"day",0);
        rejects("patient","heart_rate_bpm","year",0);rejects("patient","heart_rate_bpm",null,0);
        rejects("patient","heart_rate_bpm","day",-1);
        System.out.println("PASS: "+checks+" MetricHistoryQuery checks");
    }
}
