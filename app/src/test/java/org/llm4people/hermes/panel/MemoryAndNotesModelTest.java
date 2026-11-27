package org.llm4people.hermes.panel;

import org.junit.Test;
import org.llm4people.hermes.R;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class MemoryAndNotesModelTest {
    @Test
    public void memoryValidationRejectsEmptyControlSurrogatesOverflowAndBadTargets() {
        assertEquals("memory", MemoryModel.validateTarget("memory"));
        assertEquals("user", MemoryModel.validateTarget("user"));
        for (String badTarget : new String[]{null, "", "other", "../memory", "MEMORY"}) {
            try { MemoryModel.validateTarget(badTarget); fail("Accepted target " + badTarget); }
            catch (IllegalArgumentException expected) {}
        }

        assertEquals("Préfère le café filtre", MemoryModel.validateEntryText("  Préfère le café filtre  ", 100));
        for (String badText : new String[]{null, "   ", "bad\u0007bell", "lone\uD800surrogate", "123456"}) {
            try { MemoryModel.validateEntryText(badText, 5); fail("Accepted text"); }
            catch (IllegalArgumentException expected) {}
        }

        assertEquals("abc123", MemoryModel.validateEntryId("abc123"));
        for (String badId : new String[]{null, "", "..", "a/b", "id\n1"}) {
            try { MemoryModel.validateEntryId(badId); fail("Accepted id " + badId); }
            catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    public void memoryFilterFoldsDiacriticsAndMapsHttpErrorsPlainly() {
        MemoryModel.Bucket bucket = new MemoryModel.Bucket("memory", List.of(
                new MemoryModel.Entry("1", "Habite à Montréal", true),
                new MemoryModel.Entry("2", "Travaille en Java", false)), 40, 2000, true);
        assertEquals(1, bucket.filter("montreal").size());
        assertEquals("1", bucket.filter("montreal").get(0).id());

        assertEquals(R.string.ws_memory_err_conflict, MemoryModel.describeHttpErrorRes(409, "conflict"));
        assertEquals(R.string.ws_memory_err_secret, MemoryModel.describeHttpErrorRes(400, "secret_detected"));
        assertEquals(R.string.ws_memory_err_full, MemoryModel.describeHttpErrorRes(400, "char_limit_exceeded"));
        assertEquals(R.string.ws_memory_err_denied, MemoryModel.describeHttpErrorRes(403, ""));
        assertEquals(R.string.ws_memory_err_upgrade, MemoryModel.describeHttpErrorRes(501, ""));
        assertEquals(R.string.ws_http_rejected, MemoryModel.describeHttpErrorRes(500, ""));
    }

    @Test
    public void notesAclEnforcesOwnerVsReadVsEditAndHabitThresholdIsStrictlyThree() {
        NotesModel.Note owned = new NotesModel.Note("n1", "Course", "Pain, lait", List.of("perso"), true, "", "", "");
        assertTrue(owned.isOwner());
        assertTrue(owned.canEdit());
        assertTrue(owned.canDelete());
        assertTrue(owned.canShare());

        NotesModel.Note sharedRead = new NotesModel.Note("n2", "Recette", "Farine", List.of(), false, "", "julie", "read");
        assertFalse(sharedRead.isOwner());
        assertFalse(sharedRead.canEdit());
        assertFalse(sharedRead.canDelete());
        assertFalse(sharedRead.canShare());

        NotesModel.Note sharedEdit = new NotesModel.Note("n3", "Projet", "Étapes", List.of(), false, "", "julie", "edit");
        assertFalse(sharedEdit.isOwner());
        assertTrue(sharedEdit.canEdit());
        assertFalse(sharedEdit.canDelete());
        assertFalse(sharedEdit.canShare());
        // Fork answers HTTP 403 note_owner_required when a non-owner sends "pinned": only owners may pin.
        assertTrue(owned.canPin());
        assertFalse(sharedEdit.canPin());
        assertFalse(sharedRead.canPin());

        assertEquals(List.of("travail", "urgent"), NotesModel.parseTagsInput(" travail, urgent, , travail "));

        // Habits with count < 3 MUST be suppressed.
        List<NotesModel.HabitSuggestion> rawHabits = List.of(
                new NotesModel.HabitSuggestion("h1", "Brouillon trop tôt", "", "", "", 0),
                new NotesModel.HabitSuggestion("h2", "Une fois", "", "", "", 1),
                new NotesModel.HabitSuggestion("h3", "Deux fois", "", "", "", 2),
                new NotesModel.HabitSuggestion("h4", "Résumé du matin", "Chaque jour", "", "Fais mon résumé", 3),
                new NotesModel.HabitSuggestion("h5", "Relecture de code", "", "", "Relis ce diff", 7));
        List<NotesModel.HabitSuggestion> learned = NotesModel.filterLearned(rawHabits);
        assertEquals(2, learned.size());
        assertEquals("h4", learned.get(0).id());
        assertEquals("h5", learned.get(1).id());
    }
}
