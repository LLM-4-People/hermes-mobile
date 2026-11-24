package org.llm4people.hermes;

/**
 * Single Source of Truth for pure-Java bounded plain-text sanitization.
 * Strips ISO control and Unicode format characters, collapses consecutive whitespace into single
 * ASCII spaces, trims leading/trailing whitespace, and enforces a strict character bound.
 */
public final class PlainTextSanitizer {
    private PlainTextSanitizer() {}

    public static String collapseBounded(String raw, int maxChars) {
        if (raw == null || raw.isEmpty() || maxChars <= 0) return "";
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
}
