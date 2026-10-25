package org.caremonitor.demo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Standalone JVM checks for local alert review behavior; no Android runtime required. */
public final class AlertInboxTest {
    private static int checked;
    private static int failed;

    public static void main(String[] args) {
        catalog();
        reviewWorkflow();
        orderingAndSnapshots();
        invalidInput();
        if (failed != 0) {
            throw new AssertionError(failed + " of " + checked + " AlertInbox checks failed");
        }
        System.out.println("PASS: " + checked + " AlertInbox checks");
    }

    private static void catalog() {
        AlertCatalog.Type[] types = AlertCatalog.Type.values();
        check("all requested alert variants have catalog entries", types.length == 13);
        Set<String> ids = new HashSet<>();
        for (AlertCatalog.Type type : types) {
            check("unique nonblank type ID: " + type.name(),
                    type.id != null && !type.id.trim().isEmpty() && ids.add(type.id));
            check("type ID round trip: " + type.name(), AlertCatalog.find(type.id) == type);
            check("display copy is present: " + type.name(),
                    present(type.title) && present(type.message) && present(type.source)
                            && present(type.capability) && type.severity != null);
        }
        check("unknown type does not become an unrelated alert", AlertCatalog.find("unknown") == null);
        check("null type ID is not recognized", AlertCatalog.find(null) == null);
        check("empty type ID is not recognized", AlertCatalog.find("") == null);
        check("watch worn is informational", AlertCatalog.Type.WATCH_WORN.severity == AlertCatalog.Severity.INFO);
        check("watch worn does not require acknowledgement", !AlertCatalog.Type.WATCH_WORN.requiresReview);
    }

    private static void reviewWorkflow() {
        AlertInbox inbox = new AlertInbox();
        AlertInbox.Entry fall = event("fall-1", AlertCatalog.Type.POSSIBLE_FALL, 1000);
        AlertInbox.Entry heart = event("heart-1", AlertCatalog.Type.HEART_RATE_HIGH, 1200);
        AlertInbox.Entry worn = new AlertInbox.Entry("worn-1", AlertCatalog.Type.WATCH_WORN,
                1300, "elder-demo-01", "Wear event received", 1400);
        check("first fall is accepted", inbox.add(fall));
        check("independent heart event is accepted", inbox.add(heart));
        check("watch worn event is retained", inbox.add(worn));
        check("informational event is excluded from pending total", inbox.size() == 3 && inbox.pendingCount() == 2);
        check("only urgent pending event is counted as urgent", inbox.urgentPendingCount() == 1);
        check("no event starts reviewed", inbox.reviewedCount() == 0);
        check("informational event has no pending or reviewed state",
                !worn.pending() && !worn.reviewed() && worn.acknowledgedAt == 0);
        check("informational event cannot be acknowledged", !inbox.acknowledge("worn-1", 1500));
        check("unknown event cannot be acknowledged", !inbox.acknowledge("not-found", 1500));
        check("null event ID cannot be acknowledged", !inbox.acknowledge(null, 1500));
        check("negative acknowledgement time is rejected", !inbox.acknowledge("fall-1", -1));
        check("acknowledgement before the event is rejected", !inbox.acknowledge("fall-1", 999));
        check("failed acknowledgements preserve original pending event", inbox.find("fall-1") == fall
                && inbox.pendingCount() == 2 && inbox.reviewedCount() == 0);

        AlertInbox.Entry future = event("future-event", AlertCatalog.Type.WATCH_NOT_WORN, 10000);
        inbox.add(future);
        check("acknowledgement before a future event is rejected", !inbox.acknowledge("future-event", 5000));
        check("future event is unchanged by invalid time", inbox.find("future-event") == future && future.pending());

        check("acknowledgement at event time is valid", inbox.acknowledge("fall-1", 1000));
        AlertInbox.Entry reviewed = inbox.find("fall-1");
        check("review creates a reviewed replacement", reviewed != fall && reviewed.reviewed()
                && !reviewed.pending() && reviewed.acknowledgedAt == 1000);
        check("review preserves event type and source reading", reviewed.type == fall.type
                && reviewed.occurredAt == fall.occurredAt && reviewed.reading.equals(fall.reading)
                && reviewed.deviceId.equals(fall.deviceId));
        check("old event reference is an immutable pending snapshot", fall.pending() && fall.acknowledgedAt == 0);
        check("reviewed fall is excluded from urgent pending count", inbox.urgentPendingCount() == 0);
        check("reviewed total includes the acknowledged event", inbox.reviewedCount() == 1 && inbox.pendingCount() == 2);
        check("repeat acknowledgement is a no-op", !inbox.acknowledge("fall-1", 9000));
        check("repeat acknowledgement preserves first review time", inbox.find("fall-1") == reviewed
                && inbox.find("fall-1").acknowledgedAt == 1000);

        AlertInbox.Entry replay = new AlertInbox.Entry("fall-1", AlertCatalog.Type.OXYGEN_LOW,
                1800, "another-device", "A replay must not replace the original", 0);
        check("replayed ID is rejected even when payload differs", !inbox.add(replay));
        check("replay cannot erase acknowledgement or change event payload", inbox.find("fall-1") == reviewed
                && inbox.reviewedCount() == 1 && inbox.size() == 4);
        check("separate event ID of same type is accepted", inbox.add(event("fall-2", AlertCatalog.Type.POSSIBLE_FALL, 2000)));
        check("new event still needs separate review", inbox.find("fall-2").pending()
                && inbox.pendingCount() == 3 && inbox.urgentPendingCount() == 1);
    }

