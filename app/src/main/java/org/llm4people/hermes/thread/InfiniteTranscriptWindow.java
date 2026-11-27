package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * O(1)-memory bounded sliding window for infinite conversation transcripts (supports 100,000+ server messages).
 * Supports keyset cursor paging ({@code before_id} / {@code after_id}) with graceful legacy {@code offset}
 * fallback, O(1) row deduplication, automatic top/bottom trimming, and exact scroll-anchor deltas.
 */
public final class InfiniteTranscriptWindow<T> {
    public static final int MAX_WINDOW_ROWS = 400;

    public interface RowIdentity<T> {
        String stableKey(T row);
        Long numericId(T row);
    }

    public record PageResult(
            int addedCount,
            int prependedCount,
            int appendedCount,
            int trimmedFromTop,
            int trimmedFromBottom,
            int anchorDelta) {}

    private final RowIdentity<T> identity;
    private final int maxWindowRows;
    private final Deque<T> rows = new ArrayDeque<>();
    private final Set<String> keys = new HashSet<>();

    private boolean hasOlder;
    private boolean hasNewer;
    private Long oldestId;
    private Long newestId;
    private int totalCount = -1;
    private int legacyOffset;

    public InfiniteTranscriptWindow(RowIdentity<T> identity, int maxWindowRows) {
        if (maxWindowRows < 10) throw new IllegalArgumentException("maxWindowRows must be >= 10");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.maxWindowRows = maxWindowRows;
    }

    /** Standard JSON wire adapter compatible with Hermes Mobile's {@link ThreadData} message objects. */
    public static InfiniteTranscriptWindow<JSONObject> forJson() {
        return forJson(MAX_WINDOW_ROWS);
    }

    public static InfiniteTranscriptWindow<JSONObject> forJson(int maxWindowRows) {
        return new InfiniteTranscriptWindow<>(new RowIdentity<>() {
            @Override public String stableKey(JSONObject row) {
                if (row == null) return null;
                ThreadData.RowId rid = ThreadData.rowId(row);
                return rid == null ? null : rid.segment() + "\0" + rid.id();
            }

            @Override public Long numericId(JSONObject row) {
                return parsePositiveLong(row == null ? null : row.opt("id"));
            }
        }, maxWindowRows);
    }

