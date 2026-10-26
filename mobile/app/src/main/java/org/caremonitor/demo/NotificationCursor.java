package org.caremonitor.demo;

/** Delivery belongs to this installation; a server account acknowledgement never suppresses another phone. */
final class NotificationCursor {
    private NotificationCursor(){}
    static boolean shouldDeliver(long eventId,long localCursor){return eventId>0&&eventId>Math.max(0,localCursor);}
    static long advance(long localCursor,long serverCursor){return Math.max(Math.max(0,localCursor),serverCursor);}
}
