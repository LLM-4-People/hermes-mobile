package org.llm4people.hermes.artifact;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class LiveUiStateManagerTest {
    private static final class MemoryStore implements InstrumentStateStore.BackingStore {
        final Map<String, String> kv = new HashMap<>();
        @Override public String read(String key) { return kv.get(key); }
        @Override public void write(String key, String value) { kv.put(key, value); }
        @Override public void remove(String key) { kv.remove(key); }
    }

    private static final class MemorySurfaces implements LiveUiPatch.StateManager.SurfaceAdapter {
        final Map<String, String> surfaces = new HashMap<>();
        @Override public String readCurrent(String surface) { return surfaces.getOrDefault(surface, ""); }
        @Override public void replace(String surface, String revisionId, String summary, String nextRaw) {
            if (nextRaw == null || nextRaw.isEmpty()) surfaces.remove(surface);
            else surfaces.put(surface, nextRaw);
        }
    }

    @Test public void applyPersistAndRollbackStateIntegrity() throws Exception {
        MemoryStore store = new MemoryStore();
        MemorySurfaces surfaces = new MemorySurfaces();
        LiveUiPatch.StateManager manager = new LiveUiPatch.StateManager(store, surfaces, false);

        ThemePalette.OverrideSpec blueTheme = ThemePalette.OverrideSpec.validated(
                "light", "#1B4D89", "compact", 1, "outlined", false);
        LiveUiPatch.WidgetSpec widget = new LiveUiPatch.WidgetSpec(
                "briefing_matin", "Résumé du jour", "2 tâches en cours", "briefing",
                List.of(new LiveUiPatch.WidgetItem("Focus", "Revue mobile", "Fais le point sur mes tâches")),
                "#1B4D89", true, false);

        LiveUiPatch rev1 = new LiveUiPatch("rev-001", "", "atelier", "Thème bleu et raccourcis",
                Arrays.asList(
                        new LiveUiPatch.Op.UpsertSurface("page/suivi", "{\"version\":2}", "Suivi"),
                        new LiveUiPatch.Op.UpsertTab(new LiveUiPatch.CustomTab("tab_suivi", "Suivi", "page/suivi")),
                        new LiveUiPatch.Op.UpsertQuickAction(new LiveUiPatch.QuickAction("qa_resume", "Résumer", "Résume ce fil en 3 points.", null)),
                        new LiveUiPatch.Op.SetThemeOverride(blueTheme),
                        new LiveUiPatch.Op.UpsertWidget(widget)),
                true);
        manager.apply(rev1);

        // Reload in a fresh manager (simulates restart) and verify full persistence.
        LiveUiPatch.StateManager reloaded = new LiveUiPatch.StateManager(store, surfaces, false);
        assertEquals("{\"version\":2}", surfaces.readCurrent("page/suivi"));
        assertEquals(1, reloaded.tabs().size());
        assertEquals("Suivi", reloaded.tabs().get(0).label);
        assertEquals(1, reloaded.quickActions().size());
        assertEquals("Résume ce fil en 3 points.", reloaded.quickActions().get(0).draft);
        assertEquals("#1B4D89", reloaded.theme().accentHex);
        assertEquals(1, reloaded.widgets().size());
        assertEquals("briefing_matin", reloaded.widgets().get(0).widgetId);
        assertTrue(reloaded.canRollback());

        // Apply a second patch modifying surface & resetting theme.
        LiveUiPatch rev2 = new LiveUiPatch("rev-002", "rev-001", "atelier", "Mise à jour du suivi",
                Arrays.asList(
                        new LiveUiPatch.Op.UpsertSurface("page/suivi", "{\"version\":2,\"v\":2}", "Suivi v2"),
                        new LiveUiPatch.Op.ResetTheme()),
                true);
        reloaded.apply(rev2);
        assertEquals("{\"version\":2,\"v\":2}", surfaces.readCurrent("page/suivi"));
        assertTrue(reloaded.theme().isDefault());
        assertEquals(2, reloaded.history().size());

        // 1-tap rollback of rev-002 restores rev-001 state exactly.
        reloaded.rollbackLast();
        assertEquals("{\"version\":2}", surfaces.readCurrent("page/suivi"));
        assertEquals("#1B4D89", reloaded.theme().accentHex);
        assertEquals(1, reloaded.history().size());

        // Rollback to rev-001 restores initial clean state.
        reloaded.rollbackTo("rev-001");
        assertEquals("", surfaces.readCurrent("page/suivi"));
        assertTrue(reloaded.tabs().isEmpty());
        assertTrue(reloaded.quickActions().isEmpty());
        assertTrue(reloaded.widgets().isEmpty());
        assertTrue(reloaded.theme().isDefault());
        assertFalse(reloaded.canRollback());
    }

    @Test public void rejectsInvalidQuickActionsTabsWidgetsAndPatchBounds() {
        // Quick action must have exactly one of draft or page (never both, never neither).
        assertThrowsIo(() -> new LiveUiPatch.QuickAction("qa_a", "Test", null, null));
        assertThrowsIo(() -> new LiveUiPatch.QuickAction("qa_a", "Test", "Brouillon", "page/x"));
        assertThrowsIo(() -> new LiveUiPatch.QuickAction("BAD ID", "Test", "Brouillon", null));
        assertThrowsIo(() -> new LiveUiPatch.QuickAction("qa_a", "Test", null, "https://evil.example"));

        // Widget must opt in explicitly with home_screen_safe: true and valid template + contrast + no secrets.
        assertThrowsIo(() -> new LiveUiPatch.WidgetSpec(
                "w1", "Titre", "", "metric",
                List.of(new LiveUiPatch.WidgetItem("A", "1", null)), null, false, false));
        assertThrowsIo(() -> new LiveUiPatch.WidgetSpec(
                "w1", "Titre", "", "unknown_tpl",
                List.of(new LiveUiPatch.WidgetItem("A", "1", null)), null, true, false));
        assertThrowsIo(() -> new LiveUiPatch.WidgetSpec(
                "w1", "Titre", "", "metric",
                List.of(new LiveUiPatch.WidgetItem("A", "1", null)), "#FFFF00", true, false));
        assertThrowsIo(() -> new LiveUiPatch.WidgetSpec(
                "w1", "Bearer abcdefghijklmnopqrstuvwx", "", "status",
                Collections.emptyList(), null, true, false));
        // Fork PR #15 allows 0..8 items + checked + accent_ink_hex
        try {
            LiveUiPatch.WidgetSpec emptyItemsOk = new LiveUiPatch.WidgetSpec(
                    "w_empty", "Statut calme", "Rien à signaler", "status",
                    Collections.emptyList(), "#0D6E6E", "#FFFFFF", true, false);
            assertEquals("#FFFFFF", emptyItemsOk.accentInkHex);
            assertTrue(emptyItemsOk.items.isEmpty());
            LiveUiPatch.WidgetItem checkedItem = new LiveUiPatch.WidgetItem("Courses", "", Boolean.TRUE, null);
            assertEquals(Boolean.TRUE, checkedItem.checked);
        } catch (IOException unexpected) {
            fail("Valid Fork PR #15 widget rejected: " + unexpected);
        }

        // UTF-8 byte cap check (65,536 bytes, counting multi-byte UTF-8 sequences properly)
        assertFalse(LiveUiPatch.exceedsByteLimit("a".repeat(LiveUiPatch.MAX_PATCH_BYTES)));
        assertTrue(LiveUiPatch.exceedsByteLimit("a".repeat(LiveUiPatch.MAX_PATCH_BYTES + 1)));
        assertTrue(LiveUiPatch.exceedsByteLimit("é".repeat(LiveUiPatch.MAX_PATCH_BYTES / 2 + 1)));

        // Fork PR #15 QuickAction destination mapping
        try {
            LiveUiPatch.QuickAction dest = new LiveUiPatch.QuickAction("qa_act", "Activité", null, null, "activite", "bell");
            assertEquals("reminders", dest.nativeActionId());
        } catch (IOException unexpected) {
            fail("Valid destination quick action rejected: " + unexpected);
        }
        assertThrowsIo(() -> new LiveUiPatch.QuickAction("qa_bad", "Bad", null, null, "unknown_dest", null));

        // Patch revision id & ops count bounds.
        assertThrowsIo(() -> new LiveUiPatch("", "", "thread", "Bad",
                List.of(new LiveUiPatch.Op.ResetTheme()), true));
        assertThrowsIo(() -> new LiveUiPatch("rev/../bad", "", "thread", "Bad",
                List.of(new LiveUiPatch.Op.ResetTheme()), true));
        assertThrowsIo(() -> new LiveUiPatch("rev-1", "", "thread", "Empty",
                Collections.emptyList(), true));

        // Suggested actions must specify exactly one valid target (draft, custom page, or allowlisted action).
        try {
            LiveUiPatch.SuggestedAction okDraft = new LiveUiPatch.SuggestedAction("Créer un rappel", "Crée un rappel pour demain matin.", null, null);
            assertEquals("Créer un rappel", okDraft.label);
            LiveUiPatch.SuggestedAction okPage = new LiveUiPatch.SuggestedAction("Ouvrir le suivi", null, "page/suivi", null);
            assertEquals("page/suivi", okPage.page);
            LiveUiPatch.SuggestedAction okAction = new LiveUiPatch.SuggestedAction("Voir mes rappels", null, null, "reminders");
            assertEquals("reminders", okAction.action);
        } catch (IOException unexpected) {
            fail("Valid suggested actions rejected: " + unexpected);
        }
        assertThrowsIo(() -> new LiveUiPatch.SuggestedAction("Aucun cible", null, null, null));
        assertThrowsIo(() -> new LiveUiPatch.SuggestedAction("Deux cibles", "Brouillon", "page/suivi", null));
        assertThrowsIo(() -> new LiveUiPatch.SuggestedAction("Action inconnue", null, null, "shell_exec"));
        assertThrowsIo(() -> new LiveUiPatch.SuggestedAction("Page invalide", null, "https://evil.example", null));
    }

    private interface IoRunnable { void run() throws IOException; }

    @Test public void failsClosedOnCorruptedStateUntilExplicitReset() throws Exception {
        MemoryStore store = new MemoryStore();
        MemorySurfaces surfaces = new MemorySurfaces();
        store.write("workspace.live_ui.v2", "CORRUPT\tLINE");

        LiveUiPatch.StateManager manager = new LiveUiPatch.StateManager(store, surfaces, false);
        assertTrue(manager.isCorrupted());
        assertFalse(manager.canRollback());

        LiveUiPatch patch = new LiveUiPatch("rev-new", "", "atelier", "Test",
                List.of(new LiveUiPatch.Op.ResetTheme()), true);
        assertThrowsIo(() -> manager.apply(patch));
        assertThrowsIo(manager::rollbackLast);
        // Corrupted record must still be intact (never silently wiped on apply).
        assertEquals("CORRUPT\tLINE", store.read("workspace.live_ui.v2"));

        // Only explicit user-confirmed reset clears corruption and allows new patches.
        manager.resetCorrupted();
        assertFalse(manager.isCorrupted());
        assertNull(store.read("workspace.live_ui.v2"));
        manager.apply(patch);
        assertTrue(manager.canRollback());
    }

    private static void assertThrowsIo(IoRunnable runnable) {
        try {
            runnable.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }
}
