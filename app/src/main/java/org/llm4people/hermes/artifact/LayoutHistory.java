package org.llm4people.hermes.artifact;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;

/** The active and previous layouts for a surface are replaced in one encrypted account record. */
final class LayoutHistory {
    private final Account account;
    private final String key;
    LayoutHistory(Account account, String surface) {
        if (!WorkspaceLayout.SURFACES.containsKey(surface)) throw new IllegalArgumentException("Unknown screen");
        this.account = account;
        key = "workspace.v1." + surface;
    }

    private JSONObject read() {
        String raw = account.read(key);
        if (raw == null) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (JSONException e) { throw new IllegalStateException("Unreadable workspace record"); }
    }

    String current() { return read().optString("current", ""); }
    boolean canUndo() { return read().has("previous"); }

    void replace(String next) {
        synchronized (account) {
            String old = current();
            if (old.equals(next)) return;
            try {
                account.write(key, new JSONObject().put("current", next).put("previous", old).toString());
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }

    void undo() {
        synchronized (account) {
            JSONObject record = read();
            if (!record.has("previous")) return;
            String previous = record.optString("previous", "");
            if (previous.isEmpty()) { account.remove(key); return; }
            try { account.write(key, new JSONObject().put("current", previous).toString()); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }
}
