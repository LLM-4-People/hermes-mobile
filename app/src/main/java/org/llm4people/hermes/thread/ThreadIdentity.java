package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Only the session-list projection classifies a compression chain. A fork is its own thread. */
final class ThreadIdentity {
    final String root;
    final String tip;

    ThreadIdentity(String root, String tip) {
        if (!ThreadData.validId(root) || !ThreadData.validId(tip)) throw new IllegalArgumentException("Invalid thread id");
        this.root = root;
        this.tip = tip;
    }

    static ThreadIdentity of(JSONObject row) {
        String tip = ThreadData.string(row, "id");
        String root = ThreadData.string(row, "_lineage_root_id");
        return new ThreadIdentity(root.isEmpty() ? tip : root, tip);
    }

    static ThreadIdentity find(JSONArray page, String id) {
        for (int i = 0; i < page.length(); i++) {
            JSONObject row = page.optJSONObject(i);
            if (row != null && (id.equals(ThreadData.string(row, "id"))
                    || id.equals(ThreadData.string(row, "_lineage_root_id")))) return of(row);
        }
        return null;
    }

    static ThreadIdentity resolve(HermesApi api, String id, BooleanSupplier active) throws IOException {
        // A notification may refer to an intermediate generation. Ask the same lineage reader
        // as the transcript for its current tip, then identify its root in the list projection.
        if (!active.getAsBoolean()) return null;
        JSONObject transcript = api.get(ThreadData.path(id) + "/messages?order=latest&limit=0");
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
