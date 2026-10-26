package org.caremonitor.demo;

/** Per-channel availability for processed optical estimates, never healthy-person thresholds. */
public final class OpticalEstimatePolicy {
    private OpticalEstimatePolicy() {}

    public static Double heartRate(Object status, Object online, Object finger,
                                   Object valid, Object value) {
        return estimate(status, online, finger, valid, value, 1, 300);
    }

    public static Double oxygen(Object status, Object online, Object finger,
                                Object valid, Object value) {
        return estimate(status, online, finger, valid, value, 1, 100);
    }

    public static Double heartRate(Object status, Object online, Object finger,
                                   Object valid, Object value, boolean hasSampleAge,
                                   Object sampleAge, boolean hasResultAge, Object resultAge) {
        return agesFresh(hasSampleAge, sampleAge, hasResultAge, resultAge)
                ? heartRate(status, online, finger, valid, value) : null;
    }

    public static Double oxygen(Object status, Object online, Object finger,
                                Object valid, Object value, boolean hasSampleAge,
                                Object sampleAge, boolean hasResultAge, Object resultAge) {
        return agesFresh(hasSampleAge, sampleAge, hasResultAge, resultAge)
                ? oxygen(status, online, finger, valid, value) : null;
    }

    /** Old firmware may omit ages. Present ages, including JSON null, must be numeric and fresh. */
    public static boolean agesFresh(boolean hasSampleAge, Object sampleAge,
                                    boolean hasResultAge, Object resultAge) {
        return (!hasSampleAge || ageWithin(sampleAge, 500))
                && (!hasResultAge || ageWithin(resultAge, 2500));
    }

    private static boolean ageWithin(Object value, double limit) {
        if (!(value instanceof Number)) return false;
        double age = ((Number) value).doubleValue();
        return !Double.isNaN(age) && !Double.isInfinite(age) && age >= 0 && age < limit;
    }

    private static Double estimate(Object status, Object online, Object finger,
                                   Object valid, Object value, double low, double high) {
        // A partial window can yield one usable estimate. Warming-up flags from old
        // firmware may be stale, so only explicit completed-window states qualify.
        if (!("ok".equals(status) || "partial".equals(status))
                || Boolean.FALSE.equals(online) || !Boolean.TRUE.equals(finger)
                || !Boolean.TRUE.equals(valid) || !(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        return Double.isNaN(number) || Double.isInfinite(number) || number < low || number > high
                ? null : number;
    }
}
