package org.llm4people.hermes.thread;

import android.content.Context;

import org.json.JSONArray;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.artifact.MessageParts;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;

import java.util.HashSet;
import java.util.Set;

/** Wire conversions shared by the inbox, transcript and durable run state. */
final class ThreadData {
    static final int PAGE = HermesConfig.DEFAULT_THREAD_PAGE_SIZE;

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
        return title(null, session);
    }

    static String title(Context context, JSONObject session) {
        return ThreadTitles.display(context, string(session, "title").trim(), ThreadTitles.inboxPrompt(session),
                ThreadTitles.emptySession(session));
    }

    private static final String PHOTO_HINT = "[Image attached at: /";
    private static final String RETAINED_HINT =
            "\\[Image attached at: /[^\\r\\n\\]]*?/native-inputs/([a-f0-9]{32})[a-f0-9]{32}/api_\\1\\.(?:png|jpe?g|webp|gif)\\]";
    private static final java.util.regex.Pattern PHOTO_LINE = java.util.regex.Pattern.compile(
            "(?m)^" + RETAINED_HINT + "(?:\\n\\[screenshot\\])?$");
    private static final java.util.regex.Pattern PHOTO_PREVIEW = java.util.regex.Pattern.compile(
            RETAINED_HINT + "(?:\\s+\\[screenshot\\])?");

    static String content(JSONObject message) {
        return content(null, message);
    }

    static String content(Context context, JSONObject message) {
        String value = MessageParts.display(message);
        if ("user".equals(string(message, "role")) && value.contains(PHOTO_HINT)) {
            return PHOTO_LINE.matcher(value).replaceAll(java.util.regex.Matcher.quoteReplacement(
                    ThreadStrings.get(context, R.string.thread_attached_image)));
        }
        return value;
    }

    static String caption(String text) {
        return text.contains(PHOTO_HINT) ? PHOTO_LINE.matcher(text).replaceAll("").trim() : text;
    }

    /** A clipped wire preview lacks provenance. Strip only a complete canonical retained-media suffix. */
    static String inboxPreview(String text) {
        int marker = text.lastIndexOf(PHOTO_HINT);
        if (marker < 0 || marker > 0 && !Character.isWhitespace(text.charAt(marker - 1))) return text;
        String hint = text.substring(marker);
        if (PHOTO_PREVIEW.matcher(hint).matches()) return text.substring(0, marker).trim();
        return text;
    }

    record RowId(String segment, String id) {}

    static RowId rowId(JSONObject row) {
        String id = string(row, "id");
        return id.isEmpty() ? null : new RowId(string(row, "session_id"), id);
    }

    static JSONArray join(JSONArray first, JSONArray second) {
        JSONArray joined = new JSONArray();
        Set<RowId> ids = new HashSet<>();
        for (JSONArray page : new JSONArray[]{first, second}) {
            for (int i = 0; i < page.length(); i++) {
                JSONObject row = page.optJSONObject(i);
                if (row == null) continue;
                RowId id = rowId(row);
                if (id == null || ids.add(id)) joined.put(row);
            }
        }
        return joined;
    }

    static JSONArray visibleMessages(JSONArray rows) {
        JSONArray visible = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && !"session_meta".equals(string(row, "role"))
                    && !"hidden".equals(string(row, "display_kind"))) visible.put(row);
        }
        return visible;
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

    static long generation(JSONObject row) {
        Object value = row.opt("execution_generation");
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1;
    }

    static JSONObject unknownReceipt(JSONObject status) {
        String admission = string(status, "admission_id");
        long generation = generation(status);
        if (!"unknown".equals(status.optString("status")) || !validId(admission) || generation < 0) return null;
        return put(put(new JSONObject(), "admission_id", admission), "execution_generation", generation);
    }

    static String failureDetail(JSONObject status, String bearer) {
        String detail = string(status, "error");
        if (detail.isEmpty()) detail = string(status, "turn_exit_reason");
        if (!bearer.isEmpty()) detail = detail.replace(bearer, "•••");
        // Hermes redacts the error before egress. Keep common auth fragments out of the UI
        // even when a custom provider echoed its request headers or included a token in a URL.
        detail = detail.replaceAll("(?i)data:image/[^, ]{1,40},[a-zA-Z0-9+/=_-]+", "[image]")
                .replaceAll("(?i)(bearer\\s+|(?:api[_-]?key|access_token|token|password|secret)\\s*[=:]\\s*)[^\\s&;,]+", "$1•••")
                .replaceAll("[\\p{Cntrl}\\p{Cf}]+", " ").trim();
        return detail.length() <= 500 ? detail : detail.substring(0, 500) + "…";
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
