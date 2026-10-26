package org.caremonitor.demo;

/** Regression checks for home privacy uncertainty and queue account separation. */
public final class PrivacyBoundaryTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Privacy check "+checks);}
    public static void main(String[] args){
        check(!PrivacyBoundary.mayShare(200,0,200,0,false));
        check(!PrivacyBoundary.mayShare(210,10,200,0,false));
        check(PrivacyBoundary.mayShare(211,10,200,0,false));
        check(!PrivacyBoundary.mayShare(500,101,200,0,false));
        check(!PrivacyBoundary.mayShare(500,10,200,120001,false));
        check(!PrivacyBoundary.mayShare(500,10,200,-1,false));
        check(!PrivacyBoundary.mayShare(500,10,200,0,true));
        for(double value:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-1}){
            check(!PrivacyBoundary.mayShare(value,10,200,0,false));
            check(!PrivacyBoundary.mayShare(500,value,200,0,false));
            check(!PrivacyBoundary.mayShare(500,10,value,0,false));
        }
        for(int radius:new int[]{100,200,1000,10000})for(int accuracy:new int[]{0,10,100}){
            for(int distance=0;distance<=radius+accuracy;distance+=17)check(!PrivacyBoundary.mayShare(distance,accuracy,radius,0,false));
            check(PrivacyBoundary.mayShare(radius+accuracy+1,accuracy,radius,120000,false));
        }
        check(!PrivacyBoundary.mayShare(500,10,99,0,false));
        check(!PrivacyBoundary.mayShare(20000,10,10001,0,false));
        String a="https://a.example/api/v1/readings",b="https://b.example/api/v1/readings";
        check(RelayPolicy.mayDeliver(a,"elder-1",a,"elder-1"));
        check(!RelayPolicy.mayDeliver(a,"elder-1",a,"elder-2"));
        check(!RelayPolicy.mayDeliver(a,"elder-1",b,"elder-1"));
        check(!RelayPolicy.mayDeliver(a,"elder-1",a,""));
        check(!RelayPolicy.mayDeliver(a,"",a,""));
        check(!RelayPolicy.mayDeliver(null,"elder-1",a,"elder-1"));
        check(!RelayPolicy.mayDeliver(a,null,a,"elder-1"));
        System.out.println("PASS: "+checks+" PrivacyBoundary checks");
    }
}
