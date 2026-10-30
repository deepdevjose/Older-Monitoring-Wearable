package org.caremonitor.demo;

/** Ages server observations with a monotonic clock; a cached 'fresh' flag cannot stay fresh offline. */
final class ReadingAge {
    private ReadingAge() {}
    static double seconds(double atResponse,long receivedElapsed,long nowElapsed) {
        if(Double.isNaN(atResponse)||Double.isInfinite(atResponse)||atResponse<0
                ||receivedElapsed<0||nowElapsed<receivedElapsed)return Double.NaN;
        return atResponse+(nowElapsed-receivedElapsed)/1000.0;
    }
    static boolean fresh(double seconds) {
        return !Double.isNaN(seconds)&&seconds>=0&&seconds<=10;
    }
    static String description(double seconds) {
        if(Double.isNaN(seconds)||Double.isInfinite(seconds)||seconds<0)return "Reading time unavailable";
        if(seconds<=10)return "Last reading: just now";
        if(seconds<60)return "Last reading: "+(long)seconds+" seconds ago";
        long minutes=(long)(seconds/60);
        if(minutes<60)return "Last reading: "+minutes+(minutes==1?" minute ago":" minutes ago");
        long hours=minutes/60;
        if(hours<24)return "Last reading: "+hours+(hours==1?" hour ago":" hours ago");
        long days=hours/24;
        return "Last reading: "+days+(days==1?" day ago":" days ago");
    }
}
