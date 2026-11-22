package org.llm4people.hermes.artifact;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONTokener;
import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** One encrypted index atomically contains named pages, their memberships and undo revisions. */
final class PageLayouts {
    private static final String KEY = "workspace.pages.v1";
    private static final int LIMIT = 24;

    static JSONObject read(Account account) {
        String raw;
        try { raw = account.read(KEY); }
        catch (Account.UnreadableRecord error) { throw new Damaged(account, null, error, new JSONObject()); }
        if (raw == null) return new JSONObject();
        try {
            JSONTokener input = new JSONTokener(raw);
            if (!(input.nextValue() instanceof JSONObject pages)) throw new JSONException("Invalid catalog");
            boolean damaged = retainReadableEntries(pages);
            if (damaged || input.nextClean() != 0) throw new Damaged(account, raw, null, pages);
            return pages;
        } catch (JSONException malformed) {
            throw new Damaged(account, raw, null, new JSONObject());
        }
    }

    // Work only on decoded memory. A damaged catalog never changes until its owner confirms.
    private static boolean retainReadableEntries(JSONObject pages) throws JSONException {
        JSONArray ids = pages.names();
        boolean damaged = false;
        for (int i = 0; ids != null && i < ids.length(); i++) {
            String id = ids.getString(i);
            JSONObject record = pages.optJSONObject(id);
            if (WorkspaceLayout.custom(id) && LayoutHistory.validRecord(record)) continue;
            damaged = true;
            if (!WorkspaceLayout.custom(id) || record == null
                    || (!(record.opt("current") instanceof String) && !(record.opt("previous") instanceof String))) {
                pages.remove(id);
            } else {
                // A surviving previous revision remains recoverable through Undo.
                if (!(record.opt("current") instanceof String)) record.put("current", "");
                if (!(record.opt("previous") instanceof String)) record.remove("previous");
            }
        }
        return damaged;
    }

    static final class Damaged extends IllegalStateException {
        private final Account owner;
        private final String source;
        private final Account.UnreadableRecord encrypted;
        private final JSONObject retained;

        private Damaged(Account owner, String source, Account.UnreadableRecord encrypted, JSONObject retained) {
            super("Unreadable named page catalog", encrypted);
            this.owner = owner;
            this.source = source;
            this.encrypted = encrypted;
            this.retained = retained;
        }

        boolean hasReadablePages() { return retained.length() != 0; }

        void recover() {
            synchronized (LayoutHistory.class) {
                try { read(owner); }
                catch (Damaged current) {
                    boolean unchanged = encrypted == null ? source.equals(current.source)
                            : encrypted.sameVersion(current.encrypted);
                    if (unchanged) {
                        if (current.hasReadablePages()) owner.write(KEY, current.retained.toString());
                        else owner.remove(KEY);
                        return;
                    }
                }
                throw new IllegalStateException("Named page catalog changed");
            }
        }
    }

    // Called under LayoutHistory's process-wide mutation lock. An account can be unlocked twice.
    static void update(Account account, String page, UnaryOperator<JSONObject> mutation) {
        JSONObject pages = read(account);
        JSONObject old = pages.optJSONObject(page);
        if (old == null) old = new JSONObject();
        JSONObject updated = mutation.apply(old);
        if (updated == old) return;
        if (updated == null) pages.remove(page);
        else {
            if (!pages.has(page) && pages.length() >= LIMIT) throw new Full();
            try { pages.put(page, updated); } catch (JSONException e) { throw new IllegalStateException(e); }
        }
        if (pages.length() == 0) account.remove(KEY);
        else account.write(KEY, pages.toString());
    }

    static Map<String,String> titles(Account account, boolean includeRemoved) {
        Map<String,String> result = new TreeMap<>();
        JSONObject pages = read(account);
        Iterator<String> ids = pages.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject record = pages.optJSONObject(id);
            if (record == null || !WorkspaceLayout.custom(id)) continue;
            String raw = record.optString("current", "");
            if (raw.isEmpty() && !includeRemoved) continue;
            String title = WorkspaceLayout.screenName(id);
            try { title = WorkspaceLayout.parse(raw.isEmpty() ? record.optString("previous") : raw).title; }
            catch (IOException e) { org.llm4people.hermes.Diagnostics.warning("workspace","invalid_page"); }
            result.put(id, raw.isEmpty() ? title + " · supprimé" : title);
        }
        return result;
    }

    static final class Full extends IllegalStateException {
        Full() { super("Saved page limit reached"); }
    }
}
