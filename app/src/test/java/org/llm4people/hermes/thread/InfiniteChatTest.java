package org.llm4people.hermes.thread;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class InfiniteChatTest {
    private record FakeRow(String segment, long id, String body) {}

    private static InfiniteTranscriptWindow<FakeRow> newWindow() {
        return new InfiniteTranscriptWindow<>(new InfiniteTranscriptWindow.RowIdentity<>() {
            @Override public String stableKey(FakeRow row) { return row.segment() + "\0" + row.id(); }
            @Override public Long numericId(FakeRow row) { return row.id(); }
        }, InfiniteTranscriptWindow.MAX_WINDOW_ROWS);
    }

    private static List<FakeRow> range(long startInclusive, long endInclusive) {
        List<FakeRow> out = new ArrayList<>((int) (endInclusive - startInclusive + 1));
        for (long id = startInclusive; id <= endInclusive; id++) {
            String segment = "seg_" + ((id - 1) / 25_000L);
            out.add(new FakeRow(segment, id, "Message #" + id));
        }
        return out;
    }

    @Test public void simulates100kMessagesWithBoundedWindowZeroDuplicatesAndExactAnchors() {
        final long totalMessages = 100_000L;
        final int pageSize = org.llm4people.hermes.HermesConfig.DEFAULT_THREAD_PAGE_SIZE;
        InfiniteTranscriptWindow<FakeRow> window = newWindow();

        // 1. Open latest page (99,961..100,000).
        window.replaceLatest(range(totalMessages - pageSize + 1, totalMessages),
                true, totalMessages - pageSize + 1, totalMessages, (int) totalMessages);
        assertEquals(pageSize, window.snapshot().size());
        assertTrue(window.hasOlder());
        assertFalse(window.hasNewer());
        assertEquals(Long.valueOf(99_961L), window.oldestId());
        assertEquals(Long.valueOf(100_000L), window.newestId());
        assertEquals("limit=40&order=latest&visible_only=true&include_compacted=true&before_id=99961", window.olderQueryParams(pageSize, true));
        assertEquals("limit=40&offset=40&order=latest&visible_only=true&include_compacted=true", window.olderQueryParams(pageSize, false));
        assertEquals("limit=40&order=oldest&visible_only=true&include_compacted=true&after_id=100000", window.newerQueryParams(pageSize));

        // 2. Walk backward across ALL 2,499 remaining pages (all the way to message #1 across 4 compaction segments!).
        Set<Long> seen = new HashSet<>();
        for (FakeRow r : window.snapshot()) assertTrue(seen.add(r.id()));

        long cursor = window.oldestId();
        int pagesWalked = 0;
        while (cursor > 1L) {
            long start = Math.max(1L, cursor - pageSize);
            long end = cursor - 1L;
            List<FakeRow> page = range(start, end);
            // Inject an overlapping boundary duplicate to prove O(1) dedup holds under server races.
            List<FakeRow> withOverlap = new ArrayList<>(page);
            withOverlap.add(new FakeRow("seg_" + ((cursor - 1) / 25_000L), cursor, "Overlap"));

            InfiniteTranscriptWindow.PageResult res = window.prependOlder(
                    withOverlap, start > 1L, start, (int) totalMessages);
            pagesWalked++;
            assertEquals(page.size(), res.prependedCount());
            assertEquals(page.size(), res.anchorDelta());
            List<FakeRow> snap = window.snapshot();
            assertTrue("Window must stay strictly <= 400 rows", snap.size() <= InfiniteTranscriptWindow.MAX_WINDOW_ROWS);

            // Verify exact contiguous chronological order inside the window.
            for (int i = 1; i < snap.size(); i++) {
                assertEquals(snap.get(i - 1).id() + 1L, snap.get(i).id());
            }
            for (FakeRow r : page) assertTrue("Duplicate id detected: " + r.id(), seen.add(r.id()));
            assertEquals(Long.valueOf(start), window.oldestId());
            cursor = start;
        }
        assertEquals(2_499, pagesWalked);
        assertEquals(totalMessages, seen.size());
        assertEquals(InfiniteTranscriptWindow.MAX_WINDOW_ROWS, window.snapshot().size());
        assertFalse(window.hasOlder());
        assertTrue(window.hasNewer());
        assertEquals(Long.valueOf(1L), window.oldestId());
        assertEquals(Long.valueOf(400L), window.newestId());

        // 3. Page forward toward present and verify top trimming + negative anchorDelta.
        InfiniteTranscriptWindow.PageResult fwd = window.appendNewer(range(401L, 440L), true, 440L, (int) totalMessages);
        assertEquals(40, fwd.appendedCount());
        assertEquals(40, fwd.trimmedFromTop());
        assertEquals(-40, fwd.anchorDelta());
        assertEquals(InfiniteTranscriptWindow.MAX_WINDOW_ROWS, window.snapshot().size());
        assertTrue(window.hasOlder());
        assertTrue(window.hasNewer());
        assertEquals(Long.valueOf(41L), window.oldestId());
        assertEquals(Long.valueOf(440L), window.newestId());

        // 4. Snap to latest + O(1) after_id live delta append (3 new messages arrive).
        window.replaceLatest(range(99_961L, 100_000L), true, 99_961L, 100_000L, (int) totalMessages);
        assertFalse(window.hasNewer());
        assertEquals("limit=40&order=oldest&visible_only=true&include_compacted=true&after_id=100000", window.newerQueryParams(40));

        InfiniteTranscriptWindow.PageResult delta = window.appendNewer(range(100_001L, 100_003L), false, 100_003L, 100_003);
        assertEquals(3, delta.appendedCount());
        assertEquals(0, delta.trimmedFromTop());
        assertEquals(43, window.snapshot().size());
        assertEquals(Long.valueOf(100_003L), window.newestId());
        assertFalse(window.hasNewer());
    }

    @Test public void queryParamsFollowGatewayContractAndNeverEmitInvalidOrderDesc() {
        InfiniteTranscriptWindow<FakeRow> empty = newWindow();
        assertEquals("limit=40&offset=0&order=latest&visible_only=true&include_compacted=true",
                empty.olderQueryParams(40, true));
        assertEquals("limit=1&offset=0&order=latest&visible_only=true&include_compacted=true",
                empty.olderQueryParams(0, false));
        assertEquals("limit=200&order=latest&visible_only=true&include_compacted=true",
                empty.newerQueryParams(500));
        assertFalse(empty.olderQueryParams(40, true).contains("order=desc"));
        assertFalse(empty.newerQueryParams(40).contains("order=desc"));

        empty.replaceLatest(range(501L, 540L), true, 501L, 540L, 540);
        assertEquals("limit=40&order=latest&visible_only=true&include_compacted=true&before_id=501",
                empty.olderQueryParams(40, true));
        assertEquals("limit=40&order=oldest&visible_only=true&include_compacted=true&after_id=540",
                empty.newerQueryParams(40));
    }

    @Test public void tailCacheEnforcesLruProfileEpochFencingVaultBoundsAndFastRestore() {
        ThreadTailCache cache = new ThreadTailCache(ThreadTailCache.MAX_THREADS);
        long epochA = cache.bindProfile("profile-a");

        List<String> oversizedRows = new ArrayList<>();
        for (int i = 1; i <= 90; i++) oversizedRows.add("{\"id\":" + i + ",\"content\":\"Row " + i + "\"}");

        for (int t = 0; t < 30; t++) {
            ThreadTailCache.Entry e = new ThreadTailCache.Entry(
                    "sess-" + t, "remote-" + t, "Thread " + t, oversizedRows, true, 31L, 90L, 90, 1000L + t);
            assertEquals("Per-thread tail must cap at 60 rows",
                    ThreadTailCache.MAX_MESSAGES_PER_THREAD, e.encodedRows().size());
            assertTrue(cache.put(epochA, e));
        }
        assertNull("Oldest evicted thread must be gone", cache.get(epochA, "sess-0"));
        assertNotNull("Most recent threads must remain cached", cache.get(epochA, "sess-15"));

        long t0 = System.nanoTime();
        ThreadTailCache.Entry hit = cache.get(epochA, "sess-29");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertNotNull(hit);
        assertTrue("Tail cache lookup must be fast (<100ms), got " + elapsedMs + "ms", elapsedMs < 100L);

        // Switching profiles immediately wipes RAM and rejects stale epoch reads/writes.
        long epochB = cache.bindProfile("profile-b");
        assertNotEquals(epochA, epochB);
        assertNull(cache.get(epochA, "sess-29"));
        assertNull(cache.get(epochB, "sess-29"));
        assertFalse(cache.put(epochA, hit));

        // onTrimMemory clears RAM while keeping profile epoch active.
        assertTrue(cache.put(epochB, hit));
        assertNotNull(cache.get(epochB, "sess-29"));
        cache.onTrimMemory();
        assertNull(cache.get(epochB, "sess-29"));
        assertTrue(cache.put(epochB, hit));

        // clear() wipes RAM and invalidates epochB.
        cache.clear();
        assertNull(cache.get(epochB, "sess-29"));
        assertFalse(cache.put(epochB, hit));

        // Vault snapshot roundtrip, size capping, corruption fail-open, and wipe.
        Map<String, String> vaultMap = new HashMap<>();
        ThreadTailCache.VaultBacking vault = new ThreadTailCache.VaultBacking() {
            @Override public String read(String key) { return vaultMap.get(key); }
            @Override public void write(String key, String value) { vaultMap.put(key, value); }
            @Override public void remove(String key) { vaultMap.remove(key); }
        };
        assertTrue(ThreadTailCache.saveSnapshot(vault, hit));
        ThreadTailCache.Entry restored = ThreadTailCache.loadSnapshot(vault, "sess-29");
        assertNotNull(restored);
        assertEquals("sess-29", restored.sessionId());
        assertEquals(60, restored.encodedRows().size());
        assertEquals(Long.valueOf(90L), restored.newestId());

        // Oversized rows trim oldest automatically to fit MAX_SNAPSHOT_CHARS.
        List<String> bigRows = new ArrayList<>();
        String chunk = "x".repeat(4000);
        for (int i = 0; i < 40; i++) bigRows.add("{\"id\":" + i + ",\"body\":\"" + chunk + "\"}");
        ThreadTailCache.Entry bigEntry = new ThreadTailCache.Entry(
                "sess-big", "r-big", "Big", bigRows, true, 1L, 40L, 40, 2000L);
        assertTrue(ThreadTailCache.saveSnapshot(vault, bigEntry));
        assertTrue(vaultMap.get(ThreadTailCache.vaultKey("sess-big")).length() <= ThreadTailCache.MAX_SNAPSHOT_CHARS);
        ThreadTailCache.Entry bigRestored = ThreadTailCache.loadSnapshot(vault, "sess-big");
        assertNotNull(bigRestored);
        assertTrue(bigRestored.encodedRows().size() > 0 && bigRestored.encodedRows().size() < 40);

        // Corrupt vault snapshot fails open (returns null, never throws).
        vaultMap.put(ThreadTailCache.vaultKey("sess-corrupt"), "v99\tcorrupted");
        assertNull(ThreadTailCache.loadSnapshot(vault, "sess-corrupt"));

        ThreadTailCache.wipeSnapshot(vault, "sess-29");
        assertNull(ThreadTailCache.loadSnapshot(vault, "sess-29"));
    }

    @Test public void cellCacheHitsInvalidatesOnThemeOrContentAndTrimsCleanly() {
        TranscriptCellCache cache = new TranscriptCellCache(4);
        AtomicInteger computes = new AtomicInteger();

        TranscriptCellCache.Key kDay = TranscriptCellCache.keyFor("row-1", "**Hello**", "", true, false, 6000);
        TranscriptCellCache.Key kNight = TranscriptCellCache.keyFor("row-1", "**Hello**", "", true, true, 6000);
        TranscriptCellCache.Key kEdited = TranscriptCellCache.keyFor("row-1", "**Hello!**", "", true, false, 6000);
        assertNotEquals(kDay, kNight);
        assertNotEquals(kDay, kEdited);

        TranscriptCellCache.Rendered r1 = cache.getOrCompute(kDay, () -> {
            computes.incrementAndGet();
            return new TranscriptCellCache.Rendered(null, "Hello", "Hello", "");
        });
        TranscriptCellCache.Rendered r2 = cache.getOrCompute(kDay, () -> {
            computes.incrementAndGet();
            return new TranscriptCellCache.Rendered(null, "Recomputed", "Recomputed", "");
        });
        assertSame(r1, r2);
        assertEquals(1, computes.get());

        // Eviction beyond capacity (capacity=4): inserting 6 entries evicts kDay and r-0/r-1.
        TranscriptCellCache.Key[] inserted = new TranscriptCellCache.Key[6];
        for (int i = 0; i < 6; i++) {
            inserted[i] = TranscriptCellCache.keyFor("r-" + i, "Body " + i, "", true, false, 6000);
            cache.put(inserted[i], new TranscriptCellCache.Rendered(null, "Body " + i, "Body " + i, ""));
        }
        assertNull(cache.get(kDay));
        assertNull(cache.get(inserted[0]));
        assertNull(cache.get(inserted[1]));
        assertNotNull(cache.get(inserted[5]));

        cache.clear();
        assertNull(cache.get(inserted[5]));
    }

    @Test public void scrollControllerTriggersAutoPrefetchAndJumpPillWithoutReentrancy() {
        // Near top with older available -> triggers loadOlder, shows jump pill.
        InfiniteScrollController.ScrollState top = InfiniteScrollController.evaluate(
                2, 12, 102, 1, 1, 6, 6, true, false, false, false);
        assertTrue(top.nearTop());
        assertTrue(top.triggerOlder());
        assertFalse(top.triggerNewer());
        assertFalse(top.atTail());
        assertTrue(top.showJumpPill());

        // Already loading older -> re-entrancy guard blocks duplicate request.
        InfiniteScrollController.ScrollState topBusy = InfiniteScrollController.evaluate(
                2, 12, 102, 1, 1, 6, 6, true, false, true, false);
        assertFalse(topBusy.triggerOlder());

        // Scrolled to live tail (hasNewer == false) -> pill hidden, no prefetch.
        InfiniteScrollController.ScrollState tail = InfiniteScrollController.evaluate(
                89, 12, 102, 1, 1, 6, 6, true, false, false, false);
        assertTrue(tail.atTail());
        assertFalse(tail.showJumpPill());
        assertFalse(tail.triggerOlder());
        assertFalse(tail.triggerNewer());

        // Near bottom of a trimmed window (hasNewer == true) -> triggers loadNewer AND keeps pill visible!
        InfiniteScrollController.ScrollState trimmedBottom = InfiniteScrollController.evaluate(
                386, 14, 402, 1, 1, 6, 6, true, true, false, false);
        assertTrue(trimmedBottom.nearBottom());
        assertTrue(trimmedBottom.triggerNewer());
        assertFalse(trimmedBottom.atTail());
        assertTrue(trimmedBottom.showJumpPill());

        // Mid-layout scroll callbacks (e.g. IME resize inside ListView.layoutChildren) must defer pill mutation.
        assertFalse(InfiniteScrollController.shouldDeferPillUpdate(false, false));
        assertTrue(InfiniteScrollController.shouldDeferPillUpdate(true, false));
        assertTrue(InfiniteScrollController.shouldDeferPillUpdate(false, true));
        assertTrue(InfiniteScrollController.shouldDeferPillUpdate(true, true));
    }
}
