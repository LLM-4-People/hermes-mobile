package org.llm4people.hermes.thread;

import org.junit.Test;
import org.llm4people.hermes.R;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ThreadWorkspaceR3RegressionTest {
    @Test
    public void dispatchDeviceResultEnforcesEpochClosedRevokedAndRejectedExecutionGuards() {
        AtomicInteger executed = new AtomicInteger();
        ExecutorService direct = new DirectExecutor(false);

        // Active workspace dispatches and executes cleanly.
        assertTrue(ThreadWorkspace.dispatchDeviceResult(direct, () -> true, executed::incrementAndGet));
        assertEquals(1, executed.get());

        // Inactive (closed / profile switched / revoked) fails closed without touching executor.
        assertFalse(ThreadWorkspace.dispatchDeviceResult(direct, () -> false, executed::incrementAndGet));
        assertEquals(1, executed.get());

        // Null inputs fail closed.
        assertFalse(ThreadWorkspace.dispatchDeviceResult(null, () -> true, executed::incrementAndGet));
        assertFalse(ThreadWorkspace.dispatchDeviceResult(direct, null, executed::incrementAndGet));
        assertFalse(ThreadWorkspace.dispatchDeviceResult(direct, () -> true, null));

        // Shutdown executor fails closed without throwing.
        ExecutorService stopped = Executors.newSingleThreadExecutor();
        stopped.shutdownNow();
        assertFalse(ThreadWorkspace.dispatchDeviceResult(stopped, () -> true, executed::incrementAndGet));
        assertEquals(1, executed.get());

        // Concurrent RejectedExecutionException is swallowed cleanly.
        ExecutorService rejecting = new DirectExecutor(true);
        assertFalse(ThreadWorkspace.dispatchDeviceResult(rejecting, () -> true, executed::incrementAndGet));
        assertEquals(1, executed.get());

        // If state flips inactive between queueing and running, the worker skips submission.
        AtomicBoolean stillActive = new AtomicBoolean(true);
        final Runnable[] queued = {null};
        ExecutorService deferred = new DirectExecutor(false) {
            @Override public void execute(Runnable command) { queued[0] = command; }
        };
        assertTrue(ThreadWorkspace.dispatchDeviceResult(deferred, stillActive::get, executed::incrementAndGet));
        stillActive.set(false);
        queued[0].run();
        assertEquals(1, executed.get());
    }

    @Test
    public void jumpPillContentDescriptionUsesSingularForOneUnreadAndPluralAboveOne() {
        assertEquals(R.string.infinite_jump_latest_cd, InfiniteScrollController.jumpPillContentDescRes(-1));
        assertEquals(R.string.infinite_jump_latest_cd, InfiniteScrollController.jumpPillContentDescRes(0));
        assertEquals(R.string.infinite_jump_latest_one_cd, InfiniteScrollController.jumpPillContentDescRes(1));
        assertEquals(R.string.infinite_jump_latest_unread_cd, InfiniteScrollController.jumpPillContentDescRes(2));
        assertEquals(R.string.infinite_jump_latest_unread_cd, InfiniteScrollController.jumpPillContentDescRes(42));
    }

    private static class DirectExecutor extends AbstractExecutorService {
        private final boolean reject;

        DirectExecutor(boolean reject) { this.reject = reject; }

        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("shutting down");
            command.run();
        }
    }
}
