package org.llm4people.hermes.artifact;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;
import org.llm4people.hermes.VisibleText;
import org.llm4people.hermes.places.NearbyCategory;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("deprecation")
public class LiveLayoutContractTest extends InstrumentationTestCase {
    static final String REVIEW = "{\"version\":1,\"surface\":\"page/revue\",\"mode\":\"replace\"," +
            "\"title\":\"Revue\",\"blocks\":[{\"type\":\"value\",\"title\":\"Fil\",\"source\":\"thread.title\"}," +
            "{\"type\":\"link\",\"title\":\"Suite\",\"page\":\"page/suite\"}," +
            "{\"type\":\"form\",\"title\":\"Code\",\"prompt\":\"Examiner le composant.\",\"fields\":[" +
            "{\"id\":\"file\",\"kind\":\"text\",\"label\":\"Fichier\"}," +
            "{\"id\":\"why\",\"kind\":\"choice\",\"label\":\"Objectif\",\"options\":[\"Rapidité\",\"Fiabilité\"]}," +
            "{\"id\":\"notes\",\"kind\":\"multiline\",\"label\":\"Notes\",\"required\":false}]}]}";
    private String thread(String slots) {
        return "{\"version\":1,\"surface\":\"thread\",\"title\":\"Fil\",\"blocks\":[{\"type\":\"content\",\"slots\":"
                + slots + "}]}";
    }

    public void testNearbyGuideAcceptsOnlyOneAllowlistedCategoryOnANamedPage() throws Exception {
        String block = "{\"type\":\"nearby_places\",\"category\":\"restaurant\"}";
        String page = "{\"version\":1,\"surface\":\"page/guide\",\"mode\":\"replace\",\"title\":\"À table\",\"blocks\":[" + block + "]}";
        assertEquals("nearby_places", WorkspaceLayout.parse(page).blocks.get(0).type);
        WorkspaceLayout pharmacy = WorkspaceLayout.parse(page.replace("restaurant", "pharmacy"));
        assertEquals("pharmacy", pharmacy.blocks.get(0).category);
        assertEquals(NearbyCategory.PHARMACY.heading, pharmacy.blocks.get(0).title);
        Context target = getInstrumentation().getTargetContext();
        Context localized = AppLanguage.wrapContext(target);
        assertEquals(localized.getString(R.string.places_cat_pharmacy_heading), NearbyCategory.PHARMACY.heading(target));
        WorkspaceLayout bounded = WorkspaceLayout.parse(
                "{\"version\":2,\"surface\":\"page/cafes\",\"mode\":\"replace\",\"title\":\"Cafés\",\"blocks\":[{\"type\":\"nearby_places\",\"category\":\"cafe\",\"radius_m\":3000,\"limit\":20}]}");
        assertEquals(3000, bounded.blocks.get(0).radiusMeters);
        assertEquals(20, bounded.blocks.get(0).limit);
        invalid(page.replace("restaurant", "nuclear_plant"));
        invalid(page.replace(",\"category\":\"restaurant\"", ""));
        invalid(page.replace("\"category\":\"restaurant\"", "\"category\":\"cafe\",\"radius_m\":499"));
        invalid(page.replace("\"category\":\"restaurant\"", "\"category\":\"cafe\",\"radius_m\":5001"));
        invalid(page.replace("\"category\":\"restaurant\"", "\"category\":\"cafe\",\"limit\":25"));
        invalid(page.replace("\"type\":\"nearby_places\"", "\"type\":\"nearby_places\",\"places\":[]"));
        invalid(page.replace("\"type\":\"nearby_places\"", "\"type\":\"nearby_places\",\"latitude\":45"));
        invalid(page.replace(block, block + "," + block));
        invalid(page.replace("page/guide", "thread"));
    }

