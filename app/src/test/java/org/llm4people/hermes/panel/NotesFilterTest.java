package org.llm4people.hermes.panel;

import org.junit.Test;
import org.llm4people.hermes.R;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
}
