package org.llm4people.hermes.panel;

import org.junit.Test;
import org.llm4people.hermes.R;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class NotesFilterTest {
    @Test
    public void filtersNotesByOwnershipTabAndQuery() {
        NotesModel.Note ownedA = new NotesModel.Note(
                "n-1", "Résumé vacances", "Réserver le train", List.of("voyage"), true, "2026-01-01", "", "owner");
        NotesModel.Note ownedB = new NotesModel.Note(
                "n-2", "Courses", "Lait et pain", List.of("maison"), false, "2026-01-02", "", "owner");
        NotesModel.Note sharedA = new NotesModel.Note(
                "n-3", "Résumé sprint", "Livraison mobile", List.of("travail"), false, "2026-01-03", "julie", "read");
        NotesModel.Note sharedB = new NotesModel.Note(
                "n-4", "Recette", "Pâtes au basilic", List.of("cuisine"), false, "2026-01-04", "alex", "edit");
        List<NotesModel.Note> mixed = List.of(ownedA, ownedB, sharedA, sharedB);

        List<NotesModel.Note> mineAll = NotesModel.filterForTab(mixed, NotesModel.FilterTab.MINE, "");
        assertEquals(2, mineAll.size());
        assertEquals("n-1", mineAll.get(0).id());
        assertEquals("n-2", mineAll.get(1).id());

        List<NotesModel.Note> sharedAll = NotesModel.filterForTab(mixed, NotesModel.FilterTab.SHARED, "");
        assertEquals(2, sharedAll.size());
        assertEquals("n-3", sharedAll.get(0).id());
        assertEquals("n-4", sharedAll.get(1).id());

        List<NotesModel.Note> sharedQuery = NotesModel.filterForTab(mixed, NotesModel.FilterTab.SHARED, "resume");
        assertEquals(1, sharedQuery.size());
        assertEquals("n-3", sharedQuery.get(0).id());

        List<NotesModel.Note> mineQuery = NotesModel.filterForTab(mixed, NotesModel.FilterTab.MINE, "resume");
        assertEquals(1, mineQuery.size());
        assertEquals("n-1", mineQuery.get(0).id());

        assertTrue(NotesModel.filterForTab(null, NotesModel.FilterTab.SHARED, "x").isEmpty());
    }

    @Test
    public void resolvesEmptyStateMessagesForMineSharedAndQueryMiss() {
        assertEquals(R.string.brain_notes_empty,
                NotesModel.emptyMessageRes(NotesModel.FilterTab.MINE, true));
        assertEquals(R.string.brain_notes_shared_empty,
                NotesModel.emptyMessageRes(NotesModel.FilterTab.SHARED, true));
        assertEquals(R.string.brain_memory_empty_filter,
                NotesModel.emptyMessageRes(NotesModel.FilterTab.MINE, false));
        assertEquals(R.string.brain_memory_empty_filter,
                NotesModel.emptyMessageRes(NotesModel.FilterTab.SHARED, false));
    }

    @Test
    public void sharedReadOrEditNoteWithEmptyOwnerProfileIsNeverTreatedAsOwner() {
        NotesModel.Note sharedReadNoOwner = new NotesModel.Note(
                "n-shared-read", "Shared doc", "Body", List.of(), false, "", "", "read");
        assertFalse(sharedReadNoOwner.isOwner());
        assertFalse(sharedReadNoOwner.canEdit());
        assertFalse(sharedReadNoOwner.canPin());
        assertFalse(sharedReadNoOwner.canDelete());
        assertFalse(sharedReadNoOwner.canShare());

        NotesModel.Note sharedEditNoOwner = new NotesModel.Note(
                "n-shared-edit", "Collab doc", "Body", List.of(), false, "", "", "edit");
        assertFalse(sharedEditNoOwner.isOwner());
        assertTrue(sharedEditNoOwner.canEdit());
        assertFalse(sharedEditNoOwner.canPin());
        assertFalse(sharedEditNoOwner.canDelete());
        assertFalse(sharedEditNoOwner.canShare());

        NotesModel.Note explicitOwner = new NotesModel.Note(
                "n-owner", "Mine", "Body", List.of(), false, "", "alice", "owner");
        assertTrue(explicitOwner.isOwner());
        assertTrue(explicitOwner.canPin());
        assertTrue(explicitOwner.canDelete());
        assertTrue(explicitOwner.canShare());

        NotesModel.Note legacyLocal = new NotesModel.Note(
                "n-local", "Legacy", "Body", List.of(), false, "", "", "");
        assertTrue(legacyLocal.isOwner());
    }

    @Test
    public void filterHandlesNullElementsAndValidatesInputsAndHabits() {
        java.util.ArrayList<NotesModel.Note> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add(new NotesModel.Note("n-1", "Alpha", "Beta", List.of("tag1"), false, "", "", "owner"));
        assertEquals(1, NotesModel.filter(withNull, "").size());
        assertEquals(1, NotesModel.filter(withNull, "alpha").size());
        assertTrue(NotesModel.filter(withNull, "missing").isEmpty());

        assertEquals("Clean Title", NotesModel.validateTitle("  Clean Title  "));
        try {
            NotesModel.validateTitle("   ");
            fail("Expected blank title rejection");
        } catch (LocalizedInputException expected) {
            assertEquals(R.string.ws_note_err_title_empty, expected.resId);
        }
        try {
            NotesModel.validateTitle("Bad\u0007Title");
            fail("Expected control-char title rejection");
        } catch (LocalizedInputException expected) {
            assertEquals(R.string.ws_note_err_title_invalid, expected.resId);
        }
        try {
            NotesModel.validateBody("");
            fail("Expected blank body rejection");
        } catch (LocalizedInputException expected) {
            assertEquals(R.string.ws_note_err_body_empty, expected.resId);
        }

        assertEquals(List.of("work", "urgent"), NotesModel.parseTagsInput("work, urgent, work,  , "
                + "x".repeat(40)));

        NotesModel.HabitSuggestion unlearned = new NotesModel.HabitSuggestion("h1", "Summarize", "", "", "", 2);
        NotesModel.HabitSuggestion learned = new NotesModel.HabitSuggestion("h2", "Summarize", "", "", "", 3);
        assertFalse(unlearned.isLearned());
        assertTrue(learned.isLearned());
        assertEquals(1, NotesModel.filterLearned(List.of(unlearned, learned)).size());
    }
}
