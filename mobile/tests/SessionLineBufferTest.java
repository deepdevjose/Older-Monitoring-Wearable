package org.caremonitor.demo;

import java.util.Arrays;
import java.util.List;

/** Exercises bounded backlogs and old-session isolation without Android hardware. */
public final class SessionLineBufferTest {
    private static int checked;
    public static void main(String[] args) {
        SessionLineBuffer queue = new SessionLineBuffer(3);
        queue.reset(100);
        expect("first", queue.offer(100, "first"), SessionLineBuffer.Result.ACCEPTED);
        expect("second", queue.offer(100, "second"), SessionLineBuffer.Result.ACCEPTED);
        expect("third", queue.offer(100, "third"), SessionLineBuffer.Result.ACCEPTED);
        expect("full does not evict", queue.offer(100, "lost"), SessionLineBuffer.Result.FULL);
        expect("stale producer cannot affect full queue", queue.offer(99, "old"), SessionLineBuffer.Result.STALE);
        expect("stale consumer sees nothing", queue.take(99, 4));
        check("pending remains after stale read", queue.hasPending(100));
        expect("bounded FIFO batch", queue.take(100, 2), "first", "second");
        check("third remains", queue.hasPending(100));
        expect("capacity released after drain", queue.offer(100, "fourth"), SessionLineBuffer.Result.ACCEPTED);
        expect("FIFO preserved after rejected overflow", queue.take(100, 9), "third", "fourth");
        check("drained", !queue.hasPending(100));
        queue.offer(100, "disconnect backlog");
        queue.reset(101);
        check("reset clears pending", !queue.hasPending(101));
        expect("old producer after reconnect", queue.offer(100, "stale"), SessionLineBuffer.Result.STALE);
        expect("new producer", queue.offer(101, "new"), SessionLineBuffer.Result.ACCEPTED);
        expect("old consumer cannot steal new record", queue.take(100, 4));
        expect("new record survives stale callback", queue.take(101, 4), "new");
        for (int iteration = 0; iteration < 100; iteration++) {
            long session = 102L + iteration;
            queue.reset(session);
            queue.offer(session, "A"); queue.offer(session, "B"); queue.offer(session, "C");
            expect("flood is bounded " + iteration, queue.offer(session, "D"), SessionLineBuffer.Result.FULL);
            expect("session rejects delayed IO " + iteration, queue.offer(session - 1, "old"), SessionLineBuffer.Result.STALE);
            expect("flood preserves accepted order " + iteration, queue.take(session, 4), "A", "B", "C");
        }
        expectIllegal("zero capacity", () -> new SessionLineBuffer(0));
        expectIllegal("zero batch", () -> queue.take(201, 0));
        expectIllegal("null current line", () -> queue.offer(201, null));
        System.out.println("PASS: " + checked + " SessionLineBuffer checks");
    }
    private static void check(String label, boolean value) {
        checked++; if (!value) throw new AssertionError(label);
    }
    private static void expect(String label, SessionLineBuffer.Result actual, SessionLineBuffer.Result expected) {
        check(label, actual == expected);
    }
    private static void expect(String label, List<String> actual, String... expected) {
        check(label, actual.equals(Arrays.asList(expected)));
    }
    private static void expectIllegal(String label, Runnable operation) {
        try { operation.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { checked++; }
    }
}
