package org.caremonitor.demo;

/** Plain-JVM regression checks shared by live cards and chart history. */
public final class OpticalEstimatePolicyTest {
    private static int checked;

    public static void main(String[] args) {
        for (String status : new String[]{"ok", "partial"}) {
            expect(76.0, OpticalEstimatePolicy.heartRate(status, true, true, true, 76));
            expect(98.0, OpticalEstimatePolicy.oxygen(status, true, true, true, 98));
            expect(76.0, OpticalEstimatePolicy.heartRate(status, null, true, true, 76));
            expect(98.0, OpticalEstimatePolicy.oxygen(status, null, true, true, 98));
            // Each validity flag affects only its own metric.
            expect(null, OpticalEstimatePolicy.heartRate(status, true, true, false, 76));
            expect(98.0, OpticalEstimatePolicy.oxygen(status, true, true, true, 98));
            expect(76.0, OpticalEstimatePolicy.heartRate(status, true, true, true, 76));
            expect(null, OpticalEstimatePolicy.oxygen(status, true, true, false, 98));
        }
        for (Object state : new Object[]{"warming_up", "no_finger", "read_error", "waiting",
                "sample_backlog", "fifo_overflow", "unstable", "timeout", null, true}) {
            expect(null, OpticalEstimatePolicy.heartRate(state, true, true, true, 76));
            expect(null, OpticalEstimatePolicy.oxygen(state, true, true, true, 98));
        }
        for (Object flag : new Object[]{false, null, "true", 1}) {
            expect(null, OpticalEstimatePolicy.heartRate("ok", true, flag, true, 76));
            expect(null, OpticalEstimatePolicy.oxygen("partial", true, true, flag, 98));
        }
        expect(null, OpticalEstimatePolicy.heartRate("ok", false, true, true, 76));
        expect(null, OpticalEstimatePolicy.oxygen("partial", false, true, true, 98));
        for (Object value : new Object[]{null, true, "76", Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, -1}) {
            expect(null, OpticalEstimatePolicy.heartRate("ok", true, true, true, value));
            expect(null, OpticalEstimatePolicy.oxygen("partial", true, true, true, value));
        }
        expect(null, OpticalEstimatePolicy.heartRate("partial", true, true, true, 301));
        expect(null, OpticalEstimatePolicy.oxygen("partial", true, true, true, 101));
        expect(1.0, OpticalEstimatePolicy.heartRate("partial", true, true, true, 1));
        expect(300.0, OpticalEstimatePolicy.heartRate("partial", true, true, true, 300));
        expect(1.0, OpticalEstimatePolicy.oxygen("partial", true, true, true, 1));
        expect(100.0, OpticalEstimatePolicy.oxygen("partial", true, true, true, 100));
        for (Object[] ages : new Object[][]{
                {false, null, false, null}, {true, 0, true, 0},
                {true, 499, true, 2499}, {true, 1, false, null},
                {false, null, true, 1}}) {
            expect(76.0, OpticalEstimatePolicy.heartRate("ok", true, true, true, 76,
                    (Boolean)ages[0], ages[1], (Boolean)ages[2], ages[3]));
            expect(98.0, OpticalEstimatePolicy.oxygen("partial", true, true, true, 98,
                    (Boolean)ages[0], ages[1], (Boolean)ages[2], ages[3]));
        }
        for (Object[] ages : new Object[][]{
                {true, null, false, null}, {false, null, true, null},
                {true, true, false, null}, {false, null, true, false},
                {true, "1", false, null}, {false, null, true, "1"},
                {true, -1, true, 1}, {true, 1, true, -1},
                {true, 500, true, 1}, {true, 1, true, 2500},
                {true, Double.NaN, false, null}, {false, null, true, Double.POSITIVE_INFINITY}}) {
            expect(null, OpticalEstimatePolicy.heartRate("ok", true, true, true, 76,
                    (Boolean)ages[0], ages[1], (Boolean)ages[2], ages[3]));
            expect(null, OpticalEstimatePolicy.oxygen("partial", true, true, true, 98,
                    (Boolean)ages[0], ages[1], (Boolean)ages[2], ages[3]));
        }
        System.out.println("OpticalEstimatePolicyTest: " + checked + " checks passed");
    }

    private static void expect(Double expected, Double actual) {
        checked++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Availability mismatch at check " + checked);
    }
}
