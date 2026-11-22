package org.llm4people.hermes.artifact;

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

    private void save(JSONObject record) {
        if (WorkspaceLayout.custom(surface)) PageLayouts.save(account, surface, record);
        else if (record == null) account.remove("workspace.v1." + surface);
        else account.write("workspace.v1." + surface, record.toString());
    }

    String current() { return read().optString("current", ""); }
    boolean canUndo() { return read().has("previous"); }

    void replace(String next) {
        synchronized (account) {
            String old = current();
            if (old.equals(next)) return;
            try { save(new JSONObject().put("current", next).put("previous", old)); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }

    void undo() {
        synchronized (account) {
            JSONObject record = read();
            if (!record.has("previous")) return;
            String previous = record.optString("previous", "");
            try { save(previous.isEmpty() ? null : new JSONObject().put("current", previous)); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }

    void forgetRemoved() {
        synchronized (account) {
            if (WorkspaceLayout.custom(surface) && current().isEmpty()) save(null);
        }
    }
}
