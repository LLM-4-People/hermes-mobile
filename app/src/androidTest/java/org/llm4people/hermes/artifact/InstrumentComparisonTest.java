package org.llm4people.hermes.artifact;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.llm4people.hermes.R;
import java.util.List;

@SuppressWarnings("deprecation")
public final class InstrumentComparisonTest extends InstrumentationTestCase {
    private static final String FIELD = """
            {"id":"file","label":"Fichier","kind":"text"}""";
    private static final String CHOICE = """
            {"id":"priority","label":"Priorité","kind":"choice","required":false,"options":["Normale","Haute"]}""";
    private static final String SECTION = """
            {"title":"Outils","actions":["artifacts","stats"]}""";

    private Context ctx() { return getInstrumentation().getTargetContext(); }
    private String s(int res, Object... args) { return ctx().getString(res, args); }

    private static String form(String fields) {
        return String.format(java.util.Locale.ROOT, """
                {"type":"form","title":"Relecture","prompt":"Relire le fichier.","fields":[%s]}""", fields);
    }
    private static WorkspaceLayout page(String blocks) throws Exception {
        return WorkspaceLayout.parse(String.format(java.util.Locale.ROOT, """
                {"version":1,"surface":"page/revue","mode":"replace","title":"Revue","blocks":[%s]}""", blocks));
    }

    public void testWireFormattingAndExplicitDefaultsProduceNoSemanticChanges() throws Exception {
        WorkspaceLayout before = page(form(FIELD));
        WorkspaceLayout after = page(form("{\"required\":true, \"kind\":\"text\", \"id\":\"file\", \"label\":\"Fichier\"}"));
        assertTrue(InstrumentChanges.between(ctx(), before, after).isEmpty());
    }

    public void testChangesExplainPromptInputsAndActionsWithoutFalseReorders() throws Exception {
        Context c = ctx();
        WorkspaceLayout before = page(form(FIELD) + "," + SECTION);
        WorkspaceLayout after = page(form(CHOICE + "," + FIELD.replace("Fichier","Chemin"))
                .replace("Relire le fichier.", "Relire le module.") + "," + SECTION.replace("stats", "models"));
        List<InstrumentChanges.Change> changes = InstrumentChanges.between(c, before, after);
        String optChoice = s(R.string.ws_diff_optional_suffix, s(R.string.ws_diff_kind_choice));
        String reqText = s(R.string.ws_diff_required_suffix, s(R.string.ws_diff_kind_text));
        assertEquals(5, changes.size());
        assertTrue(changes.contains(new InstrumentChanges.Change("Relecture", s(R.string.ws_diff_draft_prompt),
                "Relire le fichier.","Relire le module.")));
        assertTrue(changes.contains(new InstrumentChanges.Change("Relecture", s(R.string.ws_diff_field_added),
                null,"Priorité\n" + optChoice + "\nNormale\nHaute")));
        assertTrue(changes.contains(new InstrumentChanges.Change("Relecture", s(R.string.ws_diff_field_changed),
                "Fichier\n" + reqText, "Chemin\n" + reqText)));
        assertTrue(changes.contains(new InstrumentChanges.Change("Outils", s(R.string.ws_diff_action_added),
                null, s(R.string.ws_action_models))));
        assertTrue(changes.contains(new InstrumentChanges.Change("Outils", s(R.string.ws_diff_action_removed),
                s(R.string.ws_action_stats), null)));
    }

    public void testOrderAndRemovedFieldsAreDistinguishedFromRenames() throws Exception {
        Context c = ctx();
        WorkspaceLayout old = page(SECTION + "," + form(FIELD + "," + CHOICE));
        WorkspaceLayout reordered = page(form(CHOICE + "," + FIELD) + "," + SECTION);
        List<InstrumentChanges.Change> order = InstrumentChanges.between(c, old, reordered);
        assertEquals(2, order.size());
        assertTrue(order.contains(new InstrumentChanges.Change("", s(R.string.ws_diff_section_order),
                "Outils\nRelecture", "Relecture\nOutils")));
        assertTrue(order.contains(new InstrumentChanges.Change("Relecture", s(R.string.ws_diff_field_order),
                "Fichier\nPriorité", "Priorité\nFichier")));
        WorkspaceLayout renamed = page(SECTION + "," + form(FIELD).replace("Relecture","Inspection"));
        List<InstrumentChanges.Change> rename = InstrumentChanges.between(c, old, renamed);
        assertEquals(2, rename.size());
        assertEquals(s(R.string.ws_diff_section_title), rename.get(0).label());
        assertEquals(s(R.string.ws_diff_field_removed), rename.get(1).label());
    }

