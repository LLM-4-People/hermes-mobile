package org.llm4people.hermes.panel;

import org.junit.Test;

import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SearchAndSkillStudioModelTest {
    @Test
    public void searchSnippetStripsMarkTagsAndPreservesHighlightRanges() {
        SearchResultModel.HighlightedSnippet parsed =
                SearchResultModel.parseSnippet("  Voir <mark>Montréal</mark> et <MARK>café</MARK> ici ");
        assertEquals("Voir Montréal et café ici", parsed.plainText());
        assertEquals(2, parsed.highlights().size());
        assertEquals("Montréal", parsed.plainText().substring(
                parsed.highlights().get(0).start(), parsed.highlights().get(0).end()));
        assertEquals("café", parsed.plainText().substring(
                parsed.highlights().get(1).start(), parsed.highlights().get(1).end()));

        assertEquals("", SearchResultModel.parseSnippet(null).plainText());
        assertEquals("Vous", SearchResultModel.roleLabel("user"));
        assertEquals("Hermes", SearchResultModel.roleLabel("assistant"));
        assertEquals("Outil", SearchResultModel.roleLabel("tool"));
        assertEquals("", SearchResultModel.roleLabel("unknown"));
        assertEquals("bonjour monde", SearchResultModel.normalizeQuery("  bonjour\u0000 monde  "));
        assertFalse(SearchResultModel.formatTimestamp("2026-11-22T14:30:00Z", ZoneId.of("America/Toronto")).isEmpty());
        assertEquals("", SearchResultModel.formatTimestamp("not-a-date", ZoneId.of("UTC")));
    }

    @Test
    public void skillStudioValidationDocumentBuilderAndToggleRollback() {
        assertEquals("travail/relecture", SkillStudioModel.validateNewSkillName("travail/relecture"));
        for (String badName : new String[]{null, "", "../escape", "/root", "a/b/c", "bad\\name", "name with spaces"}) {
            try { SkillStudioModel.validateNewSkillName(badName); fail("Accepted bad skill name: " + badName); }
            catch (IllegalArgumentException expected) {}
        }

        String doc = SkillStudioModel.buildSkillDocument(
                "travail/resume", "Résume mes notes \"rapidement\"", "Productivité", "Fais 3 points clés.");
        assertTrue(doc.startsWith("---\nname: travail/resume\n"));
        assertTrue(doc.contains("description: \"Résume mes notes \\\"rapidement\\\"\""));
        assertTrue(doc.contains("category: \"Productivité\""));
        assertTrue(doc.endsWith("Fais 3 points clés.\n"));

        SkillStudioModel.ToggleState state = new SkillStudioModel.ToggleState();
        state.sync("travail/resume", true);
        boolean previous = state.beginToggle("travail/resume", false);
        assertTrue(previous);
        assertFalse(state.isEnabled("travail/resume", true));
        // Rollback restores exact prior state after server rejection.
        state.rollback("travail/resume", previous);
        assertTrue(state.isEnabled("travail/resume", false));

        assertEquals("Personnel", SkillStudioModel.sourceLabel("user", true));
        assertEquals("Intégré", SkillStudioModel.sourceLabel("builtin", false));
        assertEquals("Extension", SkillStudioModel.sourceLabel("plugin", false));
    }

    @Test
    public void universalShareValidationCoversAllSevenKindsAndRejectsBadProfiles() {
        for (String kind : new String[]{"note", "surface", "widget", "tool", "skill", "artifact", "session"}) {
            assertEquals(kind, UniversalShareModel.validateKind(kind));
        }
        try { UniversalShareModel.validateKind("password"); fail("Accepted invalid kind"); }
        catch (IllegalArgumentException expected) {}

        assertEquals("Lecture seule", UniversalShareModel.permissionLabel("read"));
        assertEquals("Modification", UniversalShareModel.permissionLabel("edit"));
        assertEquals("Copie", UniversalShareModel.permissionLabel("clone"));
        assertEquals("julie", UniversalShareModel.validateTargetProfile(" julie "));

        for (String badProfile : new String[]{null, "", "..", "a/b", "user name", "-bad"}) {
            try { UniversalShareModel.validateTargetProfile(badProfile); fail("Accepted bad profile: " + badProfile); }
            catch (IllegalArgumentException expected) {}
        }
        assertTrue(UniversalShareModel.describeHttpError(404, "profile_not_found").contains("introuvable"));
        assertTrue(UniversalShareModel.describeHttpError(403, "").contains("autorisation"));
    }
}
