package org.llm4people.hermes.artifact;

import junit.framework.TestCase;
import java.util.List;

public final class InstrumentComparisonTest extends TestCase {
    private static final String FIELD = """
            {"id":"file","label":"Fichier","kind":"text"}""";
    private static final String CHOICE = """
            {"id":"priority","label":"Priorité","kind":"choice","required":false,"options":["Normale","Haute"]}""";
    private static final String SECTION = """
            {"title":"Outils","actions":["artifacts","stats"]}""";

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
        assertTrue(InstrumentChanges.between(before, after).isEmpty());
    }

    public void testChangesExplainPromptInputsAndActionsWithoutFalseReorders() throws Exception {
        WorkspaceLayout before = page(form(FIELD) + "," + SECTION);
        WorkspaceLayout after = page(form(CHOICE + "," + FIELD.replace("Fichier","Chemin"))
                .replace("Relire le fichier.", "Relire le module.") + "," + SECTION.replace("stats", "models"));
        List<InstrumentChanges.Change> changes = InstrumentChanges.between(before, after);
        assertEquals(5, changes.size());
        assertTrue(changes.contains(new InstrumentChanges.Change("Relecture","Consigne du brouillon",
                "Relire le fichier.","Relire le module.")));
        assertTrue(changes.contains(new InstrumentChanges.Change("Relecture","Champ ajouté",
                null,"Priorité\nChoix facultatif\nNormale\nHaute")));
        assertTrue(changes.contains(new InstrumentChanges.Change("Relecture","Champ modifié",
                "Fichier\nTexte court obligatoire","Chemin\nTexte court obligatoire")));
        assertTrue(changes.contains(new InstrumentChanges.Change("Outils","Action ajoutée",null,"Choisir un modèle")));
        assertTrue(changes.contains(new InstrumentChanges.Change("Outils","Action retirée","Voir les statistiques",null)));
    }

    public void testOrderAndRemovedFieldsAreDistinguishedFromRenames() throws Exception {
        WorkspaceLayout old = page(SECTION + "," + form(FIELD + "," + CHOICE));
        WorkspaceLayout reordered = page(form(CHOICE + "," + FIELD) + "," + SECTION);
        List<InstrumentChanges.Change> order = InstrumentChanges.between(old, reordered);
        assertEquals(2, order.size());
        assertTrue(order.contains(new InstrumentChanges.Change("","Ordre des rubriques",
                "Outils\nRelecture", "Relecture\nOutils")));
        assertTrue(order.contains(new InstrumentChanges.Change("Relecture", "Ordre des champs",
                "Fichier\nPriorité", "Priorité\nFichier")));
        WorkspaceLayout renamed = page(SECTION + "," + form(FIELD).replace("Relecture","Inspection"));
        List<InstrumentChanges.Change> rename = InstrumentChanges.between(old, renamed);
        assertEquals(2, rename.size());
        assertEquals("Titre de la rubrique", rename.get(0).label());
        assertEquals("Champ retiré", rename.get(1).label());
    }

    public void testDuplicateHeadingsFollowExactBlockContentAcrossAReorder() throws Exception {
        String apple = "{\"title\":\"Rubrique\",\"note\":\"Pomme\"}";
        String pear = apple.replace("Pomme", "Poire");
        List<InstrumentChanges.Change> changes = InstrumentChanges.between(page(apple + "," + pear),
                page(pear + "," + apple));
        assertEquals(1, changes.size());
        assertEquals("Ordre des rubriques", changes.get(0).label());
        assertEquals("Rubrique · Pomme\nRubrique · Poire", changes.get(0).before());
        assertEquals("Rubrique · Poire\nRubrique · Pomme", changes.get(0).after());
    }

    public void testEqualFieldLabelsUseKindThenStableIdToExplainTheirNewOrder() throws Exception {
        String a = FIELD.replace("Fichier", "Même");
        for (String kind : new String[]{"text", "multiline"}) {
            String b = a.replace("file", "objet").replace("\"text\"", "\"" + kind + "\"");
            List<InstrumentChanges.Change> changes = InstrumentChanges.between(page(form(a + "," + b)),
                    page(form(b + "," + a)));
            assertEquals(1, changes.size());
            InstrumentChanges.Change change = changes.get(0);
            assertEquals("Ordre des champs", change.label());
            assertFalse(change.before().equals(change.after()));
            assertTrue(change.before().startsWith("Même · Texte court"));
            assertTrue(change.after().startsWith("text".equals(kind) ? "Même · Texte court (objet)" : "Même · Texte long"));
        }
    }

    public void testOnlyChoiceMeaningOrBindingChanged() throws Exception {
        String value = "{\"type\":\"value\",\"title\":\"Contexte\",\"source\":\"thread.title\"}";
        WorkspaceLayout old = page(value + "," + form(CHOICE));
        WorkspaceLayout next = page(value.replace("thread.title", "profile.label") + ","
                + form(CHOICE.replace("Haute", "Urgente").replace("false", "true")));
        List<InstrumentChanges.Change> diff = InstrumentChanges.between(old, next);
        assertEquals(2, diff.size());
        assertEquals("Titre de la conversation", diff.get(0).before());
        assertEquals("Nom du profil", diff.get(0).after());
        assertTrue(diff.get(1).after().contains("Choix obligatoire\nNormale\nUrgente"));
    }
}