    public static Long parsePositiveLong(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return null;
        if (raw instanceof Number num) {
            long v = num.longValue();
            return v > 0L ? v : null;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) return null;
        try {
            long v = Long.parseLong(text);
            return v > 0L ? v : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public synchronized void clear() {
        rows.clear();
        keys.clear();
        hasOlder = false;
        hasNewer = false;
        oldestId = null;
        newestId = null;
        totalCount = -1;
        legacyOffset = 0;
    }

    /** Replaces the window with the latest tail page (initial open or snap-to-latest). */
    public synchronized PageResult replaceLatest(List<T> latestChronological, boolean hasOlder,
                                                 Long cursorOldestId, Long cursorNewestId, int totalCount) {
        rows.clear();
        keys.clear();
        int added = 0;
        if (latestChronological != null) {
            for (T row : latestChronological) {
                if (row == null) continue;
                String k = identity.stableKey(row);
                if (k == null || keys.add(k)) {
                    rows.addLast(row);
                    added++;
                }
            }
        }
        int trimmedTop = trimTopIfNeeded();
        this.hasOlder = hasOlder || trimmedTop > 0;
        this.hasNewer = false;
        this.totalCount = totalCount;
        this.legacyOffset = rows.size();
        recomputeCursors(cursorOldestId, cursorNewestId, trimmedTop > 0, false);
        return new PageResult(added, 0, added, trimmedTop, 0, 0);
    }

    /**
     * Prepends an older chronological page at the top of the window.
     * Automatically trims excess rows from the bottom ({@code hasNewer = true}) so memory stays O(1).
     */
    public synchronized PageResult prependOlder(List<T> olderChronological, boolean hasMoreOlder,
                                                Long pageOldestId, int totalCount) {
        List<T> unique = new ArrayList<>();
        if (olderChronological != null) {
            for (T row : olderChronological) {
                if (row == null) continue;
                String k = identity.stableKey(row);
                if (k == null || !keys.contains(k)) {
                    if (k != null) keys.add(k);
                    unique.add(row);
                }
            }
        }
        for (int i = unique.size() - 1; i >= 0; i--) {
            rows.addFirst(unique.get(i));
        }
        int trimmedBottom = trimBottomIfNeeded();
        this.hasOlder = hasMoreOlder;
        if (trimmedBottom > 0) this.hasNewer = true;
        if (totalCount >= 0) this.totalCount = totalCount;
        this.legacyOffset += unique.size();
        recomputeCursors(pageOldestId, null, false, trimmedBottom > 0);
        return new PageResult(unique.size(), unique.size(), 0, 0, trimmedBottom, unique.size());
    }

    /**
     * Appends newer chronological rows at the bottom (incremental {@code after_id} delta sync OR downward paging).
     * Automatically trims excess rows from the top ({@code hasOlder = true}) and returns negative {@code anchorDelta}.
     */
    public synchronized PageResult appendNewer(List<T> newerChronological, boolean hasMoreNewer,
                                               Long pageNewestId, int totalCount) {
        int appended = 0;
        if (newerChronological != null) {
            for (T row : newerChronological) {
                if (row == null) continue;
                String k = identity.stableKey(row);
                if (k == null || keys.add(k)) {
                    rows.addLast(row);
                    appended++;
                }
            }
        }
        int trimmedTop = trimTopIfNeeded();
        if (trimmedTop > 0) this.hasOlder = true;
        this.hasNewer = hasMoreNewer;
        if (totalCount >= 0) this.totalCount = totalCount;
        recomputeCursors(null, pageNewestId, trimmedTop > 0, false);
        return new PageResult(appended, 0, appended, trimmedTop, 0, -trimmedTop);
    }

    /** Builds query string for loading the next older page (uses keyset {@code before_id} when available). */
    public synchronized String olderQueryParams(int limit, boolean supportsKeyset) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        if (supportsKeyset && oldestId != null && oldestId > 0L) {
            return "limit=" + safeLimit + "&order=latest&visible_only=true&include_compacted=true&before_id=" + oldestId;
        }
        return "limit=" + safeLimit + "&offset=" + Math.max(0, legacyOffset)
                + "&order=latest&visible_only=true&include_compacted=true";
    }

    /** Builds query string for O(1) incremental delta sync or downward paging via {@code after_id}. */
    public synchronized String newerQueryParams(int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        if (newestId == null || newestId <= 0L) {
            return "limit=" + safeLimit + "&order=latest&visible_only=true&include_compacted=true";
        }
        return "limit=" + safeLimit + "&order=oldest&visible_only=true&include_compacted=true&after_id=" + newestId;
    }

    public synchronized List<T> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(rows));
    }

    public static JSONArray toJsonArray(List<JSONObject> list) {
        JSONArray out = new JSONArray();
        if (list != null) {
            for (JSONObject row : list) {
                if (row != null) out.put(row);
            }
        }
        return out;
    }

    public static List<JSONObject> fromJsonArray(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<JSONObject> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj != null) out.add(obj);
        }
        return out;
    }

    public synchronized int size() { return rows.size(); }
    public synchronized boolean isEmpty() { return rows.isEmpty(); }
    public synchronized boolean hasOlder() { return hasOlder; }
    public synchronized boolean hasNewer() { return hasNewer; }
    public synchronized boolean atLiveTail() { return !hasNewer; }
    public synchronized Long oldestId() { return oldestId; }
    public synchronized Long newestId() { return newestId; }
    public synchronized int totalCount() { return totalCount; }

    private int trimTopIfNeeded() {
        int trimmed = 0;
        while (rows.size() > maxWindowRows) {
            T removed = rows.removeFirst();
            removeKey(removed);
            trimmed++;
        }
        return trimmed;
    }

    private int trimBottomIfNeeded() {
        int trimmed = 0;
        while (rows.size() > maxWindowRows) {
            T removed = rows.removeLast();
            removeKey(removed);
            trimmed++;
        }
        return trimmed;
    }

    private void removeKey(T row) {
        String k = identity.stableKey(row);
        if (k != null) keys.remove(k);
    }

    private void recomputeCursors(Long hintOldest, Long hintNewest, boolean trimmedTop, boolean trimmedBottom) {
        if (rows.isEmpty()) {
            oldestId = hintOldest;
            newestId = hintNewest;
            return;
        }
        Long firstId = identity.numericId(rows.peekFirst());
        Long lastId = identity.numericId(rows.peekLast());
        if (firstId != null) oldestId = firstId;
        else if (!trimmedTop && hintOldest != null) oldestId = hintOldest;

        if (lastId != null) newestId = lastId;
        else if (!trimmedBottom && hintNewest != null) newestId = hintNewest;
    }
}
