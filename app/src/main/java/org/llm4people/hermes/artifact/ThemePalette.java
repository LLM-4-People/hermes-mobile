package org.llm4people.hermes.artifact;

import android.content.Context;
import android.content.res.Configuration;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.Digests;
import org.llm4people.hermes.profile.Account;

/**
 * Single source of truth for DESIGN.md tokens and WCAG-AA contrast-checked live theme overrides.
 * Strictly rejects invalid hex codes or low-contrast colors before they can reach any screen.
 */
public final class ThemePalette {
    public static final double WCAG_AA_NORMAL = 4.5;

    // DESIGN.md day tokens (synced with res/values/colors.xml)
    public static final int DAY_PAPER = 0xFFF6F4EE;
    public static final int DAY_SURFACE = 0xFFFFFEFB;
    public static final int DAY_INK = 0xFF14231E;
    public static final int DAY_MUTED = 0xFF4E5D56;
    public static final int DAY_ACCENT = 0xFF1B5E50;
    public static final int DAY_ON_ACCENT = 0xFFFFFFFF;
    public static final int DAY_ACCENT_SURFACE = 0xFFD8EDE3;
    public static final int DAY_LINE = 0xFFD2D7CE;
    public static final int DAY_DANGER = 0xFFA12E28;
    public static final int DAY_WARNING = 0xFF7D4B00;
    public static final int DAY_POSITIVE = 0xFF1E6641;

    // DESIGN.md night tokens (synced with res/values-night/colors.xml)
    public static final int NIGHT_PAPER = 0xFF111916;
    public static final int NIGHT_SURFACE = 0xFF19241F;
    public static final int NIGHT_INK = 0xFFF2F1E8;
    public static final int NIGHT_MUTED = 0xFFAEBCB2;
    public static final int NIGHT_ACCENT = 0xFF9AD7BA;
    public static final int NIGHT_ON_ACCENT = 0xFF0E2B20;
    public static final int NIGHT_ACCENT_SURFACE = 0xFF223D31;
    public static final int NIGHT_LINE = 0xFF33433B;
    public static final int NIGHT_DANGER = 0xFFFFB4AA;
    public static final int NIGHT_WARNING = 0xFFF3CB7E;
    public static final int NIGHT_POSITIVE = 0xFF8FD8AE;

    public static final List<String> THEMES = Collections.unmodifiableList(Arrays.asList("system", "light", "dark"));
    public static final List<String> DENSITIES = Collections.unmodifiableList(Arrays.asList("compact", "comfortable", "spacious"));
    public static final List<String> SURFACE_STYLES = Collections.unmodifiableList(
            Arrays.asList("paper", "elevated", "outlined", "tinted", "glass"));
    public static final int MIN_FONT_SCALE_STEP = -2;
    public static final int MAX_FONT_SCALE_STEP = 3;
    static final String STORAGE_KEY = "workspace.theme.v2";

    public static final class ContrastException extends IOException {
        private static final long serialVersionUID = 1L;
        public final double ratio;

        public ContrastException(String message, double ratio) {
            super(message);
            this.ratio = ratio;
        }
    }

    /** Immutable, validated theme override specification (aligned with Fork PR #15 + WorkspaceLayout). */
    public static final class OverrideSpec {
        public final String theme;          // system | light | dark
        public final String accentHex;      // nullable #RRGGBB
        public final String accentInkHex;   // nullable #RRGGBB
        public final String surfaceHex;     // nullable #RRGGBB
        public final String inkHex;         // nullable #RRGGBB
        public final String density;        // compact | comfortable | spacious
        public final int fontScaleStep;     // -2..3
        public final String surfaceStyle;   // paper | elevated | outlined | tinted | glass

        private OverrideSpec(String theme, String accentHex, String accentInkHex, String surfaceHex,
                             String inkHex, String density, int fontScaleStep, String surfaceStyle) {
            this.theme = theme;
            this.accentHex = accentHex;
            this.accentInkHex = accentInkHex;
            this.surfaceHex = surfaceHex;
            this.inkHex = inkHex;
            this.density = density;
            this.fontScaleStep = fontScaleStep;
            this.surfaceStyle = surfaceStyle;
        }

