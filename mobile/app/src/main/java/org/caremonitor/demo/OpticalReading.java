package org.caremonitor.demo;

/** Availability of an unfiltered sensor estimate, separate from signal quality and alert limits. */
public final class OpticalReading {
    private OpticalReading() {}
    public static Double value(Object raw) {
        if (!(raw instanceof Number)) return null;
        double value=((Number)raw).doubleValue();
        // The Maxim algorithm uses -999 to mean it could not compute an estimate.
        return Double.isNaN(value)||Double.isInfinite(value)||value==-999 ? null : value;
    }
}
