package org.llm4people.hermes.hub;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.push.DeliveryItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-JVM Single Source of Truth for the living Notes &amp; Tasks hub screen.
 * Parses, normalizes, and sorts Notes, Reminders, and Inbox (Deliveries + Shared) snapshots.
 */
public final class NotesTasksModel {
    public static final int MAX_PREVIEW_CHARS = 120;
    public static final int MAX_VISIBLE_ITEMS = 12;

    public enum Segment {
        NOTES,
        REMINDERS,
        INBOX
    }

    public record NoteRow(
            String id,
            String title,
            String preview,
            boolean pinned,
            String updatedAt) {
        public NoteRow {
            id = safe(id);
            title = safe(title);
            preview = compactPreview(preview);
            updatedAt = safe(updatedAt);
        }
    }

    public record ReminderRow(
            String id,
            String name,
            String promptPreview,
            String schedule,
            boolean enabled,
            boolean needsAttention) {
        public ReminderRow {
            id = safe(id);
            name = safe(name);
            promptPreview = compactPreview(promptPreview);
            schedule = safe(schedule);
        }
    }

    public record ReminderOverview(
            List<ReminderRow> rows,
            int activeCount,
            int pausedCount,
            int attentionCount) {
        public int total() {
            return activeCount + pausedCount;
        }
    }

    public record SharedRow(
            String shareId,
            String resourceKind,
            String label,
            String ownerProfile,
            String permission) {
        public SharedRow {
            shareId = safe(shareId);
            resourceKind = safe(resourceKind).toLowerCase(Locale.ROOT);
            label = safe(label);
            ownerProfile = safe(ownerProfile);
            permission = safe(permission).toLowerCase(Locale.ROOT);
        }

        public String displayTitle() {
            return label.isEmpty() ? shareId : label;
        }
    }

    private NotesTasksModel() {}

    /** Sort note rows pinned-first, skip empty rows, and cap to {@link #MAX_VISIBLE_ITEMS}. */
    public static List<NoteRow> sortNotes(List<NoteRow> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<NoteRow> filtered = new ArrayList<>(raw.size());
        for (NoteRow row : raw) {
            if (row == null) continue;
            if (row.id().isEmpty() && row.title().isEmpty() && row.preview().isEmpty()) continue;
            filtered.add(row);
        }
        filtered.sort(Comparator.comparing(NoteRow::pinned).reversed());
        return Collections.unmodifiableList(limit(filtered, MAX_VISIBLE_ITEMS));
    }

