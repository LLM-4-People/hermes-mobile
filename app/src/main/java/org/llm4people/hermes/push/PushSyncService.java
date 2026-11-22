package org.llm4people.hermes.push;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.llm4people.hermes.Diagnostics;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PushSyncService extends JobService {
    private static final int JOB_ID = 0x484d5055;
    private AtomicBoolean stopped;

    static void schedule(Context context) {
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, PushSyncService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        if (context.getSystemService(JobScheduler.class).schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Diagnostics.event("push", "scheduling_failed");
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        AtomicBoolean ticket = new AtomicBoolean();
        stopped = ticket;
        PushConnection.WORK.execute(() -> {
            boolean retry = PushConnection.synchronize(getApplicationContext(), ticket::get);
            getMainExecutor().execute(() -> {
                if (!ticket.get()) jobFinished(params, retry);
            });
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (stopped != null) stopped.set(true);
        return true;
    }
}
