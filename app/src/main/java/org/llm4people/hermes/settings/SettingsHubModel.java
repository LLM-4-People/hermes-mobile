package org.llm4people.hermes.settings;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.palette.CommandRegistry;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-JVM Single Source of Truth for {@link SettingsHubPanel} host redaction, live summary tokens,
 * and accent-insensitive multi-token settings search.
 */
public final class SettingsHubModel {
    private SettingsHubModel() {}

    public record ItemSpec(
            String actionId,
            int iconRes,
            String title,
            String subtitle,
            String keywords) {
        public ItemSpec {
            actionId = actionId == null ? "" : actionId.trim();
            title = title == null ? "" : title.trim();
            subtitle = subtitle == null ? "" : subtitle.trim();
            keywords = keywords == null ? "" : keywords.trim();
        }
    }

    public record SectionSpec(String id, String title, List<ItemSpec> items) {
        public SectionSpec {
            id = id == null ? "" : id.trim();
            title = title == null ? "" : title.trim();
            items = items == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    /**
     * Extract only the safe server host (never credentials, path, query, or fragment) for display
     * in the profile hero card. Returns {@code fallback} when {@code endpoint} is blank or invalid.
     */
    public static String redactedHost(String endpoint, String fallback) {
        String safeFallback = fallback == null ? "" : fallback.trim();
        if (endpoint == null || endpoint.trim().isEmpty()) return safeFallback;
        try {
            URI uri = URI.create(endpoint.trim());
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) return safeFallback;
            int port = uri.getPort();
            return port > 0 && port != 443 && port != 80 ? host + ":" + port : host;
        } catch (IllegalArgumentException malformed) {
            return safeFallback;
        }
    }

    /**
     * Accent-insensitive multi-token filter across section titles, item titles, live subtitles,
     * and localized/technical keywords. Drops empty sections when a non-empty query is active.
     */
    public static List<SectionSpec> filterSections(List<SectionSpec> sections, String rawQuery) {
        if (sections == null || sections.isEmpty()) return Collections.emptyList();
        String normalized = CommandRegistry.normalize(rawQuery);
        if (normalized.isEmpty()) return Collections.unmodifiableList(sections);
        String[] tokens = normalized.split(" ");
        List<SectionSpec> out = new ArrayList<>();
        for (SectionSpec section : sections) {
            String sectionNorm = CommandRegistry.normalize(section.title());
            List<ItemSpec> matched = new ArrayList<>();
            for (ItemSpec item : section.items()) {
                String haystack = sectionNorm + " "
                        + CommandRegistry.normalize(item.title()) + " "
                        + CommandRegistry.normalize(item.subtitle()) + " "
                        + CommandRegistry.normalize(item.keywords());
                if (matchesAllTokens(haystack, tokens)) {
                    matched.add(item);
                }
            }
            if (!matched.isEmpty()) {
                out.add(new SectionSpec(section.id(), section.title(), matched));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Count total visible setting rows across filtered sections. */
    public static int countItems(List<SectionSpec> sections) {
        if (sections == null) return 0;
        int total = 0;
        for (SectionSpec section : sections) total += section.items().size();
        return total;
    }

    /** Format a clean dot-separated summary such as "System · Forest · Comfortable". */
    public static String formatAppearanceSummary(String themeLabel, String paletteLabel, String densityLabel) {
        List<String> parts = new ArrayList<>(3);
        addIfNonEmpty(parts, themeLabel);
        addIfNonEmpty(parts, paletteLabel);
        addIfNonEmpty(parts, densityLabel);
        return String.join(" · ", parts);
    }

    /** Resolve the localized label resource for a persisted {@link AppLanguage.Mode}. */
    public static int languageModeLabelRes(AppLanguage.Mode mode) {
        if (mode == null || mode == AppLanguage.Mode.AUTO) return R.string.settings_lang_auto;
        return mode == AppLanguage.Mode.ENGLISH ? R.string.settings_lang_en : R.string.settings_lang_fr;
    }

    /** Resolve the localized short language name for an effective tag ("en" or "fr"). */
    public static int resolvedLanguageNameRes(String resolvedTag) {
        return AppLanguage.TAG_ENGLISH.equalsIgnoreCase(resolvedTag)
                ? R.string.settings_lang_en : R.string.settings_lang_fr;
    }

    /** Resolve the localized label resource for a theme mode wire value. */
    public static int themeModeLabelRes(String themeMode) {
        return AppearanceSettingsModel.themeModeLabelRes(themeMode);
    }

    /** Resolve the localized label resource for an {@link AppCustomization.PalettePreset}. */
    public static int paletteLabelRes(AppCustomization.PalettePreset preset) {
        return AppearanceSettingsModel.paletteLabelRes(preset);
    }

    /** Resolve the localized label resource for an {@link AppCustomization.Density}. */
    public static int densityLabelRes(AppCustomization.Density density) {
        return AppearanceSettingsModel.densityLabelRes(density);
    }

    /** Build a safe version subtitle, preferring a live update badge when an update is ready. */
    public static String updateSubtitle(String updateBadge, String formattedVersion) {
        if (updateBadge != null && !updateBadge.trim().isEmpty()) return updateBadge.trim();
        return formattedVersion == null ? "" : formattedVersion.trim();
    }

    private static boolean matchesAllTokens(String haystack, String[] tokens) {
        for (String token : tokens) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

    private static void addIfNonEmpty(List<String> target, String value) {
        if (value != null && !value.trim().isEmpty()) target.add(value.trim());
    }
}
