package org.caremonitor.demo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded IO-to-main-thread handoff; old sessions cannot add or consume records. */
final class SessionLineBuffer {
    enum Result { ACCEPTED, STALE, FULL }
    private final int capacity;
    private final ArrayDeque<String> lines = new ArrayDeque<String>();
    private long session;

    SessionLineBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("A positive capacity is required");
        this.capacity = capacity;
    }

    synchronized void reset(long session) {
        this.session = session;
        lines.clear();
    }

    synchronized Result offer(long session, String line) {
        if (this.session != session) return Result.STALE;
        if (line == null) throw new IllegalArgumentException("A line is required");
        if (lines.size() == capacity) return Result.FULL;
        lines.addLast(line);
        return Result.ACCEPTED;
    }

    synchronized List<String> take(long session, int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("A positive batch size is required");
        List<String> result = new ArrayList<String>();
        if (this.session != session) return result;
        while (!lines.isEmpty() && result.size() < maximum) result.add(lines.removeFirst());
        return result;
    }

    synchronized boolean hasPending(long session) {
        return this.session == session && !lines.isEmpty();
    }
}
