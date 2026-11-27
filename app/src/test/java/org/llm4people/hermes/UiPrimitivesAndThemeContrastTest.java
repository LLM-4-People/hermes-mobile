package org.llm4people.hermes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-JVM proof of Light & Dark WCAG 2.1 AA contrast ratios (>= 4.5:1),
 * Ui primitive helpers, and ThreadViews localization purity.
 */
public final class UiPrimitivesAndThemeContrastTest {
    private static final Pattern COLOR_TAG = Pattern.compile(
            "<color\\s+name=\"([a-z0-9_]+)\">(#?[A-Za-z0-9_@/]+)</color>");
    private static final Pattern STRING_TAG = Pattern.compile(
            "<string\\s+name=\"([a-z0-9_]+)\">([^<]*)</string>");

    private static final String[][] CONTRAST_PAIRS = {
            {"ink", "paper"},
            {"ink", "surface"},
            {"ink", "surface_container"},
            {"muted", "paper"},
            {"muted", "surface"},
            {"muted", "surface_container"},
            {"accent", "paper"},
            {"accent", "surface"},
            {"accent", "accent_surface"},
            {"on_accent", "accent"},
            {"danger", "paper"},
            {"danger", "surface"},
            {"danger", "danger_surface"},
            {"warning", "paper"},
            {"warning", "surface"},
            {"warning", "warning_surface"},
            {"disabled_ink", "disabled_surface"},
    };

    private static final String[][] NON_TEXT_CONTRAST_PAIRS = {
            {"line_strong", "surface"},
            {"line_strong", "paper"},
            {"line_strong", "surface_container"},
    };

