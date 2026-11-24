package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.R;
import org.llm4people.hermes.panel.UniversalShareModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-JVM SSoT for inline Chats inbox filtering (`All` / `Pinned` / `Shared`) and
 * shared-discussion row normalization.
 */
public final class ThreadInboxFilter {
    static final String KEY_SHARED = "_hermes_shared";
    static final String KEY_SHARE_ID = "_hermes_share_id";
    static final String KEY_OWNER = "_hermes_owner_profile";
    static final String KEY_PERMISSION = "_hermes_permission";

    public enum Filter {
        ALL(R.string.thread_filter_all),
        PINNED(R.string.thread_filter_pinned),
        SHARED(R.string.thread_filter_shared);

        public final int labelRes;

        Filter(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** Pure-JVM representation of a local or shared inbox entry. */
    public record Entry(
            String id,
            String title,
            boolean pinned,
            boolean shared,
            String shareId,
            String ownerProfile,
            String permission) {}

    private ThreadInboxFilter() {}

    public static boolean isPinned(JSONObject row) {
        if (row == null) return false;
        return row.optBoolean("pinned", false) || row.optBoolean("is_pinned", false);
    }

    public static boolean isPinnedMap(Map<?, ?> row) {
        if (row == null) return false;
        return Boolean.TRUE.equals(row.get("pinned")) || Boolean.TRUE.equals(row.get("is_pinned"));
    }

    public static boolean isSharedRow(JSONObject row) {
        return row != null && row.optBoolean(KEY_SHARED, false);
    }

    public static String shareId(JSONObject row) {
        return ThreadData.string(row, KEY_SHARE_ID);
    }

    public static String ownerProfile(JSONObject row) {
        return ThreadData.string(row, KEY_OWNER);
    }

    public static String permission(JSONObject row) {
        return ThreadData.string(row, KEY_PERMISSION);
    }

    /** Pure-JVM filter over inbox entries for {@link Filter#ALL}, {@link Filter#PINNED}, and {@link Filter#SHARED}. */
    public static List<Entry> filterEntries(List<Entry> rows, Filter filter) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        Filter active = filter == null ? Filter.ALL : filter;
        if (active == Filter.ALL) {
            List<Entry> clean = new ArrayList<>(rows.size());
            for (Entry row : rows) {
                if (row != null) clean.add(row);
            }
            return Collections.unmodifiableList(clean);
        }
        if (active == Filter.SHARED) return Collections.emptyList();
        List<Entry> pinned = new ArrayList<>();
        for (Entry row : rows) {
            if (row != null && row.pinned()) pinned.add(row);
        }
        return Collections.unmodifiableList(pinned);
    }

    /** Filters Android runtime {@link JSONArray} inbox rows according to {@code filter}. */
    public static JSONArray filterLocal(JSONArray rows, Filter filter) {
        if (rows == null || rows.length() == 0) return new JSONArray();
        Filter active = filter == null ? Filter.ALL : filter;
        if (active == Filter.ALL) return rows;
        if (active == Filter.SHARED) return new JSONArray();
        JSONArray pinned = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (isPinned(row)) pinned.put(row);
        }
        return pinned;
    }

