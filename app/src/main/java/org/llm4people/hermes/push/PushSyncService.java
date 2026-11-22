package org.llm4people.hermes.push;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import org.llm4people.hermes.Diagnostics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PushSyncService extends JobService {
    private static final int JOB_ID = 0x484d5055, REGISTRATION_JOB = JOB_ID + 1;
    private final Map<Integer, AtomicBoolean> running = new HashMap<>();

    static void ensureScheduled(Context context) {
        if (context.getSystemService(JobScheduler.class).getPendingJob(JOB_ID) == null) schedule(context);
    }

    private static JobInfo.Builder job(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, PushSyncService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true);
    }

    private static void submit(Context context, JobInfo.Builder builder) {
        if (context.getSystemService(JobScheduler.class).schedule(builder.build()) != JobScheduler.RESULT_SUCCESS)
            Diagnostics.event("push", "scheduling_failed");
    }

    static void schedule(Context context) { submit(context, job(context, JOB_ID)); }

    // A delayed distributor retry must not replace an immediate removal or endpoint upload.
    static void scheduleRetries(Context context) { scheduleRetries(context, false); }

    private static synchronized void scheduleRetries(Context context, boolean completed) {
        long due = Long.MAX_VALUE;
        for (PushStore.Device device : PushStore.get(context).devices()) {
            if (device.unified() && device.retryAt > 0 && (PushStore.PENDING.equals(device.state)
                    || PushStore.ACTIVE.equals(device.state) && device.repair != PushStore.NO_REPAIR))
                due = Math.min(due, device.retryAt);
        }
        if (due == Long.MAX_VALUE) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo current = scheduler.getPendingJob(REGISTRATION_JOB);
        if (!completed && current != null && current.getExtras().getLong("due") == due) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putLong("due", due);
        long delay = due - System.currentTimeMillis();
        submit(context, job(context, REGISTRATION_JOB).setExtras(extras)
                .setMinimumLatency(delay > PushStore.MAX_DELAY ? 0 : Math.max(0, delay)));
    }

    @Override public boolean onStartJob(JobParameters params) {
        AtomicBoolean ticket = new AtomicBoolean();
        int id = params.getJobId();
        running.put(id, ticket);
        PushConnection.WORK.execute(() -> {
            boolean retry;
            if (id == REGISTRATION_JOB) {
                try {
                    if (!ticket.get()) DistributorEnrollment.retryDue(this);
                    retry = false;
                } catch (RuntimeException failure) {
                    Diagnostics.warning("push", "registration_retry_deferred");
                    retry = true;
                }
            } else retry = PushConnection.synchronize(this, ticket::get);
            boolean reschedule = retry;
            getMainExecutor().execute(() -> {
                if (ticket.get()) return;
                running.remove(id);
                jobFinished(params, reschedule);
                if (id == REGISTRATION_JOB && !reschedule) scheduleRetries(this, true);
            });
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        AtomicBoolean ticket = running.remove(params.getJobId());
        if (ticket != null) ticket.set(true);
        return true;
    }
}
