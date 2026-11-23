package org.llm4people.hermes.perf;

import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.llm4people.hermes.Diagnostics;

/**
 * Bounded process-wide thumbnail cache ({@code maxMemory / 16}, capped at 4 MiB) with safe
 * {@code inJustDecodeBounds} + power-of-2 {@code inSampleSize} decoding and memory-pressure eviction.
 */
public final class ThumbnailCache {
    static final int MIN_BUDGET_KIB = 256;
    static final int MAX_BUDGET_KIB = 4096;
    public static final int DEFAULT_THUMB_MAX_EDGE = 144;

    private static volatile LruCache<String, Bitmap> cache;

    private ThumbnailCache() {}

    /** Pure-Java budget calculation: {@code maxMemory / 16} in KiB, clamped to [256 KiB, 4 MiB]. */
    public static int computeCacheBudgetKiB(long maxMemoryBytes) {
        if (maxMemoryBytes <= 0L) return MIN_BUDGET_KIB;
        long sixteenthKiB = (maxMemoryBytes / 16L) / 1024L;
        if (sixteenthKiB < MIN_BUDGET_KIB) return MIN_BUDGET_KIB;
        if (sixteenthKiB > MAX_BUDGET_KIB) return MAX_BUDGET_KIB;
        return (int) sixteenthKiB;
    }

    private static LruCache<String, Bitmap> instance() {
        LruCache<String, Bitmap> local = cache;
        if (local != null) return local;
        synchronized (ThumbnailCache.class) {
            if (cache == null) {
                int budgetKiB = computeCacheBudgetKiB(Runtime.getRuntime().maxMemory());
                cache = new LruCache<>(budgetKiB) {
                    @Override protected int sizeOf(String key, Bitmap value) {
                        return Math.max(1, value.getAllocationByteCount() / 1024);
                    }
                };
            }
            return cache;
        }
    }

    public static Bitmap get(String key) {
        if (key == null || key.isEmpty()) return null;
        LruCache<String, Bitmap> local = cache;
        if (local == null) return null;
        Bitmap hit = local.get(key);
        if (hit != null && hit.isRecycled()) {
            local.remove(key);
            return null;
        }
        return hit;
    }

    public static void put(String key, Bitmap bitmap) {
        if (key == null || key.isEmpty() || bitmap == null || bitmap.isRecycled()) return;
        instance().put(key, bitmap);
    }

    /** Decode encoded image bytes with {@code inJustDecodeBounds} + power-of-2 {@code inSampleSize}. */
    public static Bitmap decodeSampled(byte[] encoded, int maxEdge) {
        if (encoded == null || encoded.length == 0 || maxEdge <= 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        if (!BitmapSampling.validDimensions(bounds.outWidth, bounds.outHeight)) {
            return null;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = BitmapSampling.calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge);
        decode.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.length, decode);
    }

    /** Return cached thumbnail or decode bounded with {@code inSampleSize} and cache it. */
    public static Bitmap getOrDecode(String key, byte[] encoded, int maxEdge) {
        if (key != null && !key.isEmpty()) {
            Bitmap cached = get(key);
            if (cached != null) return cached;
        }
        Bitmap decoded = decodeSampled(encoded, maxEdge);
        if (decoded != null && key != null && !key.isEmpty()) {
            put(key, decoded);
        }
        return decoded;
    }

    /**
     * Pure-Java eviction policy for Android {@code onTrimMemory(int)} levels:
     * returns {@code 2} for full clear, {@code 1} for half-trim, {@code 0} for no-op.
     */
    public static int evictionActionForLevel(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            return 2;
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            return 1;
        }
        return 0;
    }

    public static void trimMemory(int level) {
        LruCache<String, Bitmap> local = cache;
        if (local == null) return;
        int action = evictionActionForLevel(level);
        if (action == 2) {
            local.evictAll();
            Diagnostics.debug("perf", "thumbnail_cache_cleared");
        } else if (action == 1) {
            local.trimToSize(Math.max(1, local.maxSize() / 2));
            Diagnostics.debug("perf", "thumbnail_cache_halved");
        }
    }

    public static void clear() {
        LruCache<String, Bitmap> local = cache;
        if (local != null) local.evictAll();
    }
}
