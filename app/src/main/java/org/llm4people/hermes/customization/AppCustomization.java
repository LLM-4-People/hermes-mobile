package org.llm4people.hermes.customization;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.os.HermesWidgetSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated, immutable per-profile live app customization model.
 * Enforces WCAG 2.1 AA contrast (&gt;= 4.5:1) for every curated and custom hex accent,
 * bounds density/font scale/greeting, and upholds the Editable Draft Rule for composer shortcuts.
 * Pure Java so every invariant runs directly on the host JVM.
 */
public final class AppCustomization {
    public static final double WCAG_AA_MIN_CONTRAST = ThemePalette.WCAG_AA_NORMAL;
    public static final int LIGHT_SURFACE_RGB = ThemePalette.DAY_SURFACE & 0xFFFFFF;
    public static final int DARK_SURFACE_RGB = ThemePalette.NIGHT_SURFACE & 0xFFFFFF;
    public static final int LIGHT_INK_RGB = ThemePalette.DAY_INK & 0xFFFFFF;
    public static final int WHITE_RGB = ThemePalette.DAY_ON_ACCENT & 0xFFFFFF;

    public static final float MIN_FONT_SCALE_DELTA = -0.15f;
    public static final float MAX_FONT_SCALE_DELTA = 0.30f;
    public static final int MAX_GREETING_CHARS = 96;
    public static final int MAX_COMPOSER_SHORTCUTS = 6;
    public static final int MAX_SHORTCUT_LABEL_CHARS = 32;
    public static final int MAX_SHORTCUT_DRAFT_CHARS = 240;

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern SHORTCUT_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    public enum PalettePreset {
        FOREST("forest", ThemePalette.formatHex(ThemePalette.DAY_ACCENT), ThemePalette.formatHex(ThemePalette.NIGHT_ACCENT)),
        SLATE("slate", "#264B73", "#9CC2EB"),
        TERRACOTTA("terracotta", "#8E3B2E", "#F0A89B"),
        INDIGO("indigo", "#3C3A85", "#B9B7F5"),
        PLUM("plum", "#6D305A", "#E5A9D3"),
        CUSTOM("custom", ThemePalette.formatHex(ThemePalette.DAY_ACCENT), ThemePalette.formatHex(ThemePalette.NIGHT_ACCENT));

        public final String wire;
        public final String lightAccentHex;
        public final String darkAccentHex;

        PalettePreset(String wire, String lightAccentHex, String darkAccentHex) {
            this.wire = wire;
            this.lightAccentHex = lightAccentHex;
            this.darkAccentHex = darkAccentHex;
        }

        public static PalettePreset parse(String wire) {
            if (wire == null || wire.trim().isEmpty()) return null;
            String normalized = wire.trim().toLowerCase(Locale.ROOT);
            for (PalettePreset preset : values()) {
                if (preset.wire.equals(normalized)) return preset;
            }
            return null;
        }
    }

    public enum Density {
        COMPACT("compact", 0.88f),
        COMFORTABLE("comfortable", 1.00f),
        SPACIOUS("spacious", 1.14f);

        public final String wire;
        public final float spacingMultiplier;

        Density(String wire, float spacingMultiplier) {
            this.wire = wire;
            this.spacingMultiplier = spacingMultiplier;
        }

        public static Density parse(String wire) {
            if (wire == null || wire.trim().isEmpty()) return null;
            String normalized = wire.trim().toLowerCase(Locale.ROOT);
            for (Density value : values()) {
                if (value.wire.equals(normalized)) return value;
            }
            return null;
        }
    }

    public enum StartupTab {
        CHATS("chats"),
        NOTES_TASKS("notes_tasks"),
        APPS("apps");

        public final String wire;

        StartupTab(String wire) { this.wire = wire; }

        public static StartupTab parse(String wire) {
            if (wire == null || wire.trim().isEmpty()) return null;
            return switch (wire.trim().toLowerCase(Locale.ROOT)) {
                case "chats", "discussions", "discussion" -> CHATS;
                case "notes_tasks", "tasks", "notes", "activite", "activity" -> NOTES_TASKS;
                case "apps", "atelier", "workspace", "cerveau", "brain" -> APPS;
                default -> null;
            };
        }
    }

