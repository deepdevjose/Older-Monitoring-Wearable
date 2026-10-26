package org.caremonitor.demo;

/** Fail-closed location sharing gate; uncertainty must be fully beyond the home privacy radius. */
final class PrivacyBoundary {
    private PrivacyBoundary(){}
    static boolean mayShare(double distanceM,double accuracyM,double radiusM,long ageMs,boolean mock){
        return !mock&&Double.isFinite(distanceM)&&Double.isFinite(accuracyM)&&Double.isFinite(radiusM)
            &&distanceM>=0&&accuracyM>=0&&accuracyM<=100&&radiusM>=100&&radiusM<=10000
            &&ageMs>=0&&ageMs<=120000&&distanceM-accuracyM>radiusM;
    }
}
