package org.caremonitor.demo;
import java.util.*;

public final class NotificationWindowTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        TreeSet<Integer> active=new TreeSet<>();NotificationWindow window=new NotificationWindow(active);
        for(int id=1;id<=30;id++){
            for(int old:window.beforePost(id))active.remove(old);active.add(id);
            check(active.size()<=12&&active.contains(id),"Every new alert survives the native quota window");
        }
        check(active.equals(new TreeSet<>(Arrays.asList(19,20,21,22,23,24,25,26,27,28,29,30))),"Latest twelve cards remain after a thirty-alert burst");
        check(window.beforePost(30).isEmpty(),"A repeated native ID does not evict another card");
        List<Integer> initial=new ArrayList<>();for(int id=1;id<=25;id++)initial.add(id);
        window=new NotificationWindow(initial);List<Integer> removed=window.beforePost(26);
        check(removed.size()==14&&removed.get(0)==1&&removed.get(13)==14,"A previously full OEM tray is reduced before new posting");
        window=new NotificationWindow(Arrays.asList(100,101,102,103,104,105,106,107,108,109,110,111));
        check(!window.beforePost(1).contains(1),"A new target remains visible even when another account had larger IDs");
        window=new NotificationWindow(Arrays.asList(null,-1,0,4,4));
        check(window.beforePost(5).isEmpty(),"Invalid and repeated active IDs do not consume slots");
        try{window.beforePost(0);throw new AssertionError("Invalid target accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS: "+checks+" NotificationWindow checks");
    }
}
