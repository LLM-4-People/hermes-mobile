package org.llm4people.hermes.os;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.llm4people.hermes.Diagnostics;

import java.util.concurrent.atomic.AtomicReference;

/** Handles the inline Stop button on the LiveRunNotification strictly within the active unlocked session. */
public final class LiveRunActionReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP_LIVE_RUN = "org.llm4people.hermes.os.STOP_LIVE_RUN";
    private static final AtomicReference<Runnable> STOP_HANDLER = new AtomicReference<>();

    public static void setStopHandler(Runnable handler) {
        STOP_HANDLER.set(handler);
    }

    public static void clearStopHandler(Runnable expected) {
        STOP_HANDLER.compareAndSet(expected, null);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_STOP_LIVE_RUN.equals(intent.getAction())) return;
        Runnable stop = STOP_HANDLER.get();
        if (stop != null) {
            Diagnostics.event("live_run", "stop_requested");
            stop.run();
        } else {
            LiveRunNotification.cancel(context);
        }
    }
}
