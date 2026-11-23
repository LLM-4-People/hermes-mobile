package org.llm4people.hermes.perf;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.artifact.SyntaxHighlighter;

/**
 * Single Source of Truth for process-wide cache eviction under Android memory pressure
 * ({@code Application.onTrimMemory} / {@code onLowMemory}) or explicit security/lock wipes.
 */
public final class MemoryPressure {
    private MemoryPressure() {}

    /** Respond proportionally to Android memory trim levels without throwing on host JVM. */
    public static void onTrimMemory(int level) {
        int action = ThumbnailCache.evictionActionForLevel(level);
        if (action <= 0) return;
        ThumbnailCache.trimMemory(level);
        if (action >= 2) {
            SyntaxHighlighter.clearCache();
            org.llm4people.hermes.thread.ThreadViews.clearCellCache();
        }
        Diagnostics.event("perf", action >= 2 ? "memory_cleared" : "memory_trimmed");
    }

    /** Immediately evict all in-memory performance caches. */
    public static void clearAll() {
        ThumbnailCache.clear();
        SyntaxHighlighter.clearCache();
        org.llm4people.hermes.thread.ThreadViews.clearCellCache();
        Diagnostics.event("perf", "memory_cleared");
    }
}
