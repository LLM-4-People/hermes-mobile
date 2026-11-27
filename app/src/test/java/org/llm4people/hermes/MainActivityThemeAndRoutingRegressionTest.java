package org.llm4people.hermes;

import org.junit.Test;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.hub.NotesTasksPanel;
import org.llm4people.hermes.presence.AppPresenceTracker;
import org.llm4people.hermes.presence.ContinuousLocationTracker;
import org.llm4people.hermes.presence.DeepAndroidActions;
import org.llm4people.hermes.presence.PresencePolicy;
import org.llm4people.hermes.push.DeliveryRouter;
import org.llm4people.hermes.push.PushPolicy;
import org.llm4people.hermes.settings.AppThemeMode;
import org.llm4people.hermes.settings.AppearanceSettingsModel;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM regression tests covering:
 * - R3.6.2 cross-profile ThemePalette polarity reconciliation against global AppThemeMode
 * - R3.6.3 NotesTasksPanel.Callbacks single-note open contract
 * - R3.4.2 DeliveryRouter unified route IDs and section taxonomy
 * - R3.4.5 SSoT theme-mode normalization and validation delegation
 */
public final class MainActivityThemeAndRoutingRegressionTest {

    @Test
    public void reconcileThemeForModeAlignsStaleProfilePolarityToGlobalTheme() throws Exception {
        // Profile B saved light theme + light Slate accent while global AppThemeMode is now dark.
        AppCustomization slateCustom = AppCustomization.defaults()
                .withPalette(AppCustomization.PalettePreset.SLATE, null);
        ThemePalette.OverrideSpec staleLight = ThemePalette.OverrideSpec.validated(
                "light", "#264B73", "comfortable", 0, "paper", false);

        ThemePalette.OverrideSpec darkReconciled = MainActivity.reconcileThemeForMode(
                slateCustom, staleLight, "dark", false);
        assertEquals("dark", darkReconciled.theme);
        assertNotNull(darkReconciled.accentHex);
        // Must pass WCAG AA contrast on dark surface without throwing.
        ThemePalette.validateAccentContrast(ThemePalette.parseHexColor(darkReconciled.accentHex), true);

        // Switching back from dark to light adapts accent back for light surface.
        ThemePalette.OverrideSpec lightReconciled = MainActivity.reconcileThemeForMode(
                slateCustom, darkReconciled, "light", true);
        assertEquals("light", lightReconciled.theme);
        assertNotNull(lightReconciled.accentHex);
        ThemePalette.validateAccentContrast(ThemePalette.parseHexColor(lightReconciled.accentHex), false);

        // Matching mode is a no-op returning the exact existing spec.
        assertEquals(lightReconciled, MainActivity.reconcileThemeForMode(
                slateCustom, lightReconciled, "light", false));
    }

    @Test
    public void buildThemeOverrideForModeAlwaysHonorsGlobalThemeModeEvenWhenExistingStale() throws Exception {
        AppCustomization terracotta = AppCustomization.defaults()
                .withPalette(AppCustomization.PalettePreset.TERRACOTTA, null)
                .withDensity(AppCustomization.Density.COMPACT)
                .withFontScaleDelta(0.10f);
        ThemePalette.OverrideSpec staleLight = ThemePalette.OverrideSpec.validated(
                "light", "#264B73", "spacious", -1, "outlined", false);

        ThemePalette.OverrideSpec spec = MainActivity.buildThemeOverrideForMode(
                terracotta, staleLight, "dark", false);
        assertEquals("dark", spec.theme);
        assertEquals("compact", spec.density);
        assertEquals(2, spec.fontScaleStep);
        assertEquals("outlined", spec.surfaceStyle);
        assertNotNull(spec.accentHex);
        ThemePalette.validateAccentContrast(ThemePalette.parseHexColor(spec.accentHex), true);

        // Negative / unexpected inputs: null custom, null existing, invalid global mode fail closed to "system".
        ThemePalette.OverrideSpec fallback = MainActivity.buildThemeOverrideForMode(
                null, null, "sepia-invalid", false);
        assertEquals("system", fallback.theme);
        assertNull(fallback.accentHex);
        assertEquals("comfortable", fallback.density);
    }

    @Test
    public void deliveryRouterRegistersCanonicalRoutesUnderModernSections() {
        RouteRegistry registry = new RouteRegistry();
        AtomicInteger inboxHits = new AtomicInteger();
        AtomicInteger policyHits = new AtomicInteger();
        DeliveryRouter.registerRoutes(registry, null, inboxHits::incrementAndGet, policyHits::incrementAndGet);

        assertTrue(registry.dispatch(DeliveryRouter.ROUTE_DELIVERIES));
        assertTrue(registry.dispatch(DeliveryRouter.ROUTE_PUSH_POLICY));
        assertEquals(1, inboxHits.get());
        assertEquals(1, policyHits.get());

        // Legacy French-prefixed route IDs must no longer be registered.
        assertFalse(registry.dispatch("activite/livraisons"));
        assertFalse(registry.dispatch("activite/preferences_notifications"));
    }

    @Test
    public void themeNormalizationAndValidationDelegateToSingleSourcesOfTruth() {
        for (String sample : new String[]{null, "", "   ", "system", "LIGHT", " dark ", "sepia", "auto"}) {
            String normalized = AppThemeMode.normalize(sample);
            int expectedLabel = switch (normalized) {
                case "light" -> R.string.appearance_theme_light;
                case "dark" -> R.string.appearance_theme_dark;
                default -> R.string.appearance_theme_system;
            };
            assertEquals(expectedLabel, AppearanceSettingsModel.themeModeLabelRes(sample));
        }

        // DeepAndroidActions must accept every ThemePalette.THEMES mode and reject unknown modes.
        AtomicReference<String> stored = new AtomicReference<>();
        AppCustomizationStore store = new AppCustomizationStore(new AppCustomizationStore.Backing() {
            @Override public String read() { return stored.get(); }
            @Override public void write(String value) { stored.set(value); }
            @Override public void remove() { stored.set(null); }
        });
        DeepAndroidActions actions = new DeepAndroidActions(
                new AppPresenceTracker(),
                new ContinuousLocationTracker(),
                store,
                new DeepAndroidActions.NudgeRateLimiter(),
                new DeepAndroidActions.HostBridge() {
                    @Override public boolean applyThemeMode(String themeMode) { return true; }
                });
        PresencePolicy off = PresencePolicy.defaults();
        PushPolicy push = PushPolicy.defaults();
        for (String mode : ThemePalette.THEMES) {
            DeepAndroidActions.Result ok = actions.execute(
                    "app_customize",
                    Map.of("operation", "apply", "theme", mode),
                    off, push, true, 10_000L, 600);
            assertEquals("Expected valid theme=" + mode, DeepAndroidActions.Status.EXECUTED, ok.status());
        }
        DeepAndroidActions.Result bad = actions.execute(
                "app_customize",
                Map.of("operation", "apply", "theme", "midnight"),
                off, push, true, 10_000L, 600);
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS, bad.status());
        assertEquals("invalid_theme_mode", bad.reasonCode());
    }

    @Test
    public void notesTasksCallbacksExposesDirectOpenNoteContract() throws Exception {
        Method openNote = NotesTasksPanel.Callbacks.class.getMethod("openNote", String.class);
        assertEquals(void.class, openNote.getReturnType());
        Method openAllNotes = NotesTasksPanel.Callbacks.class.getMethod("openAllNotes");
        assertEquals(void.class, openAllNotes.getReturnType());
    }
}
