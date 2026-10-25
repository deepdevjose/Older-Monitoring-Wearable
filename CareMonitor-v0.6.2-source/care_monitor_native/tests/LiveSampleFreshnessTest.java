package org.caremonitor.demo;

/** JVM checks for connection-scoped monotonic sample age. */
public final class LiveSampleFreshnessTest {
    private static int checked;

    public static void main(String[] args) {
        LiveSampleFreshness f = new LiveSampleFreshness(10_000);
        expect("no received sample", false, f.isFresh(true, true, 0));
        expect("ready is not a sample", false, f.isFresh(true, true, 5_000));
        f.received(0);
        expect("elapsed zero is valid", true, f.isFresh(true, true, 0));
        expect("one millisecond after zero", true, f.isFresh(true, true, 1));
        expect("just below ten seconds", true, f.isFresh(true, true, 9_999));
        expect("exact ten second boundary", false, f.isFresh(true, true, 10_000));
        expect("beyond boundary", false, f.isFresh(true, true, 10_001));
        expect("stopped", false, f.isFresh(false, true, 100));
        expect("not ready", false, f.isFresh(true, false, 100));
        expect("stopped and not ready", false, f.isFresh(false, false, 100));
        expect("negative query time", false, f.isFresh(true, true, -1));
        expect("very negative query time", false, f.isFresh(true, true, Long.MIN_VALUE));

        f.received(25_000);
        expect("receive is fresh", true, f.isFresh(true, true, 25_000));
        expect("clock rollback rejects future sample", false, f.isFresh(true, true, 24_999));
        expect("clock rollback to zero", false, f.isFresh(true, true, 0));
        expect("offset window below boundary", true, f.isFresh(true, true, 34_999));
        expect("offset window exact boundary", false, f.isFresh(true, true, 35_000));

        f.invalidate();
        expect("disconnect immediately invalidates sample", false, f.isFresh(true, false, 25_001));
        expect("ready after reconnect cannot reuse previous sample", false, f.isFresh(true, true, 25_002));
        expect("multiple ready queries cannot recreate sample", false, f.isFresh(true, true, 25_003));
        f.received(25_004);
        expect("new reconnect packet becomes fresh", true, f.isFresh(true, true, 25_004));
        f.received(-1);
        expect("negative receive discards existing sample", false, f.isFresh(true, true, 25_005));
        f.received(Long.MIN_VALUE);
        expect("minimum receive time rejected", false, f.isFresh(true, true, 25_006));
        f.received(0);
        expect("valid sample recovers after invalid receive", true, f.isFresh(true, true, 0));
        f.invalidate();
        f.invalidate();
        expect("repeated invalidation is safe", false, f.isFresh(true, true, 0));

        f.received(Long.MAX_VALUE - 1);
        expect("large future receive rejected until its monotonic time", false, f.isFresh(true, true, 100));
        expect("large valid times do not overflow subtraction", true, f.isFresh(true, true, Long.MAX_VALUE));
        f.received(0);
        expect("large sample age cannot wrap into freshness", false, f.isFresh(true, true, Long.MAX_VALUE));

        LiveSampleFreshness one = new LiveSampleFreshness(1);
        one.received(0);
        expect("one millisecond window at receive", true, one.isFresh(true, true, 0));
        expect("one millisecond exact boundary", false, one.isFresh(true, true, 1));
        LiveSampleFreshness huge = new LiveSampleFreshness(Long.MAX_VALUE);
        huge.received(0);
        expect("largest permitted window just below boundary", true, huge.isFresh(true, true, Long.MAX_VALUE - 1));
        expect("largest permitted window exact boundary", false, huge.isFresh(true, true, Long.MAX_VALUE));
        rejectWindow(0);
        rejectWindow(-1);
        rejectWindow(Long.MIN_VALUE);
        System.out.println("PASS: " + checked + " LiveSampleFreshness checks");
    }

    private static void rejectWindow(long value) {
        checked++;
        try {
            new LiveSampleFreshness(value);
            throw new AssertionError("Invalid freshness window accepted: " + value);
        } catch (IllegalArgumentException expected) { }
    }

    private static void expect(String label, boolean expected, boolean actual) {
        checked++;
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
