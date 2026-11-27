package org.llm4people.hermes.perf;

import android.content.ComponentCallbacks2;

import org.junit.After;
import org.junit.Test;
import org.llm4people.hermes.artifact.SyntaxHighlighter;
import org.llm4people.hermes.thread.DiffRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Deterministic host-JVM benchmarks and regression tests for Track 6 runtime performance,
 * streaming coalescing, parser/highlighter memoization, bitmap sampling, and memory-pressure eviction.
 */
public final class AppPerformanceTest {
    @After
    public void cleanUp() {
        MemoryPressure.clearAll();
    }

    @Test
    public void streamCoalescerCoalesces1000RapidDeltasWithExactFinalTextIntegrity() {
        List<String> emitted = new ArrayList<>();
        StreamCoalescer coalescer = new StreamCoalescer(64L, emitted::add);
        StringBuilder expected = new StringBuilder();

        long now = 1_000L;
        for (int i = 0; i < 1_000; i++) {
            String token = (i % 10 == 0) ? ("\nLigne " + i + ": ") : ("mot" + i + " ");
            expected.append(token);
            coalescer.offer(expected.toString(), now);
            now += 2L; // 500 tokens/sec fast stream over 2,000 ms
        }
        assertTrue("Expected pending tail before final flush", coalescer.hasPending());
        assertTrue("Expected positive remaining delay or immediate due", coalescer.pendingDelayMs(now) <= 64L);
        assertTrue("Final flush must emit pending tail", coalescer.flush(now + 64L));
        assertFalse("Second flush must be a no-op", coalescer.flush(now + 128L));

        assertEquals("First token must flush immediately for zero TTFT penalty", "\nLigne 0: ", emitted.get(0));
        assertEquals("Final text must preserve every single character", expected.toString(), emitted.get(emitted.size() - 1));
        assertEquals(1_000, coalescer.offeredCount());
        assertEquals("Every non-duplicate offer must be accounted for as flushed or coalesced",
                coalescer.offeredCount(), coalescer.flushedCount() + coalescer.coalescedCount());
        assertTrue("1000 deltas over 2s at 64ms budget must flush <= 35 times (got " + coalescer.flushedCount() + ")",
                coalescer.flushedCount() <= 35);
        assertTrue("Over 95% of rapid deltas must coalesce (got " + coalescer.coalescedCount() + ")",
                coalescer.coalescedCount() >= 950);

        // Reset clears counters and buffers cleanly.
        coalescer.reset();
        assertEquals(0, coalescer.offeredCount());
        assertEquals(0, coalescer.flushedCount());
        assertEquals(0, coalescer.coalescedCount());
        assertFalse(coalescer.hasPending());
    }

    @Test
    public void streamCoalescerRejectsInvalidBudgetsAndNullSink() {
        AtomicReference<String> sink = new AtomicReference<>();
        try {
            new StreamCoalescer(15L, sink::set);
            fail("Expected rejection below MIN_FRAME_BUDGET_MS");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new StreamCoalescer(251L, sink::set);
            fail("Expected rejection above MAX_FRAME_BUDGET_MS");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new StreamCoalescer(64L, null);
            fail("Expected rejection for null sink");
        } catch (NullPointerException expected) {
            // ok
        }
        StreamCoalescer duplicateGuard = new StreamCoalescer(64L, sink::set);
        assertTrue(duplicateGuard.offer("bonjour", 100L));
        assertFalse("Identical snapshot must not re-flush", duplicateGuard.offer("bonjour", 200L));
        assertEquals(1, duplicateGuard.flushedCount());

        // An initial empty SSE status frame must NOT consume the 0ms TTFT slot for the first real token.
        List<String> ttft = new ArrayList<>();
        StreamCoalescer ttftGuard = new StreamCoalescer(64L, ttft::add);
        assertTrue(ttftGuard.offer("", 1_000L));
        assertTrue("First non-empty token arriving 5ms after empty frame must flush immediately (0ms TTFT)",
                ttftGuard.offer("Bonjour", 1_005L));
        assertEquals(List.of("", "Bonjour"), ttft);
        assertFalse("Subsequent rapid delta within budget must coalesce normally",
                ttftGuard.offer("Bonjour le monde", 1_010L));
    }

    @Test
    public void syntaxHighlighterMemoizesBoundedAndEvictsUnderMemoryPressure() {
        SyntaxHighlighter.clearCache();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            code.append("@Override public final class Service").append(i)
                    .append(" { int count = ").append(i).append("; String s = \"val\"; // note\n}\n");
        }
        String sample = code.toString();
        assertTrue(sample.length() > 10_000 && sample.length() < 16_384);

        long t0 = System.nanoTime();
        List<SyntaxHighlighter.Token> first = SyntaxHighlighter.tokenize(sample, SyntaxHighlighter.Language.JAVA);
        long coldNs = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        List<SyntaxHighlighter.Token> second = SyntaxHighlighter.tokenize(sample, SyntaxHighlighter.Language.JAVA);
        long warmNs = System.nanoTime() - t1;