    public void testDuplicateHeadingsFollowExactBlockContentAcrossAReorder() throws Exception {
        Context c = ctx();
        String apple = "{\"title\":\"Rubrique\",\"note\":\"Pomme\"}";
        String pear = apple.replace("Pomme", "Poire");
        List<InstrumentChanges.Change> changes = InstrumentChanges.between(c, page(apple + "," + pear),
                page(pear + "," + apple));
        assertEquals(1, changes.size());
        assertEquals(s(R.string.ws_diff_section_order), changes.get(0).label());
        assertEquals("Rubrique · Pomme\nRubrique · Poire", changes.get(0).before());
        assertEquals("Rubrique · Poire\nRubrique · Pomme", changes.get(0).after());
    }

    public void testNestedActionsIdentifyTheParticularSectionEvenWithTheSameTitle() throws Exception {
        Context c = ctx();
        String first = "{\"title\":\"Contrôle\",\"note\":\"Avant livraison\"}";
        String second = first.replace("Avant", "Après");
        String action = ",\"actions\":[\"artifacts\"]}";
        WorkspaceLayout old = page(first + "," + second);
        List<InstrumentChanges.Change> early = InstrumentChanges.between(c, old, page(first.replace("}", action) + "," + second));
        List<InstrumentChanges.Change> late = InstrumentChanges.between(c, old, page(first + "," + second.replace("}", action)));
        assertEquals(List.of(new InstrumentChanges.Change("Contrôle · Avant livraison", s(R.string.ws_diff_action_added),
                null, s(R.string.ws_action_artifacts))), early);
        assertEquals(List.of(new InstrumentChanges.Change("Contrôle · Après livraison", s(R.string.ws_diff_action_added),
                null, s(R.string.ws_action_artifacts))), late);

        // The proposed title can become unique while the original title was ambiguous.
        List<InstrumentChanges.Change> rename = InstrumentChanges.between(c, old, page(first + "," + second.replace("Contrôle", "Vérification")));
        assertEquals(s(R.string.ws_diff_section_renamed, "Contrôle · Après livraison", "Vérification"),
                rename.get(0).section());
    }

    public void testEqualFieldLabelsUseKindThenStableIdToExplainTheirNewOrder() throws Exception {
        Context c = ctx();
        String a = FIELD.replace("Fichier", "Même");
        String textKind = s(R.string.ws_diff_kind_text);
        String multiKind = s(R.string.ws_diff_kind_multiline);
        for (String kind : new String[]{"text", "multiline"}) {
            String b = a.replace("file", "objet").replace("\"text\"", "\"" + kind + "\"");
            List<InstrumentChanges.Change> changes = InstrumentChanges.between(c, page(form(a + "," + b)),
                    page(form(b + "," + a)));
            assertEquals(1, changes.size());
            InstrumentChanges.Change change = changes.get(0);
            assertEquals(s(R.string.ws_diff_field_order), change.label());
            assertFalse(change.before().equals(change.after()));
            assertTrue(change.before().startsWith("Même · " + textKind));
            assertTrue(change.after().startsWith("text".equals(kind) ? "Même · " + textKind + " (objet)" : "Même · " + multiKind));
        }
    }

    public void testSameNamedFieldsIdentifyEditsWithoutReportingUnchangedPeers() throws Exception {
        Context c = ctx();
        String a = FIELD.replace("file", "contrainte_a").replace("Fichier", "Contrainte");
        String b = a.replace("contrainte_a", "contrainte-a");
        String optional = ",\"required\":false}";
        WorkspaceLayout both = page(form(a + "," + b));
        List<InstrumentChanges.Change> editA = InstrumentChanges.between(c, both, page(form(a.replace("}", optional) + "," + b)));
        List<InstrumentChanges.Change> editB = InstrumentChanges.between(c, both, page(form(a + "," + b.replace("}", optional))));
        assertEquals(1, editA.size());
        assertEquals(1, editB.size());
        assertTrue(editA.get(0).before().contains("(contrainte_a)"));
        assertTrue(editA.get(0).after().contains("(contrainte_a)"));
        assertTrue(editB.get(0).before().contains("(contrainte-a)"));
        assertTrue(editB.get(0).after().contains("(contrainte-a)"));

        // Introducing or losing a name collision doesn't change the surviving field's definition.
        WorkspaceLayout justA = page(form(a));
        List<InstrumentChanges.Change> added = InstrumentChanges.between(c, justA, both);
        List<InstrumentChanges.Change> removed = InstrumentChanges.between(c, both, justA);
        assertEquals(1, added.size());
        assertEquals(1, removed.size());
        assertTrue(added.get(0).after().contains("(contrainte-a)"));
        assertTrue(removed.get(0).before().contains("(contrainte-a)"));
    }

