package org.llm4people.hermes.settings;

import org.llm4people.hermes.R;
import org.llm4people.hermes.customization.AppCustomization;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-JVM Single Source of Truth for {@link AppearanceSettingsPanel}: WCAG AA hex contrast
 * evaluation, text-size stepper math, startup-tab / palette / density resource mapping,
 * companion greeting validation, and Editable-Draft-Rule composer shortcut creation.
 */
public final class AppearanceSettingsModel {
    public static final float FONT_SCALE_STEP = 0.05f;
    public static final int MIN_FONT_SCALE_STEP = -3;
    public static final int MAX_FONT_SCALE_STEP = 6;
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    private static final Pattern HEX_PATTERN = Pattern.compile("^#?[0-9A-Fa-f]{6}$");

    public static final AppCustomization.PalettePreset[] CURATED_PRESETS = {
            AppCustomization.PalettePreset.FOREST,
            AppCustomization.PalettePreset.SLATE,
            AppCustomization.PalettePreset.TERRACOTTA,
            AppCustomization.PalettePreset.INDIGO,
            AppCustomization.PalettePreset.PLUM
    };

    public record HexEvaluation(
            boolean validSyntax,
            boolean accessible,
            String normalizedHex,
            double contrastRatio,
            int errorRes) {
        public static HexEvaluation invalidSyntax() {
            return new HexEvaluation(false, false, "", 0.0, R.string.appearance_hex_invalid);
        }
    }

    private AppearanceSettingsModel() {}

    /** Normalize any theme wire string into {@code system|light|dark}. */
    public static String normalizeThemeMode(String raw) {
        return AppThemeMode.normalize(raw);
    }

    /** Evaluate a user-typed custom hex color against WCAG 2.1 AA (4.5:1 on light surface). */
    public static HexEvaluation evaluateCustomHex(String raw) {
        if (raw == null || raw.trim().isEmpty()) return HexEvaluation.invalidSyntax();
        String trimmed = raw.trim();
        if (!HEX_PATTERN.matcher(trimmed).matches()) return HexEvaluation.invalidSyntax();
        String normalized = (trimmed.startsWith("#") ? trimmed : "#" + trimmed).toUpperCase(Locale.ROOT);
        int rgb = AppCustomization.parseRgb(normalized);
        double ratio = AppCustomization.contrastRatio(rgb, AppCustomization.LIGHT_SURFACE_RGB);
        boolean ok = ratio >= AppCustomization.WCAG_AA_MIN_CONTRAST;
        return new HexEvaluation(true, ok, normalized, ratio, ok ? 0 : R.string.appearance_hex_low_contrast);
    }

    /** Format contrast ratio to 1 decimal place in the active locale (e.g. "7.8" or "7,8"). */
    public static String formatContrastRatio(double ratio, Locale locale) {
        Locale effective = locale == null ? Locale.getDefault() : locale;
        double safeRatio = Double.isFinite(ratio) ? Math.max(1.0, ratio) : 1.0;
        NumberFormat fmt = NumberFormat.getNumberInstance(effective);
        fmt.setMinimumFractionDigits(1);
        fmt.setMaximumFractionDigits(1);
        return fmt.format(safeRatio);
    }

