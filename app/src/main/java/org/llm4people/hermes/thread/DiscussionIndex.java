package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;

/** The gateway orders the inbox. The currently open root stays reachable even off its first page. */
final class DiscussionIndex {
    private DiscussionIndex() {}

    static JSONArray merge(JSONArray current, JSONArray next) {
        LinkedHashMap<String, JSONObject> ordered = new LinkedHashMap<>();
        for (JSONArray page : new JSONArray[]{current, next}) {
            if (page == null) continue;
            for (int i = 0; i < page.length(); i++) {
                JSONObject row = page.optJSONObject(i);
                if (row == null) continue;
                String root = ThreadIdentity.rootId(row);
                if (!root.isEmpty()) ordered.put(root, row);
            }
        }
        JSONArray rows = new JSONArray();
        for (JSONObject row : ordered.values()) rows.put(row);
        return rows;
    }

    static JSONArray selectedFirst(JSONArray rows, String root, String tip, String title, String opening,
                                   long messageCount) {
        if (root == null || root.isEmpty()) return rows == null ? new JSONArray() : rows;
        String safeTip = tip == null || tip.isEmpty() ? root : tip;
        String resolvedTitle = title == null ? "" : title;
        String preview = opening == null ? "" : opening;
        int matchIndex = -1;
        JSONArray source = rows == null ? new JSONArray() : rows;
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            if (row == null) continue;
            if (root.equals(ThreadIdentity.rootId(row)) || root.equals(ThreadData.string(row, "id"))) {
                if (matchIndex < 0) matchIndex = i;
                if (preview.isEmpty()) preview = ThreadTitles.previewForRow(row);
                if ("Conversation".equals(resolvedTitle) && !ThreadData.string(row, "title").isEmpty())
                    resolvedTitle = ThreadData.string(row, "title");
            }
        }
        JSONObject selected = ThreadData.put(new JSONObject(), "id", safeTip);
        ThreadData.put(selected, "_lineage_root_id", root);
        ThreadData.put(selected, "title", resolvedTitle);
        ThreadData.put(selected, "preview", preview);
        ThreadData.put(selected, "message_count", messageCount);
        JSONArray visible = new JSONArray();
        if (matchIndex < 0) visible.put(selected);
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            if (row == null) continue;
            if (i == matchIndex) visible.put(selected);
            else if (!root.equals(ThreadIdentity.rootId(row)) && !root.equals(ThreadData.string(row, "id")))
                visible.put(row);
        }
        return visible;
    }
}