        assertSame("Repeat tokenization must hit memoized token list", first, second);
        assertFalse(first.isEmpty());
        assertEquals(1, SyntaxHighlighter.cacheSizeForTest());
        assertTrue("Cold tokenize of 12 KiB Java must stay well under 2000ms (was " + (coldNs / 1_000_000) + "ms)",
                coldNs < 2_000_000_000L);
        assertTrue("Warm cache hit must stay under 100ms (was " + (warmNs / 1_000_000) + "ms)",
                warmNs < 100_000_000L);

        // LRU cap keeps cache strictly bounded even after 40 distinct snippets.
        for (int i = 0; i < 40; i++) {
            SyntaxHighlighter.tokenize("int x" + i + " = " + i + ";", SyntaxHighlighter.Language.JAVA);
        }
        assertTrue("Token cache must remain bounded <= 24", SyntaxHighlighter.cacheSizeForTest() <= 24);

        // Memory pressure eviction clears cache completely.
        MemoryPressure.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        assertEquals(0, SyntaxHighlighter.cacheSizeForTest());
    }

    @Test
    public void diffRendererParsesLargeUnifiedDiffFastAndBounded() {
        StringBuilder unified = new StringBuilder("--- a/src/Engine.java\n+++ b/src/Engine.java\n@@ -1,1000 +1,1000 @@\n");
        for (int i = 0; i < 1000; i++) {
            unified.append("-int oldVal").append(i).append(" = ").append(i).append(";\n");
            unified.append("+int newVal").append(i).append(" = ").append(i + 1).append(";\n");
        }
        long startNs = System.nanoTime();
        DiffRenderer.DiffModel model = DiffRenderer.parseUnified(
                "src/Engine.java", "java", unified.toString(), "Refactor", "Clean", "low", false);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertNotNull(model);
        assertEquals(1000, model.additions());
        assertEquals(1000, model.deletions());
        assertTrue(model.truncated());
        assertTrue(model.lines().size() <= 80);
        assertTrue("2,000-line unified diff parse must stay under 250ms (was " + elapsedMs + "ms)", elapsedMs < 250L);
    }

    @Test
    public void bitmapSamplingComputesPowerOfTwoInSampleSizeAndRejectsPixelBombs() {
        assertEquals(1, BitmapSampling.calculateInSampleSize(1200, 900, 1600));
        assertEquals(1, BitmapSampling.calculateInSampleSize(1600, 1200, 1600));
        assertEquals(2, BitmapSampling.calculateInSampleSize(4000, 3000, 1600));
        assertEquals(4, BitmapSampling.calculateInSampleSize(8000, 6000, 1600));
        assertEquals(8, BitmapSampling.calculateInSampleSize(12800, 9600, 1600));

        assertArrayEquals(new int[]{1200, 900}, BitmapSampling.targetDimensions(1200, 900, 1600));
        assertArrayEquals(new int[]{1600, 1200}, BitmapSampling.targetDimensions(4000, 3000, 1600));
        assertArrayEquals(new int[]{1200, 1600}, BitmapSampling.targetDimensions(3000, 4000, 1600));

        assertTrue(BitmapSampling.validDimensions(4000, 3000));
        assertFalse(BitmapSampling.validDimensions(0, 100));
        assertFalse(BitmapSampling.validDimensions(-10, 100));
        assertFalse("Pixel bomb > 128 MP must be rejected", BitmapSampling.validDimensions(20_000, 20_000));

        try {
            BitmapSampling.calculateInSampleSize(0, 100, 1600);
            fail("Expected rejection for zero width");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            BitmapSampling.targetDimensions(20_000, 20_000, 1600);
            fail("Expected rejection for pixel bomb");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void thumbnailCacheBudgetAndMemoryTrimLevelsFollowPolicy() {
        assertEquals(256, ThumbnailCache.computeCacheBudgetKiB(0L));
        assertEquals(256, ThumbnailCache.computeCacheBudgetKiB(-1L));
        assertEquals(256, ThumbnailCache.computeCacheBudgetKiB(2L * 1024 * 1024));
        assertEquals(2048, ThumbnailCache.computeCacheBudgetKiB(32L * 1024 * 1024));
        assertEquals(4096, ThumbnailCache.computeCacheBudgetKiB(512L * 1024 * 1024));

        assertEquals(0, ThumbnailCache.evictionActionForLevel(0));
        assertEquals(1, ThumbnailCache.evictionActionForLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE));
        assertEquals(1, ThumbnailCache.evictionActionForLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW));
        assertEquals(2, ThumbnailCache.evictionActionForLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL));
        assertEquals(2, ThumbnailCache.evictionActionForLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN));
        assertEquals(2, ThumbnailCache.evictionActionForLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND));
        assertEquals(2, ThumbnailCache.evictionActionForLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE));
    }
}