    /**
     * Parse and validate a wire {@code font_scale_step} integer strictly within
     * {@code [MIN_FONT_SCALE_STEP, MAX_FONT_SCALE_STEP]}. Returns {@code null} for non-numeric,
     * non-finite, fractional, or out-of-range inputs.
     */
    public static Integer parseFontScaleStep(Object raw) {
        if (!(raw instanceof Number n)) return null;
        double value = n.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value)) return null;
        if (value < MIN_FONT_SCALE_STEP || value > MAX_FONT_SCALE_STEP) return null;
        return (int) value;
    }

    /** Convert a validated {@code font_scale_step} into its font-scale delta ({@code step * FONT_SCALE_STEP}). */
    public static float fontScaleDeltaForStep(int step) {
        if (step < MIN_FONT_SCALE_STEP || step > MAX_FONT_SCALE_STEP) {
            throw new IllegalArgumentException("font_scale_step out of range [" + MIN_FONT_SCALE_STEP
                    + ", " + MAX_FONT_SCALE_STEP + "]");
        }
        return step * FONT_SCALE_STEP;
    }

    /** Step font scale delta by +/- 5% clamped strictly to {@code [-0.15, +0.30]}. */
    public static float stepFontScale(float currentDelta, int direction) {
        float safe = Float.isFinite(currentDelta) ? currentDelta : 0f;
        int step = direction > 0 ? 1 : direction < 0 ? -1 : 0;
        float next = safe + step * FONT_SCALE_STEP;
        float clamped = Math.max(AppCustomization.MIN_FONT_SCALE_DELTA,
                Math.min(AppCustomization.MAX_FONT_SCALE_DELTA, next));
        return Math.round(clamped * 100f) / 100f;
    }

    public static boolean canDecreaseFontScale(float currentDelta) {
        return Float.isFinite(currentDelta) && currentDelta > AppCustomization.MIN_FONT_SCALE_DELTA + 0.001f;
    }

    public static boolean canIncreaseFontScale(float currentDelta) {
        return Float.isFinite(currentDelta) && currentDelta < AppCustomization.MAX_FONT_SCALE_DELTA - 0.001f;
    }

    /** Format effective font scale percentage (e.g. "100%", "115%", "85%"). */
    public static String formatFontScalePercent(float delta) {
        float safe = Float.isFinite(delta) ? delta : 0f;
        int pct = Math.round((1.0f + safe) * 100f);
        return pct + "%";
    }

    /** Format compact delta label for preview badges (e.g. "100%", "+10%", "-5%"). */
    public static String formatFontDeltaBadge(float delta) {
        float safe = Float.isFinite(delta) ? delta : 0f;
        int diff = Math.round(safe * 100f);
        if (diff == 0) return "100%";
        return (diff > 0 ? "+" : "") + diff + "%";
    }

    /**
     * Validate and create a new {@link AppCustomization.ComposerShortcut} with a deterministic unique
     * slug ID, enforcing {@link AppCustomization#MAX_COMPOSER_SHORTCUTS} and secret-pattern hygiene.
     */
    public static AppCustomization.ComposerShortcut createShortcut(
            List<AppCustomization.ComposerShortcut> existing, String rawLabel, String rawDraft) {
        int currentSize = existing == null ? 0 : existing.size();
        if (currentSize >= AppCustomization.MAX_COMPOSER_SHORTCUTS) {
            throw new IllegalStateException("Shortcut limit reached");
        }
        String label = AppCustomization.sanitizePlainText(rawLabel, AppCustomization.MAX_SHORTCUT_LABEL_CHARS);
        String draft = AppCustomization.sanitizePlainText(rawDraft, AppCustomization.MAX_SHORTCUT_DRAFT_CHARS);
        if (label.isEmpty() || draft.isEmpty()) {
            throw new IllegalArgumentException("Shortcut label and draft required");
        }
        Set<String> usedIds = new HashSet<>();
        if (existing != null) {
            for (AppCustomization.ComposerShortcut s : existing) {
                if (s != null) usedIds.add(s.id());
            }
        }
        String id = uniqueShortcutId(label, usedIds);
        return new AppCustomization.ComposerShortcut(id, label, draft);
    }

    /** Remove a shortcut by ID, returning an updated immutable list. */
    public static List<AppCustomization.ComposerShortcut> removeShortcut(
            List<AppCustomization.ComposerShortcut> existing, String shortcutId) {
        List<AppCustomization.ComposerShortcut> next = new ArrayList<>();
        if (existing != null) {
            for (AppCustomization.ComposerShortcut s : existing) {
                if (s != null && !s.id().equals(shortcutId)) next.add(s);
            }
        }
        return Collections.unmodifiableList(next);
    }

    static String uniqueShortcutId(String label, Set<String> usedIds) {
        StringBuilder slug = new StringBuilder();
        String lower = label.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length() && slug.length() < 24; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                slug.append(c);
            } else if ((c == ' ' || c == '-' || c == '_') && slug.length() > 0
                    && slug.charAt(slug.length() - 1) != '_') {
                slug.append('_');
            }
        }
        while (slug.length() > 0 && slug.charAt(slug.length() - 1) == '_') {
            slug.deleteCharAt(slug.length() - 1);
        }
        String base = slug.length() == 0 ? "shortcut" : slug.toString();
        if (!usedIds.contains(base)) return base;
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + "_" + suffix;
            if (!usedIds.contains(candidate)) return candidate;
        }
        return "shortcut_" + (usedIds.size() + 1);
    }

    public static int themeModeLabelRes(String themeMode) {
        return switch (normalizeThemeMode(themeMode)) {
            case THEME_LIGHT -> R.string.appearance_theme_light;
            case THEME_DARK -> R.string.appearance_theme_dark;
            default -> R.string.appearance_theme_system;
        };
    }

    public static int paletteLabelRes(AppCustomization.PalettePreset preset) {
        if (preset == null) return R.string.appearance_palette_forest;
        return switch (preset) {
            case FOREST -> R.string.appearance_palette_forest;
            case SLATE -> R.string.appearance_palette_slate;
            case TERRACOTTA -> R.string.appearance_palette_terracotta;
            case INDIGO -> R.string.appearance_palette_indigo;
            case PLUM -> R.string.appearance_palette_plum;
            case CUSTOM -> R.string.appearance_palette_custom;
        };
    }

    public static int densityLabelRes(AppCustomization.Density density) {
        if (density == null) return R.string.appearance_density_comfortable;
        return switch (density) {
            case COMPACT -> R.string.appearance_density_compact;
            case COMFORTABLE -> R.string.appearance_density_comfortable;
            case SPACIOUS -> R.string.appearance_density_spacious;
        };
    }

    public static int startupTabLabelRes(AppCustomization.StartupTab tab) {
        if (tab == null) return R.string.nav_discussions;
        return switch (tab) {
            case CHATS -> R.string.nav_discussions;
            case NOTES_TASKS -> R.string.nav_notes_tasks;
            case APPS -> R.string.nav_apps;
        };
    }
}
