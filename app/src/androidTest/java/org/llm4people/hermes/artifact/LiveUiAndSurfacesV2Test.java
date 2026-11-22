package org.llm4people.hermes.artifact;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.LinearLayout;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

@SuppressWarnings("deprecation")
public final class LiveUiAndSurfacesV2Test extends InstrumentationTestCase {
    private static final String V2_DASHBOARD = """
            {
              "version": 2,
              "surface": "page/tableau-suivi",
              "mode": "replace",
              "title": "Suivi hebdomadaire",
              "density": "spacious",
              "surface_style": "outlined",
              "accent": "#1B4D89",
              "blocks": [
                {
                  "type": "metric_grid",
                  "title": "Indicateurs",
                  "items": [
                    {"label": "Tâches terminées", "value": "12", "delta": "+3", "tone": "positive", "provenance": "Cette semaine"},
                    {"label": "Messages", "source": "thread.messageCount", "provenance": "Discussion active"}
                  ]
                },
                {
                  "type": "sparkline",
                  "title": "Progression",
                  "values": [4, 7, 9, 12],
                  "style": "line",
                  "unit": "tâches",
                  "caption": "4 derniers jours",
                  "target": 10
                },
                {
                  "type": "table",
                  "title": "Priorités",
                  "sortable": true,
                  "filterable": true,
                  "prompt": "Fais un point sur :",
                  "columns": [
                    {"key": "tache", "label": "Tâche"},
                    {"key": "effort", "label": "Minutes", "numeric": true}
                  ],
                  "rows": [
                    ["Revue de code", 30],
                    ["Courses", 15]
                  ]
                },
                {
                  "type": "controls",
                  "title": "Liste de contrôle",
                  "state_key": "suivi_hebdo",
                  "prompt": "Voici l’état de ma liste :",
                  "items": [
                    {"id": "tests_ok", "label": "Tests validés", "kind": "toggle", "default": false},
                    {"id": "focus", "label": "Temps prévu", "kind": "slider", "min": 15, "max": 120, "step": 15, "default": 45, "unit": "min"},
                    {"id": "categorie", "label": "Domaine", "kind": "chips", "options": ["Travail", "Maison", "Santé"], "multi": true, "default": ["Travail"]}
                  ]
                }
              ]
            }
            """;

    public void testParsesAndRendersRichSurfacesV2AndRejectsLowContrastAccent() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.parse(V2_DASHBOARD);
        assertEquals(2, layout.version);
        assertEquals("#1B4D89", layout.accent);
        assertEquals("spacious", layout.density);
        assertEquals(4, layout.blocks.size());
        assertEquals("metric_grid", layout.blocks.get(0).type);
        assertEquals(2, layout.blocks.get(0).metrics.size());
        assertEquals("sparkline", layout.blocks.get(1).type);
        assertEquals(4, layout.blocks.get(1).sparkline.values.size());
        assertEquals("table", layout.blocks.get(2).type);
        assertEquals(2, layout.blocks.get(2).table.rows.size());
        assertEquals("controls", layout.blocks.get(3).type);
        assertEquals(3, layout.blocks.get(3).controls.size());

        // Low-contrast accent in layout must be rejected strictly.
        try {
            WorkspaceLayout.parse(V2_DASHBOARD.replace("#1B4D89", "#FFFF00"));
            fail("Expected WCAG-AA rejection for low-contrast accent");
        } catch (IOException expected) {}

