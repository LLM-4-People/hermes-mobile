package org.llm4people.hermes.hub;

import org.junit.Test;
import org.llm4people.hermes.R;
import org.llm4people.hermes.push.DeliveryItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class NotesTasksModelTest {

    @Test
    public void sortNotesOrdersPinnedFirstCompactsPreviewsAndSkipsEmptyRows() {
        List<NotesTasksModel.NoteRow> rows = NotesTasksModel.sortNotes(Arrays.asList(
                new NotesTasksModel.NoteRow("n1", "Regular", "Line 1\n\n  Line 2", false, ""),
                new NotesTasksModel.NoteRow("n2", "Pinned", "Important", true, ""),
                new NotesTasksModel.NoteRow("", "", "", false, ""),
                null));

        assertEquals(2, rows.size());
        assertEquals("n2", rows.get(0).id());
        assertTrue(rows.get(0).pinned());
        assertEquals("n1", rows.get(1).id());
        assertEquals("Line 1 Line 2", rows.get(1).preview());
        assertTrue(NotesTasksModel.parseNotes(null).isEmpty());
        assertTrue(NotesTasksModel.sortNotes(null).isEmpty());
    }

    @Test
    public void summarizeRemindersComputesCountsAndOrdersAttentionThenActiveThenPaused() {
        NotesTasksModel.ReminderOverview overview = NotesTasksModel.summarizeReminders(Arrays.asList(
                new NotesTasksModel.ReminderRow("j_paused", "Paused", "Check", "09:00", false, false),
                new NotesTasksModel.ReminderRow("j_active", "Morning", "Briefing", "08:00", true, false),
                new NotesTasksModel.ReminderRow("j_err", "Broken", "Retry", "07:00", true, true),
                new NotesTasksModel.ReminderRow("", "", "", "", false, false),
                null));

        assertEquals(3, overview.total());
        assertEquals(2, overview.activeCount());
        assertEquals(1, overview.pausedCount());
        assertEquals(1, overview.attentionCount());
        assertEquals("j_err", overview.rows().get(0).id());
        assertTrue(overview.rows().get(0).needsAttention());
        assertEquals(R.string.panel_jobs_run_status_failed,
                NotesTasksModel.reminderStatusLabelRes(overview.rows().get(0)));
        assertEquals("j_active", overview.rows().get(1).id());
        assertEquals(R.string.panel_scheduled,
                NotesTasksModel.reminderStatusLabelRes(overview.rows().get(1)));
        assertEquals("j_paused", overview.rows().get(2).id());
        assertFalse(overview.rows().get(2).enabled());
        assertEquals(R.string.panel_paused,
                NotesTasksModel.reminderStatusLabelRes(overview.rows().get(2)));
        assertEquals(R.string.panel_paused, NotesTasksModel.reminderStatusLabelRes(null));

        NotesTasksModel.ReminderOverview empty = NotesTasksModel.parseReminders(null);
        assertEquals(0, empty.total());
        assertTrue(empty.rows().isEmpty());
    }

    @Test
    public void normalizeSharesAndUnreadDeliveriesHandleMissingFieldsSafely() {
        List<NotesTasksModel.SharedRow> shares = NotesTasksModel.normalizeShares(Arrays.asList(
                new NotesTasksModel.SharedRow("s1", "NOTE", "Sprint plan", "alice", "READ"),
                new NotesTasksModel.SharedRow("", "", "", "", ""),
                null));

        assertEquals(1, shares.size());
        assertEquals("note", shares.get(0).resourceKind());
        assertEquals("Sprint plan", shares.get(0).displayTitle());
        assertEquals("alice", shares.get(0).ownerProfile());
        assertTrue(NotesTasksModel.parseReceivedShares(null).isEmpty());

        DeliveryItem unread = new DeliveryItem(
                "d1", DeliveryItem.Kind.BRIEFING, DeliveryItem.Urgency.NORMAL,
                "Morning brief", "Ready", "{}", 100L, "", "", "", 0L);
        DeliveryItem read = new DeliveryItem(
                "d2", DeliveryItem.Kind.BRIEFING, DeliveryItem.Urgency.NORMAL,
                "Old brief", "Done", "{}", 90L, "", "", "", 95L);
        assertEquals(1, NotesTasksModel.countUnreadDeliveries(List.of(unread, read)));
        assertEquals(0, NotesTasksModel.countUnreadDeliveries(null));

        String longText = "a".repeat(NotesTasksModel.MAX_PREVIEW_CHARS + 30);
        String compacted = NotesTasksModel.compactPreview(longText);
        assertTrue(compacted.length() <= NotesTasksModel.MAX_PREVIEW_CHARS);
        assertTrue(compacted.endsWith("…"));
    }

    @Test
    public void parseNotesMapHandlesNotesAndDataKeysOrderingTruncationAndMalformedEntries() {
        Map<String, Object> regular = new HashMap<>();
        regular.put("note_id", "n1");
        regular.put("title", "Regular");
        regular.put("content", "First\nSecond");
        regular.put("pinned", "false");
        regular.put("created_at", "2026-01-01");

        Map<String, Object> pinned = Map.of(
                "id", "n2",
                "title", "Pinned",
                "body", "Priority",
                "pinned", true,
                "updated_at", "2026-01-02");

        Map<String, Object> empty = Map.of("title", "   ");

        List<NotesTasksModel.NoteRow> rows = NotesTasksModel.parseNotesMap(Map.of(
                "notes", Arrays.asList(regular, "not-a-map", null, empty, pinned)));
        assertEquals(2, rows.size());
        assertEquals("n2", rows.get(0).id());
        assertTrue(rows.get(0).pinned());
        assertEquals("n1", rows.get(1).id());
        assertEquals("First Second", rows.get(1).preview());

        // Fallback "data" envelope and MAX_VISIBLE_ITEMS cap.
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < NotesTasksModel.MAX_VISIBLE_ITEMS + 5; i++) {
            many.add(Map.of("id", "id_" + i, "title", "Note " + i));
        }
        List<NotesTasksModel.NoteRow> capped = NotesTasksModel.parseNotesMap(Map.of("data", many));
        assertEquals(NotesTasksModel.MAX_VISIBLE_ITEMS, capped.size());

        // Negative / empty inputs.
        assertTrue(NotesTasksModel.parseNotesMap(null).isEmpty());
        assertTrue(NotesTasksModel.parseNotesMap(Collections.emptyMap()).isEmpty());
        assertTrue(NotesTasksModel.parseNotesMap(Map.of("notes", "invalid")).isEmpty());
    }

    @Test
    public void parseRemindersMapAndSharesMapHandleValidFallbackAndMalformedPayloads() {
        Map<String, Object> failedJob = Map.of(
                "job_id", "j1",
                "title", "Nightly",
                "message", "Sync",
                "schedule", "02:00",
                "enabled", true,
                "last_status", "FAILED");
        Map<String, Object> pausedJob = Map.of(
                "id", "j2",
                "prompt", "Digest",
                "schedule_display", "Daily",
                "enabled", false,
                "last_status", "ok");

        NotesTasksModel.ReminderOverview overview = NotesTasksModel.parseRemindersMap(Map.of(
                "jobs", Arrays.asList(pausedJob, null, "bad", failedJob)));
        assertEquals(2, overview.total());
        assertEquals(1, overview.activeCount());
        assertEquals(1, overview.pausedCount());
        assertEquals(1, overview.attentionCount());
        assertEquals("j1", overview.rows().get(0).id());
        assertTrue(overview.rows().get(0).needsAttention());
        assertEquals("j2", overview.rows().get(1).name());

        assertEquals(0, NotesTasksModel.parseRemindersMap(null).total());
        assertEquals(0, NotesTasksModel.parseRemindersMap(Map.of("data", List.of("bad"))).total());

        List<NotesTasksModel.SharedRow> shares = NotesTasksModel.parseReceivedSharesMap(Map.of(
                "data", Arrays.asList(
                        Map.of("id", "sh1", "kind", "NOTE", "resource_id", "res-1",
                                "shared_by", "bob", "permission", "WRITE"),
                        null,
                        "bad",
                        Map.of("share_id", "", "label", ""))));
        assertEquals(1, shares.size());
        assertEquals("sh1", shares.get(0).shareId());
        assertEquals("note", shares.get(0).resourceKind());
        assertEquals("res-1", shares.get(0).displayTitle());
        assertEquals("bob", shares.get(0).ownerProfile());
        assertEquals("write", shares.get(0).permission());

        assertTrue(NotesTasksModel.parseReceivedSharesMap(null).isEmpty());
        assertTrue(NotesTasksModel.parseReceivedSharesMap(Map.of("shares", 42)).isEmpty());

        try {
            shares.add(new NotesTasksModel.SharedRow("x", "note", "x", "a", "read"));
            fail("parseReceivedSharesMap must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {}
    }
}