        public static OverrideSpec defaults() {
            return new OverrideSpec("system", null, null, null, null, "comfortable", 0, "paper");
        }

        public static OverrideSpec validated(String theme, String accentHex, String density,
                                             int fontScaleStep, String surfaceStyle, boolean nightMode) throws IOException {
            return validated(theme, accentHex, null, null, null, density, fontScaleStep, surfaceStyle, nightMode);
        }

        public static OverrideSpec validated(String theme, String accentHex, String accentInkHex,
                                             String surfaceHex, String inkHex, String density,
                                             int fontScaleStep, String surfaceStyle, boolean nightMode) throws IOException {
            String safeTheme = theme == null || theme.isEmpty() ? "system" : theme;
            if (!THEMES.contains(safeTheme)) throw new IOException("Unsupported theme mode");
            String safeDensity = density == null || density.isEmpty() ? "comfortable" : density;
            if (!DENSITIES.contains(safeDensity)) throw new IOException("Unsupported density");
            if (fontScaleStep < MIN_FONT_SCALE_STEP || fontScaleStep > MAX_FONT_SCALE_STEP) {
                throw new IOException("Font scale step out of bounds");
            }
            String safeStyle = surfaceStyle == null || surfaceStyle.isEmpty() ? "paper" : surfaceStyle;
            if (!SURFACE_STYLES.contains(safeStyle)) throw new IOException("Unsupported surface style");

            boolean checkNight = "dark".equals(safeTheme) || ("system".equals(safeTheme) && nightMode);
            String normalizedSurface = null;
            String normalizedInk = null;
            int effSurface = checkNight ? NIGHT_SURFACE : DAY_SURFACE;
            int effPaper = checkNight ? NIGHT_PAPER : DAY_PAPER;
            int effInk = checkNight ? NIGHT_INK : DAY_INK;

            if (surfaceHex != null && !surfaceHex.isEmpty()) {
                effSurface = parseHexColor(surfaceHex);
                effPaper = effSurface;
                normalizedSurface = formatHex(effSurface);
            }
            if (inkHex != null && !inkHex.isEmpty()) {
                effInk = parseHexColor(inkHex);
                normalizedInk = formatHex(effInk);
            }
            if (normalizedSurface != null || normalizedInk != null) {
                double textContrast = textContrast(normalizedSurface, normalizedInk, effSurface, effPaper, effInk,
                        "system".equals(safeTheme));
                if (textContrast < WCAG_AA_NORMAL) {
                    throw new ContrastException("Text/surface contrast below WCAG AA (4.5:1)", textContrast);
                }
            }

            String normalizedAccent = null;
            String normalizedAccentInk = null;
            if (accentInkHex != null && !accentInkHex.isEmpty() && (accentHex == null || accentHex.isEmpty())) {
                throw new IOException("accent_ink_hex requires accent_hex");
            }
            if (accentHex != null && !accentHex.isEmpty()) {
                int rgb = parseHexColor(accentHex);
                if (accentInkHex != null && !accentInkHex.isEmpty()) {
                    int inkRgb = parseHexColor(accentInkHex);
                    double onAccentRatio = contrastRatio(inkRgb, rgb);
                    if (onAccentRatio < WCAG_AA_NORMAL) {
                        throw new ContrastException("Accent ink contrast below WCAG AA (4.5:1)", onAccentRatio);
                    }
                    normalizedAccentInk = formatHex(inkRgb);
                }
                if (normalizedSurface != null) {
                    double vsCustomSurface = contrastRatio(rgb, effSurface);
                    double vsOnAccent = contrastRatio(
                            normalizedAccentInk != null ? parseHexColor(normalizedAccentInk) : bestOnAccent(rgb), rgb);
                    double minRatio = Math.min(vsCustomSurface, vsOnAccent);
                    if (minRatio < WCAG_AA_NORMAL) {
                        throw new ContrastException("Accent contrast against custom surface below WCAG AA", minRatio);
                    }
                } else if ("system".equals(safeTheme)) {
                    validateSystemAccentContrast(rgb);
                } else {
                    validateAccentContrast(rgb, checkNight);
                }
                normalizedAccent = formatHex(rgb);
            }
            return new OverrideSpec(safeTheme, normalizedAccent, normalizedAccentInk,
                    normalizedSurface, normalizedInk, safeDensity, fontScaleStep, safeStyle);
        }

