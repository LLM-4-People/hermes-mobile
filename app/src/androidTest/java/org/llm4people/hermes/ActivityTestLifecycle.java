package org.llm4people.hermes;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;

import junit.framework.Assert;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single Source of Truth for deterministic {@link Activity} teardown in instrumentation tests.
 * Always waits for {@link Activity#isDestroyed()} so asynchronous {@code onDestroy()} callbacks
 * (e.g. {@link MainActivity#onDestroy()}) never leak into the next test.
 */
@SuppressWarnings("deprecation")
public final class ActivityTestLifecycle {
    private static final long DESTROY_TIMEOUT_MS = 5_000L;
    private static final long POLL_STEP_MS = 10L;

    private ActivityTestLifecycle() {}

    /** Finish {@code activity} on the main thread and wait until {@link Activity#isDestroyed()} is true. */
    public static void finishAndAwaitDestroyed(Instrumentation instrumentation, Activity activity) {
        if (instrumentation == null || activity == null) return;
        instrumentation.runOnMainSync(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.finish();
            }
        });
        instrumentation.waitForIdleSync();
        long deadline = SystemClock.uptimeMillis() + DESTROY_TIMEOUT_MS;
        AtomicBoolean destroyed = new AtomicBoolean(false);
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> destroyed.set(activity.isDestroyed()));
            if (destroyed.get()) return;
            SystemClock.sleep(POLL_STEP_MS);
        }
        instrumentation.runOnMainSync(() -> destroyed.set(activity.isDestroyed()));
        Assert.assertTrue("Expected launched Activity to complete onDestroy() before next test", destroyed.get());
    }

    /** Convenience overload for {@link InstrumentationTestCase} subclasses. */
    public static void finishAndAwaitDestroyed(InstrumentationTestCase test, Activity activity) {
        if (test == null || activity == null) return;
        finishAndAwaitDestroyed(test.getInstrumentation(), activity);
    }
}
