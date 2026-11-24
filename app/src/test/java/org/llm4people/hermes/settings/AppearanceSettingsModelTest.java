package org.llm4people.hermes.settings;

import org.junit.Test;
import org.llm4people.hermes.R;
import org.llm4people.hermes.customization.AppCustomization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class AppearanceSettingsModelTest {

    @Test
    public void themeModeNormalizationRejectsUnknownValuesToSystem() {
        assertEquals("system", AppearanceSettingsModel.normalizeThemeMode(null));
        assertEquals("system", AppearanceSettingsModel.normalizeThemeMode(""));
        assertEquals("system", AppearanceSettingsModel.normalizeThemeMode("sepia"));
        assertEquals("light", AppearanceSettingsModel.normalizeThemeMode(" LIGHT "));
        assertEquals("dark", AppearanceSettingsModel.normalizeThemeMode("Dark"));
    }

    @Test
    public void customHexEvaluationEnforcesSyntaxAndWcagAaContrast() {
        AppearanceSettingsModel.HexEvaluation ok = AppearanceSettingsModel.evaluateCustomHex("#1b5e50");
        assertTrue(ok.validSyntax());
        assertTrue(ok.accessible());
        assertEquals("#1B5E50", ok.normalizedHex());
        assertTrue(ok.contrastRatio() >= 4.5);
        assertEquals(0, ok.errorRes());

        AppearanceSettingsModel.HexEvaluation noHash = AppearanceSettingsModel.evaluateCustomHex("264b73");
        assertTrue(noHash.validSyntax());
        assertTrue(noHash.accessible());
        assertEquals("#264B73", noHash.normalizedHex());

        AppearanceSettingsModel.HexEvaluation lowContrast = AppearanceSettingsModel.evaluateCustomHex("#FFFF00");
        assertTrue(lowContrast.validSyntax());
        assertFalse(lowContrast.accessible());
        assertTrue(lowContrast.contrastRatio() < 4.5);
        assertEquals(R.string.appearance_hex_low_contrast, lowContrast.errorRes());

        for (String invalid : new String[]{null, "", "#123", "#GGGGGG", "blue", "#1234567"}) {
            AppearanceSettingsModel.HexEvaluation bad = AppearanceSettingsModel.evaluateCustomHex(invalid);
            assertFalse("Expected syntax failure for " + invalid, bad.validSyntax());
            assertFalse(bad.accessible());
            assertEquals(R.string.appearance_hex_invalid, bad.errorRes());
        }

        assertEquals("7.8", AppearanceSettingsModel.formatContrastRatio(7.84, Locale.US));
        assertEquals("7,8", AppearanceSettingsModel.formatContrastRatio(7.84, Locale.FRANCE));
        assertEquals("1.0", AppearanceSettingsModel.formatContrastRatio(Double.NaN, Locale.US));
        assertEquals("1.0", AppearanceSettingsModel.formatContrastRatio(Double.POSITIVE_INFINITY, Locale.US));
        assertEquals("1.0", AppearanceSettingsModel.formatContrastRatio(Double.NEGATIVE_INFINITY, Locale.US));
        assertEquals("1.0", AppearanceSettingsModel.formatContrastRatio(0.2, Locale.US));
        assertEquals(AppearanceSettingsModel.formatContrastRatio(5.0, Locale.getDefault()),
                AppearanceSettingsModel.formatContrastRatio(5.0, null));
    }

    @Test
    public void fontScaleStepperStepsByFivePercentAndClampsStrictly() {
        assertEquals(0.05f, AppearanceSettingsModel.stepFontScale(0.0f, 1), 0.0001f);
        assertEquals(-0.05f, AppearanceSettingsModel.stepFontScale(0.0f, -1), 0.0001f);
        assertEquals(-0.15f, AppearanceSettingsModel.stepFontScale(-0.15f, -1), 0.0001f);
        assertEquals(0.30f, AppearanceSettingsModel.stepFontScale(0.30f, 1), 0.0001f);
        assertEquals(0.0f, AppearanceSettingsModel.stepFontScale(Float.NaN, 0), 0.0001f);

        assertFalse(AppearanceSettingsModel.canDecreaseFontScale(-0.15f));
        assertTrue(AppearanceSettingsModel.canDecreaseFontScale(-0.10f));
        assertFalse(AppearanceSettingsModel.canIncreaseFontScale(0.30f));
        assertTrue(AppearanceSettingsModel.canIncreaseFontScale(0.25f));

        assertEquals("100%", AppearanceSettingsModel.formatFontScalePercent(0.0f));
        assertEquals("85%", AppearanceSettingsModel.formatFontScalePercent(-0.15f));
        assertEquals("130%", AppearanceSettingsModel.formatFontScalePercent(0.30f));
        assertEquals("100%", AppearanceSettingsModel.formatFontDeltaBadge(0.0f));
        assertEquals("+15%", AppearanceSettingsModel.formatFontDeltaBadge(0.15f));
        assertEquals("-10%", AppearanceSettingsModel.formatFontDeltaBadge(-0.10f));
    }

    @Test
    public void shortcutsEnforceUniqueIdsBoundsAndSecretRejection() {
        List<AppCustomization.ComposerShortcut> list = new ArrayList<>();
        AppCustomization.ComposerShortcut first = AppearanceSettingsModel.createShortcut(
                list, "Summarize", "Summarize the key points.");
        assertEquals("summarize", first.id());
        list.add(first);

        AppCustomization.ComposerShortcut duplicateLabel = AppearanceSettingsModel.createShortcut(
                list, "Summarize", "Summarize in 3 bullets.");
        assertEquals("summarize_2", duplicateLabel.id());
        list.add(duplicateLabel);

        // Blank label or draft rejected.
        try {
            AppearanceSettingsModel.createShortcut(list, "   ", "Draft");
            fail("Blank label must be rejected");
        } catch (IllegalArgumentException expected) {}

        // Secret-like content rejected.
        try {
            AppearanceSettingsModel.createShortcut(list, "Key", "Use bearer sk-1234567890abcdef123456");
            fail("Secret-like draft must be rejected");
        } catch (IllegalArgumentException expected) {}

        // Fill up to MAX_COMPOSER_SHORTCUTS (6), then 7th must fail.
        while (list.size() < AppCustomization.MAX_COMPOSER_SHORTCUTS) {
            list.add(AppearanceSettingsModel.createShortcut(list, "Item " + list.size(), "Draft " + list.size()));
        }
        try {
            AppearanceSettingsModel.createShortcut(list, "Overflow", "Too many");
            fail("Exceeding shortcut limit must be rejected");
        } catch (IllegalStateException expected) {}

        List<AppCustomization.ComposerShortcut> removed = AppearanceSettingsModel.removeShortcut(list, "summarize");
        assertEquals(AppCustomization.MAX_COMPOSER_SHORTCUTS - 1, removed.size());
        assertFalse(removed.stream().anyMatch(s -> "summarize".equals(s.id())));
        try {
            removed.add(first);
            fail("removeShortcut must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {}
        assertTrue(AppearanceSettingsModel.removeShortcut(null, "summarize").isEmpty());
    }

    @Test
    public void startupTabRoundTripsAndParsesLegacyWireValuesCleanly() {
        for (AppCustomization.StartupTab tab : AppCustomization.StartupTab.values()) {
            AppCustomization custom = AppCustomization.defaults().withStartupTab(tab);
            AppCustomization decoded = AppCustomization.decode(custom.encode());
            assertNotNull(decoded);
            assertEquals(tab, decoded.startupTab);
        }
        assertEquals(AppCustomization.StartupTab.CHATS, AppCustomization.StartupTab.parse("discussions"));
        assertEquals(AppCustomization.StartupTab.NOTES_TASKS, AppCustomization.StartupTab.parse("activite"));
        assertEquals(AppCustomization.StartupTab.APPS, AppCustomization.StartupTab.parse("atelier"));
        assertEquals(AppCustomization.StartupTab.APPS, AppCustomization.StartupTab.parse("cerveau"));
        assertEquals(R.string.nav_discussions,
                AppearanceSettingsModel.startupTabLabelRes(AppCustomization.StartupTab.CHATS));
        assertEquals(R.string.nav_notes_tasks,
                AppearanceSettingsModel.startupTabLabelRes(AppCustomization.StartupTab.NOTES_TASKS));
        assertEquals(R.string.nav_apps,
                AppearanceSettingsModel.startupTabLabelRes(AppCustomization.StartupTab.APPS));
    }
}
