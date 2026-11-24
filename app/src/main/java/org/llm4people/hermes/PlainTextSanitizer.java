package org.llm4people.hermes;

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
    private PlainTextSanitizer() {}

    /**
     * Strips ISO control, format, and unpaired surrogate code points, collapses consecutive
     * whitespace/space code points to single ASCII spaces, trims, and bounds to {@code maxChars}
     * UTF-16 code units without splitting surrogate pairs.
     */
    public static String collapseBounded(String raw, int maxChars) {
        if (raw == null || raw.isEmpty() || maxChars <= 0) return "";
        StringBuilder out = new StringBuilder();
        boolean prevSpace = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int step = Character.charCount(cp);
            i += step;
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE) {
                continue;
            }
            if (isAnySpace(cp)) {
                if (out.length() > 0 && !prevSpace) {
                    if (out.length() + 1 > maxChars) break;
                    out.append(' ');
                    prevSpace = true;
                }
            } else {
                if (out.length() + step > maxChars) break;
                out.appendCodePoint(cp);
                prevSpace = false;
            }
        }
        return out.toString().trim();
    }

    /**
     * Code-point-safe preview sanitizer that strips format (bidi/zero-width/Plane 14 tags) and
     * unpaired surrogates, collapses ISO controls and Unicode whitespace/space characters to single
     * ASCII spaces, and truncates with {@code "…"} without splitting surrogate pairs.
     */
    public static String collapseWithEllipsis(String raw, int maxChars) {
        if (raw == null || maxChars <= 0) return "";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxChars + 2));
        boolean prevSpace = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            int step = Character.charCount(cp);
            i += step;
            int type = Character.getType(cp);
            if (type == Character.FORMAT || type == Character.SURROGATE) {
                continue;
            }
            if (Character.isISOControl(cp) || isAnySpace(cp)) {
                if (out.length() > 0 && !prevSpace) {
                    out.append(' ');
                    prevSpace = true;
                }
            } else {
                out.appendCodePoint(cp);
                prevSpace = false;
            }
            if (out.length() > maxChars) break;
        }
        String trimmed = out.toString().trim();
        if (trimmed.length() <= maxChars) return trimmed;
        if (maxChars == 1) return "…";
        int cut = maxChars - 1;
        if (cut > 0 && Character.isHighSurrogate(trimmed.charAt(cut - 1))) {
            cut--;
        }
        return trimmed.substring(0, cut).trim() + "…";
    }

    private static boolean isAnySpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static boolean isControlWhitespace(int cp) {
        return cp == '\n' || cp == '\r' || cp == '\t'
                || cp == 0x0B || cp == 0x0C || cp == 0x85
                || (cp >= 0x1C && cp <= 0x1F);
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
     * Multi-view obfuscation-resilient text representation combining raw text, control/format-stripped
     * text, and space-preserving normalized text with per-character stripped-separator boundary metadata.
     */
    public static final class DetectionView {
        private static final DetectionView EMPTY = new DetectionView(null, null, null);

        private final Form raw;
        private final Form stripped;
        private final Form spaced;

        private DetectionView(Form raw, Form stripped, Form spaced) {
            this.raw = raw;
            this.stripped = stripped;
            this.spaced = spaced;
        }

        public static DetectionView of(String raw) {
            if (raw == null || raw.isEmpty()) return EMPTY;
            Form rawForm = new Form(raw, null, null);
            Form strippedForm = buildForm(raw, false);
            Form spacedForm = buildForm(raw, true);
            if (spacedForm.text.equals(strippedForm.text)
                    && sameBoundaryFlags(spacedForm, strippedForm)) {
                spacedForm = null;
            }
            return new DetectionView(rawForm, strippedForm, spacedForm);
        }

        public boolean isEmpty() {
            return raw == null;
        }

        public boolean matches(Pattern pattern, boolean requireStartBoundary, boolean requireEndBoundary) {
            if (raw == null) return false;
            if (raw.matches(pattern, requireStartBoundary, requireEndBoundary)) return true;
            if (stripped != null && stripped.matches(pattern, requireStartBoundary, requireEndBoundary)) return true;
            return spaced != null && spaced.matches(pattern, requireStartBoundary, requireEndBoundary);
        }

        private static boolean sameBoundaryFlags(Form a, Form b) {
            int n = a.text.length();
            for (int i = 0; i < n; i++) {
                if (a.sepBefore[i] != b.sepBefore[i] || a.sepAfter[i] != b.sepAfter[i]) return false;
            }
            return true;
        }

        private static Form buildForm(String raw, boolean preserveControlWhitespace) {
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
                        || type == Character.SURROGATE;
                if (stripped) {
                    pendingSep = true;
                    if (out.length() > 0) sepAfter[out.length() - 1] = true;
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
