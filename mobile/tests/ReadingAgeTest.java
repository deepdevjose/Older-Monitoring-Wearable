package org.caremonitor.demo;

/** Boundary checks for cached server observations; no wall clock or Android dependency. */
public final class ReadingAgeTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Reading age boundary "+checks);}
    public static void main(String[] args){
        check(ReadingAge.fresh(ReadingAge.seconds(1,1000,1000)));
        check(ReadingAge.fresh(ReadingAge.seconds(1,1000,10000)));
        check(!ReadingAge.fresh(ReadingAge.seconds(1,1000,10001)));
        check(!ReadingAge.fresh(ReadingAge.seconds(1,1000,61_000)));
        check(!ReadingAge.fresh(ReadingAge.seconds(Double.NaN,1000,1000)));
        check(!ReadingAge.fresh(ReadingAge.seconds(Double.POSITIVE_INFINITY,1000,1000)));
        check(!ReadingAge.fresh(ReadingAge.seconds(-1,1000,1000)));
        check(!ReadingAge.fresh(ReadingAge.seconds(1,-1,1000)));
        check(!ReadingAge.fresh(ReadingAge.seconds(1,1000,999)));
        check(!ReadingAge.fresh(ReadingAge.seconds(1,0,Long.MAX_VALUE)));
        check(ReadingAge.seconds(4,200,1200)==5);
        check(ReadingAge.description(Double.NaN).equals("Reading time unavailable"));
        check(ReadingAge.description(0).equals("Last reading: just now"));
        check(ReadingAge.description(10).equals("Last reading: just now"));
        check(ReadingAge.description(11).equals("Last reading: 11 seconds ago"));
        check(ReadingAge.description(59).equals("Last reading: 59 seconds ago"));
        check(ReadingAge.description(60).equals("Last reading: 1 minute ago"));
        check(ReadingAge.description(3599).equals("Last reading: 59 minutes ago"));
        check(ReadingAge.description(3600).equals("Last reading: 1 hour ago"));
        check(ReadingAge.description(86399).equals("Last reading: 23 hours ago"));
        check(ReadingAge.description(86400).equals("Last reading: 1 day ago"));
        check(ReadingAge.description(172800).equals("Last reading: 2 days ago"));
        System.out.println("PASS: "+checks+" ReadingAge checks");
    }
}
