package org.caremonitor.demo;

/** Two phones using the same caregiver account must independently receive each event. */
public final class NotificationCursorTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Notification check "+checks);}
    public static void main(String[] args){
        long phoneA=5,phoneB=5,event=7;
        check(NotificationCursor.shouldDeliver(event,phoneA));
        phoneA=NotificationCursor.advance(phoneA,event);
        // A server acknowledgement by A does not alter B's installation cursor.
        check(NotificationCursor.shouldDeliver(event,phoneB));
        check(!NotificationCursor.shouldDeliver(event,phoneA));
        phoneB=NotificationCursor.advance(phoneB,event);
        check(!NotificationCursor.shouldDeliver(event,phoneB));
        check(NotificationCursor.shouldDeliver(12,phoneA)); // IDs are global; gaps are expected.
        check(NotificationCursor.advance(12,7)==12); // Late response cannot rewind cursor.
        check(NotificationCursor.advance(12,12)==12);
        check(NotificationCursor.advance(0,0)==0);
        check(NotificationCursor.advance(-1,-1)==0);
        check(!NotificationCursor.shouldDeliver(0,0));
        check(!NotificationCursor.shouldDeliver(-1,0));
        check(NotificationCursor.shouldDeliver(1,-1));
        check(NotificationCursor.shouldDeliver(Long.MAX_VALUE,Long.MAX_VALUE-1));
        System.out.println("PASS: "+checks+" NotificationCursor checks");
    }
}
