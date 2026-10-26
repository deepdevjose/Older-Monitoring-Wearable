package org.caremonitor.demo;

/** Connection-scoped freshness using monotonic elapsed milliseconds only. */
public final class LiveSampleFreshness {
    private final long maximumAgeMs;
    private boolean hasSample;
    private long receivedElapsedMs;

    public LiveSampleFreshness(long maximumAgeMs) {
        if (maximumAgeMs <= 0) throw new IllegalArgumentException("The freshness window must be positive");
        this.maximumAgeMs = maximumAgeMs;
    }

    /** A reconnect / disconnect starts a new sample session. */
    public synchronized void invalidate() {
        hasSample = false;
        receivedElapsedMs = 0;
    }

    /** Zero is a valid monotonic time. A negative timestamp invalidates the sample. */
    public synchronized void received(long elapsedMs) {
        if (elapsedMs < 0) { invalidate(); return; }
        receivedElapsedMs = elapsedMs;
        hasSample = true;
    }

    /** The boundary is exclusive; future / negative times never count as fresh. */
    public synchronized boolean isFresh(boolean running, boolean ready, long nowElapsed) {
        return running && ready && hasSample && nowElapsed >= 0
                && receivedElapsedMs <= nowElapsed
                && nowElapsed - receivedElapsedMs < maximumAgeMs;
    }
}
