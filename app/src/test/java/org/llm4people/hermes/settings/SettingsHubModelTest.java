package org.llm4people.hermes.settings;

import org.junit.Test;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;
import org.llm4people.hermes.customization.AppCustomization;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SettingsHubModelTest {

    @Test
    public void redactedHostExtractsSafeHostOnlyAndFallsBackOnMalformedEndpoints() {
        assertEquals("chat.ai.localserver.ca",
                SettingsHubModel.redactedHost("https://chat.ai.localserver.ca/mobile?token=secret#frag", "Private"));
        assertEquals("hermes.internal:8443",
                SettingsHubModel.redactedHost("https://user:pass@hermes.internal:8443/p/work", "Private"));
        assertEquals("example.org",
                SettingsHubModel.redactedHost("https://example.org:443/api", "Private"));
        assertEquals("Private", SettingsHubModel.redactedHost("", "Private"));
        assertEquals("Private", SettingsHubModel.redactedHost(null, "Private"));
        assertEquals("Private", SettingsHubModel.redactedHost("not a valid uri :::", "Private"));
        assertEquals("Private", SettingsHubModel.redactedHost("/relative/path", "Private"));
    }

    @Test
    public void filterSectionsSupportsAccentInsensitiveMultiTokenSearchAcrossFields() {
        List<SettingsHubModel.SectionSpec> sections = List.of(
                new SettingsHubModel.SectionSpec("assistant", "Assistant et IA", List.of(
                        new SettingsHubModel.ItemSpec("brain/profile_defaults", 0,
                                "Modèle, raisonnement et personnalité",
                                "Réglages par défaut", "model reasoning soul"),
                        new SettingsHubModel.ItemSpec("voice_options", 0,
                                "Voix et dictée", "Français", "voice speech")
                )),
                new SettingsHubModel.SectionSpec("security", "Sécurité et confidentialité", List.of(
                        new SettingsHubModel.ItemSpec("security", 0,
                                "Verrouillage et délai automatique",
                                "Biométrie · 5 minutes", "lock pin biometric timeout")
                ))
        );

        assertEquals(2, SettingsHubModel.filterSections(sections, "").size());
        assertEquals(3, SettingsHubModel.countItems(SettingsHubModel.filterSections(sections, "   ")));

        // Accent-insensitive title match ("dictee" -> "dictée")
        List<SettingsHubModel.SectionSpec> voice = SettingsHubModel.filterSections(sections, "dictee");
        assertEquals(1, voice.size());
        assertEquals("assistant", voice.get(0).id());
        assertEquals(1, voice.get(0).items().size());
        assertEquals("voice_options", voice.get(0).items().get(0).actionId());

        // Multi-token across section + live subtitle ("securite biometrie")
        List<SettingsHubModel.SectionSpec> sec = SettingsHubModel.filterSections(sections, "securite biometrie");
        assertEquals(1, sec.size());
        assertEquals("security", sec.get(0).id());
        assertEquals(1, sec.get(0).items().size());

        // Keyword match ("soul")
        List<SettingsHubModel.SectionSpec> soul = SettingsHubModel.filterSections(sections, "SOUL");
        assertEquals(1, soul.size());
        assertEquals("brain/profile_defaults", soul.get(0).items().get(0).actionId());

        // No-match prunes all sections cleanly
        assertTrue(SettingsHubModel.filterSections(sections, "nonexistent_xyz").isEmpty());
        assertTrue(SettingsHubModel.filterSections(null, "voice").isEmpty());
    }

    @Test
    public void liveSummariesAndResourceMappingsHandleNullAndFallbacks() {
        assertEquals("System · Forest · Comfortable",
                SettingsHubModel.formatAppearanceSummary("System", "Forest", "Comfortable"));
        assertEquals("Dark · Slate",
                SettingsHubModel.formatAppearanceSummary("Dark", "Slate", ""));

        assertEquals(R.string.settings_lang_auto, SettingsHubModel.languageModeLabelRes(null));
        assertEquals(R.string.settings_lang_auto, SettingsHubModel.languageModeLabelRes(AppLanguage.Mode.AUTO));
        assertEquals(R.string.settings_lang_en, SettingsHubModel.languageModeLabelRes(AppLanguage.Mode.ENGLISH));
        assertEquals(R.string.settings_lang_fr, SettingsHubModel.languageModeLabelRes(AppLanguage.Mode.FRENCH));

        assertEquals(R.string.settings_lang_en, SettingsHubModel.resolvedLanguageNameRes("en"));
        assertEquals(R.string.settings_lang_fr, SettingsHubModel.resolvedLanguageNameRes("fr"));
        assertEquals(R.string.settings_lang_fr, SettingsHubModel.resolvedLanguageNameRes(null));

        assertEquals(R.string.appearance_theme_dark, SettingsHubModel.themeModeLabelRes("dark"));
        assertEquals(R.string.appearance_palette_indigo,
                SettingsHubModel.paletteLabelRes(AppCustomization.PalettePreset.INDIGO));
        assertEquals(R.string.appearance_density_spacious,
                SettingsHubModel.densityLabelRes(AppCustomization.Density.SPACIOUS));

        assertEquals("Update 0.4.0 available",
                SettingsHubModel.updateSubtitle("Update 0.4.0 available", "Version 0.3.0"));
        assertEquals("Version 0.3.0",
                SettingsHubModel.updateSubtitle("   ", "Version 0.3.0"));
        assertEquals("Version 0.3.0",
                SettingsHubModel.updateSubtitle(null, "Version 0.3.0"));
    }

    @Test
    public void filterSectionsAndCountItemsSafelyIgnoreNullSectionsAndNullItems() {
        SettingsHubModel.SectionSpec withNullItems = new SettingsHubModel.SectionSpec(
                "sec", "General", Arrays.asList(
                        null,
                        new SettingsHubModel.ItemSpec("act", 0, "Appearance", "Theme", "color"),
                        null));
        assertEquals(1, withNullItems.items().size());

        List<SettingsHubModel.SectionSpec> mixed = Arrays.asList(null, withNullItems, null);
        List<SettingsHubModel.SectionSpec> all = SettingsHubModel.filterSections(mixed, "");
        assertEquals(1, all.size());
        assertEquals(1, SettingsHubModel.countItems(mixed));
        assertEquals(0, SettingsHubModel.countItems(null));

        List<SettingsHubModel.SectionSpec> matched = SettingsHubModel.filterSections(mixed, "theme");
        assertEquals(1, matched.size());
        assertEquals("act", matched.get(0).items().get(0).actionId());

        try {
            all.add(withNullItems);
            fail("filterSections must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {}
    }
}
