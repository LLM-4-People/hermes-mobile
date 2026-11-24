package org.llm4people.hermes.thread;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.LiveRunNotification;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ActiveRunTrackerTest {

    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void resetDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }
    @Test
    public void tracksFivePlusConcurrentRunsWithoutMixingAndEnforcesBound() {
        ActiveRunTracker tracker = new ActiveRunTracker();
        assertEquals(0, tracker.activeCount());
        assertFalse(tracker.aggregateSnapshot().active());

        // Start and background 5 simultaneous tasks across distinct discussions.
        for (int i = 1; i <= 5; i++) {
            String sid = "session-" + i;
            String rid = "run-" + i;
            assertTrue(tracker.canStart(sid));
            assertTrue(tracker.markBackgrounded(sid, rid, new LiveRunNotification.Snapshot(
                    i == 3 ? LiveRunNotification.Phase.TOOL_RUNNING : LiveRunNotification.Phase.WORKING,
                    i == 3 ? 2 : 0, 0, false, true)));
        }
        assertEquals(5, tracker.activeCount());
        Map<String, ActiveRunTracker.TaskBadge> badges = tracker.badges();
        assertEquals(5, badges.size());
        assertEquals(LiveRunNotification.Phase.TOOL_RUNNING, badges.get("session-3").phase());
        assertEquals(LiveRunNotification.Phase.WORKING, badges.get("session-1").phase());

        LiveRunNotification.Snapshot agg = tracker.aggregateSnapshot();
        assertTrue(agg.active());
        assertEquals(5, agg.activeCount());
        assertEquals(0, agg.decisionCount());
        assertEquals(LiveRunNotification.Phase.TOOL_RUNNING, agg.phase());
        assertTrue(agg.canStop());

        // Escalate one background session to AWAITING_DECISION without disturbing the other 4.
        tracker.markBackgrounded("session-2", "run-2", new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.AWAITING_DECISION, 1, 0, true, true));
        LiveRunNotification.Snapshot aggDecision = tracker.aggregateSnapshot();
        assertEquals(5, aggDecision.activeCount());
        assertEquals(1, aggDecision.decisionCount());
        assertEquals(LiveRunNotification.Phase.AWAITING_DECISION, aggDecision.phase());
        assertTrue(aggDecision.awaitingDecision());
        assertTrue(tracker.badges().get("session-2").awaitingDecision());
        assertFalse(tracker.badges().get("session-1").awaitingDecision());

        // Fill remaining slots up to MAX_CONCURRENT_RUNS; 9th distinct session must be rejected.
        for (int i = 6; i <= ActiveRunTracker.MAX_CONCURRENT_RUNS; i++) {
            assertTrue(tracker.canStart("session-" + i));
            assertTrue(tracker.updateForeground("session-" + i, "run-" + i,
                    new LiveRunNotification.Snapshot(LiveRunNotification.Phase.WORKING, 0, 0, false, true)));
        }
        assertEquals(ActiveRunTracker.MAX_CONCURRENT_RUNS, tracker.activeCount());
        assertFalse(tracker.canStart("session-overflow"));
        // Updating an already-active session is still allowed.
        assertTrue(tracker.canStart("session-1"));
    }

    @Test
    public void completionAndStopAllWatchersCleanlyReturnToIdle() {
        ActiveRunTracker tracker = new ActiveRunTracker();
        tracker.markBackgrounded("s1", "r1", new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.WORKING, 0, 0, false, true));
        tracker.updateForeground("s2", "r2", new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.TOOL_RUNNING, 1, 0, false, true));

        assertEquals("s2", tracker.mostRecentStoppableSession());
        assertEquals(2, tracker.aggregateSnapshot().activeCount());

        // Completing s2 drops smoothly back to single-task snapshot for s1.
        tracker.updateForeground("s2", "r2", LiveRunNotification.Snapshot.idle());
        assertEquals(1, tracker.activeCount());
        assertEquals("s1", tracker.mostRecentStoppableSession());
        assertEquals(1, tracker.aggregateSnapshot().activeCount());

        // Locking / closing profile instantaneously clears all watchers & returns idle.
        tracker.stopAllWatchers();
        assertEquals(0, tracker.activeCount());
        assertTrue(tracker.badges().isEmpty());
        assertNull(tracker.mostRecentStoppableSession());
        assertFalse(tracker.aggregateSnapshot().active());
    }

    @Test
    public void dedicatedCallbackPosterSurvivesExternalWorkspaceClearsAndClearsOnlyOnStopAllWatchers() {
        Deque<Runnable> trackerQueue = new ArrayDeque<>();
        Deque<Runnable> unrelatedWorkspaceQueue = new ArrayDeque<>();
        AtomicInteger clearCalls = new AtomicInteger();

        ActiveRunTracker tracker = new ActiveRunTracker(new ActiveRunTracker.CallbackPoster() {
            @Override public void post(Runnable action) { trackerQueue.addLast(action); }
            @Override public void clearPending() {
                clearCalls.incrementAndGet();
                trackerQueue.clear();
            }
        });

        AtomicInteger executed = new AtomicInteger();
        tracker.dispatchBackgroundCallback(executed::incrementAndGet);
        unrelatedWorkspaceQueue.addLast(() -> executed.addAndGet(100));

        // Simulating ThreadWorkspace.switchScreen() wiping its own Handler queue must NOT drop tracker callbacks.
        unrelatedWorkspaceQueue.clear();
        assertEquals(1, trackerQueue.size());
        assertEquals(0, clearCalls.get());
        trackerQueue.removeFirst().run();
        assertEquals(1, executed.get());

        // Queuing another callback and calling stopAllWatchers() must cancel pending background callbacks.
        tracker.markBackgrounded("s1", "r1", new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.WORKING, 0, 0, false, true));
        tracker.dispatchBackgroundCallback(executed::incrementAndGet);
        assertEquals(1, trackerQueue.size());

        tracker.stopAllWatchers();
        assertEquals(1, clearCalls.get());
        assertTrue("stopAllWatchers must clear queued background callbacks", trackerQueue.isEmpty());
        assertEquals(1, executed.get());
    }

    @Test
    public void workspaceTeardownHelpersHandleNullApiNullHostAndIdempotentRevocation() {
        // Null client and null host must never throw during ThreadWorkspace.close() / onBackground().
        ThreadWorkspace.revokeApiSafely(null);
        ThreadWorkspace.notifyIdleSafely(null);

        HermesApi client = new HermesApi("http://127.0.0.1:9", "secret");
        assertFalse(client.isRevoked());
        ThreadWorkspace.revokeApiSafely(client);
        assertTrue(client.isRevoked());
        // Calling revoke a second time must remain idempotent.
        ThreadWorkspace.revokeApiSafely(client);
        assertTrue(client.isRevoked());

        AtomicReference<LiveRunNotification.Snapshot> delivered = new AtomicReference<>();
        ThreadWorkspace.Host host = new ThreadWorkspace.Host() {
            @Override public void selected(String id, String title) {}
            @Override public void pendingChanged() {}
            @Override public org.json.JSONObject options(String sessionId) { return new org.json.JSONObject(); }
            @Override public void timing(String sessionId, long firstTextMillis, long elapsedMillis, long outputTokens) {}
            @Override public void runProgress(LiveRunNotification.Snapshot snapshot) { delivered.set(snapshot); }
        };
        ThreadWorkspace.notifyIdleSafely(host);
        assertTrue(delivered.get() != null && !delivered.get().active());
    }

    @Test
    public void threadRunAdmissionAllowsUnboundSessionsBelowCapacityAndEnforcesCap() {
        ActiveRunTracker tracker = new ActiveRunTracker();
        assertFalse(tracker.canStart(null));
        assertFalse(tracker.canStart(""));
        assertTrue(ThreadRun.canAdmitRun(tracker, null));
        assertTrue(ThreadRun.canAdmitRun(tracker, ""));

        for (int i = 1; i <= ActiveRunTracker.MAX_CONCURRENT_RUNS; i++) {
            assertTrue(tracker.updateForeground("s-" + i, "r-" + i,
                    new LiveRunNotification.Snapshot(LiveRunNotification.Phase.WORKING, 0, 0, false, true)));
        }
        assertFalse(ThreadRun.canAdmitRun(tracker, null));
        assertFalse(ThreadRun.canAdmitRun(tracker, ""));
        assertFalse(ThreadRun.canAdmitRun(tracker, "s-overflow"));
        assertTrue(ThreadRun.canAdmitRun(tracker, "s-1"));
    }
}
