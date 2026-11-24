package org.llm4people.hermes.thread;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.LiveRunNotification;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.IntSupplier;

/**
 * Profile-scoped coordinator for concurrent tasks across multiple discussions.
 * Keeps background discussions streaming live when the user switches threads without mixing
 * deltas, approvals, or drafts across sessions, and aggregates privacy-safe Live Updates progress.
 */
public final class ActiveRunTracker {
    public static final int MAX_CONCURRENT_RUNS = 8;

    public record TaskBadge(
            String sessionId,
            String runId,
            LiveRunNotification.Phase phase,
            int toolSteps,
            boolean awaitingDecision,
            boolean canStop) {
        public boolean active() {
            return phase != null && phase != LiveRunNotification.Phase.IDLE;
        }
    }

    static final class ReclaimedState {
        final String runId;
        final RunFollower.Frame latestFrame;
        final LiveRunNotification.Snapshot progress;

        ReclaimedState(String runId, RunFollower.Frame latestFrame, LiveRunNotification.Snapshot progress) {
            this.runId = runId;
            this.latestFrame = latestFrame;
            this.progress = progress;
        }
    }

    private static final class Entry {
        final String sessionId;
        String runId;
        LiveRunNotification.Snapshot progress;
        boolean backgrounded;
        ThreadJournal backgroundJournal;
        RunFollower backgroundFollower;
        RunFollower.Frame latestFrame;
        long bgToken;

        Entry(String sessionId, String runId, LiveRunNotification.Snapshot progress) {
            this.sessionId = sessionId;
            this.runId = runId == null ? "" : runId;
            this.progress = progress == null ? LiveRunNotification.Snapshot.idle() : progress;
        }

        TaskBadge toBadge() {
            return new TaskBadge(
                    sessionId,
                    runId,
                    progress.phase(),
                    progress.completedSteps(),
                    progress.awaitingDecision() || progress.phase() == LiveRunNotification.Phase.AWAITING_DECISION,
                    progress.canStop() && !runId.isEmpty());
        }
    }

    /** Main-looper callback dispatcher owned exclusively by {@link ActiveRunTracker}. */
    interface CallbackPoster {
        void post(Runnable action);
        void clearPending();
    }

    private static final class MainLooperPoster implements CallbackPoster {
        private Handler handler;

        @Override
        public synchronized void post(Runnable action) {
            if (action == null) return;
            Handler target = ensureHandler();
            if (target != null) {
                target.post(action);
            }
        }

        @Override
        public synchronized void clearPending() {
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
        }

        private Handler ensureHandler() {
            if (handler == null) {
                Looper looper = Looper.getMainLooper();
                if (looper != null) {
                    handler = new Handler(looper);
                }
            }
            return handler;
        }
    }

    private final CallbackPoster callbackPoster;
    private final Map<String, Entry> tasks = new LinkedHashMap<>();
    private long nextToken;

    public ActiveRunTracker() {
        this(new MainLooperPoster());
    }

    ActiveRunTracker(CallbackPoster callbackPoster) {
        this.callbackPoster = Objects.requireNonNull(callbackPoster, "callbackPoster");
    }

    /** True when {@code sessionId} is already tracked or another concurrent slot is available. */
    public synchronized boolean canStart(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        return tasks.containsKey(sessionId) || tasks.size() < MAX_CONCURRENT_RUNS;
    }

    public synchronized int activeCount() {
        return tasks.size();
    }

