package org.llm4people.hermes.hub;

import org.junit.Test;
import org.llm4people.hermes.push.DeliveryItem;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
        assertEquals("j_active", overview.rows().get(1).id());
        assertEquals("j_paused", overview.rows().get(2).id());
        assertFalse(overview.rows().get(2).enabled());

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
}