    /** Composer quick-shortcut strictly following The Editable Draft Rule (stages draft, never auto-sends). */
    public record ComposerShortcut(String id, String label, String draftText) {
        public ComposerShortcut {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            if (!SHORTCUT_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid shortcut id");
            }
            label = sanitizePlainText(label, MAX_SHORTCUT_LABEL_CHARS);
            if (label.isEmpty()) {
                throw new IllegalArgumentException("Shortcut label required");
            }
            draftText = sanitizePlainText(draftText, MAX_SHORTCUT_DRAFT_CHARS);
            if (draftText.isEmpty()) {
                throw new IllegalArgumentException("Shortcut draft required");
            }
        }
    }

    public final PalettePreset palette;
    public final String customAccentHex;
    public final Density density;
    public final float fontScaleDelta;
    public final StartupTab startupTab;
    public final String companionGreeting;
    public final List<ComposerShortcut> composerShortcuts;

    public AppCustomization(
            PalettePreset palette,
            String customAccentHex,
            Density density,
            float fontScaleDelta,
            StartupTab startupTab,
            String companionGreeting,
            List<ComposerShortcut> composerShortcuts) {
        this.palette = Objects.requireNonNull(palette, "palette");
        if (palette == PalettePreset.CUSTOM) {
            this.customAccentHex = validateCustomAccentHex(customAccentHex);
        } else {
            this.customAccentHex = null;
        }
        this.density = Objects.requireNonNull(density, "density");
        if (!Float.isFinite(fontScaleDelta)) {
            throw new IllegalArgumentException("Invalid font scale delta");
        }
        this.fontScaleDelta = Math.round(clamp(fontScaleDelta, MIN_FONT_SCALE_DELTA, MAX_FONT_SCALE_DELTA) * 100f) / 100f;
        this.startupTab = Objects.requireNonNull(startupTab, "startupTab");
        this.companionGreeting = sanitizePlainText(companionGreeting, MAX_GREETING_CHARS);
        List<ComposerShortcut> safeShortcuts = new ArrayList<>();
        if (composerShortcuts != null) {
            for (ComposerShortcut item : composerShortcuts) {
                if (item == null || safeShortcuts.size() >= MAX_COMPOSER_SHORTCUTS) break;
                safeShortcuts.add(item);
            }
        }
        this.composerShortcuts = Collections.unmodifiableList(safeShortcuts);
    }

    public static AppCustomization defaults() {
        return new AppCustomization(
                PalettePreset.FOREST, null, Density.COMFORTABLE, 0.0f,
                StartupTab.CHATS, "", Collections.emptyList());
    }

    public AppCustomization withPalette(PalettePreset preset, String customHex) {
        return new AppCustomization(preset, customHex, density, fontScaleDelta,
                startupTab, companionGreeting, composerShortcuts);
    }

    public AppCustomization withDensity(Density nextDensity) {
        return new AppCustomization(palette, customAccentHex, nextDensity, fontScaleDelta,
                startupTab, companionGreeting, composerShortcuts);
    }

    public AppCustomization withFontScaleDelta(float delta) {
        return new AppCustomization(palette, customAccentHex, density, delta,
                startupTab, companionGreeting, composerShortcuts);
    }

    public AppCustomization withStartupTab(StartupTab tab) {
        return new AppCustomization(palette, customAccentHex, density, fontScaleDelta,
                tab, companionGreeting, composerShortcuts);
    }

    public AppCustomization withCompanionGreeting(String greeting) {
        return new AppCustomization(palette, customAccentHex, density, fontScaleDelta,
                startupTab, greeting, composerShortcuts);
    }

    public AppCustomization withComposerShortcuts(List<ComposerShortcut> shortcuts) {
        return new AppCustomization(palette, customAccentHex, density, fontScaleDelta,
                startupTab, companionGreeting, shortcuts);
    }

    /** Effective light-mode ARGB accent color guaranteed WCAG AA (&gt;= 4.5:1) against {@link #LIGHT_SURFACE_RGB}. */
    public int lightAccentArgb() {
        String hex = palette == PalettePreset.CUSTOM ? customAccentHex : palette.lightAccentHex;
        return 0xFF000000 | parseRgb(hex);
    }

    /** Effective dark-mode ARGB accent color guaranteed WCAG AA (&gt;= 4.5:1) against {@link #DARK_SURFACE_RGB}. */
    public int darkAccentArgb() {
        if (palette != PalettePreset.CUSTOM) {
            return 0xFF000000 | parseRgb(palette.darkAccentHex);
        }
        return 0xFF000000 | liftForDarkSurface(parseRgb(customAccentHex));
    }

