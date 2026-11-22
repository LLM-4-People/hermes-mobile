package org.llm4people.hermes.thread;

import android.os.SystemClock;

import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.share.DeviceBridgeFlow;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/** A subscription is disposable. The remote run and its durable receipt outlive it. */
final class RunFollower implements AutoCloseable {
    interface Listener {
        void update(Frame frame);
        void complete(JSONObject status, long firstTextMs, long elapsedMs);
        void disconnected();
        void unknown(JSONObject status);
    }

    static final class Frame {
        final long seq;
        final String text;
        final RunPreview.Origin origin;
        final int highSurrogate;
        final String activity;
        final List<JSONObject> prompts;
        final int toolSteps;
        final List<DeviceBridgeFlow.BridgeRequest> deviceRequests;
        final String reasoning;

        Frame(long seq, RunPreview preview, String activity, List<JSONObject> prompts) {
            this(seq, preview, activity, prompts, 0, Collections.emptyList(), "");
        }

        Frame(long seq, RunPreview preview, String activity, List<JSONObject> prompts,
              int toolSteps, List<DeviceBridgeFlow.BridgeRequest> deviceRequests, String reasoning) {
            this.seq = seq;
            text = preview.toString();
            origin = preview.origin();
            highSurrogate = preview.pendingHighSurrogate();
            this.activity = activity;
            this.prompts = prompts;
            this.toolSteps = toolSteps;
            this.deviceRequests = deviceRequests == null ? Collections.emptyList() : deviceRequests;
            this.reasoning = reasoning == null ? "" : reasoning;
        }
    }

    static final int PREVIEW_LIMIT = RunPreview.LIMIT;
    static final int PAINT_INTERVAL_MS = 80;
    private static final int REASONING_LIMIT = TranscriptPage.MAX_REASONING_CHARS;
    private static final long CONTROL_CHECK_MS = 5_000;
    private final HermesApi api;
    private final String run;
    private final Listener listener;
    private final LongSupplier uptime;
    private final RunPreview preview;
    private final StringBuilder reasoning = new StringBuilder();
    private boolean textAwaitingPaint;
    private long seq;
    private long started;
    private long firstText = -1;
    private int toolSteps;
    private String activity = "Hermes travaille…";
    private final RunPrompts controls = new RunPrompts();
    private List<DeviceBridgeFlow.BridgeRequest> deviceRequests = Collections.emptyList();
    private long controlsCheckedAt;
    private volatile boolean closed;
    private HermesApi.RunEvents stream;

    RunFollower(HermesApi api, String run, JSONObject pending, long started, Listener listener) {
        this(api, run, pending, started, listener, SystemClock::elapsedRealtime);
    }

    RunFollower(HermesApi api, String run, JSONObject pending, long started, Listener listener, LongSupplier uptime) {
        this.uptime = uptime;
        this.api = api;
        this.run = run;
        this.listener = listener;
        this.started = started;
        seq = pending.optLong("seq", -1);
        preview = new RunPreview(pending);
    }