        Context context = getInstrumentation().getTargetContext();
        View preview = SurfaceRenderer.preview(context, layout);
        assertTrue(preview instanceof LinearLayout);
        assertTrue(((LinearLayout) preview).getChildCount() >= 4);
    }

    public void testLiveUiPatchValidationApplyRollbackAndProfileIsolation() throws Exception {
        Context app = getInstrumentation().getTargetContext();
        JSONObject validPatch = new JSONObject()
                .put("revision_id", "rev-101")
                .put("scope", "atelier")
                .put("summary", "Nouveau tableau de suivi et raccourci")
                .put("reversible", true)
                .put("requires_apply", true)
                .put("ops", new JSONArray()
                        .put(new JSONObject()
                                .put("op", "upsert_surface")
                                .put("surface", "page/tableau-suivi")
                                .put("layout", new JSONObject(V2_DASHBOARD)))
                        .put(new JSONObject()
                                .put("op", "upsert_tab")
                                .put("id", "suivi")
                                .put("label", "Suivi")
                                .put("page", "page/tableau-suivi"))
                        .put(new JSONObject()
                                .put("op", "upsert_quick_action")
                                .put("id", "bilan")
                                .put("label", "Mon bilan")
                                .put("draft", "Prépare mon bilan de la semaine."))
                        .put(new JSONObject()
                                .put("op", "set_theme_override")
                                .put("theme", "light")
                                .put("accent_hex", "#1B4D89")
                                .put("density", "compact")
                                .put("font_scale_step", 1)
                                .put("surface_style", "outlined"))
                        .put(new JSONObject()
                                .put("op", "upsert_widget")
                                .put("widget_id", "suivi_jour")
                                .put("title", "Suivi du jour")
                                .put("subtitle", "En cours")
                                .put("template", "metric")
                                .put("accent_hex", "#1B4D89")
                                .put("home_screen_safe", true)
                                .put("items", new JSONArray().put(
                                        new JSONObject().put("label", "Tâches").put("value", "12")))));

        LiveUiPatch parsed = LiveUiPatch.parse(validPatch, false);
        assertEquals("rev-101", parsed.revisionId);
        assertEquals(5, parsed.ops.size());

        JSONObject clearThemePatch = new JSONObject()
                .put("revision_id", "rev-102")
                .put("ops", new JSONArray().put(new JSONObject().put("op", "clear_theme_override")))
                .put("suggested_actions", new JSONArray().put(
                        new JSONObject().put("label", "Créer un rappel").put("action_draft", "Rappelle-moi demain à 9h.")));
        LiveUiPatch parsedClear = LiveUiPatch.parse(clearThemePatch, false);
        assertEquals(1, parsedClear.ops.size());
        assertEquals(1, parsedClear.suggestedActions.size());
        assertEquals("Rappelle-moi demain à 9h.", parsedClear.suggestedActions.get(0).draft);

        // Negative cases: oversized patch, non-reversible, low-contrast theme, auto-send quick action, unsafe widget.
        JSONObject oversized = new JSONObject(validPatch.toString())
                .put("summary", "a".repeat(LiveUiPatch.MAX_PATCH_BYTES + 10));
        assertPatchRejected(oversized);

        JSONObject nonReversible = new JSONObject(validPatch.toString()).put("reversible", false);
        assertPatchRejected(nonReversible);

        JSONObject autoSendAction = new JSONObject()
                .put("revision_id", "rev-bad-1")
                .put("ops", new JSONArray().put(new JSONObject()
                        .put("op", "upsert_quick_action")
                        .put("id", "send_now")
                        .put("label", "Envoyer")
                        .put("draft", "Salut")
                        .put("send", true)));
        assertPatchRejected(autoSendAction);

        JSONObject lowContrastTheme = new JSONObject()
                .put("revision_id", "rev-bad-2")
                .put("ops", new JSONArray().put(new JSONObject()
                        .put("op", "set_theme_override")
                        .put("theme", "light")
                        .put("accent_hex", "#EEEEEE")));
        assertPatchRejected(lowContrastTheme);

        JSONObject unsafeWidget = new JSONObject()
                .put("revision_id", "rev-bad-3")
                .put("ops", new JSONArray().put(new JSONObject()
                        .put("op", "upsert_widget")
                        .put("widget_id", "secret_w")
                        .put("title", "Privé")
                        .put("template", "status")
                        .put("home_screen_safe", false)
                        .put("items", new JSONArray().put(new JSONObject().put("label", "A").put("value", "B")))));
        assertPatchRejected(unsafeWidget);

        // Apply on Profile A -> verify encrypted persistence, LayoutHistory, Profile B isolation, and 1-tap rollback.
        try (WorkspaceAccountFixture profileA = new WorkspaceAccountFixture(app);
             WorkspaceAccountFixture profileB = new WorkspaceAccountFixture(app)) {
            LiveUiPatch.StateManager mgrA = LiveUiPatch.StateManager.forAccount(profileA.account, false);
            mgrA.apply(parsed);

            assertEquals(1, mgrA.tabs().size());
            assertEquals(1, mgrA.quickActions().size());
            assertEquals(1, mgrA.widgets().size());
            assertEquals("#1B4D89", mgrA.theme().accentHex);
            assertFalse(new LayoutHistory(profileA.account, "page/tableau-suivi").current().isEmpty());

            // Profile B remains untouched.
            LiveUiPatch.StateManager mgrB = LiveUiPatch.StateManager.forAccount(profileB.account, false);
            assertTrue(mgrB.tabs().isEmpty());
            assertTrue(mgrB.quickActions().isEmpty());
            assertTrue(mgrB.widgets().isEmpty());
            assertTrue(mgrB.theme().isDefault());
            assertEquals("", new LayoutHistory(profileB.account, "page/tableau-suivi").current());

            // Encrypted InstrumentStateStore persists across reopen and isolates Profile B.
            InstrumentStateStore stateA = InstrumentStateStore.forAccount(profileA.account);
            stateA.putBoolean("atelier", "suivi_hebdo", "tests_ok", true);
            stateA.putNumber("atelier", "suivi_hebdo", "focus", 90);
            stateA.putStringList("atelier", "suivi_hebdo", "categorie", Arrays.asList("Travail", "Santé"));

            try (Account reopenedA = profileA.reopen()) {
                InstrumentStateStore restoredA = InstrumentStateStore.forAccount(reopenedA);
                assertTrue(restoredA.getBoolean("atelier", "suivi_hebdo", "tests_ok", false));
                assertEquals(90.0, restoredA.getNumber("atelier", "suivi_hebdo", "focus", 0), 0.001);
                assertEquals(Arrays.asList("Travail", "Santé"),
                        restoredA.getStringList("atelier", "suivi_hebdo", "categorie", Collections.emptyList()));
            }
            assertFalse(InstrumentStateStore.forAccount(profileB.account)
                    .getBoolean("atelier", "suivi_hebdo", "tests_ok", false));

            // 1-tap rollback restores Profile A completely.
            mgrA.rollbackTo("rev-101");
            assertTrue(mgrA.tabs().isEmpty());
            assertTrue(mgrA.quickActions().isEmpty());
            assertTrue(mgrA.widgets().isEmpty());
            assertTrue(mgrA.theme().isDefault());
            assertEquals("", new LayoutHistory(profileA.account, "page/tableau-suivi").current());
        }
    }

    public void testQuickActionStripStagesEditableDraftWithoutSending() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        String[] staged = {""};
        String[] opened = {""};
        View strip = LiveUiPatchBar.quickActionStrip(
                context,
                Arrays.asList(
                        new LiveUiPatch.QuickAction("qa_draft", "Résumer", "Résume les points clés.", null),
                        new LiveUiPatch.QuickAction("qa_open", "Suivi", null, "page/tableau-suivi")),
                null,
                text -> staged[0] = text,
                page -> opened[0] = page);
        View draftChip = strip.findViewWithTag("quick-action-qa_draft");
        View pageChip = strip.findViewWithTag("quick-action-qa_open");
        assertNotNull(draftChip);
        assertNotNull(pageChip);
        draftChip.performClick();
        assertEquals("Résume les points clés.", staged[0]);
        assertEquals("", opened[0]);
        pageChip.performClick();
        assertEquals("page/tableau-suivi", opened[0]);

        String[] actionTriggered = {""};
        View suggestions = LiveUiPatchBar.suggestedActionsStrip(
                context,
                Arrays.asList(
                        new LiveUiPatch.SuggestedAction("Enregistrer", "Retiens cette préférence.", null, null),
                        new LiveUiPatch.SuggestedAction("Voir les rappels", null, null, "reminders")),
                null,
                text -> staged[0] = text,
                page -> opened[0] = page,
                act -> actionTriggered[0] = act);
        View s0 = suggestions.findViewWithTag("suggested-action-0");
        View s1 = suggestions.findViewWithTag("suggested-action-1");
        assertNotNull(s0);
        assertNotNull(s1);
        s0.performClick();
        assertEquals("Retiens cette préférence.", staged[0]);
        s1.performClick();
        assertEquals("reminders", actionTriggered[0]);
    }

    private static void assertPatchRejected(JSONObject raw) {
        try {
            LiveUiPatch.parse(raw, false);
            fail("Expected malformed ui.patch to be rejected");
        } catch (IOException expected) {}
    }
}
