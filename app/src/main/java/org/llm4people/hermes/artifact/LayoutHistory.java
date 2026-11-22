package org.llm4people.hermes.artifact;

import java.util.function.UnaryOperator;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;

/** One previous revision per surface. Each mutation is committed to authenticated local storage. */
final class LayoutHistory {
    private final Account account;
    final String surface;
    LayoutHistory(Account account, String surface) {
        if (!WorkspaceLayout.SURFACES.containsKey(surface) && !WorkspaceLayout.custom(surface))
            throw new IllegalArgumentException("Unknown screen");
        this.account = account;
        this.surface = surface;
    }

    private JSONObject read() {
        if (WorkspaceLayout.custom(surface)) {
            JSONObject row = PageLayouts.read(account).optJSONObject(surface);
            return row == null ? new JSONObject() : row;
        }
        String raw = account.read("workspace.v1." + surface);
        if (raw == null) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (JSONException e) { throw new IllegalStateException("Unreadable workspace record"); }
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
            if (updated == null) account.remove("workspace.v1." + surface);
            else account.write("workspace.v1." + surface, updated.toString());
        }
    }

    String current() { return read().optString("current", ""); }
    boolean canUndo() { return read().has("previous"); }

    void replace(String next) {
        update(old -> {
            String previous = old.optString("current", "");
            if (previous.equals(next)) return old;
            try { return new JSONObject().put("current", next).put("previous", previous); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    void undo() {
        update(record -> {
            if (!record.has("previous")) return record;
            String previous = record.optString("previous", "");
            try { return previous.isEmpty() ? null : new JSONObject().put("current", previous); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    void forgetRemoved() {
        if (WorkspaceLayout.custom(surface)) update(row -> row.optString("current", "").isEmpty() ? null : row);
    }
}
