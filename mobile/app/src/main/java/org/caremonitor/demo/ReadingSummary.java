package org.caremonitor.demo;

/** Reading availability and review state, not a medical diagnosis or health score. */
public final class ReadingSummary {
    public enum State { STABLE, ATTENTION, UNAVAILABLE }

    private ReadingSummary() {}

    public static State assess(boolean recent, Double heartRate, Double oxygen,
                               Double surfaceTemperature, boolean needsReview) {
        if (!recent || !finite(heartRate) || heartRate <= 0 || !finite(oxygen)
                || oxygen < 0 || oxygen > 100 || !finite(surfaceTemperature)) {
            return State.UNAVAILABLE;
        }
        return needsReview ? State.ATTENTION : State.STABLE;
    }

    private static boolean finite(Double value) {
        return value != null && !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
