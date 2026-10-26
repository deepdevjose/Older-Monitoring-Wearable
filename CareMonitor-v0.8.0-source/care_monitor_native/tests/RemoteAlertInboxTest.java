package org.caremonitor.demo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Regression coverage for remote-only identity, lifecycle, durable-page candidates, and ACK cursor races. */
public final class RemoteAlertInboxTest {
    private static int checks;
    private static void check(boolean value,String description){checks++;if(!value)throw new AssertionError(description);}
    private static void rejects(Runnable code,String description){checks++;try{code.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError(description);}
    private static RemoteAlertInbox.Entry entry(long id,long revision,String status){
        return new RemoteAlertInbox.Entry(id,revision,"heart_rate_high","warning","rule",status,"elder-demo-01",
            "patient-001","Reading recorded",1000,1000+revision,"active".equals(status)?0:1000+revision,
            "resolved".equals(status)?1000+revision:0,"rules-v1","","{}");
    }
    public static void main(String[]args){
        RemoteAlertInbox empty=new RemoteAlertInbox();check(empty.entries().isEmpty(),"initially empty");check(empty.revision==0,"initial cursor");
        RemoteAlertInbox first=empty.page(Arrays.asList(entry(1,1,"active"),entry(2,2,"active")),2,false);
        check(empty.entries().isEmpty(),"candidate leaves previous ledger unchanged for failed persistence");
        check(first.entries().size()==2,"two separate episodes");check(first.pendingCount()==2,"pending episodes");
        RemoteAlertInbox confirmed=first.acknowledge(entry(1,5,"acknowledged"));
        check(confirmed.revision==2,"ACK does not skip unreceived delta revisions");check(confirmed.pendingCount()==1,"ACK reviewed separately from recovery");
        check(!confirmed.entries().get(0).resolved(),"ACK is not resolution");check(confirmed.entries().get(0).reviewed(),"review timestamp recorded");
        RemoteAlertInbox delta=confirmed.page(Arrays.asList(entry(3,3,"active"),entry(1,5,"acknowledged")),5,false);
        check(delta.revision==5,"ACK replay advances GET cursor");check(delta.entries().size()==3,"intervening alert retained");
        check(delta.pendingCount()==2,"replayed ACK not pending");
        RemoteAlertInbox older=confirmed.page(Arrays.asList(entry(1,4,"active"),entry(3,3,"active")),4,true);
        check(older.entries().get(0).reviewed(),"older delta cannot regress confirmed ACK");
        RemoteAlertInbox resolved=delta.page(Collections.singletonList(entry(1,6,"resolved")),6,false);
        check(resolved.entries().get(0).resolved(),"server recovery resolves episode");
        rejects(()->resolved.page(Collections.singletonList(entry(1,7,"active")),7,false),"resolved episode cannot reopen");
        rejects(()->first.page(Collections.emptyList(),1,false),"cursor cannot regress");
        rejects(()->first.page(Collections.emptyList(),2,true),"pagination must progress");
        rejects(()->first.page(Collections.singletonList(entry(3,2,"active")),3,false),"delta cannot predate cursor");
        rejects(()->first.page(Collections.singletonList(entry(3,4,"active")),3,false),"record revision cannot exceed cursor");
        rejects(()->first.page(Arrays.asList(entry(3,3,"active"),entry(3,4,"active")),4,false),"one record per page");
        rejects(()->first.acknowledge(entry(9,3,"acknowledged")),"unknown alert cannot be acknowledged locally");
        rejects(()->first.acknowledge(entry(1,3,"active")),"unconfirmed ACK rejected");
        rejects(()->confirmed.page(Collections.singletonList(entry(1,5,"resolved")),5,false),"same revision cannot conflict");
        try{first.entries().clear();throw new AssertionError("mutable published snapshot");}catch(UnsupportedOperationException expected){checks++;}
        for(String source:new String[]{"ml","firmware","phone","demo","unknown"})rejects(()->new RemoteAlertInbox.Entry(1,1,"fall","urgent",source,"active","elder-demo-01","p","",1,2,0,0,"v","m","{}"),"untrusted source "+source);
        rejects(()->new RemoteAlertInbox.Entry(1,1,"fall","urgent","rule","active","other-device","p","",1,2,0,0,"v","","{}"),"cross-device data rejected");
        rejects(()->new RemoteAlertInbox.Entry(1,1,"fall","unknown","rule","active","elder-demo-01","p","",1,2,0,0,"v","","{}"),"unknown severity rejected");
        rejects(()->new RemoteAlertInbox.Entry(1,1,"fall","urgent","rule","acknowledged","elder-demo-01","p","",1,2,0,0,"v","","{}"),"ACK timestamp required");
        rejects(()->new RemoteAlertInbox.Entry(1,1,"fall","urgent","rule","resolved","elder-demo-01","p","",1,2,0,0,"v","","{}"),"resolution timestamp required");
        RemoteAlertInbox.Entry urgent=new RemoteAlertInbox.Entry(7,7,"fall","urgent","rule","active","elder-demo-01","p","",1,2,0,0,"v","","{}");
        RemoteAlertInbox.Entry quality=new RemoteAlertInbox.Entry(8,8,"sensor_unavailable","notice","quality","active","elder-demo-01","p","",1,2,0,0,"v","","{}");
        check(new RemoteAlertInbox().page(Arrays.asList(urgent,quality),8,false).urgentPendingCount()==1,"quality is not urgent care alarm");
        List<RemoteAlertInbox.Entry> full=new ArrayList<>();for(int i=1;i<=1000;i++)full.add(entry(i,i,"active"));
        RemoteAlertInbox pending=RemoteAlertInbox.restore(full,1000);
        rejects(()->pending.page(Collections.singletonList(entry(1001,1001,"active")),1001,false),"pending episodes never silently discarded on capacity");
        check(pending.entries().size()==1000,"previous pending cache remains intact");
        full.set(0,entry(1,1001,"resolved"));RemoteAlertInbox trim=RemoteAlertInbox.restore(full,1001).page(Collections.singletonList(entry(1001,1002,"active")),1002,false);
        check(trim.entries().size()==1000,"resolved retention bounded");check(trim.pendingCount()==1000,"all pending retained");
        rejects(()->RemoteAlertInbox.restore(Arrays.asList(entry(1,1,"active"),entry(1,2,"active")),2),"duplicate cache identity");
        ServerCredentialState saved=ServerCredentialState.saved(true,"Key saved securely");
        check(saved.saved&&!saved.verified,"saved does not mean remotely verified");check(!saved.allows("sensor:write",10),"saved credential cannot authorize requests");
        ServerCredentialState upload=ServerCredentialState.verified("k-upload","elder-demo-01",Collections.singletonList("sensor:write"),"expires",1000,"server-1",10);
        check(upload.allows("sensor:write",11),"verified upload allowed");check(!upload.allows("alerts:read",11),"upload scope cannot read alerts");
        check(!upload.allows("sensor:write",1000),"expiration stops requests");
        check(!upload.current(1000).verified&&upload.current(1000).status.startsWith("Key expired"),"expiry appears immediately in UI metadata");
        rejects(()->first.page(Collections.emptyList(),9,false),"empty page cannot skip unknown alert revisions");
        ServerCredentialState caregiver=ServerCredentialState.verified("k-reader","elder-demo-01",Collections.singletonList("alerts:read"),"expires",1000,"server-1",10);
        check(caregiver.allows("alerts:read",11),"caregiver read allowed");check(!caregiver.allows("alerts:ack",11),"read-only key cannot acknowledge");
        ServerCredentialState revoked=upload.failure("Key revoked");check(revoked.saved&&!revoked.verified,"rejected key retained securely but unverified");check(!revoked.allows("sensor:write",11),"rejection disables prior permissions");
        check(revoked.verifiedAt==10,"last verified time remains historical metadata");
        try{upload.scopes.add("alerts:ack");throw new AssertionError("mutable scope list");}catch(UnsupportedOperationException expected){checks++;}
        rejects(()->ServerCredentialState.verified("k","elder-demo-01",Collections.emptyList(),"expired",10,"server",10),"cannot verify expired credential");
        System.out.println("RemoteAlertInboxTest: "+checks+" checks passed");
    }
}
