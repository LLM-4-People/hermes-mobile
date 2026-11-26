package org.llm4people.hermes.panel;

import org.junit.Test;
import org.llm4people.hermes.R;

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
        assertEquals(R.string.ws_role_user, SearchResultModel.roleLabelRes("user"));
        assertEquals(R.string.ws_role_hermes, SearchResultModel.roleLabelRes("assistant"));
        assertEquals(R.string.ws_role_tool, SearchResultModel.roleLabelRes("tool"));
        assertEquals(0, SearchResultModel.roleLabelRes("unknown"));
        assertEquals("bonjour monde", SearchResultModel.normalizeQuery("  bonjour\u0000 monde  "));
        assertEquals("bonjour monde", SearchResultModel.normalizeQuery("bon\u202E\u2067\u200Bjour\u00A0\u3000monde\uD800"));
        assertEquals("", SearchResultModel.normalizeQuery("\u3164\u200B\u202E"));
        assertFalse(SearchResultModel.formatTimestamp("2026-11-22T14:30:00Z", ZoneId.of("America/Toronto"), java.util.Locale.US).isEmpty());
        assertEquals("", SearchResultModel.formatTimestamp("not-a-date", ZoneId.of("UTC"), java.util.Locale.US));
    }

    @Test
    public void skillStudioValidationDocumentBuilderAndToggleRollback() {
        assertEquals("travail/relecture", SkillStudioModel.validateNewSkillName("Travail/Relecture"));
        for (String badName : new String[]{null, "", "../escape", "/root", "a/b/c", "bad\\name", "name with spaces"}) {
            try { SkillStudioModel.validateNewSkillName(badName); fail("Accepted bad skill name: " + badName); }
            catch (IllegalArgumentException expected) {}
        }

        // category/slug splits into lowercase single-segment name + category; explicit category overrides prefix.
        SkillStudioModel.SkillLocation derived = SkillStudioModel.resolveSkillLocation("Travail/Resume", "");
        assertEquals("resume", derived.name());
        assertEquals("travail", derived.category());
        SkillStudioModel.SkillLocation overridden = SkillStudioModel.resolveSkillLocation("Travail/Resume", " Productivite ");
        assertEquals("resume", overridden.name());
        assertEquals("productivite", overridden.category());
        // Fork _validate_category(): one lowercase directory name, never accents, spaces, or separators.
        assertEquals("travail", SkillStudioModel.validateCategory("Travail"));
        assertEquals("", SkillStudioModel.validateCategory("   "));
        for (String badCategory : new String[]{"Productivité", "Mes outils", "dev/mobile", "a\\b", ".", "..", "-x",
                "a".repeat(65)}) {
            try { SkillStudioModel.resolveSkillLocation("resume", badCategory); fail("Accepted bad category: " + badCategory); }
            catch (IllegalArgumentException expected) {}
        }

        String doc = SkillStudioModel.buildSkillDocument(
                "Travail/Resume", "Résume mes notes \"rapidement\"", "Productivite", "Fais 3 points clés.");
        assertTrue(doc.startsWith("---\nname: resume\n"));
        assertTrue(doc.contains("description: \"Résume mes notes \\\"rapidement\\\"\""));
        assertTrue(doc.contains("category: \"productivite\""));
        assertTrue(doc.endsWith("Fais 3 points clés.\n"));

        // Blank description is synthesized from instructions so Fork _validate_frontmatter never rejects it.
        String autoDescDoc = SkillStudioModel.buildSkillDocument(
                "travail/bilan", "   ", "", "# Bilan hebdomadaire\nDétails ici.");
        assertTrue(autoDescDoc.startsWith("---\nname: bilan\n"));
        assertTrue(autoDescDoc.contains("description: \"Bilan hebdomadaire\""));
        assertTrue(autoDescDoc.contains("category: \"travail\""));

        SkillStudioModel.ToggleState state = new SkillStudioModel.ToggleState();
        state.sync("resume", true);
        boolean previous = state.beginToggle("resume", false);
        assertTrue(previous);
        assertFalse(state.isEnabled("resume", true));
        // Rollback restores exact prior state after server rejection.
        state.rollback("resume", previous);
        assertTrue(state.isEnabled("resume", false));

        assertEquals(R.string.ws_skill_source_user, SkillStudioModel.sourceLabelRes("user", true));
        assertEquals(R.string.ws_skill_source_builtin, SkillStudioModel.sourceLabelRes("builtin", false));
        assertEquals(R.string.ws_skill_source_extension, SkillStudioModel.sourceLabelRes("plugin", false));
    }

    @Test
    public void skillDescriptionsFitTheForkSixtyCharacterSkillIndex() {
        String sixty = "a".repeat(SkillStudioModel.MAX_DESCRIPTION_CHARS);
        assertEquals(sixty, SkillStudioModel.validateDescription(sixty));
        assertEquals("", SkillStudioModel.validateDescription(null));
        // Fork measures Python code points: 59 letters + one emoji is 60, not 61.
        String emoji = "a".repeat(59) + "\uD83D\uDE00";
        assertEquals(emoji, SkillStudioModel.validateDescription(emoji));
        try {
            SkillStudioModel.buildSkillDocument("resume", sixty + "b", "", "Fais 3 points clés.");
            fail("Accepted a 61-character description the Fork rejects with HTTP 400");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("61"));
        }

        // A long first instruction line is clipped at a word boundary to <= 60 code points.
        String longLine = "Prépare un résumé hebdomadaire clair de toutes mes réunions, décisions et prochaines étapes importantes";
        String doc = SkillStudioModel.buildSkillDocument("bilan", "", "", longLine + "\nDétails.");
        String description = doc.substring(doc.indexOf("description: \"") + 14, doc.indexOf("\"\n", doc.indexOf("description:")));
        assertTrue(description.codePointCount(0, description.length()) <= SkillStudioModel.MAX_DESCRIPTION_CHARS);
        assertTrue(longLine.startsWith(description));
        assertTrue(longLine.charAt(description.length()) == ' ' || longLine.charAt(description.length()) == ',');
        // A single unbroken word is hard-cut; surrogate pairs are never split.
        String clipped = SkillStudioModel.clipDescription("\uD83D\uDE00".repeat(70));
        assertEquals(SkillStudioModel.MAX_DESCRIPTION_CHARS, clipped.codePointCount(0, clipped.length()));
        assertFalse(Character.isHighSurrogate(clipped.charAt(clipped.length() - 1)));
    }

    @Test
    public void searchPageHasMoreHandlesExplicitFlagAndLegacyPageProbe() {
        SearchResultModel.Hit hit = new SearchResultModel.Hit(
                "s1", "t1", "Titre", "1", "user", SearchResultModel.parseSnippet("Bonjour"), "");
        // Explicit has_more=true unlocks pagination even when total == hits.size().
        SearchResultModel.Page explicitMore = new SearchResultModel.Page(java.util.List.of(hit), 1, "q", true, true);
        assertTrue(explicitMore.hasMore(1));
        // Explicit has_more=false stops pagination when total == loaded.
        SearchResultModel.Page explicitDone = new SearchResultModel.Page(java.util.List.of(hit), 1, "q", false, true);
        assertFalse(explicitDone.hasMore(1));
        // Legacy server omitting has_more with a full page of DEFAULT_LIMIT hits allows probing next page.
        java.util.List<SearchResultModel.Hit> fullPage = java.util.Collections.nCopies(SearchResultModel.DEFAULT_LIMIT, hit);
        SearchResultModel.Page legacyFull = new SearchResultModel.Page(fullPage, fullPage.size(), "q", false, false);
        assertTrue(legacyFull.hasMore(fullPage.size()));
        // Empty next-page probe terminates pagination cleanly.
        SearchResultModel.Page emptyProbe = new SearchResultModel.Page(java.util.List.of(), 0, "q", false, false);
        assertFalse(emptyProbe.hasMore(fullPage.size()));
    }

    @Test
    public void universalShareValidationCoversAllSevenKindsAndRejectsBadProfiles() {
        for (String kind : new String[]{"note", "surface", "widget", "tool", "skill", "artifact", "session"}) {
            assertEquals(kind, UniversalShareModel.validateKind(kind));
        }
        try { UniversalShareModel.validateKind("password"); fail("Accepted invalid kind"); }
        catch (IllegalArgumentException expected) {}

        assertEquals(R.string.ws_share_perm_read, UniversalShareModel.permissionLabelRes("read"));
        assertEquals(R.string.ws_share_perm_edit, UniversalShareModel.permissionLabelRes("edit"));
        assertEquals(R.string.ws_share_perm_clone, UniversalShareModel.permissionLabelRes("clone"));
        assertEquals("julie", UniversalShareModel.validateTargetProfile(" julie "));

        for (String badProfile : new String[]{null, "", "..", "a/b", "user name", "-bad"}) {
            try { UniversalShareModel.validateTargetProfile(badProfile); fail("Accepted bad profile: " + badProfile); }
            catch (IllegalArgumentException expected) {}
        }
        assertEquals(R.string.ws_share_err_profile_not_found,
                UniversalShareModel.describeHttpErrorRes(404, "profile_not_found"));
        assertEquals(R.string.ws_share_err_denied,
                UniversalShareModel.describeHttpErrorRes(403, ""));
    }

    @Test
    public void formatTimestampDelegatesIso8601EpochSecondsAndEpochMillisConsistently() {
        ZoneId utc = ZoneId.of("UTC");
        String expected = SearchResultModel.formatTimestamp("2026-11-22T14:30:00Z", utc, java.util.Locale.US);
        assertFalse(expected.isEmpty());
        assertEquals(expected, SearchResultModel.formatTimestamp("2026-11-22T09:30:00-05:00", utc, java.util.Locale.US));
        assertEquals(expected, SearchResultModel.formatTimestamp("1795357800", utc, java.util.Locale.US));
        assertEquals(expected, SearchResultModel.formatTimestamp("1795357800000", utc, java.util.Locale.US));

        assertEquals("", SearchResultModel.formatTimestamp(null, utc, java.util.Locale.US));
        assertEquals("", SearchResultModel.formatTimestamp("   ", utc, java.util.Locale.US));
        assertEquals("", SearchResultModel.formatTimestamp("0", utc, java.util.Locale.US));
        assertEquals("", SearchResultModel.formatTimestamp("-1795357800", utc, java.util.Locale.US));
        assertEquals("", SearchResultModel.formatTimestamp("NaN", utc, java.util.Locale.US));
        assertEquals("", SearchResultModel.formatTimestamp("invalid-date", utc, java.util.Locale.US));
    }
}
