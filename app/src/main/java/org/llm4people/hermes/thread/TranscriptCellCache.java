package org.llm4people.hermes.thread;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded LRU cache storing parsed {@link TranscriptPage} models and rendered {@link CharSequence} spans
 * keyed by {@code (rowKey, contentHash, markdown, nightMode, pageLimit)} so fast fling scrolling across
 * long Markdown discussions avoids redundant parsing and span allocation.
 * Cleared immediately on {@code onTrimMemory} or profile lock.
 */
public final class TranscriptCellCache {
    public static final int DEFAULT_CAPACITY = 160;

    public record Key(
            String rowKey,
            long contentFingerprint,
            boolean markdown,
            boolean nightMode,
            int pageLimit) {
        public Key {
            rowKey = rowKey == null ? "" : rowKey;
        }
    }

    public record Rendered(
            TranscriptPage page,
            CharSequence text,
            String visibleContent,
            String reasoningContent) {}

    private final int capacity;
    private final LinkedHashMap<Key, Rendered> lru;

    public TranscriptCellCache() {
        this(DEFAULT_CAPACITY);
    }

    public TranscriptCellCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.lru = new LinkedHashMap<>(Math.min(64, capacity + 1), 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Key, Rendered> eldest) {
                return size() > TranscriptCellCache.this.capacity;
            }
        };
    }

    /** Computes a collision-resistant 64-bit FNV-1a fingerprint over visible content + structured reasoning. */
    public static Key keyFor(String rowKey, String content, String reasoning,
                             boolean markdown, boolean nightMode, int pageLimit) {
        long hash = fnv1a64(content == null ? "" : content, 0xcbf29ce484222325L);
        hash = fnv1a64("\0", hash);
        hash = fnv1a64(reasoning == null ? "" : reasoning, hash);
        return new Key(rowKey, hash, markdown, nightMode, pageLimit);
    }

    private static long fnv1a64(String text, long seed) {
        long h = seed;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            h ^= (c & 0xFF);
            h *= 0x100000001b3L;
            h ^= ((c >>> 8) & 0xFF);
            h *= 0x100000001b3L;
        }
        return h;
    }

    public synchronized Rendered get(Key key) {
        if (key == null) return null;
        return lru.get(key);
    }

    public synchronized void put(Key key, Rendered rendered) {
        if (key == null || rendered == null) return;
        lru.put(key, rendered);
    }

    /** Returns cached entry in O(1) or computes and stores it once. */
    public Rendered getOrCompute(Key key, Supplier<Rendered> supplier) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(supplier, "supplier");
        synchronized (this) {
            Rendered cached = lru.get(key);
            if (cached != null) return cached;
        }
        Rendered computed = supplier.get();
        if (computed != null) {
            synchronized (this) {
                lru.put(key, computed);
            }
        }
        return computed;
    }

    public synchronized void clear() {
        lru.clear();
    }
}
