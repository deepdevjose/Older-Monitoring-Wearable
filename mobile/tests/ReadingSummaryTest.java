package org.caremonitor.demo;

/** Standalone JVM checks. Run with assertions enabled or call main directly. */
public final class ReadingSummaryTest {
    private static int checked;

    public static void main(String[] args) {
        expect("complete recent readings", ReadingSummary.State.STABLE,
                true, 76.0, 98.0, 33.5, false);
        expect("explicit review flag", ReadingSummary.State.ATTENTION,
                true, 76.0, 92.0, 33.5, true);
        expect("readings do not invent clinical thresholds", ReadingSummary.State.STABLE,
                true, 120.0, 92.0, 33.5, false);

        expect("stale snapshot", ReadingSummary.State.UNAVAILABLE,
                false, 76.0, 98.0, 33.5, false);
        expect("stale overrides review", ReadingSummary.State.UNAVAILABLE,
                false, 76.0, 92.0, 33.5, true);
        expect("missing heart rate", ReadingSummary.State.UNAVAILABLE,
                true, null, 98.0, 33.5, false);
        expect("missing oxygen", ReadingSummary.State.UNAVAILABLE,
                true, 76.0, null, 33.5, false);
        expect("missing surface temperature", ReadingSummary.State.UNAVAILABLE,
                true, 76.0, 98.0, null, false);
        expect("missing overrides review", ReadingSummary.State.UNAVAILABLE,
                true, null, 92.0, 33.5, true);
        expect("all readings missing", ReadingSummary.State.UNAVAILABLE,
                true, null, null, null, true);

        double[] nonFinite = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double value : nonFinite) {
            expect("non-finite heart rate " + value, ReadingSummary.State.UNAVAILABLE,
                    true, value, 98.0, 33.5, false);
            expect("non-finite oxygen " + value, ReadingSummary.State.UNAVAILABLE,
                    true, 76.0, value, 33.5, true);
            expect("non-finite surface temperature " + value, ReadingSummary.State.UNAVAILABLE,
                    true, 76.0, 98.0, value, false);
        }
        expect("zero heart rate is unavailable", ReadingSummary.State.UNAVAILABLE,
                true, 0.0, 98.0, 33.5, false);
        expect("negative heart rate is unavailable", ReadingSummary.State.UNAVAILABLE,
                true, -1.0, 98.0, 33.5, true);
        expect("oxygen below physical range", ReadingSummary.State.UNAVAILABLE,
                true, 76.0, -0.01, 33.5, true);
        expect("oxygen above physical range", ReadingSummary.State.UNAVAILABLE,
                true, 76.0, 100.01, 33.5, false);
        expect("oxygen upper range boundary", ReadingSummary.State.STABLE,
                true, 76.0, 100.0, 33.5, false);
        expect("oxygen lower range boundary with review", ReadingSummary.State.ATTENTION,
                true, 76.0, 0.0, 33.5, true);

        expect("new reviewed snapshot can clear review flag", ReadingSummary.State.STABLE,
                true, 76.0, 98.0, 33.5, false);
        System.out.println("PASS: " + checked + " ReadingSummary checks");
    }

    private static void expect(String name, ReadingSummary.State expected, boolean recent,
                               Double heartRate, Double oxygen, Double temperature,
                               boolean needsReview) {
        checked++;
        ReadingSummary.State actual = ReadingSummary.assess(
                recent, heartRate, oxygen, temperature, needsReview);
        if (actual != expected)
            throw new AssertionError(name + ": expected " + expected + ", received " + actual);
    }
}
