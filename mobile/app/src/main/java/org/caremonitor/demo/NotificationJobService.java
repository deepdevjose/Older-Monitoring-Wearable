package org.caremonitor.demo;

import android.app.job.*;
import java.util.concurrent.*;

/** OS-controlled 15 minute minimum polling; Android can defer it or suppress it after force-stop. */
public final class NotificationJobService extends JobService {
    private ExecutorService worker;
    @Override public boolean onStartJob(JobParameters params){
        worker=Executors.newSingleThreadExecutor();worker.execute(()->{
            boolean retry=false;try{NotificationSync.sync(this);}catch(Exception failure){retry=true;}
            jobFinished(params,retry);worker.shutdown();});return true;
    }
    @Override public boolean onStopJob(JobParameters params){if(worker!=null)worker.shutdownNow();return true;}
}
