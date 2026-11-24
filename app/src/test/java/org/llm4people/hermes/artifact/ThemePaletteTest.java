package org.llm4people.hermes.artifact;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public final class ThemePaletteTest {
    @Test public void designTokensMeetWcagAaContrastInDayAndNight() {
        assertTrue(ThemePalette.contrastRatio(ThemePalette.DAY_INK, ThemePalette.DAY_PAPER) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(ThemePalette.DAY_MUTED, ThemePalette.DAY_PAPER) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(ThemePalette.DAY_ACCENT, ThemePalette.DAY_PAPER) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(ThemePalette.DAY_ON_ACCENT, ThemePalette.DAY_ACCENT) >= ThemePalette.WCAG_AA_NORMAL);

        assertTrue(ThemePalette.contrastRatio(ThemePalette.NIGHT_INK, ThemePalette.NIGHT_PAPER) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(ThemePalette.NIGHT_MUTED, ThemePalette.NIGHT_PAPER) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(ThemePalette.NIGHT_ACCENT, ThemePalette.NIGHT_PAPER) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(ThemePalette.NIGHT_ON_ACCENT, ThemePalette.NIGHT_ACCENT) >= ThemePalette.WCAG_AA_NORMAL);
    }

    @Test public void acceptsHighContrastAccentsAndPersistsRoundtrip() throws Exception {
        ThemePalette.OverrideSpec daySpec = ThemePalette.OverrideSpec.validated(
                "light", "#1b4d89", "spacious", 1, "outlined", false);
        assertEquals("#1B4D89", daySpec.accentHex);
        assertEquals("spacious", daySpec.density);
        assertEquals(1, daySpec.fontScaleStep);
        assertEquals("outlined", daySpec.surfaceStyle);

        ThemePalette.OverrideSpec decoded = ThemePalette.OverrideSpec.decode(daySpec.encode(), false);
        assertEquals(daySpec, decoded);

        ThemePalette resolved = ThemePalette.resolve(daySpec, false);
        assertEquals(0xFF1B4D89, resolved.accent);
        assertEquals(18, resolved.blockVerticalPaddingDp);
        assertEquals(18f, resolved.scaledSp(16f), 0.001f);

        ThemePalette.OverrideSpec nightSpec = ThemePalette.OverrideSpec.validated(
                "dark", "#9fd5ff", "compact", -1, "tinted", true);
        assertEquals("#9FD5FF", nightSpec.accentHex);
        ThemePalette nightResolved = ThemePalette.resolve(nightSpec, true);
        assertTrue(nightResolved.night);
        assertEquals(5, nightResolved.blockVerticalPaddingDp);
    }

    @Test public void rejectsLowContrastAndInvalidColorsStrictly() {
        // Low-contrast light colors on day paper (#F7F5EF)
        for (String lowContrastDay : new String[]{"#FFFF00", "#CCCCCC", "#A0D7BC", "#F7F5EF", "#99CCBB"}) {
            try {
                ThemePalette.OverrideSpec.validated("light", lowContrastDay, "comfortable", 0, "paper", false);
                fail("Expected WCAG-AA rejection for day accent " + lowContrastDay);
            } catch (ThemePalette.ContrastException expected) {
                assertTrue(expected.ratio < ThemePalette.WCAG_AA_NORMAL);
            } catch (IOException other) {
                fail("Expected ContrastException, got " + other);
            }
        }

        // Low-contrast dark colors on night paper (#141D19)
        for (String lowContrastNight : new String[]{"#141D19", "#22332B", "#225C50", "#333333"}) {
            try {
                ThemePalette.OverrideSpec.validated("dark", lowContrastNight, "comfortable", 0, "paper", true);
                fail("Expected WCAG-AA rejection for night accent " + lowContrastNight);
            } catch (ThemePalette.ContrastException expected) {
                assertTrue(expected.ratio < ThemePalette.WCAG_AA_NORMAL);
            } catch (IOException other) {
                fail("Expected ContrastException, got " + other);
            }
        }

        // Malformed hex strings
        for (String invalidHex : new String[]{"#FFF", "#12345678", "225C50", "#GGGGGG", " #225C50", "#225C50 ", "red"}) {
            try {
                ThemePalette.parseHexColor(invalidHex);
                fail("Expected hex rejection for " + invalidHex);
            } catch (IOException expected) {
                // expected
            }
        }

        // Out-of-bounds density / font scale (-2..3) / surface style / theme
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("neon", null, "comfortable", 0, "paper", false));
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("light", null, "ultra", 0, "paper", false));
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("light", null, "comfortable", 4, "paper", false));
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("light", null, "comfortable", -3, "paper", false));
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("light", null, "comfortable", 0, "neon_card", false));
        // Custom surface/ink WCAG AA check
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("light", null, null, "#FFFFFF", "#EEEEEE", "comfortable", 0, "paper", false));
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("light", "#1B4D89", "#224466", null, null, "comfortable", 0, "paper", false));
    }

    @Test public void adaptsSystemDayAccentForDarkModeWhileGuaranteeingWcagAa() throws Exception {
        ThemePalette.OverrideSpec systemBlue = ThemePalette.OverrideSpec.validated(
                "system", "#1B4D89", "comfortable", 3, "glass", false);
        ThemePalette day = ThemePalette.resolve(systemBlue, false);
        assertEquals(0xFF1B4D89, day.accent);
        assertEquals("glass", day.surfaceStyle);

        ThemePalette night = ThemePalette.resolve(systemBlue, true);
        assertTrue(night.night);
        assertNotEquals(ThemePalette.NIGHT_ACCENT, night.accent);
        assertTrue(ThemePalette.contrastRatio(night.accent, night.paper) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(night.accent, night.surface) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(night.onAccent, night.accent) >= ThemePalette.WCAG_AA_NORMAL);
    }

    @Test public void acceptsAllForkSystemAccentPresetsInDarkAndDayAndRejectsMidGrey() throws Exception {
        String[] forkPresets = {"#0D6E6E", "#1B6B3A", "#1D4ED8", "#92400E", "#B83227", "#4338CA", "#334155"};
        for (String hex : forkPresets) {
            ThemePalette.OverrideSpec inDay = ThemePalette.OverrideSpec.validated(
                    "system", hex, "comfortable", 0, "paper", false);
            ThemePalette.OverrideSpec inNight = ThemePalette.OverrideSpec.validated(
                    "system", hex, "comfortable", 0, "paper", true);
            assertEquals(hex, inDay.accentHex);
            assertEquals(inDay, inNight);
            // Persisted system spec must decode identically across Day <-> Night transitions.
            assertEquals(inDay, ThemePalette.OverrideSpec.decode(inDay.encode(), true));
            assertEquals(inNight, ThemePalette.OverrideSpec.decode(inNight.encode(), false));
            ThemePalette resolvedNight = ThemePalette.resolve(inNight, true);
            assertTrue(ThemePalette.contrastRatio(resolvedNight.accent, resolvedNight.paper) >= ThemePalette.WCAG_AA_NORMAL);
            assertTrue(ThemePalette.contrastRatio(resolvedNight.onAccent, resolvedNight.accent) >= ThemePalette.WCAG_AA_NORMAL);
        }
        // Mid-grey fails WCAG-AA in both day and night and must be rejected even under "system".
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("system", "#808080", "comfortable", 0, "paper", false));
        assertInvalid(() -> ThemePalette.OverrideSpec.validated("system", "#808080", "comfortable", 0, "paper", true));
    }

    @Test public void customSurfaceHexUsesSurfacePolarityForSemanticTokens() throws Exception {
        // Light custom surface on dark-mode device must pick day semantic tokens (>= 4.5:1).
        ThemePalette.OverrideSpec lightOnDark = ThemePalette.OverrideSpec.validated(
                "system", "#0D6E6E", "#FFFFFF", "#FAF8F5", "#1A1917", "comfortable", 0, "paper", true);
        ThemePalette resolvedLight = ThemePalette.resolve(lightOnDark, true);
        assertTrue(ThemePalette.contrastRatio(resolvedLight.muted, resolvedLight.paper) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(resolvedLight.danger, resolvedLight.paper) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(resolvedLight.warning, resolvedLight.paper) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(resolvedLight.positive, resolvedLight.paper) >= ThemePalette.WCAG_AA_NORMAL);

        // Dark custom surface on day-mode device must pick night semantic tokens (>= 4.5:1).
        ThemePalette.OverrideSpec darkOnLight = ThemePalette.OverrideSpec.validated(
                "system", "#A0D7BC", "#102F23", "#18181B", "#F4F4F5", "comfortable", 0, "paper", false);
        ThemePalette resolvedDark = ThemePalette.resolve(darkOnLight, false);
        assertTrue(ThemePalette.contrastRatio(resolvedDark.muted, resolvedDark.paper) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(resolvedDark.danger, resolvedDark.paper) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(resolvedDark.warning, resolvedDark.paper) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(resolvedDark.positive, resolvedDark.paper) >= ThemePalette.WCAG_AA_NORMAL);
    }

    private interface Action { void run() throws IOException; }

    private static void assertInvalid(Action action) {
        try {
            action.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }


    @Test public void singleSurfaceOrInkSystemOverrideSurvivesDayNightSwitch() throws Exception {
        // surface_hex only, applied in day mode, must reload in night mode (was StateManager corruption).
        ThemePalette.OverrideSpec surfaceOnly = ThemePalette.OverrideSpec.validated(
                "system", null, null, "#FAF8F5", null, "comfortable", 0, "paper", false);
        assertEquals(surfaceOnly, ThemePalette.OverrideSpec.decode(surfaceOnly.encode(), true));
        assertEquals(surfaceOnly, ThemePalette.OverrideSpec.validated(
                "system", null, null, "#FAF8F5", null, "comfortable", 0, "paper", true));
        for (boolean night : new boolean[]{false, true}) {
            ThemePalette resolved = ThemePalette.resolve(surfaceOnly, night);
            assertEquals(0xFFFAF8F5, resolved.surface);
            assertEquals(ThemePalette.DAY_INK, resolved.ink);
            assertTrue(ThemePalette.contrastRatio(resolved.ink, resolved.surface) >= ThemePalette.WCAG_AA_NORMAL);
        }

        // ink_hex only: valid in either mode, and resolve never pairs it with an unreadable surface.
        ThemePalette.OverrideSpec inkOnly = ThemePalette.OverrideSpec.validated(
                "system", null, null, null, "#1A1917", "comfortable", 0, "paper", true);
        assertEquals(inkOnly, ThemePalette.OverrideSpec.decode(inkOnly.encode(), false));
        assertEquals(0xFF1A1917, ThemePalette.resolve(inkOnly, false).ink);
        ThemePalette inkNight = ThemePalette.resolve(inkOnly, true);
        assertEquals(ThemePalette.NIGHT_INK, inkNight.ink);
        assertTrue(ThemePalette.contrastRatio(inkNight.ink, inkNight.surface) >= ThemePalette.WCAG_AA_NORMAL);
    }

    @Test public void singleColorOverrideStillRejectsUnreadableAndExplicitModeMismatch() throws Exception {
        for (boolean night : new boolean[]{false, true}) {
            // Neither day nor night ink reaches 4.5:1 on mid grey, and grey ink fails on both surfaces.
            assertContrastRejected(() -> ThemePalette.OverrideSpec.validated(
                    "system", null, null, "#777777", null, "comfortable", 0, "paper", night));
            assertContrastRejected(() -> ThemePalette.OverrideSpec.validated(
                    "system", null, null, null, "#808080", "comfortable", 0, "paper", night));
        }
        // Surface-only override uses bestInk(effSurface) in both explicit and system modes, matching resolve().
        ThemePalette.OverrideSpec darkSurfaceInLight = ThemePalette.OverrideSpec.validated(
                "light", null, null, "#1C2823", null, "comfortable", 0, "paper", false);
        ThemePalette resolvedDarkSurface = ThemePalette.resolve(darkSurfaceInLight, false);
        assertEquals(ThemePalette.NIGHT_INK, resolvedDarkSurface.ink);
        assertTrue(ThemePalette.contrastRatio(resolvedDarkSurface.ink, resolvedDarkSurface.surface) >= ThemePalette.WCAG_AA_NORMAL);

        // Explicit mode mismatch on ink-only or explicit surface+ink pair is strictly rejected.
        assertContrastRejected(() -> ThemePalette.OverrideSpec.validated(
                "light", null, null, null, "#F2F1E8", "comfortable", 0, "paper", false));
        assertContrastRejected(() -> ThemePalette.OverrideSpec.validated(
                "light", null, null, "#1C2823", "#14231E", "comfortable", 0, "paper", false));
        assertContrastRejected(() -> ThemePalette.OverrideSpec.validated(
                "light", null, null, "#777777", null, "comfortable", 0, "paper", false));
    }

    @Test public void liftForDarkSurfaceClearsWcagAaAgainstBothDarkSurfaceAndOnAccent() {
        for (String hex : new String[]{"#0000AA", "#0011EE", "#1B5E50", "#000000", "#8E3B2E"}) {
            int rgb = org.llm4people.hermes.customization.AppCustomization.parseRgb(hex);
            int liftedRgb = org.llm4people.hermes.customization.AppCustomization.liftForDarkSurface(rgb);
            int liftedArgb = 0xFF000000 | liftedRgb;
            int onAccent = org.llm4people.hermes.customization.AppCustomization.onAccentArgb(liftedArgb);
            assertTrue("Dark surface contrast failed for " + hex,
                    ThemePalette.contrastRatio(liftedArgb, ThemePalette.NIGHT_SURFACE) >= ThemePalette.WCAG_AA_NORMAL);
            assertTrue("Dark paper contrast failed for " + hex,
                    ThemePalette.contrastRatio(liftedArgb, ThemePalette.NIGHT_PAPER) >= ThemePalette.WCAG_AA_NORMAL);
            assertTrue("On-accent contrast failed for " + hex,
                    ThemePalette.contrastRatio(onAccent, liftedArgb) >= ThemePalette.WCAG_AA_NORMAL);
        }
    }

    @Test public void adaptOverrideForThemeModeHandlesAccentInkSurfaceOnlyAndInkOnlyCases() throws Exception {
        org.llm4people.hermes.customization.AppCustomization defaults =
                org.llm4people.hermes.customization.AppCustomization.defaults();

        // Case A (negative/invalidation): light accent + white accentInk switching to dark adapts accent
        // and clears incompatible white accentInk instead of throwing ContrastException.
        ThemePalette.OverrideSpec lightWithAccentInk = ThemePalette.OverrideSpec.validated(
                "light", "#1B4D89", "#FFFFFF", null, null, "comfortable", 0, "paper", false);
        ThemePalette.OverrideSpec adaptedDarkA = defaults.adaptOverrideForThemeMode(lightWithAccentInk, "dark", true);
        assertEquals("dark", adaptedDarkA.theme);
        assertNotNull(adaptedDarkA.accentHex);
        assertNull("Incompatible white accentInk must be cleared after lifting accent for dark", adaptedDarkA.accentInkHex);
        ThemePalette resolvedA = ThemePalette.resolve(adaptedDarkA, true);
        assertTrue(ThemePalette.contrastRatio(resolvedA.accent, resolvedA.surface) >= ThemePalette.WCAG_AA_NORMAL);
        assertTrue(ThemePalette.contrastRatio(resolvedA.onAccent, resolvedA.accent) >= ThemePalette.WCAG_AA_NORMAL);

        // Case A (positive/preservation): compatible accentInk stays preserved when mode keeps same polarity.
        ThemePalette.OverrideSpec stayedLightA = defaults.adaptOverrideForThemeMode(lightWithAccentInk, "light", false);
        assertEquals("#FFFFFF", stayedLightA.accentInkHex);

        // Case B: surfaceHex-only override survives transitions to both light and dark modes.
        ThemePalette.OverrideSpec darkSurfaceOnly = ThemePalette.OverrideSpec.validated(
                "system", null, null, "#1C2823", null, "comfortable", 0, "paper", true);
        ThemePalette.OverrideSpec surfaceInLight = defaults.adaptOverrideForThemeMode(darkSurfaceOnly, "light", false);
        assertEquals("light", surfaceInLight.theme);
        assertEquals("#1C2823", surfaceInLight.surfaceHex);
        assertNull(surfaceInLight.inkHex);
        assertEquals(ThemePalette.NIGHT_INK, ThemePalette.resolve(surfaceInLight, false).ink);

        // Case C (negative/drop): light-mode dark ink drops when switching to dark; dark-mode light ink drops when switching to light.
        ThemePalette.OverrideSpec lightInkOnly = ThemePalette.OverrideSpec.validated(
                "light", null, null, null, "#1A1917", "comfortable", 0, "paper", false);
        ThemePalette.OverrideSpec droppedDarkInk = defaults.adaptOverrideForThemeMode(lightInkOnly, "dark", true);
        assertEquals("dark", droppedDarkInk.theme);
        assertNull("Dark ink failing WCAG AA on night surface must be dropped", droppedDarkInk.inkHex);
        assertTrue(ThemePalette.contrastRatio(
                ThemePalette.resolve(droppedDarkInk, true).ink, ThemePalette.NIGHT_SURFACE) >= ThemePalette.WCAG_AA_NORMAL);

        ThemePalette.OverrideSpec darkInkOnly = ThemePalette.OverrideSpec.validated(
                "dark", null, null, null, "#F2F1E8", "comfortable", 0, "paper", true);
        ThemePalette.OverrideSpec droppedLightInk = defaults.adaptOverrideForThemeMode(darkInkOnly, "light", false);
        assertEquals("light", droppedLightInk.theme);
        assertNull("Light ink failing WCAG AA on day surface must be dropped", droppedLightInk.inkHex);

        // Case C (positive/preserve): inkHex that passes WCAG AA on target polarity stays intact.
        ThemePalette.OverrideSpec preservedLightInk = defaults.adaptOverrideForThemeMode(lightInkOnly, "light", false);
        assertEquals("#1A1917", preservedLightInk.inkHex);
    }

    private static void assertContrastRejected(Action action) {
        try {
            action.run();
            fail("Expected WCAG-AA rejection");
        } catch (ThemePalette.ContrastException expected) {
            assertTrue(expected.ratio < ThemePalette.WCAG_AA_NORMAL);
        } catch (IOException other) {
            fail("Expected ContrastException, got " + other);
        }
    }
}