    void follow() {
        try {
            if (!refreshControls()) return;
            publish();
            try (HermesApi.RunEvents events = api.runEvents(run, seq)) {
                synchronized (this) {
                    if (closed) return;
                    stream = events;
                }
                long nextPaint = 0;
                JSONObject event;
                while (!closed && (event = events.next(true)) != null) {
                    if (event.length() == 0) {
                        if (controlCheckDue("")) {
                            if (!refreshControls()) return;
                            publish();
                        }
                        continue;
                    }
                    if (!run.equals(event.optString("run_id"))) continue;
                    String type = event.optString("event");
                    if ("replay.truncated".equals(type)) {
                        if (!refreshControls()) return;
                        preview.gap();
                        activity = "Extrait de la réponse en cours";
                        started = -1;
                        publish();
                        continue;
                    }
                    long eventSeq = event.optLong("seq", -1);
                    if (eventSeq < 0 || eventSeq <= seq) continue;
                    if (ThreadData.terminal(type.startsWith("run.") ? type.substring(4) : "")) break;
                    if ((controls.needsSnapshot(type, event) || "device.requested".equals(type)
                            || "device.settled".equals(type) || controlCheckDue(type)) && !refreshControls()) return;
                    apply(type, event);
                    seq = eventSeq;
                    long now = uptime.getAsLong();
                    if (!"message.delta".equals(type) || now >= nextPaint) {
                        publish();
                        nextPaint = now + PAINT_INTERVAL_MS;
                    }
                }
            } finally {
                synchronized (this) { stream = null; }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // The status resource can survive a gateway restart after its event stream vanishes.
            org.llm4people.hermes.Diagnostics.debug("threads", "stream_interrupted", ignored);
        }
        if (!closed) {
            try {
                JSONObject status = api.get(path());
                if (run.equals(status.optString("run_id")) && finish(status)) return;
            } catch (IOException ignored) {
                // The durable receipt is retained for the next connection.
                org.llm4people.hermes.Diagnostics.warning("threads", "status_reconcile_deferred", ignored);
            }
            if (!closed) {
                // Deliver any throttled final delta and its matching cursor before checkpointing.
                if (textAwaitingPaint) publish();
                listener.disconnected();
            }
        }
    }

    private boolean controlCheckDue(String event) {
        // Native waiters may expire silently. Recheck promptly when work resumes, and on idle heartbeats.
        if (controls.snapshot().isEmpty()) return false;
        long interval = "message.interim".equals(event) || "tool.completed".equals(event) ? 1_000 : CONTROL_CHECK_MS;
        return uptime.getAsLong() - controlsCheckedAt >= interval && controls.hasStandaloneApproval();
    }

    private boolean refreshControls() throws IOException {
        JSONObject status = api.get(path());
        if (!run.equals(status.opt("run_id"))) throw new IOException("Incorrect run identity");
        if (closed || finish(status)) return false;
        controls.restore(status);
        deviceRequests = DeviceBridgeFlow.parsePending(status);
        controlsCheckedAt = uptime.getAsLong();
        return true;
    }

    private boolean finish(JSONObject status) {
        String state = status.optString("status");
        boolean unknown = "unknown".equals(state);
        if (!unknown && !ThreadData.terminal(state)) return false;
        if (!closed) {
            if (textAwaitingPaint) {
                controls.clear();
                publish();
            }
            if (unknown) listener.unknown(status);
            else listener.complete(status, started < 0 ? -1 : firstText,
                    started < 0 ? -1 : uptime.getAsLong() - started);
        }
        return true;
    }

    private void apply(String type, JSONObject event) {
        switch (type) {
            case "message.delta":
                String delta = event.optString("delta");
                if (!delta.isEmpty()) {
                    if (started >= 0 && firstText < 0) firstText = uptime.getAsLong() - started;
                    preview.append(delta);
                    textAwaitingPaint = true;
                }
                break;
            case "reasoning.delta":
                String thought = event.optString("text", event.optString("delta", ""));
                if (!thought.isEmpty() && reasoning.length() < REASONING_LIMIT) {
                    int remaining = REASONING_LIMIT - reasoning.length();
                    if (thought.length() <= remaining) reasoning.append(thought);
                    else reasoning.append(thought, 0, remaining);
                    activity = "Hermes réfléchit…";
                    textAwaitingPaint = true;
                }
                break;
            case "message.interim":
                if (!event.optBoolean("already_streamed")) activity = event.optString("text", "Hermes travaille…");
                break;
            case "tool.started":
                toolSteps++;
                activity = "Outil en cours · " + event.optString("tool");
                break;
            case "tool.completed":
                activity = (event.optBoolean("error") ? "Échec de l’outil · " : "Outil terminé · ")
                        + event.optString("tool");
                break;
            case "approval.responded":
            case "approval.settled":
            case "clarify.settled":
                controls.settle(event);
                activity = "Hermes travaille…";
                break;
            default:
                break;
        }
    }

    private String path() { return "/v1/runs/" + HermesApi.segment(run); }

    private void publish() {
        if (!closed) {
            textAwaitingPaint = false;
            listener.update(new Frame(seq, preview, activity, controls.snapshot(),
                    toolSteps, deviceRequests, reasoning.toString()));
        }
    }

    @Override public synchronized void close() {
        closed = true;
        if (stream != null) stream.close();
    }
}
