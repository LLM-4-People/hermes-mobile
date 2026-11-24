package org.llm4people.hermes.artifact;

import android.content.Context;
import android.content.res.Configuration;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.LinearLayout;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.customization.InteractiveCardView;
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

        // Negative cases: oversized patch (>65,536 UTF-8 bytes), non-reversible, low-contrast theme, auto-send quick action, unsafe widget.
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

    public void testParsesExactForkPr15CanonicalWireContractAndProtectsDamagedLayouts() throws Exception {
        JSONObject forkPayload = new JSONObject("""
                {
                  "revision_id": "rev-fork-01",
                  "parent_revision_id": null,
                  "scope": "atelier",
                  "summary": "Tableau de bord quotidien et widget d’accueil",
                  "reversible": true,
                  "requires_apply": true,
                  "ops": [
                    {
                      "op": "upsert_surface",
                      "surface_id": "suivi_jour",
                      "scope": "atelier",
                      "kind": "kpi_grid",
                      "title": "Mon suivi du jour",
                      "order": 1,
                      "tab": "suivi",
                      "spec": {
                        "items": [
                          {"label": "Focus", "value": "3/4", "delta": "+1", "tone": "positive"}
                        ]
                      }
                    },
                    {
                      "op": "upsert_quick_action",
                      "action_id": "ouvrir_activite",
                      "label": "Voir mes rappels",
                      "action_kind": "open_destination",
                      "icon": "bell",
                      "destination": "activite"
                    },
                    {
                      "op": "upsert_quick_action",
                      "action_id": "ouvrir_suivi",
                      "label": "Mon suivi",
                      "action_kind": "toggle_surface",
                      "surface_id": "suivi_jour"
                    },
                    {
                      "op": "set_theme_override",
                      "theme": {
                        "accent_hex": "#0D6E6E",
                        "accent_ink_hex": "#FFFFFF",
                        "surface_hex": "#FAF8F5",
                        "ink_hex": "#1A1917",
                        "density": "comfortable",
                        "font_scale_step": 3,
                        "surface_style": "glass"
                      }
                    },
                    {
                      "op": "upsert_widget",
                      "widget_id": "briefing_matin",
                      "title": "Briefing matinal de votre journée avec résumé synthétique",
                      "subtitle": "2 actions prioritaires",
                      "template": "checklist",
                      "accent_hex": "#0D6E6E",
                      "accent_ink_hex": "#FFFFFF",
                      "home_screen_safe": true,
                      "items": [
                        {"label": "Préparer la réunion", "checked": true},
                        {"label": "Acheter du café", "checked": false, "action_draft": "Ajoute café à ma liste"}
                      ]
                    }
                  ],
                  "suggested_actions": [
                    {"id": "epingle_w", "label": "Épingler le widget", "kind": "pin_widget", "widget_id": "briefing_matin"},
                    {"id": "ouvre_s", "label": "Ouvrir le suivi", "kind": "open_surface", "target": "suivi_jour"},
                    {"id": "rappel", "label": "Créer un rappel", "kind": "create_reminder", "prompt": "Rappelle-moi à 9h"}
                  ]
                }
                """);

        LiveUiPatch parsed = LiveUiPatch.parse(forkPayload, false);
        assertEquals("rev-fork-01", parsed.revisionId);
        // upsert_surface with tab expands cleanly to UpsertSurface + UpsertTab -> 6 ops total
        assertEquals(6, parsed.ops.size());
        assertEquals(3, parsed.suggestedActions.size());
        assertEquals("briefing_matin", parsed.suggestedActions.get(0).widgetId);
        assertEquals("page/suivi_jour", parsed.suggestedActions.get(1).page);
        assertEquals("Rappelle-moi à 9h", parsed.suggestedActions.get(2).draft);

        Context app = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(app)) {
            LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(fixture.account, false);
            manager.apply(parsed);
            assertEquals(1, manager.tabs().size());
            assertEquals(2, manager.quickActions().size());
            assertEquals("nav_tasks", manager.quickActions().get(0).nativeActionId());
            assertEquals("page/suivi_jour", manager.quickActions().get(1).page);
            assertEquals(1, manager.widgets().size());
            assertEquals(Boolean.TRUE, manager.widgets().get(0).items.get(0).checked);
            assertEquals("#0D6E6E", manager.theme().accentHex);
            assertEquals("glass", manager.theme().surfaceStyle);
            assertEquals(3, manager.theme().fontScaleStep);

            // Damaged native layout record must NOT be silently overwritten by apply().
            fixture.account.write("workspace.v1.home", "{corrupt");
            LiveUiPatch touchHome = new LiveUiPatch("rev-home", "", "atelier", "Modifier accueil",
                    Collections.singletonList(new LiveUiPatch.Op.RemoveSurface("home")), true);
            try {
                manager.apply(touchHome);
                fail("Expected damaged LayoutHistory to fail closed");
            } catch (LayoutHistory.Unreadable expected) {
                assertEquals("{corrupt", fixture.account.read("workspace.v1.home"));
            }
        }
    }

    public void testInfersOmittedSuggestedActionTargetsAndAllowsExpandedSurfaceTabsUpToMaxOps() throws Exception {
        // Forgiving Fork small-LLM chip without explicit widget_id/target infers from ops; orphan chip is skipped cleanly.
        JSONObject inferred = new JSONObject("""
                {
                  "revision_id": "rev-infer-01",
                  "ops": [
                    {
                      "op": "upsert_surface",
                      "surface_id": "plan_jour",
                      "scope": "atelier",
                      "kind": "markdown_card",
                      "title": "Plan du jour",
                      "tab": "plan",
                      "spec": {"markdown": "Étape 1"}
                    },
                    {
                      "op": "upsert_widget",
                      "widget_id": "widget_plan",
                      "title": "Plan",
                      "template": "status",
                      "home_screen_safe": true,
                      "items": []
                    }
                  ],
                  "suggested_actions": [
                    {"id": "epingler", "label": "Épingler ce widget", "kind": "pin_widget"},
                    {"id": "ouvrir", "label": "Ouvrir l’écran", "kind": "open_surface"}
                  ]
                }
                """);
        LiveUiPatch parsed = LiveUiPatch.parse(inferred, true);
        assertEquals(2, parsed.suggestedActions.size());
        assertEquals("widget_plan", parsed.suggestedActions.get(0).widgetId);
        assertEquals("page/plan_jour", parsed.suggestedActions.get(1).page);

        // Orphan pin_widget with no widget op in patch is skipped without rejecting the patch.
        JSONObject orphanChip = new JSONObject("""
                {
                  "revision_id": "rev-orphan-01",
                  "ops": [{"op": "clear_theme_override"}],
                  "suggested_actions": [
                    {"id": "epingler", "label": "Épingler ce widget", "kind": "pin_widget"},
                    {"id": "ok", "label": "Continuer", "kind": "prompt", "prompt": "Continue"}
                  ]
                }
                """);
        LiveUiPatch parsedOrphan = LiveUiPatch.parse(orphanChip, false);
        assertEquals(1, parsedOrphan.suggestedActions.size());
        assertEquals("Continue", parsedOrphan.suggestedActions.get(0).draft);

        // 10 raw upsert_surface ops with "tab" expand to 20 internal ops and must be accepted (rawOps <= MAX_OPS=16).
        JSONArray tenTabbed = new JSONArray();
        for (int i = 0; i < 10; i++) {
            tenTabbed.put(new JSONObject()
                    .put("op", "upsert_surface")
                    .put("surface_id", "surf_" + i)
                    .put("scope", "atelier")
                    .put("kind", "markdown_card")
                    .put("title", "Écran " + i)
                    .put("tab", "tab_" + i)
                    .put("spec", new JSONObject().put("markdown", "Contenu " + i)));
        }
        LiveUiPatch expanded = LiveUiPatch.parse(new JSONObject()
                .put("revision_id", "rev-10-tabs")
                .put("ops", tenTabbed), false);
        assertEquals(20, expanded.ops.size());
    }

    public void testInstrumentChangesDetectsAllV2BlockAndThemeDifferences() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        WorkspaceLayout base = WorkspaceLayout.parse(V2_DASHBOARD);
        assertTrue(InstrumentChanges.between(context, base, base).isEmpty());

        // Accent & surface_style diff
        WorkspaceLayout themed = WorkspaceLayout.parse(
                V2_DASHBOARD.replace("#1B4D89", "#225C50").replace("\"outlined\"", "\"tinted\""));
        assertFalse(InstrumentChanges.between(context, base, themed).isEmpty());

        // Metric grid diff
        WorkspaceLayout metricChanged = WorkspaceLayout.parse(V2_DASHBOARD.replace("\"12\"", "\"18\""));
        assertFalse(InstrumentChanges.between(context, base, metricChanged).isEmpty());

        // Sparkline diff
        WorkspaceLayout sparkChanged = WorkspaceLayout.parse(V2_DASHBOARD.replace("[4, 7, 9, 12]", "[4, 7, 9, 20]"));
        assertFalse(InstrumentChanges.between(context, base, sparkChanged).isEmpty());

        // Table diff
        WorkspaceLayout tableChanged = WorkspaceLayout.parse(V2_DASHBOARD.replace("\"Courses\"", "\"Achats\""));
        assertFalse(InstrumentChanges.between(context, base, tableChanged).isEmpty());

        // Controls diff
        WorkspaceLayout controlsChanged = WorkspaceLayout.parse(V2_DASHBOARD.replace("\"default\": 45", "\"default\": 60"));
        assertFalse(InstrumentChanges.between(context, base, controlsChanged).isEmpty());

        // Nearby places category / radius / limit diff
        WorkspaceLayout nearbyA = WorkspaceLayout.parse(
                "{\"version\":2,\"surface\":\"page/lieux\",\"mode\":\"replace\",\"title\":\"Lieux\",\"blocks\":[{\"type\":\"nearby_places\",\"category\":\"cafe\",\"radius_m\":1500,\"limit\":12}]}");
        WorkspaceLayout nearbyB = WorkspaceLayout.parse(
                "{\"version\":2,\"surface\":\"page/lieux\",\"mode\":\"replace\",\"title\":\"Lieux\",\"blocks\":[{\"type\":\"nearby_places\",\"category\":\"cafe\",\"radius_m\":3000,\"limit\":20}]}");
        assertFalse(InstrumentChanges.between(context, nearbyA, nearbyB).isEmpty());
    }


    public void testUiPatchDeliveryWithoutRevisionOrSummaryUsesDeliveryEnvelope() throws Exception {
        JSONArray ops = new JSONArray().put(new JSONObject()
                .put("op", "upsert_quick_action").put("id", "bilan")
                .put("label", "Mon bilan").put("draft", "Prépare mon bilan de la semaine."));
        JSONObject flat = new JSONObject().put("ops", ops);
        try {
            LiveUiPatch.parse(flat, false);
            fail("A delivery payload without revision_id must not parse by itself");
        } catch (IOException expected) { }
        LiveUiPatch patch = LiveUiPatch.parse(
                LiveUiDeliveryDefaults.apply(flat, "dlv_0123456789abcdef", "Nouveau raccourci"), false);
        assertEquals("dlv_0123456789abcdef", patch.revisionId);
        assertEquals("Nouveau raccourci", patch.summary);
        assertFalse(flat.has("revision_id"));

        JSONObject nested = new JSONObject().put("patch", new JSONObject().put("ops", ops).put("summary", "Gardé"));
        LiveUiPatch fromNested = LiveUiPatch.parse(
                LiveUiDeliveryDefaults.apply(nested, "dlv_fedcba9876543210", "Titre"), false);
        assertEquals("dlv_fedcba9876543210", fromNested.revisionId);
        assertEquals("Gardé", fromNested.summary);

        JSONObject invalid = new JSONObject().put("ops", ops).put("revision_id", "bad id/../x");
        try {
            LiveUiPatch.parse(LiveUiDeliveryDefaults.apply(invalid, "dlv_0123456789abcdef", "Titre"), false);
            fail("An invalid present revision_id must still be rejected");
        } catch (IOException expected) { }
    }

    public void testLiveUiPatchDelegatesSecretDetectionAndSaturatesMapCompassParams() throws Exception {
        assertTrue(LiveUiPatch.containsSecret("sk-\u200B1234567890abcdef"));
        assertTrue(LiveUiPatch.containsSecret("gho_AbCdEf1234567890"));
        assertTrue(LiveUiPatch.containsSecret("eyJhbGciOiJIUzI1NiJ9.payload"));
        assertFalse(LiveUiPatch.containsSecret("Mon tableau du jour"));

        // map_compass overflow radius_m / limit must saturate cleanly (and fail WorkspaceLayout bounds instead of wrapping).
        JSONObject overflowCompass = new JSONObject()
                .put("revision_id", "rev-compass-overflow")
                .put("scope", "atelier")
                .put("summary", "Compass bounds")
                .put("ops", new JSONArray().put(new JSONObject()
                        .put("op", "upsert_surface")
                        .put("surface_id", "compass_test")
                        .put("kind", "map_compass")
                        .put("title", "Autour")
                        .put("spec", new JSONObject()
                                .put("category", "cafe")
                                .put("radius_m", 4_294_967_296L)
                                .put("limit", 4_294_967_296L))));
        assertPatchRejected(overflowCompass);
    }

    public void testHighlightsReturnLocalizedOpDescriptions() throws Exception {
        Context base = getInstrumentation().getTargetContext();
        Configuration frConfig = new Configuration(base.getResources().getConfiguration());
        frConfig.setLocale(Locale.FRENCH);
        Context frContext = base.createConfigurationContext(frConfig);
        Configuration enConfig = new Configuration(base.getResources().getConfiguration());
        enConfig.setLocale(Locale.ENGLISH);
        Context enContext = base.createConfigurationContext(enConfig);

        LiveUiPatch.WidgetSpec widget = new LiveUiPatch.WidgetSpec(
                "w_daily", "Daily Focus", "2 items", "checklist",
                Collections.singletonList(new LiveUiPatch.WidgetItem("Check build", "09:00", null)),
                null, true);
        ThemePalette.OverrideSpec themeOverride = ThemePalette.OverrideSpec.validated(
                "light", "#1B4D89", "compact", 1, "outlined", false);
        List<LiveUiPatch.Op> ops = Arrays.asList(
                new LiveUiPatch.Op.UpsertSurface("page/focus", "{\"version\":2}", "Focus"),
                new LiveUiPatch.Op.RemoveSurface("page/old"),
                new LiveUiPatch.Op.UpsertTab(new LiveUiPatch.CustomTab("tab_focus", "Focus", "page/focus")),
                new LiveUiPatch.Op.RemoveTab("tab_old"),
                new LiveUiPatch.Op.UpsertQuickAction(
                        new LiveUiPatch.QuickAction("qa_focus", "Summarize", "Summarize this.", null)),
                new LiveUiPatch.Op.RemoveQuickAction("qa_old"),
                new LiveUiPatch.Op.SetThemeOverride(themeOverride),
                new LiveUiPatch.Op.ResetTheme(),
                new LiveUiPatch.Op.UpsertWidget(widget),
                new LiveUiPatch.Op.RemoveWidget("w_old"));
        InteractiveCardView.Spec card = InteractiveCardView.Spec.checklist(
                "card_release", "Release checklist", "Verify before tag",
                Collections.singletonList(new InteractiveCardView.ChecklistItem("item_1", "Run tests", false)));
        LiveUiPatch patch = new LiveUiPatch(
                "rev-highlights-01", "", "atelier", "Localized preview",
                ops, Collections.emptyList(), Collections.singletonList(card), true, LiveUiPatch.MAX_OPS);

        List<String> frHighlights = patch.highlights(frContext);
        List<String> enHighlights = patch.highlights(enContext);
        assertEquals(ops.size() + 1, frHighlights.size());
        assertEquals(ops.size() + 1, enHighlights.size());

        for (int i = 0; i < ops.size(); i++) {
            assertEquals("FR highlight[" + i + "] must match localized Op.describe(frContext)",
                    ops.get(i).describe(frContext), frHighlights.get(i));
            assertEquals("EN highlight[" + i + "] must match localized Op.describe(enContext)",
                    ops.get(i).describe(enContext), enHighlights.get(i));
            assertFalse("FR and EN highlight[" + i + "] must diverge for " + ops.get(i).kind(),
                    frHighlights.get(i).equals(enHighlights.get(i)));
        }
        assertFalse("ResetTheme FR highlight must be localized, never hardcoded 'default'",
                "default".equals(frHighlights.get(7)));
        assertFalse("ResetTheme EN highlight must be localized, never hardcoded 'default'",
                "default".equals(enHighlights.get(7)));
        assertEquals("Release checklist", frHighlights.get(ops.size()));
        assertEquals("Release checklist", enHighlights.get(ops.size()));

        try {
            enHighlights.add("x");
            fail("highlights() must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {}
    }
}
