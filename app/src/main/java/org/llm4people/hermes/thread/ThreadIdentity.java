package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** The gateway classifies compression chains. A branch is its own discussion. */
final class ThreadIdentity {
    final String root;
    final String tip;
    final String label;

    ThreadIdentity(String root, String tip) { this(root, tip, "Conversation"); }

    private ThreadIdentity(String root, String tip, String label) {
        if (!ThreadData.validId(root) || !ThreadData.validId(tip)) throw new IllegalArgumentException("Invalid thread id");
        this.root = root;
        this.tip = tip;
        this.label = label;
    }

    static ThreadIdentity of(JSONObject row) {
        String tip = ThreadData.string(row, "id");
        String root = ThreadData.string(row, "_lineage_root_id");
        return new ThreadIdentity(root.isEmpty() ? tip : root, tip, ThreadData.title(row));
    }

    static ThreadIdentity find(JSONArray page, String id) {
        for (int i = 0; i < page.length(); i++) {
            JSONObject row = page.optJSONObject(i);
            if (row != null && (id.equals(ThreadData.string(row, "id"))
                    || id.equals(ThreadData.string(row, "_lineage_root_id")))) return of(row);
        }
        return null;
    }

    static ThreadIdentity fromTranscript(JSONObject page) {
        String root = ThreadData.string(page, "thread_id");
        String tip = ThreadData.string(page, "session_id");
        if (!ThreadData.validId(root) || !ThreadData.validId(tip)) return null;
        return new ThreadIdentity(root, tip, ThreadContext.title(page, "thread_title", "Conversation"));
    }

    static boolean hasPendingQuestion(String sessionId, String runId, JSONObject status) throws IOException {
        if (!sessionId.equals(ThreadData.string(status, "session_id"))
                || !runId.equals(ThreadData.string(status, "run_id"))) {
            throw new IOException("Run does not belong to the selected conversation");
        }
        JSONArray controls = status.optJSONArray("pending_controls");
        for (int i = 0; controls != null && i < controls.length(); i++) {
            JSONObject prompt = controls.optJSONObject(i);
            if (RunPrompts.question(prompt) && !RunPrompts.id(prompt).isEmpty()) return true;
        }
        return false;
    }

    static ThreadIdentity resolve(HermesApi api, String id, BooleanSupplier active) throws IOException {
        // A notification may refer to an intermediate generation. Ask the same lineage reader
        // as the transcript. Older gateways classify the root only in the list projection.
        if (!active.getAsBoolean()) return null;
        JSONObject transcript = api.get(ThreadData.path(id) + "/messages?order=latest&limit=0");
        ThreadIdentity canonical = fromTranscript(transcript);
        if (canonical != null) return canonical;
        String tip = ThreadData.string(transcript, "session_id");
        if (!ThreadData.validId(tip)) throw new IOException("Missing lineage tip");
        for (int offset = 0; offset <= 1_000_000; offset += 200) {
            if (!active.getAsBoolean()) return null;
            JSONObject list = api.get("/api/sessions?limit=200&offset=" + offset);
            JSONArray rows = list.optJSONArray("data");
            if (rows == null) throw new IOException("Missing session projection");
            ThreadIdentity match = find(rows, tip);
            if (match != null) return match;
            if (!list.optBoolean("has_more")) return null;
        }
        return null;
    }
}
