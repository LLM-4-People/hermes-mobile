package org.llm4people.hermes.artifact;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** One encrypted index atomically contains named pages, their memberships and undo revisions. */
final class PageLayouts {
    private static final String KEY = "workspace.pages.v1";
    private static final int LIMIT = 24;

    static JSONObject read(Account account) {
        String raw = account.read(KEY);
        try { return raw == null ? new JSONObject() : new JSONObject(raw); }
        catch (JSONException e) { throw new IllegalStateException("Unreadable page catalog"); }
    }

    static void save(Account account, String page, JSONObject record) {
        synchronized (account) {
            JSONObject pages = read(account);
            if (record == null) pages.remove(page);
            else {
                if (!pages.has(page) && pages.length() >= LIMIT) throw new Full();
                try { pages.put(page, record); } catch (JSONException e) { throw new IllegalStateException(e); }
            }
            if (pages.length() == 0) account.remove(KEY);
            else account.write(KEY, pages.toString());
        }
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