    @Test
    public void lightAndDarkPalettesMeetWcagAaOnEverySemanticPair() throws Exception {
        Map<String, String> light = parseColors(resFile("values/colors.xml"));
        Map<String, String> dark = parseColors(resFile("values-night/colors.xml"));

        for (String token : light.keySet()) {
            if (token.startsWith("hermes_icon_")) continue;
            assertTrue("Missing night companion for color " + token, dark.containsKey(token));
        }
        assertWcagPairs("light", light, CONTRAST_PAIRS, 4.5);
        assertWcagPairs("dark", dark, CONTRAST_PAIRS, 4.5);
        assertWcagPairs("light", light, NON_TEXT_CONTRAST_PAIRS, 3.0);
        assertWcagPairs("dark", dark, NON_TEXT_CONTRAST_PAIRS, 3.0);
        assertTrue("composer_capsule.xml unfocused border must use @color/line_strong",
                readUtf8(resFile("drawable/composer_capsule.xml")).contains("@color/line_strong"));

        // ThemePalette constants must stay 100% synced with values{,-night}/colors.xml (SSoT).
        assertColorEquals("DAY_PAPER", light.get("paper"), org.llm4people.hermes.artifact.ThemePalette.DAY_PAPER);
        assertColorEquals("DAY_SURFACE", light.get("surface"), org.llm4people.hermes.artifact.ThemePalette.DAY_SURFACE);
        assertColorEquals("DAY_INK", light.get("ink"), org.llm4people.hermes.artifact.ThemePalette.DAY_INK);
        assertColorEquals("DAY_MUTED", light.get("muted"), org.llm4people.hermes.artifact.ThemePalette.DAY_MUTED);
        assertColorEquals("DAY_ACCENT", light.get("accent"), org.llm4people.hermes.artifact.ThemePalette.DAY_ACCENT);
        assertColorEquals("DAY_ON_ACCENT", light.get("on_accent"), org.llm4people.hermes.artifact.ThemePalette.DAY_ON_ACCENT);
        assertColorEquals("DAY_ACCENT_SURFACE", light.get("accent_surface"), org.llm4people.hermes.artifact.ThemePalette.DAY_ACCENT_SURFACE);
        assertColorEquals("DAY_LINE", light.get("line"), org.llm4people.hermes.artifact.ThemePalette.DAY_LINE);
        assertColorEquals("DAY_DANGER", light.get("danger"), org.llm4people.hermes.artifact.ThemePalette.DAY_DANGER);
        assertColorEquals("DAY_WARNING", light.get("warning"), org.llm4people.hermes.artifact.ThemePalette.DAY_WARNING);
        assertColorEquals("NIGHT_PAPER", dark.get("paper"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_PAPER);
        assertColorEquals("NIGHT_SURFACE", dark.get("surface"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_SURFACE);
        assertColorEquals("NIGHT_INK", dark.get("ink"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_INK);
        assertColorEquals("NIGHT_MUTED", dark.get("muted"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_MUTED);
        assertColorEquals("NIGHT_ACCENT", dark.get("accent"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_ACCENT);
        assertColorEquals("NIGHT_ON_ACCENT", dark.get("on_accent"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_ON_ACCENT);
        assertColorEquals("NIGHT_ACCENT_SURFACE", dark.get("accent_surface"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_ACCENT_SURFACE);
        assertColorEquals("NIGHT_LINE", dark.get("line"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_LINE);
        assertColorEquals("NIGHT_DANGER", dark.get("danger"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_DANGER);
        assertColorEquals("NIGHT_WARNING", dark.get("warning"), org.llm4people.hermes.artifact.ThemePalette.NIGHT_WARNING);
    }

    private static void assertColorEquals(String name, String hex, int argb) {
        int[] rgb = parseRgb(hex);
        int expected = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        assertEquals(name + " drift vs colors.xml", expected, argb);
    }

    @Test
    public void uiStringsHaveExactFrEnParityAndZeroHardcodedFrenchInThreadViews() throws Exception {
        Map<String, String> fr = parseStrings(resFile("values/ui_strings.xml"));
        Map<String, String> en = parseStrings(resFile("values-en/ui_strings.xml"));
        assertEquals("FR/EN ui_strings key parity", fr.keySet(), en.keySet());
        assertTrue(fr.size() >= 7);
        for (Map.Entry<String, String> entry : en.entrySet()) {
            assertFalse("Empty EN string " + entry.getKey(), entry.getValue().trim().isEmpty());
            assertFalse("French leak in EN " + entry.getKey(),
                    entry.getValue().contains("Voir") || entry.getValue().contains("Retirer")
                            || entry.getValue().contains("Suivi") || entry.getValue().contains("Outils"));
        }

        String threadViews = readUtf8(
                repoRoot().resolve("app/src/main/java/org/llm4people/hermes/thread/ThreadViews.java"));
        for (String forbidden : new String[]{
                "\"Image jointe",
                "\"Retirer l’image",
                "\"Suivi de la question\"",
                "\"Autorisation\"",
                "\"Outils · \"",
                "\"Voir les détails\"",
                "\"Détails de l’action\"",
                "\"Lire le message entier\"",
                "\"Fermer\""
        }) {
            assertFalse("Hardcoded French literal left in ThreadViews.java: " + forbidden,
                    threadViews.contains(forbidden));
        }
    }

    @Test
    public void initialsHandlesExpectedAndEdgeInputs() {
        assertEquals("J", Ui.initials("Jacob"));
        assertEquals("JD", Ui.initials("Jacob Dorais"));
        assertEquals("ÉG", Ui.initials("Élodie Gagnon"));
        assertEquals("AB", Ui.initials("  alice   bob   charlie  "));
        assertEquals("JL", Ui.initials("jean-luc"));
        assertEquals("OA", Ui.initials("ops_admin"));
        assertEquals("TA", Ui.initials("   ", "team-alpha"));
        assertEquals("JD", Ui.initials("Jacob Dorais", "ignored-slug"));
        assertEquals("H", Ui.initials(null));
        assertEquals("H", Ui.initials(""));
        assertEquals("H", Ui.initials("   "));
        assertEquals("H", Ui.initials(null, "   "));
        assertEquals("H", Ui.initials("!!! ---"));

        // SSoT parity: OnboardingModels.RemoteProfile delegates to Ui.initials.
        org.llm4people.hermes.onboarding.OnboardingModels.RemoteProfile remote =
                new org.llm4people.hermes.onboarding.OnboardingModels.RemoteProfile(
                        "team-alpha", "", "fr", "", "#1B5E50", 0, 0, 0L, false, true);
        assertEquals("TA", remote.initials());
    }

    @Test
    public void lockModeValidationErrorResDistinguishesMismatchFormatAndShortInputs() {
        assertEquals(0, WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PIN, "123456", "123456", false));
        int pinMismatch = WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PIN, "123456", "654321", false);
        int pinShort = WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PIN, "123", "123", false);
        assertEquals(R.string.lock_pin_mismatch, pinMismatch);
        assertEquals(R.string.lock_setup_missing_pin, pinShort);
        assertTrue(pinMismatch != pinShort);
        assertEquals(R.string.lock_pin_digits_only, WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PIN, "12ab56", "12ab56", false));
        assertEquals(R.string.lock_pin_confirm_needed, WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PIN, "123456", "", false));

        assertEquals(0, WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PASSPHRASE, "twelve-chars", "twelve-chars", false));
        int passMismatch = WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PASSPHRASE, "twelve-chars", "twelve-charX", false);
        int passShort = WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PASSPHRASE, "short", "short", false);
        assertEquals(R.string.profile_setup_passphrase_mismatch_live, passMismatch);
        assertEquals(R.string.lock_setup_missing_passphrase, passShort);
        assertTrue(passMismatch != passShort);
        assertEquals(R.string.profile_setup_passphrase_confirm_needed, WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.PASSPHRASE, "twelve-chars", "", false));

        assertEquals(0, WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.BIOMETRIC, "", "", true));
        assertEquals(R.string.lock_mode_biometric_unavailable, WorkspaceDialogs.lockModeValidationErrorRes(
                org.llm4people.hermes.profile.ProfileLockPolicy.Mode.BIOMETRIC, "", "", false));
    }

    @Test
    public void appCustomizationSyncsWithThemePaletteAndMeetsWcagAa() {
        assertEquals(org.llm4people.hermes.artifact.ThemePalette.WCAG_AA_NORMAL,
                org.llm4people.hermes.customization.AppCustomization.WCAG_AA_MIN_CONTRAST, 1e-9);
        assertEquals(org.llm4people.hermes.artifact.ThemePalette.DAY_SURFACE & 0xFFFFFF,
                org.llm4people.hermes.customization.AppCustomization.LIGHT_SURFACE_RGB);
        assertEquals(org.llm4people.hermes.artifact.ThemePalette.NIGHT_SURFACE & 0xFFFFFF,
                org.llm4people.hermes.customization.AppCustomization.DARK_SURFACE_RGB);
        assertEquals(org.llm4people.hermes.artifact.ThemePalette.DAY_INK & 0xFFFFFF,
                org.llm4people.hermes.customization.AppCustomization.LIGHT_INK_RGB);
        assertEquals(org.llm4people.hermes.artifact.ThemePalette.DAY_ON_ACCENT & 0xFFFFFF,
                org.llm4people.hermes.customization.AppCustomization.WHITE_RGB);

        String expectedDayAccent = org.llm4people.hermes.artifact.ThemePalette.formatHex(
                org.llm4people.hermes.artifact.ThemePalette.DAY_ACCENT);
        String expectedNightAccent = org.llm4people.hermes.artifact.ThemePalette.formatHex(
                org.llm4people.hermes.artifact.ThemePalette.NIGHT_ACCENT);
        assertEquals(expectedDayAccent,
                org.llm4people.hermes.customization.AppCustomization.PalettePreset.FOREST.lightAccentHex);
        assertEquals(expectedNightAccent,
                org.llm4people.hermes.customization.AppCustomization.PalettePreset.FOREST.darkAccentHex);
        assertEquals(expectedDayAccent,
                org.llm4people.hermes.customization.AppCustomization.PalettePreset.CUSTOM.lightAccentHex);
        assertEquals(expectedNightAccent,
                org.llm4people.hermes.customization.AppCustomization.PalettePreset.CUSTOM.darkAccentHex);

        for (org.llm4people.hermes.customization.AppCustomization.PalettePreset preset
                : org.llm4people.hermes.customization.AppCustomization.PalettePreset.values()) {
            assertTrue(preset.name() + " light accent contrast < 4.5",
                    org.llm4people.hermes.customization.AppCustomization.meetsWcagAa(
                            org.llm4people.hermes.customization.AppCustomization.parseRgb(preset.lightAccentHex),
                            org.llm4people.hermes.customization.AppCustomization.LIGHT_SURFACE_RGB));
            assertTrue(preset.name() + " dark accent contrast < 4.5",
                    org.llm4people.hermes.customization.AppCustomization.meetsWcagAa(
                            org.llm4people.hermes.customization.AppCustomization.parseRgb(preset.darkAccentHex),
                            org.llm4people.hermes.customization.AppCustomization.DARK_SURFACE_RGB));
        }
    }

    @Test
    public void threadViewsActiveTaskUsesUiPillInsteadOfRawBullet() throws Exception {
        String threadViews = readUtf8(
                repoRoot().resolve("app/src/main/java/org/llm4people/hermes/thread/ThreadViews.java"));
        assertFalse("ThreadViews must not hardcode raw bullet prefix in activeTaskRow",
                threadViews.contains("\"● \""));
        assertTrue("ThreadViews activeTaskRow must use Ui.pill",
                threadViews.contains("Ui.pill(context, context.getString(labelRes)"));
    }

    private static void assertWcagPairs(String theme, Map<String, String> palette, String[][] pairs, double minRatio) {
        for (String[] pair : pairs) {
            String fgHex = palette.get(pair[0]);
            String bgHex = palette.get(pair[1]);
            assertTrue(theme + " missing " + pair[0], fgHex != null && fgHex.startsWith("#"));
            assertTrue(theme + " missing " + pair[1], bgHex != null && bgHex.startsWith("#"));
            double ratio = contrastRatio(parseRgb(fgHex), parseRgb(bgHex));
            assertTrue(String.format("%s contrast %s (%s) on %s (%s) = %.2f < %.2f",
                    theme, pair[0], fgHex, pair[1], bgHex, ratio, minRatio), ratio >= minRatio);
        }
    }

    private static int[] parseRgb(String hex) {
        String clean = hex.substring(1);
        if (clean.length() == 8) clean = clean.substring(2);
        return new int[]{
                Integer.parseInt(clean.substring(0, 2), 16),
                Integer.parseInt(clean.substring(2, 4), 16),
                Integer.parseInt(clean.substring(4, 6), 16),
        };
    }

    private static double contrastRatio(int[] a, int[] b) {
        double l1 = relativeLuminance(a);
        double l2 = relativeLuminance(b);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static double relativeLuminance(int[] rgb) {
        return 0.2126 * linearChannel(rgb[0])
                + 0.7152 * linearChannel(rgb[1])
                + 0.0722 * linearChannel(rgb[2]);
    }

    private static double linearChannel(int channel) {
        double s = channel / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static Map<String, String> parseColors(Path file) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = COLOR_TAG.matcher(readUtf8(file));
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static Map<String, String> parseStrings(Path file) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING_TAG.matcher(readUtf8(file));
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static String readUtf8(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static Path resFile(String rel) {
        return repoRoot().resolve("app/src/main/res").resolve(rel);
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve("app/src/main/res")) ? cwd : cwd.getParent();
    }
}
