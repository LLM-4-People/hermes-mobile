package org.llm4people.hermes.thread;

import org.json.JSONObject;

import java.io.IOException;

/** Logical discussion metadata shared by the native title and workspace bindings. */
final class ThreadContext {
    interface Read { JSONObject get(String path) throws IOException; }

    static String title(JSONObject object, String field, String fallback) {
        String value = ThreadData.string(object, field).trim();
        return value.isEmpty() ? fallback : value;
    }

    static long visibleTotal(JSONObject response) {
        JSONObject pagination = response.optJSONObject("pagination");
        // Legacy endpoints ignore visible_only=true. An unqualified total may include
        // transport tombstones that the Android transcript hides.
        if (pagination == null || !Boolean.TRUE.equals(pagination.opt("visible_only"))) return -1;
        Object count = pagination.opt("total");
        if (count instanceof Long || count instanceof Integer) return Math.max(-1, ((Number) count).longValue());
        return -1;
    }

    static long messageCount(long total, int rawPageSize, int distinctRowsLoaded, int pageSize) {
        if (total >= 0) return total;
        int effectivePage = pageSize > 0 ? pageSize : ThreadData.PAGE;
        // Old servers give only segment counts. We can count a completed, deduplicated
        // transcript but a full page can still have an unknown number of predecessors.
        return rawPageSize < effectivePage ? distinctRowsLoaded : -1;
    }

    static String legacyTitle(String root, String tip, Read read) throws IOException {
        String tipTitle = title(read.get(ThreadData.path(tip)).optJSONObject("session"), "title", "");
        if (!tipTitle.isEmpty() || tip.equals(root)) return tipTitle;
        return title(read.get(ThreadData.path(root)).optJSONObject("session"), "title", "");
    }

    private ThreadContext() {}
}