    private static void orderingAndSnapshots() {
        AlertInbox inbox = new AlertInbox();
        inbox.add(event("older", AlertCatalog.Type.HEART_RATE_LOW, 100));
        inbox.add(event("b", AlertCatalog.Type.OXYGEN_HIGH, 200));
        inbox.add(event("a", AlertCatalog.Type.HYDRATION_CHECK, 200));
        List<AlertInbox.Entry> snapshot = inbox.entries();
        check("newest events first with stable ID order for ties", ids(snapshot).equals("a,b,older"));
        check("repeated reads keep deterministic order", ids(inbox.entries()).equals(ids(snapshot)));
        expectUnsupported("returned list cannot add", () -> snapshot.add(event("extra", AlertCatalog.Type.POSSIBLE_FALL, 300)));
        expectUnsupported("returned list cannot remove", () -> snapshot.remove(0));
        expectUnsupported("returned list cannot replace", () -> snapshot.set(0, event("extra", AlertCatalog.Type.POSSIBLE_FALL, 300)));
        expectUnsupported("returned list cannot clear", snapshot::clear);
        check("failed list mutations leave inbox unchanged", inbox.size() == 3 && ids(inbox.entries()).equals("a,b,older"));

        AlertInbox.Entry firstSnapshotEvent = snapshot.get(0);
        check("snapshot event can be acknowledged in inbox", inbox.acknowledge("a", 300));
        check("returned entry snapshot does not change after review", firstSnapshotEvent.pending()
                && firstSnapshotEvent.acknowledgedAt == 0 && inbox.find("a").reviewed());
        inbox.add(event("newest", AlertCatalog.Type.WATCH_WORN, 400));
        check("old list is a snapshot rather than a live view", snapshot.size() == 3
                && ids(snapshot).equals("a,b,older") && inbox.entries().get(0).id.equals("newest"));
        for (Field field : AlertInbox.Entry.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                check("entry field is immutable: " + field.getName(), Modifier.isFinal(field.getModifiers()));
            }
        }
        inbox.clear();
        check("clear removes pending and reviewed events", inbox.size() == 0 && inbox.pendingCount() == 0
                && inbox.urgentPendingCount() == 0 && inbox.reviewedCount() == 0 && inbox.find("a") == null);
        check("clear leaves earlier returned snapshot intact", snapshot.size() == 3 && firstSnapshotEvent.pending());
    }

    private static void invalidInput() {
        AlertInbox inbox = new AlertInbox();
        check("null event is not accepted", !inbox.add(null) && inbox.size() == 0);
        invalid("null event ID", null, AlertCatalog.Type.POSSIBLE_FALL, 100, "device", 0);
        invalid("empty event ID", "", AlertCatalog.Type.POSSIBLE_FALL, 100, "device", 0);
        invalid("blank event ID", " \t ", AlertCatalog.Type.POSSIBLE_FALL, 100, "device", 0);
        invalid("null event type", "id", null, 100, "device", 0);
        invalid("zero occurrence time", "id", AlertCatalog.Type.POSSIBLE_FALL, 0, "device", 0);
        invalid("negative occurrence time", "id", AlertCatalog.Type.POSSIBLE_FALL, -100, "device", 0);
        invalid("null device ID", "id", AlertCatalog.Type.POSSIBLE_FALL, 100, null, 0);
        invalid("empty device ID", "id", AlertCatalog.Type.POSSIBLE_FALL, 100, "", 0);
        invalid("blank device ID", "id", AlertCatalog.Type.POSSIBLE_FALL, 100, "  ", 0);
        invalid("negative review time", "id", AlertCatalog.Type.POSSIBLE_FALL, 100, "device", -1);
        invalid("review time before occurrence", "id", AlertCatalog.Type.POSSIBLE_FALL, 100, "device", 50);
        AlertInbox.Entry missingReading = new AlertInbox.Entry("missing", AlertCatalog.Type.BODY_TEMP_LOW,
                100, "device", null, 0);
        AlertInbox.Entry blankReading = new AlertInbox.Entry("blank", AlertCatalog.Type.BODY_TEMP_HIGH,
                100, "device", "  ", 0);
        check("missing reading has an explicit fallback", "Reading unavailable".equals(missingReading.reading));
        check("blank reading has an explicit fallback", "Reading unavailable".equals(blankReading.reading));
        AlertInbox.Entry alreadyReviewed = new AlertInbox.Entry("reviewed", AlertCatalog.Type.AMBIENT_TEMP_LOW,
                100, "device", "Imported local review", 100);
        check("valid pre-reviewed event retains review state", alreadyReviewed.reviewed()
                && !alreadyReviewed.pending() && alreadyReviewed.acknowledgedAt == 100);
    }

    private static AlertInbox.Entry event(String id, AlertCatalog.Type type, long when) {
        return new AlertInbox.Entry(id, type, when, "elder-demo-01", "Illustrative event", 0);
    }

    private static boolean present(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private static String ids(List<AlertInbox.Entry> entries) {
        StringBuilder result = new StringBuilder();
        for (AlertInbox.Entry entry : entries) {
            if (result.length() != 0) result.append(',');
            result.append(entry.id);
        }
        return result.toString();
    }

    private static void invalid(String name, String id, AlertCatalog.Type type,
                                long when, String deviceId, long reviewedAt) {
        boolean rejected = false;
        try {
            new AlertInbox.Entry(id, type, when, deviceId, "Reading", reviewedAt);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check("invalid input rejected: " + name, rejected);
    }

    private static void expectUnsupported(String name, Runnable operation) {
        boolean rejected = false;
        try {
            operation.run();
        } catch (UnsupportedOperationException expected) {
            rejected = true;
        }
        check(name, rejected);
    }

    private static void check(String name, boolean passed) {
        checked++;
        if (!passed) {
            failed++;
            System.err.println("FAIL: " + name);
        }
    }
}
