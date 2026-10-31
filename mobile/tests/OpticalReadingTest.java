package org.caremonitor.demo;

public final class OpticalReadingTest {
    public static void main(String[] args){
        int checks=0;
        for(double value:new double[]{0,-1,29,221,450,65,101,130}){
            if(!Double.valueOf(value).equals(OpticalReading.value(value)))throw new AssertionError("Estimate was filtered: "+value);
            checks++;
        }
        for(Object value:new Object[]{null,"72",true,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-999}){
            if(OpticalReading.value(value)!=null)throw new AssertionError("Absent estimate became a value");
            checks++;
        }
        String a=OutboxScope.file("elder-a","https://host/api/v1/readings");
        if(!a.equals(OutboxScope.file("elder-a","https://HOST:443/api/v1/readings")))throw new AssertionError("Unstable endpoint scope");checks++;
        if(a.equals(OutboxScope.file("elder-b","https://host/api/v1/readings")))throw new AssertionError("Owner collision");checks++;
        if(a.equals(OutboxScope.file("elder-a","https://other/api/v1/readings")))throw new AssertionError("Server collision");checks++;
        if(!a.matches("sensor_outbox_scope_[0-9a-f]{64}\\.enc")||a.contains("elder-a"))throw new AssertionError("Unsafe private filename");checks++;
        System.out.println("PASS: "+checks+" OpticalReading checks");
    }
}
