package org.llm4people.hermes;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single Source of Truth for pure-Java bounded plain-text sanitization and obfuscation-resilient
 * normalization shared by {@link SecretPatterns} and {@code NotificationPrivacyGuard}.
 * Iterates by Unicode code point, strips ISO control, Unicode format (including Plane 14 tags),
 * and unpaired surrogate code points, normalizes all Unicode whitespace/space characters (including
 * NBSP {@code \u00A0}, figure space {@code \u2007}, narrow NBSP {@code \u202F}, ideographic space
 * {@code \u3000}) to single ASCII spaces, and never splits surrogate pairs.
 */
public final class PlainTextSanitizer {
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private PlainTextSanitizer() {}

    /** Accent- and case-insensitive normalization (NFD decomposition, combining marks stripped, root lowercase). */
    public static String foldAccents(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return COMBINING_MARKS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Strips ISO control, format, unpaired surrogate, standalone strippable invisible, and unattached
     * variation-selector code points, collapses consecutive whitespace/space code points to single
     * ASCII spaces, trims, and bounds to {@code maxChars} UTF-16 code units without splitting surrogate pairs.
     */
    public static String collapseBounded(String raw, int maxChars) {
        if (raw == null || raw.isEmpty() || maxChars <= 0) return "";
        StringBuilder out = new StringBuilder();
        boolean prevSpace = false;
        boolean prevVisibleBase = false;
        boolean hasVisible = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int step = Character.charCount(cp);
            i += step;
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE
                    || isStrippableInvisible(cp)) {
                continue;
            }
            if (isVariationSelector(cp)) {
                if (!prevVisibleBase) continue;
                if (step > maxChars - out.length()) break;
                out.appendCodePoint(cp);
                prevVisibleBase = false;
                continue;
            }
            if (isAnySpace(cp)) {
                if (out.length() > 0 && !prevSpace) {
                    if (out.length() >= maxChars) break;
                    out.append(' ');
                    prevSpace = true;
                }
                prevVisibleBase = false;
            } else {
                if (step > maxChars - out.length()) break;
                out.appendCodePoint(cp);
                prevSpace = false;
                prevVisibleBase = true;
                hasVisible = true;
            }
        }
        return hasVisible ? out.toString().trim() : "";
    }

    /**
     * Code-point-safe preview sanitizer that strips format (bidi/zero-width/Plane 14 tags),
     * standalone strippable invisibles (CGJ, Hangul fillers, Mongolian FVS), unattached variation
     * selectors, and unpaired surrogates, collapses ISO controls and Unicode whitespace/space
     * characters to single ASCII spaces, and truncates with {@code "…"} without splitting surrogate pairs.
     */
    public static String collapseWithEllipsis(String raw, int maxChars) {
        if (raw == null || maxChars <= 0) return "";
        StringBuilder out = new StringBuilder((int) Math.min((long) raw.length(), (long) maxChars + 2L));
        boolean prevSpace = false;
        boolean prevVisibleBase = false;
        boolean hasVisible = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int step = Character.charCount(cp);
            i += step;
            int type = Character.getType(cp);
            if (type == Character.FORMAT || type == Character.SURROGATE || isStrippableInvisible(cp)) {
                continue;
            }
            if (isVariationSelector(cp)) {
                if (!prevVisibleBase) continue;
                out.appendCodePoint(cp);
                prevVisibleBase = false;
            } else if (Character.isISOControl(cp) || isAnySpace(cp)) {
                if (out.length() > 0 && !prevSpace) {
                    out.append(' ');
                    prevSpace = true;
                }
                prevVisibleBase = false;
            } else {
                out.appendCodePoint(cp);
                prevSpace = false;
                prevVisibleBase = true;
                hasVisible = true;
            }
            if (!prevSpace && out.length() > maxChars) break;
        }
        if (!hasVisible) return "";
        String trimmed = out.toString().trim();
        if (trimmed.length() <= maxChars) return trimmed;
        if (maxChars == 1) return "…";
        int cut = maxChars - 1;
        if (cut > 0 && Character.isHighSurrogate(trimmed.charAt(cut - 1))) {
            cut--;
        }
        String prefix = trimmed.substring(0, cut).trim();
        return hasVisibleText(prefix) ? prefix + "…" : "…";
    }

    /** True when {@code raw} contains at least one visible (non-space, non-control, non-format, non-invisible-mark) code point. */
    public static boolean hasVisibleText(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (!isAnySpace(cp) && !Character.isISOControl(cp)
                    && type != Character.FORMAT && type != Character.SURROGATE
                    && !isInvisibleMark(cp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code cp} is safe in display text (rejects ISO controls, unpaired surrogates,
     * Unicode line/paragraph separators, standalone strippable invisibles, and format controls
     * other than ZWNJ {@code U+200C} and ZWJ {@code U+200D}).
     */
    public static boolean isSafeDisplayCodePoint(int cp) {
        int type = Character.getType(cp);
        return !Character.isISOControl(cp)
                && type != Character.SURROGATE
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR
                && !isStrippableInvisible(cp)
                && (type != Character.FORMAT || cp == 0x200C || cp == 0x200D);
    }

    /**
     * True when {@code raw} contains any code point unsafe for multiline display text
     * (permits {@code '\n'}, and {@code '\t'} when {@code allowTab} is true).
     */
    public static boolean hasUnsafeMultilineCodePoint(String raw, boolean allowTab) {
        if (raw == null || raw.isEmpty()) return false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || (allowTab && cp == '\t')) continue;
            if (!isSafeDisplayCodePoint(cp)) return true;
        }
        return false;
    }

    /** True when {@code raw} contains any ISO control, format, unpaired surrogate, line/paragraph separator, or standalone strippable invisible code point. */
    public static boolean hasUnsafeSingleLineCodePoint(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || isStrippableInvisible(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** Standalone invisible filler/joiner code points (CGJ, Hangul fillers, Mongolian FVS) safe to strip from display/single-line text. */
    public static boolean isStrippableInvisible(int cp) {
        return cp == 0x034F
                || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0
                || (cp >= 0x180B && cp <= 0x180F);
    }

    /** Unicode Variation Selectors (BMP VS1..VS16 and Plane-14 VS17..VS256). */
    public static boolean isVariationSelector(int cp) {
        return (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    private static boolean isAnySpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static boolean isControlWhitespace(int cp) {
        return cp == '\n' || cp == '\r' || cp == '\t'
                || cp == 0x0B || cp == 0x0C || cp == 0x85
                || (cp >= 0x1C && cp <= 0x1F);
    }

    private static boolean isInvisibleMark(int cp) {
        return isStrippableInvisible(cp) || isVariationSelector(cp);
    }

    private static boolean isCombiningMark(int type) {
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean hasCombiningMark(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCombiningMark(Character.getType(cp))) return true;
        }
        return false;
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static final class Form {
        final String text;
        final boolean[] sepBefore;
        final boolean[] sepAfter;

        Form(String text, boolean[] sepBefore, boolean[] sepAfter) {
            this.text = text;
            this.sepBefore = sepBefore;
            this.sepAfter = sepAfter;
        }

        boolean matches(Pattern pattern, boolean requireStartBoundary, boolean requireEndBoundary) {
            if (text.isEmpty()) return false;
            Matcher m = pattern.matcher(text);
            int searchFrom = 0;
            while (searchFrom < text.length() && m.find(searchFrom)) {
                int start = m.start();
                int end = m.end();
                boolean startOk = !requireStartBoundary
                        || start == 0
                        || !isWordChar(text.charAt(start - 1))
                        || (sepBefore != null && sepBefore[start]);
                boolean endOk = !requireEndBoundary
                        || end >= text.length()
                        || !isWordChar(text.charAt(end))
                        || (sepAfter != null && sepAfter[end - 1]);
                if (startOk && endOk) return true;
                searchFrom = start + 1;
            }
            return false;
        }
    }

    /**
     * Multi-view obfuscation-resilient text representation combining raw text, control/format/
     * invisible-mark-stripped text, space-preserving normalized text, and NFD combining-mark-stripped
     * text with per-character stripped-separator boundary metadata.
     */
    public static final class DetectionView {
        private static final DetectionView EMPTY = new DetectionView(null, null, null, null, null);

        private final Form raw;
        private final Form stripped;
        private final Form spaced;
        private final Form foldedStripped;
        private final Form foldedSpaced;

        private DetectionView(Form raw, Form stripped, Form spaced, Form foldedStripped, Form foldedSpaced) {
            this.raw = raw;
            this.stripped = stripped;
            this.spaced = spaced;
            this.foldedStripped = foldedStripped;
            this.foldedSpaced = foldedSpaced;
        }

        public static DetectionView of(String raw) {
            if (raw == null || raw.isEmpty()) return EMPTY;
            Form rawForm = new Form(raw, null, null);
            Form strippedForm = buildForm(raw, false, false);
            Form spacedForm = buildForm(raw, true, false);
            if (sameForm(spacedForm, strippedForm)) {
                spacedForm = null;
            }
            Form foldedStrippedForm = null;
            Form foldedSpacedForm = null;
            String nfd = Normalizer.normalize(raw, Normalizer.Form.NFD);
            if (hasCombiningMark(nfd) || !nfd.equals(raw)) {
                Form candidateStripped = buildForm(nfd, false, true);
                if (!sameForm(candidateStripped, strippedForm) && !sameForm(candidateStripped, spacedForm)) {
                    foldedStrippedForm = candidateStripped;
                }
                Form candidateSpaced = buildForm(nfd, true, true);
                if (!sameForm(candidateSpaced, strippedForm)
                        && !sameForm(candidateSpaced, spacedForm)
                        && !sameForm(candidateSpaced, foldedStrippedForm)) {
                    foldedSpacedForm = candidateSpaced;
                }
            }
            return new DetectionView(rawForm, strippedForm, spacedForm, foldedStrippedForm, foldedSpacedForm);
        }

        public boolean isEmpty() {
            return raw == null;
        }

        public boolean matches(Pattern pattern, boolean requireStartBoundary, boolean requireEndBoundary) {
            if (raw == null) return false;
            if (raw.matches(pattern, requireStartBoundary, requireEndBoundary)) return true;
            if (stripped != null && stripped.matches(pattern, requireStartBoundary, requireEndBoundary)) return true;
            if (spaced != null && spaced.matches(pattern, requireStartBoundary, requireEndBoundary)) return true;
            if (foldedStripped != null && foldedStripped.matches(pattern, requireStartBoundary, requireEndBoundary)) {
                return true;
            }
            return foldedSpaced != null && foldedSpaced.matches(pattern, requireStartBoundary, requireEndBoundary);
        }

        private static boolean sameForm(Form a, Form b) {
            if (a == null || b == null) return false;
            if (!a.text.equals(b.text)) return false;
            return sameBoundaryFlags(a, b);
        }

        private static boolean sameBoundaryFlags(Form a, Form b) {
            int n = a.text.length();
            for (int i = 0; i < n; i++) {
                if (a.sepBefore[i] != b.sepBefore[i] || a.sepAfter[i] != b.sepAfter[i]) return false;
            }
            return true;
        }

        private static Form buildForm(String raw, boolean preserveControlWhitespace, boolean stripCombiningMarks) {
            int cap = raw.length();
            StringBuilder out = new StringBuilder(cap);
            boolean[] sepBefore = new boolean[cap + 1];
            boolean[] sepAfter = new boolean[cap + 1];
            boolean pendingSep = false;
            boolean prevSpace = false;
            for (int i = 0; i < raw.length(); ) {
                int cp = raw.codePointAt(i);
                int step = Character.charCount(cp);
                i += step;
                boolean controlWs = isControlWhitespace(cp);
                int type = Character.getType(cp);
                boolean stripped = (Character.isISOControl(cp) && !(preserveControlWhitespace && controlWs))
                        || type == Character.FORMAT
                        || type == Character.SURROGATE
                        || isInvisibleMark(cp);
                if (stripped) {
                    pendingSep = true;
                    if (out.length() > 0) sepAfter[out.length() - 1] = true;
                    continue;
                }
                if (stripCombiningMarks && isCombiningMark(type)) {
                    // Strip ordinary combining diacritics without injecting spurious word-boundary breaks.
                    continue;
                }
                if (isAnySpace(cp) || (preserveControlWhitespace && controlWs)) {
                    pendingSep = true;
                    if (out.length() > 0) sepAfter[out.length() - 1] = true;
                    if (out.length() > 0 && !prevSpace) {
                        sepBefore[out.length()] = true;
                        out.append(' ');
                        prevSpace = true;
                    }
                    continue;
                }
                int pos = out.length();
                if (pendingSep) sepBefore[pos] = true;
                out.appendCodePoint(cp);
                pendingSep = false;
                prevSpace = false;
            }
            int len = out.length();
            if (len > 0 && out.charAt(len - 1) == ' ') {
                out.setLength(len - 1);
                if (len - 1 > 0) sepAfter[len - 2] = true;
            }
            return new Form(out.toString(), sepBefore, sepAfter);
        }
    }
}
