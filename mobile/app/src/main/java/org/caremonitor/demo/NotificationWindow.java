package org.caremonitor.demo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Bounded native cards; the complete server inbox and in-app history remain durable. */
final class NotificationWindow {
    static final int MAX_VISIBLE=12;
    private final TreeSet<Integer> visible=new TreeSet<>();
    NotificationWindow(Collection<Integer> active){for(Integer id:active)if(id!=null&&id>0)visible.add(id);}
    List<Integer> beforePost(int id){
        if(id<=0)throw new IllegalArgumentException("Notification identity unavailable");
        visible.add(id);List<Integer> evicted=new ArrayList<>();
        while(visible.size()>MAX_VISIBLE){int oldest=visible.first();if(oldest==id)oldest=visible.higher(oldest);visible.remove(oldest);evicted.add(oldest);}
        return evicted;
    }
}