    /** Update or remove the foreground session's run progress. Pure and deterministic for JVM tests. */
    public synchronized boolean updateForeground(String sessionId, String runId, LiveRunNotification.Snapshot next) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        LiveRunNotification.Snapshot normalized = next == null || !next.active()
                ? LiveRunNotification.Snapshot.idle() : next;
        if (!normalized.active()) {
            Entry removed = tasks.remove(sessionId);
            if (removed != null) closeBackgroundHandle(removed, true);
            return removed != null;
        }
        Entry existing = tasks.get(sessionId);
        String cleanRun = runId == null ? "" : runId;
        if (existing == null) {
            if (tasks.size() >= MAX_CONCURRENT_RUNS) return false;
            tasks.put(sessionId, new Entry(sessionId, cleanRun, normalized));
            return true;
        }
        boolean changed = !Objects.equals(existing.progress, normalized)
                || (!cleanRun.isEmpty() && !cleanRun.equals(existing.runId));
        if (!cleanRun.isEmpty()) existing.runId = cleanRun;
        existing.progress = normalized;
        existing.backgrounded = false;
        return changed;
    }

    /** Pure state handoff used by both Android background streaming and host-JVM tests. */
    public synchronized boolean markBackgrounded(String sessionId, String runId, LiveRunNotification.Snapshot progress) {
        if (sessionId == null || sessionId.isEmpty() || runId == null || runId.isEmpty()) return false;
        LiveRunNotification.Snapshot safe = progress != null && progress.active()
                ? progress
                : new LiveRunNotification.Snapshot(LiveRunNotification.Phase.WORKING, 0, 0, false, true);
        Entry entry = tasks.get(sessionId);
        if (entry == null) {
            if (tasks.size() >= MAX_CONCURRENT_RUNS) return false;
            entry = new Entry(sessionId, runId, safe);
            tasks.put(sessionId, entry);
        } else {
            entry.runId = runId;
            entry.progress = safe;
        }
        entry.backgrounded = true;
        return true;
    }

    /**
     * Detaches a live foreground run into a background SSE watcher when the user switches to another
     * discussion within the same unlocked profile. Strictly fenced by {@code expectedEpoch}.
     * Callbacks always post onto {@link ActiveRunTracker}'s dedicated main-looper {@link Handler}
     * (never the caller's workspace {@code main} handler) so screen switches cannot cancel them.
     */
    boolean moveToBackground(
            String sessionId,
            String runId,
            ThreadJournal journal,
            RunFollower.Frame lastFrame,
            LiveRunNotification.Snapshot lastProgress,
            HermesApi api,
            ExecutorService runStreams,
            IntSupplier currentEpoch,
            int expectedEpoch,
            Runnable onStateChanged) {
        if (sessionId == null || sessionId.isEmpty() || runId == null || runId.isEmpty()
                || journal == null || api == null || api.isRevoked()) {
            return false;
        }
        JSONObject receipt = journal.pending();
        if (receipt == null || !journal.sameOwner()) return false;

        final Entry entry;
        final long token;
        synchronized (this) {
            Entry existing = tasks.get(sessionId);
            LiveRunNotification.Snapshot safe = lastProgress != null && lastProgress.active()
                    ? lastProgress
                    : new LiveRunNotification.Snapshot(LiveRunNotification.Phase.WORKING, 0, 0, false, true);
            if (existing == null) {
                if (tasks.size() >= MAX_CONCURRENT_RUNS) return false;
                existing = new Entry(sessionId, runId, safe);
                tasks.put(sessionId, existing);
            } else {
                closeBackgroundHandle(existing, false);
                existing.runId = runId;
                existing.progress = safe;
            }
            existing.backgrounded = true;
            existing.backgroundJournal = journal;
            existing.latestFrame = lastFrame;
            existing.bgToken = ++nextToken;
            entry = existing;
            token = existing.bgToken;
        }

        RunFollower bgFollower = new RunFollower(api, runId, receipt, -1L, new RunFollower.Listener() {
            private void postIfCurrent(Runnable action) {
                dispatchBackgroundCallback(() -> {
                    if (currentEpoch.getAsInt() != expectedEpoch) return;
                    synchronized (ActiveRunTracker.this) {
                        if (tasks.get(sessionId) != entry || !entry.backgrounded || entry.bgToken != token) return;
                    }
                    action.run();
                });
            }

            @Override public void update(RunFollower.Frame frame) {
                postIfCurrent(() -> {
                    boolean awaiting = !frame.prompts.isEmpty() || !frame.deviceRequests.isEmpty();
                    LiveRunNotification.Phase phase = awaiting
                            ? LiveRunNotification.Phase.AWAITING_DECISION
                            : frame.toolSteps > 0 ? LiveRunNotification.Phase.TOOL_RUNNING
                            : LiveRunNotification.Phase.WORKING;
                    LiveRunNotification.Snapshot snap = new LiveRunNotification.Snapshot(
                            phase, frame.toolSteps, 0, awaiting, true);
                    synchronized (ActiveRunTracker.this) {
                        if (tasks.get(sessionId) != entry || !entry.backgrounded || entry.bgToken != token) return;
                        entry.latestFrame = frame;
                        entry.progress = snap;
                    }
                    try {
                        journal.checkpoint(frame.seq, frame.text, frame.origin, frame.highSurrogate);
                    } catch (RuntimeException unavailable) {
                        logWarning("bg_checkpoint_failed");
                    }
                    onStateChanged.run();
                });
            }

            @Override public void complete(JSONObject status, long firstTextMs, long elapsedMs) {
                postIfCurrent(() -> {
                    try {
                        journal.finish();
                    } catch (RuntimeException unavailable) {
                        logWarning("bg_finish_failed");
                    }
                    synchronized (ActiveRunTracker.this) {
                        if (tasks.get(sessionId) == entry && entry.bgToken == token) {
                            tasks.remove(sessionId);
                            closeBackgroundHandle(entry, true);
                        }
                    }
                    logEvent("bg_run_completed");
                    onStateChanged.run();
                });
            }

            @Override public void unknown(JSONObject status) {
                postIfCurrent(() -> {
                    checkpointLatest(entry);
                    synchronized (ActiveRunTracker.this) {
                        if (tasks.get(sessionId) == entry && entry.bgToken == token) {
                            tasks.remove(sessionId);
                            closeBackgroundHandle(entry, true);
                        }
                    }
                    onStateChanged.run();
                });
            }

            @Override public void disconnected() {
                postIfCurrent(() -> {
                    checkpointLatest(entry);
                    synchronized (ActiveRunTracker.this) {
                        if (tasks.get(sessionId) == entry && entry.bgToken == token) {
                            tasks.remove(sessionId);
                            closeBackgroundHandle(entry, true);
                        }
                    }
                    onStateChanged.run();
                });
            }
        });

        synchronized (this) {
            if (tasks.get(sessionId) != entry || !entry.backgrounded || entry.bgToken != token) {
                bgFollower.close();
                return false;
            }
            entry.backgroundFollower = bgFollower;
        }
        try {
            runStreams.execute(bgFollower::follow);
            logEvent("bg_run_detached");
            return true;
        } catch (RuntimeException rejected) {
            logWarning("bg_detach_rejected");
            synchronized (this) {
                if (tasks.get(sessionId) == entry && entry.bgToken == token) {
                    tasks.remove(sessionId);
                    closeBackgroundHandle(entry, false);
                }
            }
            return false;
        }
    }

    /**
     * Stops any background watcher for {@code sessionId}, flushes & retires its background journal
     * so the foreground {@link ThreadJournal} opens cleanly with the latest checkpoint, and returns
     * the latest streamed frame (or null if none).
     */
    synchronized ReclaimedState reclaimForeground(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        Entry entry = tasks.get(sessionId);
        if (entry == null || !entry.backgrounded) return null;
        entry.backgrounded = false;
        entry.bgToken = ++nextToken;
        checkpointLatest(entry);
        ReclaimedState reclaimed = new ReclaimedState(entry.runId, entry.latestFrame, entry.progress);
        closeBackgroundHandle(entry, true);
        return reclaimed;
    }

    /** Send POST /v1/runs/{runId}/stop for a specific active background session. */
    boolean stopBackground(String sessionId, HermesApi api, ExecutorService rpcPool) {
        String targetRunId;
        synchronized (this) {
            Entry entry = tasks.get(sessionId);
            if (entry == null || entry.runId.isEmpty() || !entry.progress.canStop()) return false;
            targetRunId = entry.runId;
        }
        if (api == null || api.isRevoked()) return false;
        rpcPool.execute(() -> {
            try {
                api.stopRun(targetRunId);
                logEvent("bg_stop_requested");
            } catch (IOException | RuntimeException error) {
                logWarning("bg_stop_failed");
            }
        });
        return true;
    }

    /** Returns the most recently updated stoppable session ID (for notification Stop when foreground is idle). */
    public synchronized String mostRecentStoppableSession() {
        String candidate = null;
        for (Entry entry : tasks.values()) {
            if (entry.progress.canStop() && !entry.runId.isEmpty()) candidate = entry.sessionId;
        }
        return candidate;
    }

    /** Immediately tears down all background SSE streams and clears in-memory state (on background/lock/close). */
    public synchronized void stopAllWatchers() {
        for (Entry entry : new ArrayList<>(tasks.values())) {
            checkpointLatest(entry);
            closeBackgroundHandle(entry, true);
        }
        tasks.clear();
        callbackPoster.clearPending();
    }

    void dispatchBackgroundCallback(Runnable action) {
        callbackPoster.post(action);
    }

    /** Immutable per-session active badges for rendering in the Discussions list. */
    public synchronized Map<String, TaskBadge> badges() {
        if (tasks.isEmpty()) return Collections.emptyMap();
        Map<String, TaskBadge> out = new LinkedHashMap<>(tasks.size());
        for (Entry entry : tasks.values()) {
            if (entry.progress.active()) out.put(entry.sessionId, entry.toBadge());
        }
        return Collections.unmodifiableMap(out);
    }

    /** Privacy-safe aggregated Live Updates snapshot spanning all concurrent active tasks for this profile. */
    public synchronized LiveRunNotification.Snapshot aggregateSnapshot() {
        if (tasks.isEmpty()) return LiveRunNotification.Snapshot.idle();
        if (tasks.size() == 1) {
            Entry only = tasks.values().iterator().next();
            return only.progress.active() ? only.progress : LiveRunNotification.Snapshot.idle();
        }
        int active = 0;
        int decisions = 0;
        int completedSteps = 0;
        int totalSteps = 0;
        boolean anyStoppable = false;
        boolean anyTool = false;
        boolean anyWorking = false;
        for (Entry entry : tasks.values()) {
            LiveRunNotification.Snapshot p = entry.progress;
            if (!p.active()) continue;
            active++;
            if (p.awaitingDecision() || p.phase() == LiveRunNotification.Phase.AWAITING_DECISION) decisions++;
            if (p.phase() == LiveRunNotification.Phase.TOOL_RUNNING) anyTool = true;
            if (p.phase() == LiveRunNotification.Phase.WORKING) anyWorking = true;
            completedSteps = Math.min(99, completedSteps + Math.max(0, p.completedSteps()));
            totalSteps = Math.min(99, totalSteps + Math.max(0, p.totalSteps()));
            if (p.canStop()) anyStoppable = true;
        }
        if (active == 0) return LiveRunNotification.Snapshot.idle();
        LiveRunNotification.Phase aggPhase = decisions > 0
                ? LiveRunNotification.Phase.AWAITING_DECISION
                : anyTool ? LiveRunNotification.Phase.TOOL_RUNNING
                : anyWorking ? LiveRunNotification.Phase.WORKING
                : LiveRunNotification.Phase.STARTING;
        return new LiveRunNotification.Snapshot(
                aggPhase, completedSteps, totalSteps, decisions > 0, anyStoppable, active, decisions);
    }

    private static void checkpointLatest(Entry entry) {
        if (entry.backgroundJournal == null) return;
        try {
            if (entry.latestFrame != null) {
                entry.backgroundJournal.checkpoint(
                        entry.latestFrame.seq,
                        entry.latestFrame.text,
                        entry.latestFrame.origin,
                        entry.latestFrame.highSurrogate);
            }
            entry.backgroundJournal.flush();
        } catch (RuntimeException unavailable) {
            logWarning("bg_flush_failed");
        }
    }

    private static void closeBackgroundHandle(Entry entry, boolean retireJournal) {
        if (entry.backgroundFollower != null) {
            entry.backgroundFollower.close();
            entry.backgroundFollower = null;
        }
        if (retireJournal && entry.backgroundJournal != null) {
            try { entry.backgroundJournal.retire(); }
            catch (RuntimeException ignored) { logWarning("bg_retire_failed"); }
            entry.backgroundJournal = null;
        }
    }

    private static void logEvent(String event) {
        Diagnostics.event("multi_run", event);
    }

    private static void logWarning(String warning) {
        Diagnostics.warning("multi_run", warning);
    }
}
