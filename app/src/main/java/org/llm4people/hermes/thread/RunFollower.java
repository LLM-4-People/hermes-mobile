package org.llm4people.hermes.thread;

import android.os.SystemClock;

import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;

import java.io.IOException;

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
        final String activity;
        final JSONObject approval;

        Frame(long seq, String text, String activity, JSONObject approval) {
            this.seq = seq;
            this.text = text;
            this.activity = activity;
            this.approval = approval;
        }
    }

    private static final int PREVIEW_LIMIT = 65_536;
    private final HermesApi api;
    private final String run;
    private final Listener listener;
    private final StringBuilder preview;
    private long seq;
    private long started;
    private long firstText = -1;
    private String activity = "Hermes travaille…";
    private final RunPrompts controls = new RunPrompts();
    private volatile boolean closed;
    private HermesApi.RunEvents stream;

    RunFollower(HermesApi api, String run, JSONObject pending, long started, Listener listener) {
        this.api = api;
        this.run = run;
        this.listener = listener;
        this.started = started;
        seq = pending.optLong("seq", -1);
        preview = new StringBuilder(ThreadData.string(pending, "preview"));
    }

    void follow() {
        try {
            JSONObject status = api.get(path());
            if (finish(status) || closed) return;
            controls.restore(status);
            if (controls.next() != null) publish();
            try (HermesApi.RunEvents events = api.runEvents(run, seq)) {
                synchronized (this) {
                    if (closed) return;
                    stream = events;
                }
                long nextPaint = 0;
                JSONObject event;
                while (!closed && (event = events.next()) != null) {
                    String type = event.optString("event");
                    if ("replay.truncated".equals(type)) {
                        preview.setLength(0);
                        activity = "Extrait de la réponse en cours";
                        started = -1;
                        publish();
                        continue;
                    }
                    long eventSeq = event.optLong("seq", -1);
                    if (eventSeq < 0 || eventSeq <= seq) continue;
                    seq = eventSeq;
                    if (ThreadData.terminal(type.startsWith("run.") ? type.substring(4) : "")) break;
                    apply(type, event);
                    long now = SystemClock.elapsedRealtime();
                    if (!"message.delta".equals(type) || now >= nextPaint) {
                        publish();
                        nextPaint = now + 80;
                    }
                }
            } finally {
                synchronized (this) { stream = null; }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // The status resource can survive a gateway restart after its event stream vanishes.
        }
        if (!closed) {
            try {
                if (finish(api.get(path()))) return;
            } catch (IOException ignored) {
                // The durable receipt is retained for the next connection.
            }
            if (!closed) {
                publish();
                listener.disconnected();
            }
        }
    }

    private boolean finish(JSONObject status) {
        if ("unknown".equals(status.optString("status"))) {
            if (!closed) listener.unknown(status);
            return true;
        }
        if (!ThreadData.terminal(status.optString("status"))) return false;
        if (!closed) listener.complete(status, started < 0 ? -1 : firstText,
                started < 0 ? -1 : SystemClock.elapsedRealtime() - started);
        return true;
    }

    private void apply(String type, JSONObject event) {
        switch (type) {
            case "message.delta":
                String delta = event.optString("delta");
                if (!delta.isEmpty()) {
                    if (started >= 0 && firstText < 0) firstText = SystemClock.elapsedRealtime() - started;
                    int room = PREVIEW_LIMIT - preview.length();
                    if (room > 0) preview.append(delta, 0, Math.min(room, delta.length()));
                }
                break;
            case "message.interim":
                if (!event.optBoolean("already_streamed")) activity = event.optString("text", "Hermes travaille…");
                break;
            case "tool.started":
                activity = "Outil en cours · " + event.optString("tool");
                break;
            case "tool.completed":
                activity = (event.optBoolean("error") ? "Échec de l’outil · " : "Outil terminé · ")
                        + event.optString("tool");
                break;
            case "approval.request":
            case "clarify.request":
                controls.add(event);
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
        JSONObject control = controls.next();
        if (!closed) listener.update(new Frame(seq, preview.toString(),
                control == null ? activity : RunPrompts.activity(control), control));
    }

    @Override public synchronized void close() {
        closed = true;
        if (stream != null) stream.close();
    }
}
