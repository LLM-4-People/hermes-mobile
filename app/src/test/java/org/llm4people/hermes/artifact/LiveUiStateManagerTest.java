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
                "#1B4D89", true);

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
                List.of(new LiveUiPatch.WidgetItem("A", "1", null)), null, false));
        assertThrowsIo(() -> new LiveUiPatch.WidgetSpec(
                "w1", "Titre", "", "unknown_tpl",
                List.of(new LiveUiPatch.WidgetItem("A", "1", null)), null, true));
        assertThrowsIo(() -> new LiveUiPatch.WidgetSpec(
                "w1", "Titre", "", "metric",
                List.of(new LiveUiPatch.WidgetItem("A", "1", null)), "#808080", true));
        assertThrowsIo(() -> new LiveUiPatch.WidgetSpec(
                "w1", "Bearer abcdefghijklmnopqrstuvwx", "", "status",
                Collections.emptyList(), null, true));
        // Fork PR #15 allows 0..8 items + checked + accent_ink_hex
        try {
            LiveUiPatch.WidgetSpec emptyItemsOk = new LiveUiPatch.WidgetSpec(
                    "w_empty", "Statut calme", "Rien à signaler", "status",
                    Collections.emptyList(), "#0D6E6E", "#FFFFFF", true);
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

        // Fork PR #15 QuickAction destination mapping -> top-level native tabs
        try {
            assertEquals("nav_discussions", new LiveUiPatch.QuickAction("qa_d", "Discussions", null, null, "discussions", null).nativeActionId());
            assertEquals("nav_apps", new LiveUiPatch.QuickAction("qa_a", "Atelier", null, null, "atelier", null).nativeActionId());
            assertEquals("nav_settings", new LiveUiPatch.QuickAction("qa_c", "Cerveau", null, null, "cerveau", null).nativeActionId());
            assertEquals("nav_tasks", new LiveUiPatch.QuickAction("qa_act", "Activité", null, null, "activite", "bell").nativeActionId());
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

    @Test public void persistsAcrossDayNightSwitchesWithoutCorruption() throws Exception {
        MemoryStore store = new MemoryStore();
        MemorySurfaces surfaces = new MemorySurfaces();
        LiveUiPatch.StateManager dayManager = new LiveUiPatch.StateManager(store, surfaces, false);

        ThemePalette.OverrideSpec systemTeal = ThemePalette.OverrideSpec.validated(
                "system", "#0D6E6E", "comfortable", 0, "paper", false);
        LiveUiPatch.WidgetSpec widget = new LiveUiPatch.WidgetSpec(
                "briefing_matin", "Résumé du jour", "", "briefing",
                Collections.emptyList(), "#0D6E6E", true);
        LiveUiPatch rev1 = new LiveUiPatch("rev-day-1", "", "atelier", "Accent sarcelle",
                Arrays.asList(
                        new LiveUiPatch.Op.SetThemeOverride(systemTeal),
                        new LiveUiPatch.Op.UpsertWidget(widget)),
                true);
        dayManager.apply(rev1);

        // Reload in Dark mode -> must NOT become corrupted!
        LiveUiPatch.StateManager nightManager = new LiveUiPatch.StateManager(store, surfaces, true);
        assertFalse(nightManager.isCorrupted());
        assertEquals("#0D6E6E", nightManager.theme().accentHex);
        assertEquals(1, nightManager.widgets().size());
        assertTrue(nightManager.canRollback());

        // Apply a dark-authored system accent in Night mode and reload back in Day mode.
        ThemePalette.OverrideSpec systemMint = ThemePalette.OverrideSpec.validated(
                "system", "#A0D7BC", "comfortable", 0, "paper", true);
        LiveUiPatch rev2 = new LiveUiPatch("rev-night-2", "rev-day-1", "atelier", "Accent menthe",
                List.of(new LiveUiPatch.Op.SetThemeOverride(systemMint)), true);
        nightManager.apply(rev2);

        LiveUiPatch.StateManager dayAgain = new LiveUiPatch.StateManager(store, surfaces, false);
        assertFalse(dayAgain.isCorrupted());
        assertEquals("#A0D7BC", dayAgain.theme().accentHex);
        List<LiveUiPatch.StateManager.AppliedRevision> popped = dayAgain.rollbackAllTo("rev-day-1");
        assertEquals(2, popped.size());
        assertEquals("rev-night-2", popped.get(0).revisionId);
        assertEquals("rev-day-1", popped.get(1).revisionId);
        assertTrue(dayAgain.theme().isDefault());
        assertTrue(dayAgain.widgets().isEmpty());
    }

    @Test public void applyRollsBackPriorSurfacesAtomicallyWhenSecondSurfaceFails() throws Exception {
        MemoryStore store = new MemoryStore();
        Map<String, String> backing = new HashMap<>();
        backing.put("page/a", "{\"v\":0}");
        LiveUiPatch.StateManager.SurfaceAdapter flaky = new LiveUiPatch.StateManager.SurfaceAdapter() {
            @Override public String readCurrent(String surface) { return backing.getOrDefault(surface, ""); }
            @Override public void replace(String surface, String revisionId, String summary, String nextRaw) {
                if ("page/b".equals(surface) && !revisionId.startsWith("rollback:")) {
                    throw new IllegalStateException("Page library full");
                }
                if (nextRaw == null || nextRaw.isEmpty()) backing.remove(surface);
                else backing.put(surface, nextRaw);
            }
        };
        LiveUiPatch.StateManager manager = new LiveUiPatch.StateManager(store, flaky, false);
        LiveUiPatch patch = new LiveUiPatch("rev-atomic", "", "atelier", "Deux écrans",
                Arrays.asList(
                        new LiveUiPatch.Op.UpsertSurface("page/a", "{\"v\":1}", "A"),
                        new LiveUiPatch.Op.UpsertSurface("page/b", "{\"v\":1}", "B")),
                true);
        try {
            manager.apply(patch);
            fail("Expected failure on second surface");
        } catch (IllegalStateException expected) {
            // Surface A must have been restored to its prior value and history must stay empty.
            assertEquals("{\"v\":0}", backing.get("page/a"));
            assertFalse(backing.containsKey("page/b"));
            assertTrue(manager.history().isEmpty());
        }
    }

    @Test public void interactiveControlsPersistPerControlWithoutClobberingSiblings() {
        MemoryStore kv = new MemoryStore();
        InstrumentStateStore stateStore = new InstrumentStateStore(kv);
        InteractiveControlsBlock.persistBoolean(stateStore, "atelier", "page/shared", "toggle_a", true);
        InteractiveControlsBlock.persistNumber(stateStore, "atelier", "page/shared", "slider_b", 42.0);
        InteractiveControlsBlock.persistStringList(stateStore, "atelier", "page/shared", "chips_c", List.of("Travail"));

        // Updating one control preserves all sibling keys on the shared surface.
        InteractiveControlsBlock.persistBoolean(stateStore, "atelier", "page/shared", "toggle_a", false);
        assertFalse(stateStore.getBoolean("atelier", "page/shared", "toggle_a", true));
        assertEquals(42.0, stateStore.getNumber("atelier", "page/shared", "slider_b", 0.0), 0.001);
        assertEquals(List.of("Travail"), stateStore.getStringList("atelier", "page/shared", "chips_c", Collections.emptyList()));
    }

    @Test public void trimToSanitizesControlsBidiAndNeverSplitsSurrogatePairs() {
        assertEquals("", LiveUiPatch.trimTo(null, 16));
        assertEquals("", LiveUiPatch.trimTo("hello", 0));
        assertEquals("Line one Line two", LiveUiPatch.trimTo("Line\u202E\u034F one\n\rLine  two", 32));
        // Surrogate pair at boundary (max=5 with 4 ASCII chars + 2-char emoji) must not leave unpaired high surrogate.
        String bounded = LiveUiPatch.trimTo("abcd\uD83D\uDE00ef", 5);
        assertEquals("abcd", bounded);
        assertEquals("abcd\uD83D\uDE00", LiveUiPatch.trimTo("abcd\uD83D\uDE00ef", 6));
    }

    @Test public void validTextRejectsCgjInvisibleLabelsAndObfuscatedForbiddenPayloads() {
        assertFalse(LiveUiPatch.validText(null, 64));
        assertFalse(LiveUiPatch.validText("", 64));
        assertFalse(LiveUiPatch.validText("\u034F", 64));
        assertFalse(LiveUiPatch.validText("Titre\u034Fvalide", 64));
        assertFalse(LiveUiPatch.validText("Titre\u3164valide", 64));
        assertFalse(LiveUiPatch.validText("Titre\u115Fvalide", 64));
        assertFalse(LiveUiPatch.validText("Titre\u180Fvalide", 64));
        assertFalse(LiveUiPatch.validText("\uFE0F", 64));
        assertFalse(LiveUiPatch.validText("\u180F", 64));
        assertFalse(LiveUiPatch.validText("\u115F\u3164", 64));
        assertFalse(LiveUiPatch.validText("java\u034Fscript:alert(1)", 64));
        assertFalse(LiveUiPatch.validText("java\uFE0Fscript:alert(1)", 64));
        assertFalse(LiveUiPatch.validText("<\uFE0Fscript>alert(1)</script>", 64));
        assertFalse(LiveUiPatch.validText("file\u180F://etc/passwd", 64));

        assertTrue(LiveUiPatch.validText("Résumé du matin", 64));
        assertTrue(LiveUiPatch.validText("☕\uFE0F Pause", 64));
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