    /** Picks white or deep ink foreground for maximum legibility (&gt;= 4.5:1) over the given ARGB accent. */
    public static int onAccentArgb(int accentArgb) {
        int rgb = accentArgb & 0xFFFFFF;
        double whiteRatio = contrastRatio(WHITE_RGB, rgb);
        double inkRatio = contrastRatio(LIGHT_INK_RGB, rgb);
        return (whiteRatio >= inkRatio ? WHITE_RGB : LIGHT_INK_RGB) | 0xFF000000;
    }

    /**
     * Bridges this {@link AppCustomization} onto an existing {@link ThemePalette.OverrideSpec},
     * selecting the light or dark accent for the effective theme mode (never system-only) so
     * WCAG AA (&gt;= 4.5:1) validation succeeds regardless of device Day/Night vs explicit theme.
     */
    public ThemePalette.OverrideSpec toThemeOverride(
            ThemePalette.OverrideSpec existing, boolean systemNight) throws IOException {
        ThemePalette.OverrideSpec base = existing == null ? ThemePalette.OverrideSpec.defaults() : existing;
        boolean effectiveNight = "dark".equals(base.theme) || ("system".equals(base.theme) && systemNight);
        String accentHex = null;
        if (palette != PalettePreset.FOREST || customAccentHex != null) {
            int candidate = effectiveNight ? darkAccentArgb() : lightAccentArgb();
            accentHex = ThemePalette.formatHex(ThemePalette.adaptAccentForMode(candidate, effectiveNight));
        }
        int fontStep = Math.max(ThemePalette.MIN_FONT_SCALE_STEP,
                Math.min(ThemePalette.MAX_FONT_SCALE_STEP, Math.round(fontScaleDelta * 20f)));
        return ThemePalette.OverrideSpec.validated(
                base.theme, accentHex, density.wire, fontStep, base.surfaceStyle, systemNight);
    }

    /**
     * Transitions an existing {@link ThemePalette.OverrideSpec} to {@code safeMode} ({@code system|light|dark}),
     * adapting any active accent color for the target surface polarity so contrast always passes WCAG AA.
     */
    public ThemePalette.OverrideSpec adaptOverrideForThemeMode(
            ThemePalette.OverrideSpec current, String safeMode, boolean systemNight) throws IOException {
        ThemePalette.OverrideSpec base = current == null ? ThemePalette.OverrideSpec.defaults() : current;
        boolean targetNight = "dark".equals(safeMode) || ("system".equals(safeMode) && systemNight);
        String adaptedAccent = base.accentHex;
        if (adaptedAccent != null && base.surfaceHex == null && base.accentInkHex == null) {
            int candidate = (palette != PalettePreset.FOREST || customAccentHex != null)
                    ? (targetNight ? darkAccentArgb() : lightAccentArgb())
                    : ThemePalette.parseHexColor(adaptedAccent);
            adaptedAccent = ThemePalette.formatHex(ThemePalette.adaptAccentForMode(candidate, targetNight));
        }
        return ThemePalette.OverrideSpec.validated(
                safeMode, adaptedAccent, base.accentInkHex, base.surfaceHex, base.inkHex,
                base.density, base.fontScaleStep, base.surfaceStyle, systemNight);
    }

    /** Validates a custom {@code #RRGGBB} hex accent against WCAG 2.1 AA (&gt;= 4.5:1 on light surface). */
    public static String validateCustomAccentHex(String rawHex) {
        if (rawHex == null || !HEX_COLOR.matcher(rawHex.trim()).matches()) {
            throw new IllegalArgumentException("Accent color must be #RRGGBB");
        }
        String normalized = rawHex.trim().toUpperCase(Locale.ROOT);
        int rgb = parseRgb(normalized);
        if (!meetsWcagAa(rgb, LIGHT_SURFACE_RGB)) {
            throw new IllegalArgumentException("Accent color fails WCAG AA 4.5:1 contrast");
        }
        return normalized;
    }

