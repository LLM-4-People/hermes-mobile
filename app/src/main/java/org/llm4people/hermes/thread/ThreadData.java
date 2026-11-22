package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;

import java.util.HashSet;
import java.util.Set;

/** Wire conversions shared by the inbox, transcript and durable run state. */
final class ThreadData {
    static final int PAGE = 40;
    static String path(String session) {
        return "/api/sessions/" + HermesApi.segment(session);
    }

    static boolean validId(String id) {
        try { HermesApi.segment(id); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static JSONObject object(String encoded) {
        if (encoded == null) return new JSONObject();
        try {
            return new JSONObject(encoded);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid saved thread state", e);
        }
    }

    static JSONObject put(JSONObject target, String key, Object value) {
        try {
            return target.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid JSON value", e);
        }
    }

    static String string(JSONObject obj, String key) {
        return obj == null || obj.isNull(key) ? "" : obj.optString(key, "");
    }

    static String title(JSONObject session) {
        String title = string(session, "title").trim();
        if (!title.isEmpty()) return title;
        String preview = string(session, "preview").replace('\n', ' ').trim();
        return preview.isEmpty() ? "Nouvelle conversation" : preview;
    }

    static String content(JSONObject message) {
        if (message == null) return "";
        Object value = message.opt("content");
        if (value instanceof String) return (String) value;
        if (!(value instanceof JSONArray)) return "";
        JSONArray parts = (JSONArray) value;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String type = string(part, "type");
            if (type.equals("text") || type.equals("input_text") || type.equals("output_text")) {
                if (text.length() > 0) text.append('\n');
                text.append(string(part, "text"));
            } else if (type.contains("image")) {
                text.append("\n[Image jointe]");
            }
        }
        return text.toString();
    }

    static JSONArray join(JSONArray first, JSONArray second) {
        JSONArray joined = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (JSONArray page : new JSONArray[]{first, second}) {
            for (int i = 0; i < page.length(); i++) {
                JSONObject row = page.optJSONObject(i);
                if (row == null) continue;
                String id = string(row, "id");
                if (id.isEmpty() || ids.add(id)) joined.put(row);
            }
        }
        return joined;
    }

    static JSONObject body(String id, String text, JSONObject options) {
        JSONObject body = put(put(new JSONObject(), "session_id", id), "input", text);
        if (options != null) {
            for (String key : new String[]{"model", "provider", "instructions", "allowed_toolsets", "excluded_tools"}) {
                if (!options.isNull(key)) put(body, key, options.opt(key));
            }
        }
        return body;
    }

    static boolean terminal(String status) {
        return "completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "interrupted".equals(status);
    }

    static boolean canReplay(long now, long sentAt, int bootNow, int bootThen, long retentionSeconds, boolean durable) {
        return durable && bootThen >= 0 && bootThen == bootNow && retentionSeconds > 0 && sentAt >= 0 && now >= sentAt
                && (now - sentAt) / 1000 < retentionSeconds;
    }

    private ThreadData() {}
}
