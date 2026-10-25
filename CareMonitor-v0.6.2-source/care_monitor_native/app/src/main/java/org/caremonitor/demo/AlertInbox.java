package org.caremonitor.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local event inbox. Review state does not imply recovery or resolve an event. */
public final class AlertInbox {
    public static final class Entry {
        public final String id;
        public final AlertCatalog.Type type;
        public final long occurredAt;
        public final String deviceId;
        public final String reading;
        public final long acknowledgedAt;

        public Entry(String id, AlertCatalog.Type type, long occurredAt,
                String deviceId, String reading, long acknowledgedAt) {
            if (id == null || id.trim().isEmpty() || type == null || occurredAt <= 0
                    || deviceId == null || deviceId.trim().isEmpty() || acknowledgedAt < 0
                    || (acknowledgedAt > 0 && acknowledgedAt < occurredAt)) {
                throw new IllegalArgumentException("Invalid alert event");
            }
            this.id = id;
            this.type = type;
            this.occurredAt = occurredAt;
            this.deviceId = deviceId;
            this.reading = reading == null || reading.trim().isEmpty() ? "Reading unavailable" : reading;
            this.acknowledgedAt = type.requiresReview ? acknowledgedAt : 0;
        }

        public boolean pending() { return type.requiresReview && acknowledgedAt == 0; }
        public boolean reviewed() { return type.requiresReview && acknowledgedAt > 0; }
    }

    private final Map<String, Entry> events = new LinkedHashMap<>();

    /** Replayed IDs do not duplicate events or overwrite a previous acknowledgement. */
    public boolean add(Entry entry) {
        if (entry == null || events.containsKey(entry.id)) return false;
        events.put(entry.id, entry);
        return true;
    }

    public Entry find(String id) { return events.get(id); }
    public void clear() { events.clear(); }
    public int size() { return events.size(); }

    public boolean acknowledge(String id, long when) {
        Entry entry = find(id);
        if (entry == null || !entry.pending() || when < entry.occurredAt) return false;
        events.put(id, new Entry(entry.id, entry.type, entry.occurredAt,
                entry.deviceId, entry.reading, when));
        return true;
    }

    public int pendingCount() {
        int count = 0;
        for (Entry entry : events.values()) if (entry.pending()) count++;
        return count;
    }

    public int urgentPendingCount() {
        int count = 0;
        for (Entry entry : events.values()) {
            if (entry.pending() && entry.type.severity == AlertCatalog.Severity.URGENT) count++;
        }
        return count;
    }

    public int reviewedCount() {
        int count = 0;
        for (Entry entry : events.values()) if (entry.reviewed()) count++;
        return count;
    }

    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>(events.values());
        Collections.sort(result, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int time = Long.compare(b.occurredAt, a.occurredAt);
                return time == 0 ? a.id.compareTo(b.id) : time;
            }
        });
        return Collections.unmodifiableList(result);
    }
}
