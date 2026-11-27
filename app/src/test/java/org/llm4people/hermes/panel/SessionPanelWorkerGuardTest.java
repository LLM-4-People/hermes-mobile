package org.llm4people.hermes.panel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests verifying SessionPanel guards executor submission after teardown/close. */
public final class SessionPanelWorkerGuardTest {
    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void restoreDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void submitWorkerRunsOnLivePoolAndFailsClosedOnNullShutdownOrRace() {
        AtomicInteger executed = new AtomicInteger();
        ExecutorService direct = new AbstractExecutorService() {
            private volatile boolean shutdown;
            @Override public void shutdown() { shutdown = true; }
            @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) {
                if (shutdown) throw new RejectedExecutionException("shutdown");
                command.run();
            }
        };

        assertTrue(SessionPanel.submitWorker(direct, executed::incrementAndGet));
        assertEquals(1, executed.get());
        assertFalse(SessionPanel.submitWorker(direct, null));
        assertFalse(SessionPanel.submitWorker(null, executed::incrementAndGet));

        direct.shutdownNow();
        assertFalse(SessionPanel.submitWorker(direct, executed::incrementAndGet));
        assertEquals(1, executed.get());

        // Simulated race where executor reports !isShutdown() but execute() throws RejectedExecutionException.
        ExecutorService racingPool = new AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { throw new RejectedExecutionException("raced"); }
        };
        assertFalse(SessionPanel.submitWorker(racingPool, executed::incrementAndGet));
        assertTrue(Diagnostics.exportRedacted().contains("panel.worker_submit_rejected"));

        // Post-teardown/unopened SessionPanel (worker == null) returns false before touching box or throwing NPE.
        SessionPanel panel = new SessionPanel(null, null, null);
        assertFalse(panel.request(null, () -> "never", v -> executed.incrementAndGet(), e -> executed.incrementAndGet()));
        assertEquals(1, executed.get());

        ExecutorService realPool = Executors.newSingleThreadExecutor();
        realPool.shutdownNow();
        assertFalse(SessionPanel.submitWorker(realPool, executed::incrementAndGet));
    }
}
