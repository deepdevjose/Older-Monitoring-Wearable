package org.caremonitor.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable server-confirmed alert ledger. Sensor JSON alerts never enter this ledger. */
public final class RemoteAlertInbox {
    public static final int MAX_ENTRIES=1000;
    public static final class Entry {
        public final long id,revision,occurredAt,updatedAt,acknowledgedAt,resolvedAt;
        public final String type,severity,source,status,deviceId,patientId,reading,ruleVersion,modelVersion,evidence;
        public Entry(long id,long revision,String type,String severity,String source,String status,String deviceId,
                String patientId,String reading,long occurredAt,long updatedAt,long acknowledgedAt,long resolvedAt,
                String ruleVersion,String modelVersion,String evidence) {
            if(id<=0||revision<=0||type==null||type.isEmpty()||type.length()>100||!RelayPolicy.EXPECTED_DEVICE_ID.equals(deviceId)
                    ||!("rule".equals(source)||"quality".equals(source))
                    ||!("active".equals(status)||"acknowledged".equals(status)||"resolved".equals(status))
                    ||!("info".equals(severity)||"notice".equals(severity)||"warning".equals(severity)||"urgent".equals(severity)||"critical".equals(severity))
                    ||occurredAt<=0||updatedAt<occurredAt||acknowledgedAt<0||resolvedAt<0
                    ||("acknowledged".equals(status)&&acknowledgedAt<=0)||("resolved".equals(status)&&resolvedAt<=0))
                throw new IllegalArgumentException("Invalid server alert.");
            this.id=id;this.revision=revision;this.type=type;this.severity=severity;this.source=source;this.status=status;
            this.deviceId=deviceId;this.patientId=patientId;this.reading=reading;this.occurredAt=occurredAt;
            this.updatedAt=updatedAt;this.acknowledgedAt=acknowledgedAt;this.resolvedAt=resolvedAt;
            this.ruleVersion=ruleVersion;this.modelVersion=modelVersion;this.evidence=evidence;
        }
        public boolean pending(){return "active".equals(status);}
        public boolean reviewed(){return acknowledgedAt>0;}
        public boolean resolved(){return "resolved".equals(status);}
        private boolean sameRevision(Entry other){
            return id==other.id&&revision==other.revision&&occurredAt==other.occurredAt&&updatedAt==other.updatedAt
                &&acknowledgedAt==other.acknowledgedAt&&resolvedAt==other.resolvedAt&&type.equals(other.type)
                &&severity.equals(other.severity)&&source.equals(other.source)&&status.equals(other.status)
                &&deviceId.equals(other.deviceId)&&java.util.Objects.equals(patientId,other.patientId)
                &&java.util.Objects.equals(reading,other.reading)&&java.util.Objects.equals(ruleVersion,other.ruleVersion)
                &&java.util.Objects.equals(modelVersion,other.modelVersion)&&java.util.Objects.equals(evidence,other.evidence);
        }
    }
    private final Map<Long,Entry> records;
    public final long revision;
    public RemoteAlertInbox(){this(new LinkedHashMap<>(),0);}
    private RemoteAlertInbox(Map<Long,Entry> records,long revision){this.records=records;this.revision=revision;}
    public List<Entry> entries(){
        List<Entry> result=new ArrayList<>(records.values());
        result.sort(Comparator.comparingLong((Entry e)->e.updatedAt).reversed().thenComparingLong(e->e.id));
        return Collections.unmodifiableList(result);
    }
    public int pendingCount(){int n=0;for(Entry e:records.values())if(e.pending())n++;return n;}
    public int urgentPendingCount(){int n=0;for(Entry e:records.values())if(e.pending()&&("urgent".equals(e.severity)||"critical".equals(e.severity)))n++;return n;}
    public RemoteAlertInbox page(List<Entry> delta,long nextRevision,boolean hasMore) {
        if(nextRevision<revision||(hasMore&&nextRevision<=revision))throw new IllegalArgumentException("Invalid alert cursor.");
        long last=revision;for(Entry entry:delta)last=Math.max(last,entry.revision);
        if(nextRevision!=last)throw new IllegalArgumentException("Cursor does not match the validated page.");
        Map<Long,Entry> next=new LinkedHashMap<>(records);java.util.HashSet<Long> ids=new java.util.HashSet<>();
        for(Entry entry:delta){
            if(entry.revision<=revision||entry.revision>nextRevision||!ids.add(entry.id))throw new IllegalArgumentException("Invalid delta revision.");
            Entry before=next.get(entry.id);
            // ACK can update one record ahead of the GET cursor. A later delta must still deliver
            // intervening records, while replaying the already-confirmed ACK without regressing it.
            if(before!=null&&entry.revision<before.revision)continue;
            if(before!=null&&entry.revision==before.revision){
                if(!entry.sameRevision(before))throw new IllegalArgumentException("Conflicting alert revision.");
                continue;
            }
            if(before!=null&&before.resolved()&&!entry.resolved())throw new IllegalArgumentException("Resolved episode cannot reopen.");
            next.put(entry.id,entry);
        }
        trimResolved(next);
        return new RemoteAlertInbox(next,nextRevision);
    }
    /** ACK confirmation does not advance the GET cursor: intervening alert changes still need fetching. */
    public RemoteAlertInbox acknowledge(Entry confirmed){
        Entry before=records.get(confirmed.id);
        if(before==null||!confirmed.reviewed()||confirmed.revision<before.revision)
            throw new IllegalArgumentException("Invalid acknowledgement confirmation.");
        if(before.resolved()&&!confirmed.resolved())throw new IllegalArgumentException("Resolved episode cannot reopen.");
        Map<Long,Entry> next=new LinkedHashMap<>(records);next.put(confirmed.id,confirmed);
        return new RemoteAlertInbox(next,revision);
    }
    /** Used only after validating private cache metadata and each persisted record. */
    public static RemoteAlertInbox restore(List<Entry> entries,long revision){
        if(revision<0)throw new IllegalArgumentException("Invalid stored cursor.");
        Map<Long,Entry> next=new LinkedHashMap<>();
        for(Entry e:entries)if(next.put(e.id,e)!=null)throw new IllegalArgumentException("Duplicate cache record.");
        trimResolved(next);return new RemoteAlertInbox(next,revision);
    }
    private static void trimResolved(Map<Long,Entry> next){
        if(next.size()<=MAX_ENTRIES)return;
        List<Entry> resolved=new ArrayList<>();for(Entry e:next.values())if(e.resolved())resolved.add(e);
        resolved.sort(Comparator.comparingLong(e->e.updatedAt));
        for(Entry e:resolved){if(next.size()<=MAX_ENTRIES)break;next.remove(e.id);}
        if(next.size()>MAX_ENTRIES)throw new IllegalArgumentException("Alert cache full; pending episodes retained.");
    }
}