        public boolean isDefault() {
            return "system".equals(theme) && accentHex == null && accentInkHex == null
                    && surfaceHex == null && inkHex == null
                    && "comfortable".equals(density) && fontScaleStep == 0 && "paper".equals(surfaceStyle);
        }

        public String encode() {
            StringBuilder out = new StringBuilder();
            out.append("theme=").append(theme);
            if (accentHex != null) out.append("\naccent=").append(accentHex);
            if (accentInkHex != null) out.append("\naccent_ink=").append(accentInkHex);
            if (surfaceHex != null) out.append("\nsurface=").append(surfaceHex);
            if (inkHex != null) out.append("\nink=").append(inkHex);
            out.append("\ndensity=").append(density);
            out.append("\nfont=").append(fontScaleStep);
            out.append("\nstyle=").append(surfaceStyle);
            return out.toString();
        }

        public static OverrideSpec decode(String raw, boolean nightMode) throws IOException {
            if (raw == null || raw.isEmpty()) return defaults();
            Map<String, String> values = new HashMap<>();
            for (String line : raw.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) throw new IOException("Malformed theme record");
                String key = line.substring(0, eq);
                String val = line.substring(eq + 1);
                if (values.put(key, val) != null) throw new IOException("Duplicate theme key");
            }
            int step = 0;
            if (values.containsKey("font")) {
                try { step = Integer.parseInt(values.get("font")); }
                catch (NumberFormatException e) { throw new IOException("Invalid font scale step", e); }
            }
            return validated(values.get("theme"), values.get("accent"), values.get("accent_ink"),
                    values.get("surface"), values.get("ink"), values.get("density"),
                    step, values.get("style"), nightMode);
        }