    public void testPageAndFieldIdentifiersPreserveUnderscoresAndHyphens() throws Exception {
        String raw = REVIEW.replace("page/revue", "page/revue_code").replace("page/suite", "page/revue-code")
                .replace("\"file\"", "\"review_point\"").replace("\"why\"", "\"review-point\"");
        WorkspaceLayout layout = WorkspaceLayout.parse(raw);
        assertEquals("page/revue_code", layout.surface);
        assertEquals("page/revue-code", layout.blocks.get(1).page);
        WorkspaceLayout.Block form = layout.blocks.get(2);
        Map<String,String> answers = new HashMap<>();
        answers.put("review_point", "Panier.java");
        answers.put("review-point", "Fiabilité");
        assertEquals("Examiner le composant.\n\nFichier\nPanier.java\n\nObjectif\nFiabilité", DraftForm.compose(form, answers));
        invalid(raw.replace("review-point", "review_point"));
        String longest = "abcdefghijklmnopqrstuvwxyz0123456789";
        assertTrue(DraftForm.validId(longest));
        for (String id : new String[]{"_revue", "Revue", "révision", "a/b", longest + "_"}) {
            assertFalse(DraftForm.validId(id));
            assertFalse(WorkspaceLayout.custom("page/" + id));
        }
    }

    public void testReplacementsAndExplicitNativeSlotSelection() throws Exception {
        for (String s : WorkspaceLayout.SURFACES.keySet())
            assertEquals(s, WorkspaceLayout.parse(REVIEW.replace("page/revue",s)).surface);
        assertEquals(Arrays.asList("composer","transcript"),WorkspaceLayout.parse(
                thread("[\"composer\",\"transcript\"]")).slots());
        invalid(thread("[]"));
        invalid(thread("[\"composer\",\"composer\"]"));
        invalid(thread("[\"notice\"]").replace("\"thread\"","\"stats\""));
        invalid(thread("[\"notice\"]").replace("\"version\":1","\"version\":1,\"mode\":\"replace\""));
        invalid(REVIEW.replace("\"replace\"","\"extend\""));
        invalid(REVIEW.replace("page/revue","page/../profiles"));
        invalid(REVIEW.replace("page/suite","https://example.org"));
        invalid(REVIEW.replace("thread.title","profile.bearer"));
        invalid(REVIEW.replace("\"type\":\"form\"","\"type\":\"form\",\"send\":true"));
        invalid(REVIEW.replace("\"id\":\"notes\"","\"id\":\"why\""));
        invalid(REVIEW.replace("\"kind\":\"choice\"","\"kind\":\"text\""));
        invalid(REVIEW.replace("\"type\":\"link\"","\"type\":\"link\",\"fields\":[]"));
    }

    public void testFormBuildsOnlyTheDraftTheUserSupplied() throws Exception {
        WorkspaceLayout.Block form = WorkspaceLayout.parse(REVIEW).blocks.get(2);
        Map<String,String> answers = new HashMap<>();
        answers.put("file"," src/Parser.java ");
        answers.put("why","Fiabilité");
        answers.put("unknown","ignored");
        assertEquals("Examiner le composant.\n\nFichier\nsrc/Parser.java\n\nObjectif\nFiabilité",
                DraftForm.compose(form,answers));
        answers.put("why","unlisted");
        assertMissing(form,answers,"why");
        answers.remove("file");
        assertMissing(form,answers,"file");
    }

    public void testVisuallyBlankRequiredAndOptionalFormInputs() throws Exception {
        WorkspaceLayout.Block form = WorkspaceLayout.parse(REVIEW).blocks.get(2);
        Map<String,String> answers = new HashMap<>();
        answers.put("why","Fiabilité");
        String supplemental = new String(Character.toChars(0xe0100));
        for (String blank : Arrays.asList(" \t", "\u2003", "\u00a0", "\u202f\n", "\u200b\u200d",
                "\u00a0\ufe0f", "\ufe00", "\u180f", supplemental, new String(Character.toChars(0xe01ef)))) {
            answers.put("file",blank);
            assertMissing(form,answers,"file");
            answers.put("file","src/Parser.java");
            answers.put("notes",blank);
            assertFalse(DraftForm.compose(form,answers).contains("Notes"));
            assertTrue(VisibleText.isBlank(blank));
        }
        String visible = "\u00a0résumé\u2003\u2708\ufe0f字" + supplemental;
        answers.put("file",visible);
        assertTrue(DraftForm.compose(form,answers).contains(visible));
        assertFalse(VisibleText.isBlank(visible));
    }

    private void assertMissing(WorkspaceLayout.Block form, Map<String,String> answers, String id) {
        try { DraftForm.compose(form,answers); fail("Unexpected form acceptance"); }
        catch (DraftForm.MissingAnswer e) { assertEquals(id,e.field.id); }
    }
    private void invalid(String raw) {
        try { WorkspaceLayout.parse(raw); fail("Unexpected layout acceptance"); }
        catch (IOException expected) { }
    }
}