    public void testAuthoredLabelsThatResembleGeneratedQualifiersStillHaveDistinctOrder() throws Exception {
        Context c = ctx();
        String a = FIELD.replace("Fichier", "Même");
        String b = a.replace("file", "duplicat");
        String cField = FIELD.replace("file", "autre").replace("Fichier", "Même · " + s(R.string.ws_diff_kind_text) + " (file)");
        List<InstrumentChanges.Change> changes = InstrumentChanges.between(c, page(form(a + "," + b + "," + cField)),
                page(form(cField + "," + b + "," + a)));
        assertEquals(1, changes.size());
        assertFalse(changes.get(0).before().equals(changes.get(0).after()));
    }

    public void testOneSidedSectionTextNamesDirectionAndReplacementKeepsBothValues() throws Exception {
        Context c = ctx();
        String note = "À confirmer avant la livraison";
        WorkspaceLayout blank = page(SECTION);
        WorkspaceLayout added = page(SECTION.replace("\"actions\"", "\"note\":\"" + note + "\",\"actions\""));
        WorkspaceLayout edited = page(SECTION.replace("\"actions\"", "\"note\":\"Confirmé\",\"actions\""));
        assertEquals(List.of(new InstrumentChanges.Change("Outils", s(R.string.ws_diff_text_added), null, note)),
                InstrumentChanges.between(c, blank, added));
        assertEquals(List.of(new InstrumentChanges.Change("Outils", s(R.string.ws_diff_text_removed), note, null)),
                InstrumentChanges.between(c, added, blank));
        assertEquals(List.of(new InstrumentChanges.Change("Outils", s(R.string.ws_diff_text), note, "Confirmé")),
                InstrumentChanges.between(c, added, edited));
        assertTrue(InstrumentChanges.between(c, blank, blank).isEmpty());
    }

    public void testFieldComparisonKeepsOptionBoundariesAndSideIdentityForRendering() throws Exception {
        Context c = ctx();
        WorkspaceLayout original = page(form(FIELD));
        WorkspaceLayout added = page(form(FIELD + "," + CHOICE));
        WorkspaceLayout edited = page(form(FIELD + "," + CHOICE.replace("Haute", "Aujourd’hui · urgent")));
        InstrumentChanges.Detail addition = InstrumentChanges.details(c, original, added).get(0);
        InstrumentChanges.Detail removal = InstrumentChanges.details(c, added, original).get(0);
        InstrumentChanges.Detail revision = InstrumentChanges.details(c, added, edited).get(0);
        assertNull(addition.beforeField());
        assertEquals("Priorité", addition.afterField().name());
        assertEquals(s(R.string.ws_diff_optional_suffix, s(R.string.ws_diff_kind_choice)), addition.afterField().requirement());
        assertEquals(List.of("Normale", "Haute"), addition.afterField().options());
        assertNull(removal.afterField());
        assertEquals(addition.afterField(), removal.beforeField());
        assertEquals(removal.beforeField(), revision.beforeField());
        assertEquals(List.of("Normale", "Aujourd’hui · urgent"), revision.afterField().options());
        assertEquals(InstrumentChanges.between(c, added, edited).get(0), revision.change());
    }

    public void testOnlyChoiceMeaningOrBindingChanged() throws Exception {
        Context c = ctx();
        String value = "{\"type\":\"value\",\"title\":\"Contexte\",\"source\":\"thread.title\"}";
        WorkspaceLayout old = page(value + "," + form(CHOICE));
        WorkspaceLayout next = page(value.replace("thread.title", "profile.label") + ","
                + form(CHOICE.replace("Haute", "Urgente").replace("false", "true")));
        List<InstrumentChanges.Change> diff = InstrumentChanges.between(c, old, next);
        assertEquals(2, diff.size());
        assertEquals(s(R.string.ws_source_thread_title), diff.get(0).before());
        assertEquals(s(R.string.ws_source_profile_label), diff.get(0).after());
        assertTrue(diff.get(1).after().contains(s(R.string.ws_diff_required_suffix, s(R.string.ws_diff_kind_choice)) + "\nNormale\nUrgente"));
    }
}
