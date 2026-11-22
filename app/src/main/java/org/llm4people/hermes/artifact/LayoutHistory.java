package org.llm4people.hermes.artifact;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;

/** The active and previous layouts for a surface are replaced in one encrypted account record. */
final class LayoutHistory {
    private final Account account;
    final String surface;
    private final String key;

    static final class Unreadable extends IllegalStateException {
        private Unreadable(Throwable cause) { super("Unreadable workspace record", cause); }
    }

    LayoutHistory(Account account, String surface) {
        if (!WorkspaceLayout.SURFACES.containsKey(surface)) throw new IllegalArgumentException("Unknown screen");
        this.account = account;
        this.surface = surface;
        key = "workspace.v1." + surface;
    }

    private JSONObject read() {
        try {
            String raw = account.read(key);
            if (raw == null) return new JSONObject();
            JSONObject record = new JSONObject(raw);
            if (!(record.opt("current") instanceof String)
                    || (record.has("previous") && !(record.opt("previous") instanceof String)))
                throw new JSONException("Invalid layout history");
            return record;
        } catch (Account.UnreadableRecord | JSONException e) { throw new Unreadable(e); }
    }

    String current() { return read().optString("current", ""); }
    boolean canUndo() { return read().has("previous"); }

    void replace(String next) {
        synchronized (LayoutHistory.class) {
            String old = current();
            if (old.equals(next)) return;
            try {
                account.write(key, new JSONObject().put("current", next).put("previous", old).toString());
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
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
        synchronized (LayoutHistory.class) {
            JSONObject record = read();
            if (!record.has("previous")) return;
            String previous = record.optString("previous", "");
            if (previous.isEmpty()) { account.remove(key); return; }
            try { account.write(key, new JSONObject().put("current", previous).toString()); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }
}
