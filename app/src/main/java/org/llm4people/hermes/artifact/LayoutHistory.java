package org.llm4people.hermes.artifact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;

/** Revision history per surface. Each mutation is committed to authenticated local storage. */
final class LayoutHistory {
    static final int MAX_REVISIONS = 12;
    private final Account account;
    final String surface;
    private final String key;

    static final class Revision {
        final String id;
        final String summary;
        final String raw;
        final long timestampMs;

        Revision(String id, String summary, String raw, long timestampMs) {
            this.id = id;
            this.summary = summary;
            this.raw = raw;
            this.timestampMs = timestampMs;
        }
    }

    static final class Unreadable extends IllegalStateException {
        private Unreadable(Throwable cause) { super("Unreadable workspace record", cause); }
    }

    LayoutHistory(Account account, String surface) {
        if (!WorkspaceLayout.SURFACES.containsKey(surface) && !WorkspaceLayout.custom(surface))
            throw new IllegalArgumentException("Unknown screen");
        this.account = account;
        this.surface = surface;
        key = "workspace.v1." + surface;
    }

    private JSONObject read() {
        if (WorkspaceLayout.custom(surface)) {
            JSONObject row = PageLayouts.read(account).optJSONObject(surface);
            return row == null ? new JSONObject() : row;
        }
        try {
            String raw = account.read(key);
            if (raw == null) return new JSONObject();
            JSONObject record = new JSONObject(raw);
            if (!validRecord(record))
                throw new JSONException("Invalid layout history");
            return record;
        } catch (Account.UnreadableRecord | JSONException e) { throw new Unreadable(e); }
    }

    static boolean validRecord(JSONObject record) {
        if (record == null || !(record.opt("current") instanceof String)) return false;
        if (record.has("previous") && !(record.opt("previous") instanceof String)) return false;
        if (record.has("revisions") && !(record.opt("revisions") instanceof JSONArray)) return false;
        return true;
    }

    private void update(UnaryOperator<JSONObject> mutation) {
        synchronized (LayoutHistory.class) {
            if (WorkspaceLayout.custom(surface)) {
                PageLayouts.update(account, surface, mutation);
                return;
            }
            JSONObject old = read();
            JSONObject updated = mutation.apply(old);
            if (updated == old) return;
            if (updated == null) account.remove(key);
            else account.write(key, updated.toString());
        }
    }

    String current() { return read().optString("current", ""); }
    boolean canUndo() { return read().has("previous"); }

    List<Revision> revisions() {
        JSONArray arr = read().optJSONArray("revisions");
        if (arr == null || arr.length() == 0) return Collections.emptyList();
        List<Revision> result = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            result.add(new Revision(
                    item.optString("id", ""),
                    item.optString("summary", ""),
                    item.optString("raw", ""),
                    item.optLong("ts", 0L)));
        }
        return Collections.unmodifiableList(result);
    }

    void replace(String next) {
        update(old -> {
            String previous = old.optString("current", "");
            if (previous.equals(next)) return old;
            try { return new JSONObject().put("current", next).put("previous", previous); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    /** Records a named revision into the bounded multi-revision stack alongside previous for 1-tap undo. */
    void replaceWithRevision(String revisionId, String summary, String next) {
        update(old -> {
            String previous = old.optString("current", "");
            if (previous.equals(next)) return old;
            try {
                JSONArray stack = copyRevisions(old.optJSONArray("revisions"));
                JSONObject entry = new JSONObject()
                        .put("id", revisionId == null ? "" : revisionId)
                        .put("summary", summary == null ? "" : summary)
                        .put("raw", previous)
                        .put("ts", System.currentTimeMillis());
                stack.put(entry);
                JSONArray trimmed = trimRevisions(stack);
                return new JSONObject()
                        .put("current", next)
                        .put("previous", previous)
                        .put("revisions", trimmed);
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    /** Discard only an explicitly selected native surface that is still unreadable at confirmation. */
    void resetUnreadable() {
        if (!WorkspaceLayout.SURFACES.containsKey(surface)) throw new IllegalStateException("Not a native screen");
        synchronized (LayoutHistory.class) {
            try { read(); }
            catch (Unreadable e) { account.remove(key); return; }
            throw new IllegalStateException("Layout history is readable");
        }
    }

    void undo() {
        update(record -> {
            if (!record.has("previous")) return record;
            String previous = record.optString("previous", "");
            try {
                JSONArray stack = record.optJSONArray("revisions");
                if (stack != null && stack.length() > 1) {
                    JSONArray remaining = new JSONArray();
                    for (int i = 0; i < stack.length() - 1; i++) remaining.put(stack.get(i));
                    JSONObject restored = new JSONObject().put("current", previous)
                            .put("previous", remaining.optJSONObject(remaining.length() - 1).optString("raw", ""))
                            .put("revisions", remaining);
                    return restored;
                }
                return previous.isEmpty() ? null : new JSONObject().put("current", previous);
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    void forgetRemoved() {
        if (WorkspaceLayout.custom(surface)) update(row -> row.optString("current", "").isEmpty() ? null : row);
    }

    private static JSONArray copyRevisions(JSONArray source) throws JSONException {
        JSONArray copy = new JSONArray();
        if (source == null) return copy;
        for (int i = 0; i < source.length(); i++) copy.put(source.get(i));
        return copy;
    }

    private static JSONArray trimRevisions(JSONArray stack) throws JSONException {
        if (stack.length() <= MAX_REVISIONS) return stack;
        JSONArray trimmed = new JSONArray();
        for (int i = stack.length() - MAX_REVISIONS; i < stack.length(); i++) trimmed.put(stack.get(i));
        return trimmed;
    }
}