    /** Pure-JVM parser for `/api/shares/incoming?resource_kind=session` payloads. */
    public static List<Entry> parseSharedSessionsMap(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();
        List<?> rows = JsonValues.asList(response.get("data"));
        if (rows == null) rows = JsonValues.asList(response.get("shares"));
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<Entry> out = new ArrayList<>(rows.size());
        for (Object item : rows) {
            Map<?, ?> row = JsonValues.asMap(item);
            if (row == null) continue;
            String kind = JsonValues.mapText(row, "resource_kind").toLowerCase(Locale.ROOT);
            if (!kind.isEmpty() && !UniversalShareModel.KIND_SESSION.equals(kind)) continue;
            String shareId = JsonValues.mapText(row, "share_id");
            if (shareId.isEmpty()) shareId = JsonValues.mapText(row, "id");
            if (shareId.isEmpty()) continue;
            String resourceId = JsonValues.mapText(row, "resource_id");
            String label = JsonValues.mapText(row, "label");
            if (label.isEmpty()) label = resourceId;
            if (label.isEmpty()) continue;
            String owner = JsonValues.mapText(row, "owner_profile");
            String perm = JsonValues.mapText(row, "permission").toLowerCase(Locale.ROOT);
            if (perm.isEmpty()) perm = UniversalShareModel.PERM_READ;
            out.add(new Entry(
                    resourceId.isEmpty() ? shareId : resourceId,
                    label,
                    false,
                    true,
                    shareId,
                    owner,
                    perm));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Parses `/api/shares/incoming?resource_kind=session` into normalized inbox rows that render
     * natively inside the Chats list without colliding with owned session IDs.
     */
    public static JSONArray parseSharedSessions(JSONObject response) {
        JSONArray out = new JSONArray();
        for (Entry entry : parseSharedSessionsMap(JsonValues.toMap(response))) {
            JSONObject normalized = new JSONObject();
            ThreadData.put(normalized, "id", entry.id());
            ThreadData.put(normalized, "_lineage_root_id", "shared:" + entry.shareId());
            ThreadData.put(normalized, "title", entry.title());
            ThreadData.put(normalized, "preview", "");
            ThreadData.put(normalized, KEY_SHARED, true);
            ThreadData.put(normalized, KEY_SHARE_ID, entry.shareId());
            ThreadData.put(normalized, KEY_OWNER, entry.ownerProfile());
            ThreadData.put(normalized, KEY_PERMISSION, entry.permission());
            out.put(normalized);
        }
        return out;
    }

    /** Returns the exact localized empty-state string resource for the active inbox filter. */
    public static int emptyMessageRes(Filter filter, boolean sharedUnavailable) {
        Filter active = filter == null ? Filter.ALL : filter;
        return switch (active) {
            case ALL -> R.string.thread_empty_profile;
            case PINNED -> R.string.thread_filter_pinned_empty;
            case SHARED -> sharedUnavailable
                    ? R.string.thread_filter_shared_unavailable
                    : R.string.thread_filter_shared_empty;
        };
    }

    /** Extracts a readable transcript or text body from `/api/shares/incoming/{id}/content`. */
    public static String extractSharedContent(JSONObject reply, String userLabel, String assistantLabel) {
        return extractSharedContentMap(JsonValues.toMap(reply), userLabel, assistantLabel);
    }

    /** Pure-JVM extractor for `/api/shares/incoming/{id}/content` payloads. */
    public static String extractSharedContentMap(Map<String, Object> reply, String userLabel, String assistantLabel) {
        if (reply == null) return "";
        for (String key : new String[]{"raw", "body", "content", "text", "markdown"}) {
            Object val = reply.get(key);
            if (val instanceof String s && !s.trim().isEmpty()) return s.trim();
        }
        List<?> messages = JsonValues.asList(reply.get("messages"));
        if (messages == null) {
            Map<?, ?> nested = JsonValues.asMap(reply.get("content"));
            if (nested != null) messages = JsonValues.asList(nested.get("messages"));
        }
        if (messages != null && !messages.isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (Object item : messages) {
                Map<?, ?> row = JsonValues.asMap(item);
                if (row == null) continue;
                String text = JsonValues.mapText(row, "content");
                if (text.isEmpty()) continue;
                String role = JsonValues.mapText(row, "role").toLowerCase(Locale.ROOT);
                String speaker = "user".equals(role) ? userLabel : "assistant".equals(role) ? assistantLabel : "";
                if (out.length() > 0) out.append("\n\n");
                if (speaker != null && !speaker.isEmpty()) out.append(speaker).append(":\n");
                out.append(text);
            }
            if (out.length() > 0) return out.toString();
        }
        return JsonValues.mapText(reply, "title");
    }
}