    /** Parse `/api/notes` response into pinned-first, preview-normalized note rows. */
    public static List<NoteRow> parseNotes(JSONObject response) {
        if (response == null) return Collections.emptyList();
        JSONArray array = response.optJSONArray("notes");
        if (array == null) array = response.optJSONArray("data");
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<NoteRow> parsed = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String id = optText(obj, "id", "note_id");
            String title = optText(obj, "title");
            String body = optText(obj, "body", "content", "snippet");
            boolean pinned = obj.optBoolean("pinned", false);
            String updatedAt = optText(obj, "updated_at", "created_at");
            parsed.add(new NoteRow(id, title, body, pinned, updatedAt));
        }
        return sortNotes(parsed);
    }

    /** Pure-JVM parser for decoded `/api/notes` payload maps. */
    public static List<NoteRow> parseNotesMap(Map<String, Object> response) {
        List<?> array = extractList(response, "notes", "data");
        if (array == null || array.isEmpty()) return Collections.emptyList();
        List<NoteRow> parsed = new ArrayList<>(array.size());
        for (Object item : array) {
            if (!(item instanceof Map<?, ?> obj)) continue;
            String id = optMapText(obj, "id", "note_id");
            String title = optMapText(obj, "title");
            String body = optMapText(obj, "body", "content", "snippet");
            boolean pinned = optMapBool(obj, "pinned", false);
            String updatedAt = optMapText(obj, "updated_at", "created_at");
            parsed.add(new NoteRow(id, title, body, pinned, updatedAt));
        }
        return sortNotes(parsed);
    }

    /** Compute active/paused/attention totals and order reminders (attention -> active -> paused). */
    public static ReminderOverview summarizeReminders(List<ReminderRow> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ReminderOverview(Collections.emptyList(), 0, 0, 0);
        }
        List<ReminderRow> filtered = new ArrayList<>(raw.size());
        int active = 0;
        int paused = 0;
        int attention = 0;
        for (ReminderRow row : raw) {
            if (row == null) continue;
            if (row.id().isEmpty() && row.name().isEmpty()) continue;
            if (row.enabled()) active++;
            else paused++;
            if (row.needsAttention()) attention++;
            filtered.add(row);
        }
        filtered.sort(Comparator.comparing(ReminderRow::needsAttention).reversed()
                .thenComparing(Comparator.comparing(ReminderRow::enabled).reversed()));
        return new ReminderOverview(
                Collections.unmodifiableList(limit(filtered, MAX_VISIBLE_ITEMS)),
                active,
                paused,
                attention);
    }

    /** Parse `/api/jobs?include_disabled=true` response into active/paused/attention overview + rows. */
    public static ReminderOverview parseReminders(JSONObject response) {
        if (response == null) return new ReminderOverview(Collections.emptyList(), 0, 0, 0);
        JSONArray array = response.optJSONArray("jobs");
        if (array == null) array = response.optJSONArray("data");
        if (array == null || array.length() == 0) {
            return new ReminderOverview(Collections.emptyList(), 0, 0, 0);
        }
        List<ReminderRow> rows = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String id = optText(obj, "id", "job_id");
            String name = optText(obj, "name", "title");
            String prompt = optText(obj, "prompt", "message");
            String schedule = optText(obj, "schedule_display", "schedule");
            boolean enabled = obj.optBoolean("enabled", true);
            String lastStatus = optText(obj, "last_status").toLowerCase(Locale.ROOT);
            boolean failed = "error".equals(lastStatus) || "failed".equals(lastStatus);
            rows.add(new ReminderRow(id, name.isEmpty() ? id : name, prompt, schedule, enabled, failed));
        }
        return summarizeReminders(rows);
    }

    /** Pure-JVM parser for decoded `/api/jobs?include_disabled=true` payload maps. */
    public static ReminderOverview parseRemindersMap(Map<String, Object> response) {
        List<?> array = extractList(response, "jobs", "data");
        if (array == null || array.isEmpty()) {
            return new ReminderOverview(Collections.emptyList(), 0, 0, 0);
        }
        List<ReminderRow> rows = new ArrayList<>(array.size());
        for (Object item : array) {
            if (!(item instanceof Map<?, ?> obj)) continue;
            String id = optMapText(obj, "id", "job_id");
            String name = optMapText(obj, "name", "title");
            String prompt = optMapText(obj, "prompt", "message");
            String schedule = optMapText(obj, "schedule_display", "schedule");
            boolean enabled = optMapBool(obj, "enabled", true);
            String lastStatus = optMapText(obj, "last_status").toLowerCase(Locale.ROOT);
            boolean failed = "error".equals(lastStatus) || "failed".equals(lastStatus);
            rows.add(new ReminderRow(id, name.isEmpty() ? id : name, prompt, schedule, enabled, failed));
        }
        return summarizeReminders(rows);
    }

    /** Filter out empty share rows and cap to {@link #MAX_VISIBLE_ITEMS}. */
    public static List<SharedRow> normalizeShares(List<SharedRow> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<SharedRow> out = new ArrayList<>(raw.size());
        for (SharedRow row : raw) {
            if (row == null) continue;
            if (row.shareId().isEmpty() && row.label().isEmpty()) continue;
            out.add(row);
        }
        return Collections.unmodifiableList(limit(out, MAX_VISIBLE_ITEMS));
    }

    /** Parse `/api/shares/incoming` response cleanly, tolerating missing or partial rows. */
    public static List<SharedRow> parseReceivedShares(JSONObject response) {
        if (response == null) return Collections.emptyList();
        JSONArray array = response.optJSONArray("shares");
        if (array == null) array = response.optJSONArray("data");
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<SharedRow> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String shareId = optText(obj, "share_id", "id");
            String kind = optText(obj, "resource_kind", "kind");
            String label = optText(obj, "label", "title", "resource_id");
            String owner = optText(obj, "owner_profile", "owner", "shared_by");
            String perm = optText(obj, "permission");
            out.add(new SharedRow(shareId, kind, label, owner, perm));
        }
        return normalizeShares(out);
    }

    /** Pure-JVM parser for decoded `/api/shares/incoming` payload maps. */
    public static List<SharedRow> parseReceivedSharesMap(Map<String, Object> response) {
        List<?> array = extractList(response, "shares", "data");
        if (array == null || array.isEmpty()) return Collections.emptyList();
        List<SharedRow> out = new ArrayList<>(array.size());
        for (Object item : array) {
            if (!(item instanceof Map<?, ?> obj)) continue;
            String shareId = optMapText(obj, "share_id", "id");
            String kind = optMapText(obj, "resource_kind", "kind");
            String label = optMapText(obj, "label", "title", "resource_id");
            String owner = optMapText(obj, "owner_profile", "owner", "shared_by");
            String perm = optMapText(obj, "permission");
            out.add(new SharedRow(shareId, kind, label, owner, perm));
        }
        return normalizeShares(out);
    }

    /** Single source of truth mapping reminder status to its localized pill string resource. */
    public static int reminderStatusLabelRes(ReminderRow row) {
        if (row == null) return R.string.panel_paused;
        if (row.needsAttention()) return R.string.panel_jobs_run_status_failed;
        return row.enabled() ? R.string.panel_scheduled : R.string.panel_paused;
    }

    /** Count unacknowledged deliveries in the given list. */
    public static int countUnreadDeliveries(List<DeliveryItem> items) {
        if (items == null || items.isEmpty()) return 0;
        int unread = 0;
        for (DeliveryItem item : items) {
            if (item != null && !item.isAcknowledged()) unread++;
        }
        return unread;
    }

    /** Collapse line breaks and excess whitespace into a crisp, bounded 1-line summary. */
    public static String compactPreview(String raw) {
        if (raw == null) return "";
        String collapsed = raw.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
        if (collapsed.length() <= MAX_PREVIEW_CHARS) return collapsed;
        return collapsed.substring(0, MAX_PREVIEW_CHARS - 1).trim() + "…";
    }

    private static String optText(JSONObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) continue;
            String value = safe(obj.optString(key, ""));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static List<?> extractList(Map<String, Object> response, String primaryKey, String fallbackKey) {
        if (response == null) return null;
        Object primary = response.get(primaryKey);
        if (primary instanceof List<?> list) return list;
        Object fallback = response.get(fallbackKey);
        return fallback instanceof List<?> list ? list : null;
    }

    private static String optMapText(Map<?, ?> obj, String... keys) {
        for (String key : keys) {
            Object raw = obj.get(key);
            if (raw == null) continue;
            String value = safe(raw instanceof String s ? s : String.valueOf(raw));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static boolean optMapBool(Map<?, ?> obj, String key, boolean fallback) {
        Object raw = obj.get(key);
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            String lower = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(lower)) return true;
            if ("false".equals(lower)) return false;
        }
        return fallback;
    }

    private static <T> List<T> limit(List<T> list, int max) {
        if (list.size() <= max) return list;
        return list.subList(0, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