    public static boolean isAccessibleAccentHex(String rawHex) {
        try {
            validateCustomAccentHex(rawHex);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static int parseRgb(String hex) {
        if (hex == null || !HEX_COLOR.matcher(hex).matches()) {
            throw new IllegalArgumentException("Invalid hex color");
        }
        return Integer.parseInt(hex.substring(1), 16) & 0xFFFFFF;
    }

    /** WCAG 2.1 relative luminance for 24-bit sRGB color. */
    public static double relativeLuminance(int rgb) {
        double r = linearizeChannel(((rgb >> 16) & 0xFF) / 255.0);
        double g = linearizeChannel(((rgb >> 8) & 0xFF) / 255.0);
        double b = linearizeChannel((rgb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG 2.1 contrast ratio in {@code [1.0, 21.0]}. */
    public static double contrastRatio(int rgb1, int rgb2) {
        double l1 = relativeLuminance(rgb1);
        double l2 = relativeLuminance(rgb2);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    public static boolean meetsWcagAa(int fgRgb, int bgRgb) {
        return contrastRatio(fgRgb, bgRgb) >= WCAG_AA_MIN_CONTRAST;
    }

    /** Lifts a dark-friendly custom accent toward white until it clears WCAG AA (&gt;= 4.5:1) on dark surface. */
    public static int liftForDarkSurface(int rgb) {
        if (meetsWcagAa(rgb, DARK_SURFACE_RGB)) return rgb;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        for (int step = 1; step <= 20; step++) {
            double t = step / 20.0;
            int nr = (int) Math.round(r + (255 - r) * t);
            int ng = (int) Math.round(g + (255 - g) * t);
            int nb = (int) Math.round(b + (255 - b) * t);
            int candidate = (nr << 16) | (ng << 8) | nb;
            if (meetsWcagAa(candidate, DARK_SURFACE_RGB)) return candidate;
        }
        return WHITE_RGB;
    }

    public static String sanitizePlainText(String raw, int maxChars) {
        if (raw == null || raw.isEmpty()) return "";
        if (HermesWidgetSpec.containsSecretLikePattern(raw)) {
            throw new IllegalArgumentException("Secret-like content is not permitted in customization text");
        }
        StringBuilder out = new StringBuilder();
        boolean prevSpace = false;
        for (int i = 0; i < raw.length() && out.length() < maxChars; i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) continue;
            if (Character.isWhitespace(c)) {
                if (out.length() > 0 && !prevSpace) {
                    out.append(' ');
                    prevSpace = true;
                }
            } else {
                out.append(c);
                prevSpace = false;
            }
        }
        return out.toString().trim();
    }

    /** Compact pure-Java serialization (host-JVM testable, safe for encrypted vault storage). */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("v1|").append(palette.wire)
                .append('|').append(customAccentHex == null ? "" : customAccentHex)
                .append('|').append(density.wire)
                .append('|').append(String.format(Locale.ROOT, "%.2f", fontScaleDelta))
                .append('|').append(startupTab.wire)
                .append('|').append(b64(companionGreeting))
                .append('|').append(composerShortcuts.size());
        for (ComposerShortcut s : composerShortcuts) {
            sb.append('|').append(b64(s.id())).append(':').append(b64(s.label())).append(':').append(b64(s.draftText()));
        }
        return sb.toString();
    }

    /** Fail-closed decode: returns {@code null} on malformed data so callers can fall back cleanly. */
    public static AppCustomization decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 8 || !"v1".equals(parts[0])) {
            Diagnostics.warning("customization", "decode_invalid");
            return null;
        }
        try {
            PalettePreset p = PalettePreset.parse(parts[1]);
            if (p == null) return null;
            String customHex = parts[2].isEmpty() ? null : parts[2];
            Density d = Density.parse(parts[3]);
            if (d == null) return null;
            float delta = Float.parseFloat(parts[4]);
            StartupTab tab = StartupTab.parse(parts[5]);
            if (tab == null) return null;
            String greeting = unb64(parts[6]);
            int count = Integer.parseInt(parts[7]);
            if (count < 0 || count > MAX_COMPOSER_SHORTCUTS || parts.length != 8 + count) return null;
            List<ComposerShortcut> shortcuts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String[] triple = parts[8 + i].split(":", -1);
                if (triple.length != 3) return null;
                shortcuts.add(new ComposerShortcut(unb64(triple[0]), unb64(triple[1]), unb64(triple[2])));
            }
            return new AppCustomization(p, customHex, d, delta, tab, greeting, shortcuts);
        } catch (IllegalArgumentException invalid) {
            Diagnostics.warning("customization", "decode_invalid");
            return null;
        }
    }

    private static double linearizeChannel(double c) {
        return c <= 0.04045 ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AppCustomization o)) return false;
        return palette == o.palette
                && Objects.equals(customAccentHex, o.customAccentHex)
                && density == o.density
                && Float.compare(fontScaleDelta, o.fontScaleDelta) == 0
                && startupTab == o.startupTab
                && companionGreeting.equals(o.companionGreeting)
                && composerShortcuts.equals(o.composerShortcuts);
    }

    @Override public int hashCode() {
        return Objects.hash(palette, customAccentHex, density, fontScaleDelta,
                startupTab, companionGreeting, composerShortcuts);
    }
}
