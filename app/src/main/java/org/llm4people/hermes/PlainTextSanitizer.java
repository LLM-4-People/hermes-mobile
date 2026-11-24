package org.llm4people.hermes;

/**
 * Single Source of Truth for pure-Java bounded plain-text sanitization.
 * Iterates by Unicode code point, strips ISO control, Unicode format (including Plane 14 tags),
 * and unpaired surrogate code points, collapses consecutive whitespace into single ASCII spaces,
 * trims leading/trailing whitespace, and enforces a strict UTF-16 length bound without splitting
 * surrogate pairs.
 */
public final class PlainTextSanitizer {
    private PlainTextSanitizer() {}

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
            if (Character.isWhitespace(cp)) {
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
}
