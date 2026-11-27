package org.llm4people.hermes.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.net.HermesApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java data model, filter engine, and wire parser for profile-scoped scheduled jobs
 * ({@code /api/jobs}, {@code POST /api/jobs/{id}/run}, {@code GET /api/jobs/{id}/runs}).
 * Deterministic and host-JVM testable with zero Android framework dependencies.
 */
public final class ScheduledJobModel {
    public static final int DEFAULT_RUNS_LIMIT = 10;
    public static final int MAX_RUNS_LIMIT = 50;

    public enum Filter {
        ALL,
        ACTIVE,
        PAUSED,
        ATTENTION
    }

    public record Summary(int total, int active, int paused, int errors, int completed) {
        public int attention() {
            return errors + completed;
        }
    }

    public record JobRun(
            String id,
            String status,
            long startedAtEpochSec,
            long endedAtEpochSec,
            String title,
            String preview,
            boolean active) {
        /** Elapsed run duration in seconds, or -1 if unknown or still running. */
        public long durationSeconds() {
            if (startedAtEpochSec <= 0L || endedAtEpochSec < startedAtEpochSec) return -1L;
            return endedAtEpochSec - startedAtEpochSec;
        }
    }

    private ScheduledJobModel() {}

    /** Canonical immediate-trigger endpoint on Hermes Agent ({@code POST /api/jobs/{id}/run}). */
    public static String triggerPath(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing job id");
        }
        return ReminderPanel.JOBS + "/" + HermesApi.segment(jobId.trim()) + "/run";
    }

    /** Canonical bounded execution-history endpoint ({@code GET /api/jobs/{id}/runs?limit=N}). */
    public static String runsPath(String jobId, int limit) {
        if (jobId == null || jobId.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing job id");
        }
        int bounded = Math.max(1, Math.min(MAX_RUNS_LIMIT, limit));
        return ReminderPanel.JOBS + "/" + HermesApi.segment(jobId.trim()) + "/runs?limit=" + bounded;
    }

    /** Returns true when a normalized job state matches the selected overview filter chip. */
    public static boolean matchesFilter(String normalizedState, Filter filter) {
        Filter activeFilter = filter == null ? Filter.ALL : filter;
        String state = normalizedState == null ? "" : normalizedState.trim().toLowerCase(Locale.ROOT);
        return switch (activeFilter) {
            case ALL -> true;
            case ACTIVE -> "scheduled".equals(state);
            case PAUSED -> "paused".equals(state);
            case ATTENTION -> "error".equals(state) || "completed".equals(state);
        };
    }

    /** Aggregates profile job counts from normalized state strings (host-JVM testable). */
    public static Summary summarizeStates(List<String> states) {
        if (states == null || states.isEmpty()) return new Summary(0, 0, 0, 0, 0);
        int total = 0;
        int active = 0;
        int paused = 0;
        int errors = 0;
        int completed = 0;
        for (String raw : states) {
            total++;
            String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            switch (s) {
                case "scheduled" -> active++;
                case "paused" -> paused++;
                case "error" -> errors++;
                case "completed" -> completed++;
                default -> {}
            }
        }
        return new Summary(total, active, paused, errors, completed);
    }

    /** Aggregates counts directly from a {@code GET /api/jobs} JSONArray. */
    public static Summary summarizeJobs(JSONArray jobs) {
        if (jobs == null || jobs.length() == 0) return new Summary(0, 0, 0, 0, 0);
        List<String> states = new ArrayList<>(jobs.length());
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job == null || PanelData.text(job, "id").isEmpty()) continue;
            states.add(PanelData.jobState(job));
        }
        return summarizeStates(states);
    }

    /** Parses {@code GET /api/jobs/{id}/runs} response JSON safely on Android. */
    public static List<JobRun> parseRuns(JSONObject response) {
        return parseRunsMap(JsonValues.toMap(response));
    }

    /** Pure-Java parser for {@code GET /api/jobs/{id}/runs} payloads (host-JVM testable). */
    @SuppressWarnings("unchecked")
    public static List<JobRun> parseRunsMap(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();
        Object raw = response.get("runs");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return Collections.emptyList();
        List<JobRun> out = new ArrayList<>(Math.min(list.size(), MAX_RUNS_LIMIT));
        for (Object item : list) {
            if (out.size() >= MAX_RUNS_LIMIT) break;
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> row = (Map<String, Object>) m;
            String id = PlainTextSanitizer.collapseBounded(str(row.get("id")), 96);
            String status = normalizeRunStatus(str(row.get("status")), bool(row.get("is_active")));
            long started = org.llm4people.hermes.EpochTimestamps.parseEpochSeconds(row.get("started_at"));
            long ended = org.llm4people.hermes.EpochTimestamps.parseEpochSeconds(row.get("ended_at"));
            String title = PlainTextSanitizer.collapseBounded(str(row.get("title")), 120);
            String preview = PlainTextSanitizer.collapseBounded(str(row.get("preview")), 240);
            boolean active = bool(row.get("is_active")) || "running".equals(status);
            if (id.isEmpty() && started <= 0L && title.isEmpty()) continue;
            out.add(new JobRun(id, status, started, ended, title, preview, active));
        }
        return Collections.unmodifiableList(out);
    }

    /** Formats a non-negative duration in seconds concisely (e.g. {@code 14s}, {@code 2m 05s}, {@code 1h 04m}). */
    public static String formatDuration(long seconds) {
        return formatDuration(seconds, Locale.getDefault());
    }

    /** Locale-aware duration formatter using the active app locale instead of hardcoded {@link Locale#US}. */
    public static String formatDuration(long seconds, Locale locale) {
        if (seconds < 0L) return "";
        Locale active = locale == null ? Locale.getDefault() : locale;
        if (seconds < 60L) return String.format(active, "%ds", seconds);
        long mins = seconds / 60L;
        long remSec = seconds % 60L;
        if (mins < 60L) {
            return String.format(active, "%dm %02ds", mins, remSec);
        }
        long hours = mins / 60L;
        long remMin = mins % 60L;
        return String.format(active, "%dh %02dm", hours, remMin);
    }

    /** Extracts a clean, non-sensitive delivery channel label (e.g. {@code local}, {@code signal}, {@code telegram}). */
    public static String deliveryChannelLabel(String rawDeliver) {
        if (rawDeliver == null) return "";
        String trimmed = rawDeliver.trim();
        if (trimmed.isEmpty()) return "";
        int sep = trimmed.indexOf(':');
        String head = (sep > 0 ? trimmed.substring(0, sep) : trimmed).trim().toLowerCase(Locale.ROOT);
        return PlainTextSanitizer.collapseBounded(head, 32);
    }

    private static String normalizeRunStatus(String raw, boolean isActive) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (isActive || "running".equals(s)) return "running";
        return switch (s) {
            case "completed", "ok", "success" -> "completed";
            case "failed", "error" -> "failed";
            case "cancelled", "canceled" -> "cancelled";
            case "missed" -> "missed";
            default -> "unknown";
        };
    }

    private static String str(Object v) {
        return v instanceof String s ? s : "";
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b && b;
    }
}