        public String describe(Context context) {
            StringBuilder sb = new StringBuilder();
            if (!"system".equals(theme)) {
                sb.append(context.getString("dark".equals(theme)
                        ? org.llm4people.hermes.R.string.ws_theme_dark_mode
                        : org.llm4people.hermes.R.string.ws_theme_light_mode));
            }
            if (accentHex != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(context.getString(org.llm4people.hermes.R.string.ws_theme_accent, accentHex));
            }
            if (!"comfortable".equals(density)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(context.getString("compact".equals(density)
                        ? org.llm4people.hermes.R.string.ws_theme_density_compact
                        : org.llm4people.hermes.R.string.ws_theme_density_spacious));
            }
            if (fontScaleStep != 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(context.getString(fontScaleStep > 0
                        ? org.llm4people.hermes.R.string.ws_theme_text_larger
                        : org.llm4people.hermes.R.string.ws_theme_text_compact));
            }
            if (!"paper".equals(surfaceStyle)) {
                if (sb.length() > 0) sb.append(" · ");
                int styleRes = switch (surfaceStyle) {
                    case "outlined" -> org.llm4people.hermes.R.string.ws_theme_cards_outlined;
                    case "elevated" -> org.llm4people.hermes.R.string.ws_theme_cards_elevated;
                    case "glass" -> org.llm4people.hermes.R.string.ws_theme_cards_glass;
                    default -> org.llm4people.hermes.R.string.ws_theme_cards_tinted;
                };
                sb.append(context.getString(styleRes));
            }
            return sb.length() == 0 ? context.getString(org.llm4people.hermes.R.string.ws_theme_default) : sb.toString();
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof OverrideSpec other)) return false;
            return fontScaleStep == other.fontScaleStep
                    && theme.equals(other.theme)
                    && Objects.equals(accentHex, other.accentHex)
                    && Objects.equals(accentInkHex, other.accentInkHex)
                    && Objects.equals(surfaceHex, other.surfaceHex)
                    && Objects.equals(inkHex, other.inkHex)
                    && density.equals(other.density)
                    && surfaceStyle.equals(other.surfaceStyle);
        }

        @Override public int hashCode() {
            return Objects.hash(theme, accentHex, accentInkHex, surfaceHex, inkHex, density, fontScaleStep, surfaceStyle);
        }
    }

    public final boolean night;
    public final int paper, surface, ink, muted, accent, onAccent, accentSurface, line, danger, warning, positive;
    public final int blockVerticalPaddingDp;
    public final float textDeltaSp;
    public final String surfaceStyle;
    public final OverrideSpec spec;

    private ThemePalette(boolean night, boolean darkSurface, int paper, int surface, int ink, int muted,
                         int accent, int onAccent, int accentSurface, int danger, int warning, int positive,
                         OverrideSpec spec) {
        this.night = night;
        this.spec = spec;
        this.paper = paper;
        this.surface = surface;
        this.ink = ink;
        this.muted = muted;
        this.accent = accent;
        this.onAccent = onAccent;
        this.accentSurface = accentSurface;
        this.line = darkSurface ? NIGHT_LINE : DAY_LINE;
        this.danger = danger;
        this.warning = warning;
        this.positive = positive;
        this.blockVerticalPaddingDp = switch (spec.density) {
            case "compact" -> 5;
            case "spacious" -> 18;
            default -> 12;
        };
        this.textDeltaSp = switch (spec.fontScaleStep) {
            case -2 -> -2f;
            case -1 -> -1f;
            case 1 -> 2f;
            case 2 -> 4f;
            case 3 -> 5f;
            default -> 0f;
        };
        this.surfaceStyle = spec.surfaceStyle;
    }

    /** Single source of truth for strict ASCII {@code #RRGGBB} hex color syntax validation. */
    public static boolean isValidHexColor(String raw) {
        if (raw == null || raw.length() != 7 || raw.charAt(0) != '#') return false;
        try {
            Digests.parseUnsignedHex(raw, 1, 6);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Strictly parses #RRGGBB (opaque 24-bit sRGB). Rejects alpha, shorthand, whitespace, and non-hex. */
    public static int parseHexColor(String raw) throws IOException {
        if (raw == null || raw.length() != 7 || raw.charAt(0) != '#') {
            throw new IOException("Expected #RRGGBB color");
        }
        try {
            return 0xFF000000 | Digests.parseUnsignedHex(raw, 1, 6);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid hex digit", invalid);
        }
    }

    public static String formatHex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0x00FFFFFF);
    }

    /** WCAG 2.1 relative luminance for opaque sRGB color. */
    public static double relativeLuminance(int argb) {
        double r = linearize(((argb >> 16) & 0xFF) / 255.0);
        double g = linearize(((argb >> 8) & 0xFF) / 255.0);
        double b = linearize((argb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linearize(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /** WCAG 2.1 contrast ratio in [1.0, 21.0]. */
    public static double contrastRatio(int colorA, int colorB) {
        double l1 = relativeLuminance(colorA);
        double l2 = relativeLuminance(colorB);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** Picks the higher-contrast foreground on an accent fill (white or deep ink). */
    public static int bestOnAccent(int accentArgb) {
        double onWhite = contrastRatio(DAY_ON_ACCENT, accentArgb);
        double onDark = contrastRatio(NIGHT_ON_ACCENT, accentArgb);
        return onWhite >= onDark ? DAY_ON_ACCENT : NIGHT_ON_ACCENT;
    }

    /** Blends accent subtly into paper/surface to synthesize a calm accent-surface tint. */
    public static int blendTint(int accentArgb, int baseArgb, float weight) {
        int ar = (accentArgb >> 16) & 0xFF, ag = (accentArgb >> 8) & 0xFF, ab = accentArgb & 0xFF;
        int br = (baseArgb >> 16) & 0xFF, bg = (baseArgb >> 8) & 0xFF, bb = baseArgb & 0xFF;
        int r = Math.round(br * (1f - weight) + ar * weight);
        int g = Math.round(bg * (1f - weight) + ag * weight);
        int b = Math.round(bb * (1f - weight) + ab * weight);
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /** Mode-invariant contrast check for theme="system" and widgets: accepts accents passing WCAG AA in day OR night. */
    public static void validateSystemAccentContrast(int accentArgb) throws ContrastException {
        try {
            validateAccentContrast(accentArgb, false);
            return;
        } catch (ContrastException dayFailed) {
            try {
                validateAccentContrast(accentArgb, true);
            } catch (ContrastException nightFailed) {
                throw dayFailed.ratio >= nightFailed.ratio ? dayFailed : nightFailed;
            }
        }
    }

    /** Minimum WCAG contrast of a light-mode accent across DAY_PAPER, DAY_SURFACE, and its best label color. */
    public static double minLightAccentContrast(int accentArgb) {
        int opaque = 0xFF000000 | (accentArgb & 0xFFFFFF);
        double vsPaper = contrastRatio(opaque, DAY_PAPER);
        double vsSurface = contrastRatio(opaque, DAY_SURFACE);
        double vsOnAccent = contrastRatio(bestOnAccent(opaque), opaque);
        return Math.min(vsPaper, Math.min(vsSurface, vsOnAccent));
    }

    /** Enforces WCAG AA >= 4.5:1 against paper, surface, and its button label color. */
    public static void validateAccentContrast(int accentArgb, boolean nightMode) throws ContrastException {
        double minRatio;
        if (!nightMode) {
            minRatio = minLightAccentContrast(accentArgb);
        } else {
            int onAccent = bestOnAccent(accentArgb);
            double vsPaper = contrastRatio(accentArgb, NIGHT_PAPER);
            double vsSurface = contrastRatio(accentArgb, NIGHT_SURFACE);
            double vsOnAccent = contrastRatio(onAccent, accentArgb);
            minRatio = Math.min(vsPaper, Math.min(vsSurface, vsOnAccent));
        }
        if (minRatio < WCAG_AA_NORMAL) {
            throw new ContrastException(
                    String.format(Locale.ROOT, "Contrast ratio %.2f is below WCAG AA (4.5:1)", minRatio), minRatio);
        }
    }

    private static boolean accentPassesModeAndTint(int accentArgb, boolean targetNight) {
        try {
            validateAccentContrast(accentArgb, targetNight);
        } catch (ContrastException failed) {
            return false;
        }
        int paper = targetNight ? NIGHT_PAPER : DAY_PAPER;
        int tinted = blendTint(accentArgb, paper, targetNight ? 0.20f : 0.14f);
        return contrastRatio(accentArgb, tinted) >= WCAG_AA_NORMAL;
    }

    /**
     * Adapts a valid day-authored accent for night mode (or vice versa) when theme == "system",
     * preserving hue while guaranteeing WCAG AA >= 4.5:1 on paper, surface, tinted accentSurface, and onAccent.
     */
    public static int adaptAccentForMode(int accentArgb, boolean targetNight) {
        if (accentPassesModeAndTint(accentArgb, targetNight)) return accentArgb;
        int targetAnchor = targetNight ? 0xFFFFFFFF : 0xFF000000;
        for (int step = 1; step <= 20; step++) {
            float weight = step / 20f;
            int candidate = blendTint(targetAnchor, accentArgb, weight);
            if (accentPassesModeAndTint(candidate, targetNight)) return candidate;
        }
        return targetNight ? NIGHT_ACCENT : DAY_ACCENT;
    }

    private static int adaptColorAgainstSurface(int colorArgb, int surfaceArgb, int fallbackInk) {
        if (contrastRatio(colorArgb, surfaceArgb) >= WCAG_AA_NORMAL) return colorArgb;
        int anchor = relativeLuminance(fallbackInk) >= relativeLuminance(surfaceArgb) ? 0xFFFFFFFF : 0xFF000000;
        for (int step = 1; step <= 20; step++) {
            int candidate = blendTint(anchor, colorArgb, step / 20f);
            if (contrastRatio(candidate, surfaceArgb) >= WCAG_AA_NORMAL) return candidate;
        }
        return fallbackInk;
    }

    private static int adaptAccentAgainstSurface(int colorArgb, int surfaceArgb, int fallbackInk) {
        if (contrastRatio(colorArgb, surfaceArgb) >= WCAG_AA_NORMAL
                && contrastRatio(bestOnAccent(colorArgb), colorArgb) >= WCAG_AA_NORMAL) {
            return colorArgb;
        }
        int anchor = relativeLuminance(fallbackInk) >= relativeLuminance(surfaceArgb) ? 0xFFFFFFFF : 0xFF000000;
        for (int step = 1; step <= 20; step++) {
            int candidate = blendTint(anchor, colorArgb, step / 20f);
            if (contrastRatio(candidate, surfaceArgb) >= WCAG_AA_NORMAL
                    && contrastRatio(bestOnAccent(candidate), candidate) >= WCAG_AA_NORMAL) {
                return candidate;
            }
        }
        return fallbackInk;
    }

    private static boolean allTokensClearSurface(int surfaceArgb, int accent, int ink,
                                                 int muted, int danger, int warning, int positive) {
        return contrastRatio(accent, surfaceArgb) >= WCAG_AA_NORMAL
                && contrastRatio(ink, surfaceArgb) >= WCAG_AA_NORMAL
                && contrastRatio(muted, surfaceArgb) >= WCAG_AA_NORMAL
                && contrastRatio(danger, surfaceArgb) >= WCAG_AA_NORMAL
                && contrastRatio(warning, surfaceArgb) >= WCAG_AA_NORMAL
                && contrastRatio(positive, surfaceArgb) >= WCAG_AA_NORMAL;
    }

    private static int safeAccentSurface(int accent, int paper, int ink, int muted,
                                         int danger, int warning, int positive, boolean darkSurface) {
        float initial = darkSurface ? 0.20f : 0.14f;
        for (int step = 20; step >= 0; step--) {
            float weight = initial * (step / 20f);
            int candidate = blendTint(accent, paper, weight);
            if (allTokensClearSurface(candidate, accent, ink, muted, danger, warning, positive)) {
                return candidate;
            }
        }
        return paper;
    }

    /** Higher-contrast design ink (day or night) for a custom surface; keeps surface-only overrides mode-invariant. */
    static int bestInk(int surfaceArgb) {
        return contrastRatio(DAY_INK, surfaceArgb) >= contrastRatio(NIGHT_INK, surfaceArgb) ? DAY_INK : NIGHT_INK;
    }

    /**
     * Text contrast for a surface/ink override. When only {@code surfaceHex} is specified, pairs it with
     * {@link #bestInk(int)} to match {@link #resolve(OverrideSpec, boolean)} regardless of theme mode;
     * when {@code inkHex} is specified without a custom surface, enforces WCAG AA against BOTH surface
     * and paper (in at least one polarity for {@code theme="system"} or the active polarity otherwise).
     */
    private static double textContrast(String surfaceHex, String inkHex, int effSurface, int effPaper,
                                       int effInk, boolean system) {
        if (surfaceHex != null && inkHex != null) {
            return Math.min(contrastRatio(effInk, effSurface), contrastRatio(effInk, effPaper));
        }
        if (surfaceHex != null) return contrastRatio(bestInk(effSurface), effSurface);
        if (system) {
            double dayMin = Math.min(contrastRatio(effInk, DAY_SURFACE), contrastRatio(effInk, DAY_PAPER));
            double nightMin = Math.min(contrastRatio(effInk, NIGHT_SURFACE), contrastRatio(effInk, NIGHT_PAPER));
            return Math.max(dayMin, nightMin);
        }
        return Math.min(contrastRatio(effInk, effSurface), contrastRatio(effInk, effPaper));
    }

    public static ThemePalette resolve(OverrideSpec spec, boolean systemNight) {
        OverrideSpec safe = spec == null ? OverrideSpec.defaults() : spec;
        boolean night = "dark".equals(safe.theme) || (!"light".equals(safe.theme) && systemNight);
        int paper = night ? NIGHT_PAPER : DAY_PAPER;
        int surface = night ? NIGHT_SURFACE : DAY_SURFACE;
        int ink = night ? NIGHT_INK : DAY_INK;
        if (safe.surfaceHex != null) {
            try {
                int customSurf = parseHexColor(safe.surfaceHex);
                int customInk = safe.inkHex != null ? parseHexColor(safe.inkHex) : bestInk(customSurf);
                if (contrastRatio(customInk, customSurf) >= WCAG_AA_NORMAL) {
                    paper = customSurf;
                    surface = customSurf;
                    ink = customInk;
                }
            } catch (IOException ignored) {}
        } else if (safe.inkHex != null) {
            try {
                int customInk = parseHexColor(safe.inkHex);
                if (contrastRatio(customInk, surface) >= WCAG_AA_NORMAL
                        && contrastRatio(customInk, paper) >= WCAG_AA_NORMAL) {
                    ink = customInk;
                }
            } catch (IOException ignored) {}
        }

        boolean darkSurface = relativeLuminance(paper) < 0.2;
        int muted = darkSurface ? NIGHT_MUTED : DAY_MUTED;
        int danger = darkSurface ? NIGHT_DANGER : DAY_DANGER;
        int warning = darkSurface ? NIGHT_WARNING : DAY_WARNING;
        int positive = darkSurface ? NIGHT_POSITIVE : DAY_POSITIVE;
        int accent = darkSurface ? NIGHT_ACCENT : DAY_ACCENT;
        int onAccent = darkSurface ? NIGHT_ON_ACCENT : DAY_ON_ACCENT;
        int accentSurface = darkSurface ? NIGHT_ACCENT_SURFACE : DAY_ACCENT_SURFACE;
        if (safe.surfaceHex != null) {
            muted = adaptColorAgainstSurface(muted, paper, ink);
            danger = adaptColorAgainstSurface(danger, paper, ink);
            warning = adaptColorAgainstSurface(warning, paper, ink);
            positive = adaptColorAgainstSurface(positive, paper, ink);
            accent = adaptAccentAgainstSurface(accent, paper, ink);
            onAccent = bestOnAccent(accent);
        }
        if (safe.accentHex != null) {
            try {
                int candidate = parseHexColor(safe.accentHex);
                if (safe.surfaceHex != null && contrastRatio(candidate, surface) >= WCAG_AA_NORMAL) {
                    accent = candidate;
                    onAccent = safe.accentInkHex != null ? parseHexColor(safe.accentInkHex) : bestOnAccent(candidate);
                    if (contrastRatio(onAccent, accent) < WCAG_AA_NORMAL) onAccent = bestOnAccent(accent);
                } else if (safe.surfaceHex != null) {
                    accent = adaptAccentAgainstSurface(candidate, paper, ink);
                    onAccent = bestOnAccent(accent);
                } else {
                    accent = adaptAccentForMode(candidate, darkSurface);
                    onAccent = safe.accentInkHex != null && accent == candidate
                            ? parseHexColor(safe.accentInkHex) : bestOnAccent(accent);
                    if (contrastRatio(onAccent, accent) < WCAG_AA_NORMAL) onAccent = bestOnAccent(accent);
                }
            } catch (IOException ignored) {
                // Fall back to DESIGN.md default accent.
            }
        }
        if (safe.surfaceHex != null || safe.accentHex != null || safe.inkHex != null
                || !allTokensClearSurface(accentSurface, accent, ink, muted, danger, warning, positive)) {
            accentSurface = safeAccentSurface(accent, paper, ink, muted, danger, warning, positive, darkSurface);
        }
        return new ThemePalette(night, darkSurface, paper, surface, ink, muted,
                accent, onAccent, accentSurface, danger, warning, positive, safe);
    }

    public static boolean isNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static ThemePalette active(Context context, Account account) {
        boolean night = isNight(context);
        if (account == null) return resolve(OverrideSpec.defaults(), night);
        return resolve(load(account, night), night);
    }

    public static OverrideSpec load(Account account, boolean nightMode) {
        if (account == null) return OverrideSpec.defaults();
        try {
            String raw = account.read(STORAGE_KEY);
            return raw == null ? OverrideSpec.defaults() : OverrideSpec.decode(raw, nightMode);
        } catch (IOException | IllegalStateException e) {
            Diagnostics.warning("ui_theme", "read_failed");
            return OverrideSpec.defaults();
        }
    }

    public static void save(Account account, OverrideSpec spec) {
        if (account == null) return;
        if (spec == null || spec.isDefault()) {
            account.remove(STORAGE_KEY);
        } else {
            account.write(STORAGE_KEY, spec.encode());
        }
        Diagnostics.event("ui_theme", spec == null || spec.isDefault() ? "reset" : "updated");
    }

    public float scaledSp(float baseSp) {
        return Math.max(11f, baseSp + textDeltaSp);
    }
}
