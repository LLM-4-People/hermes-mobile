package org.llm4people.hermes.thread;

import org.junit.Test;
import org.llm4people.hermes.R;
import org.llm4people.hermes.panel.UniversalShareModel;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ThreadInboxFilterTest {
    @Test
    public void filtersLocalInboxRowsForAllPinnedAndShared() {
        List<ThreadInboxFilter.Entry> inbox = List.of(
                new ThreadInboxFilter.Entry("t-1", "General", false, false, "", "", ""),
                new ThreadInboxFilter.Entry("t-2", "Pinned A", true, false, "", "", ""),
                new ThreadInboxFilter.Entry("t-3", "Pinned B", true, false, "", "", ""));

        List<ThreadInboxFilter.Entry> all = ThreadInboxFilter.filterEntries(inbox, ThreadInboxFilter.Filter.ALL);
        assertEquals(3, all.size());

        List<ThreadInboxFilter.Entry> pinned = ThreadInboxFilter.filterEntries(inbox, ThreadInboxFilter.Filter.PINNED);
        assertEquals(2, pinned.size());
        assertEquals("t-2", pinned.get(0).id());
        assertEquals("t-3", pinned.get(1).id());

        assertTrue(ThreadInboxFilter.isPinnedMap(Map.of("pinned", true)));
        assertTrue(ThreadInboxFilter.isPinnedMap(Map.of("is_pinned", true)));
        assertFalse(ThreadInboxFilter.isPinnedMap(Map.of("pinned", false)));
        assertFalse(ThreadInboxFilter.isPinnedMap(null));

        List<ThreadInboxFilter.Entry> sharedLocal = ThreadInboxFilter.filterEntries(inbox, ThreadInboxFilter.Filter.SHARED);
        assertTrue(sharedLocal.isEmpty());
        assertTrue(ThreadInboxFilter.filterEntries(null, ThreadInboxFilter.Filter.PINNED).isEmpty());
    }

    @Test
    public void parsesIncomingSharedSessionsAndIgnoresNonSessionKinds() {
        Map<String, Object> payload = Map.of(
                "data", List.of(
                        Map.of(
                                "share_id", "sh-chat-1",
                                "resource_kind", "session",
                                "resource_id", "sess-100",
                                "label", "Architecture review",
                                "owner_profile", "julie",
                                "permission", "read"),
                        Map.of(
                                "share_id", "sh-note-1",
                                "resource_kind", "note",
                                "resource_id", "note-200",
                                "label", "Shopping list"),
                        Map.of(
                                "id", "sh-chat-2",
                                "resource_kind", "session",
                                "resource_id", "sess-101"),
                        Map.of(
                                "resource_kind", "session",
                                "label", "Missing share id")));

        List<ThreadInboxFilter.Entry> shared = ThreadInboxFilter.parseSharedSessionsMap(payload);
        assertEquals(2, shared.size());

        ThreadInboxFilter.Entry first = shared.get(0);
        assertTrue(first.shared());
        assertEquals("sh-chat-1", first.shareId());
        assertEquals("julie", first.ownerProfile());
        assertEquals(UniversalShareModel.PERM_READ, first.permission());
        assertEquals("Architecture review", first.title());

        ThreadInboxFilter.Entry second = shared.get(1);
        assertEquals("sh-chat-2", second.shareId());
        assertEquals("sess-101", second.title());
        assertEquals(UniversalShareModel.PERM_READ, second.permission());

        assertTrue(ThreadInboxFilter.parseSharedSessionsMap(null).isEmpty());
    }

    @Test
    public void resolvesEmptyStateMessagesAndExtractsSharedTranscriptContent() {
        assertEquals(R.string.thread_empty_profile,
                ThreadInboxFilter.emptyMessageRes(ThreadInboxFilter.Filter.ALL, false));
        assertEquals(R.string.thread_filter_pinned_empty,
                ThreadInboxFilter.emptyMessageRes(ThreadInboxFilter.Filter.PINNED, false));
        assertEquals(R.string.thread_filter_shared_empty,
                ThreadInboxFilter.emptyMessageRes(ThreadInboxFilter.Filter.SHARED, false));
        assertEquals(R.string.thread_filter_shared_unavailable,
                ThreadInboxFilter.emptyMessageRes(ThreadInboxFilter.Filter.SHARED, true));

        Map<String, Object> direct = Map.of("markdown", "# Summary\nReady");
        assertEquals("# Summary\nReady", ThreadInboxFilter.extractSharedContentMap(direct, "You", "Hermes"));

        Map<String, Object> transcript = Map.of("messages", List.of(
                Map.of("role", "user", "content", "Hello"),
                Map.of("role", "assistant", "content", "Hi there")));
        assertEquals("You:\nHello\n\nHermes:\nHi there",
                ThreadInboxFilter.extractSharedContentMap(transcript, "You", "Hermes"));

        assertEquals("", ThreadInboxFilter.extractSharedContentMap(null, "You", "Hermes"));

        // Nested content.messages and malformed rows fail closed cleanly.
        Map<String, Object> nested = Map.of("content", Map.of("messages", List.of(
                Map.of("role", "user", "content", "Question"),
                Map.of("role", "assistant", "content", "Answer"))));
        assertEquals("You:\nQuestion\n\nHermes:\nAnswer",
                ThreadInboxFilter.extractSharedContentMap(nested, "You", "Hermes"));

        assertFalse(ThreadInboxFilter.isPinnedMap(Map.of("pinned", 1)));
        assertFalse(ThreadInboxFilter.isPinnedMap(Map.of("is_pinned", "true")));
        assertFalse(ThreadInboxFilter.isPinnedMap(Map.of("pinned", 0)));

        java.util.ArrayList<ThreadInboxFilter.Entry> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add(new ThreadInboxFilter.Entry("t-ok", "Ok", true, false, "", "", ""));
        assertEquals(1, ThreadInboxFilter.filterEntries(withNull, null).size());
        assertEquals(1, ThreadInboxFilter.filterEntries(withNull, ThreadInboxFilter.Filter.PINNED).size());

        java.util.ArrayList<Object> noisyShares = new java.util.ArrayList<>();
        noisyShares.add(null);
        noisyShares.add("bad");
        noisyShares.add(Map.of("share_id", "sh-blank", "resource_kind", "session", "resource_id", "", "label", ""));
        List<ThreadInboxFilter.Entry> parsedNoisy = ThreadInboxFilter.parseSharedSessionsMap(Map.of("data", noisyShares));
        assertTrue(parsedNoisy.isEmpty());
    }
}
