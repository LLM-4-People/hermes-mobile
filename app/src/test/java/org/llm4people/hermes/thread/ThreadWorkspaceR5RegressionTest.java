package org.llm4people.hermes.thread;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for R5.1.1 (pageSize-aware ThreadContext.messageCount) and R5.3.3 (capture slot cleanup). */
public final class ThreadWorkspaceR5RegressionTest {

    @Before
    public void setUpDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, msg) -> {});
    }

    @After
    public void tearDownDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void messageCountUsesConfiguredPageSizeForLegacyPagination() {
        // pageSize = 20: 20 rows is a full page (unknown total -> -1); 19 rows is final page -> distinctRowsLoaded.
        assertEquals(-1L, ThreadContext.messageCount(-1L, 20, 20, 20));
        assertEquals(19L, ThreadContext.messageCount(-1L, 19, 19, 20));
        assertEquals(39L, ThreadContext.messageCount(-1L, 19, 39, 20));

        // pageSize = 60: 40 rows is < 60 (final page -> 40, not -1); 60 rows is full page -> -1.
        assertEquals(40L, ThreadContext.messageCount(-1L, 40, 40, 60));
        assertEquals(-1L, ThreadContext.messageCount(-1L, 60, 60, 60));
        assertEquals(115L, ThreadContext.messageCount(-1L, 55, 115, 60));

        // Explicit gateway total always wins regardless of pageSize.
        assertEquals(83L, ThreadContext.messageCount(83L, 20, 20, 20));
        assertEquals(0L, ThreadContext.messageCount(0L, 0, 0, 60));

        // Non-positive pageSize falls back to ThreadData.PAGE (40).
        assertEquals(-1L, ThreadContext.messageCount(-1L, ThreadData.PAGE, ThreadData.PAGE, 0));
        assertEquals(39L, ThreadContext.messageCount(-1L, ThreadData.PAGE - 1, 39, -5));
    }

    @Test
    public void submitCapturedSlotDeletesOnRejectionInactiveAbortAndDrain() {
        Set<String> tracked = ConcurrentHashMap.newKeySet();
        List<String> deleted = new ArrayList<>();
        AtomicInteger workRuns = new AtomicInteger();

        // 1) RejectedExecutionException deletes slot immediately and leaves tracked set empty.
        String slotRejected = "content://test/slot-rejected";
        ExecutorService rejecting = rejectingExecutor();
        assertFalse(ThreadWorkspace.submitCapturedSlot(
                slotRejected, rejecting, tracked, () -> true,
                workRuns::incrementAndGet, e -> {}, deleted::add));
        assertTrue(tracked.isEmpty());
        assertEquals(List.of(slotRejected), deleted);
        assertEquals(0, workRuns.get());
        assertTrue("Expected camera.import_rejected diagnostic warning",
                Diagnostics.exportRedacted().contains("camera.import_rejected"));

        // 2) Inactive/closed/revoked worker skips work.run() AND still deletes slot in finally.
        deleted.clear();
        String slotAborted = "content://test/slot-aborted";
        ExecutorService direct = directExecutor();
        assertTrue(ThreadWorkspace.submitCapturedSlot(
                slotAborted, direct, tracked, () -> false,
                workRuns::incrementAndGet, e -> {}, deleted::add));
        assertTrue(tracked.isEmpty());
        assertEquals(List.of(slotAborted), deleted);
        assertEquals(0, workRuns.get());

        // 3) Queued task dropped by shutdownNow() before running is still deleted by drainCaptureSlots().
        deleted.clear();
        String slotQueued = "content://test/slot-queued";
        List<Runnable> queued = new ArrayList<>();
        ExecutorService queuing = queuingExecutor(queued);
        assertTrue(ThreadWorkspace.submitCapturedSlot(
                slotQueued, queuing, tracked, () -> true,
                workRuns::incrementAndGet, e -> {}, deleted::add));
        assertEquals(1, tracked.size());
        assertTrue(deleted.isEmpty());
        ThreadWorkspace.drainCaptureSlots(tracked, deleted::add);
        assertTrue(tracked.isEmpty());
        assertEquals(List.of(slotQueued), deleted);
    }

    private static ExecutorService rejectingExecutor() {
        return (ExecutorService) Proxy.newProxyInstance(
                ExecutorService.class.getClassLoader(),
                new Class<?>[]{ExecutorService.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) throw new RejectedExecutionException("shutdown");
                    return null;
                });
    }

    private static ExecutorService directExecutor() {
        return (ExecutorService) Proxy.newProxyInstance(
                ExecutorService.class.getClassLoader(),
                new Class<?>[]{ExecutorService.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) { ((Runnable) args[0]).run(); return null; }
                    return null;
                });
    }

    private static ExecutorService queuingExecutor(List<Runnable> queue) {
        return (ExecutorService) Proxy.newProxyInstance(
                ExecutorService.class.getClassLoader(),
                new Class<?>[]{ExecutorService.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) { queue.add((Runnable) args[0]); return null; }
                    return null;
                });
    }
}
