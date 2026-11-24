package org.llm4people.hermes;

import java.util.regex.Pattern;

/**
 * Single Source of Truth for credential/secret pattern detection across widgets, notifications,
 * Live UI patches, workflow templates, customization, presence, and location labels.
 * Evaluates raw text, control/format-stripped text, and whitespace-normalized text so obfuscation
 * via zero-width characters, NUL bytes, newlines, bidi controls, or Plane 14 tags cannot bypass detection.
 */
public final class SecretPatterns {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(?:\\bbearer\\s+[a-z0-9._\\-]{8,}|\\bsk-[a-z0-9_\\-]{12,}|\\bgh[pousr]_[a-z0-9_]{12,}|-----BEGIN\\s+)"
                    + "|\\beyJ[A-Za-z0-9_\\-]{12,}");

    private SecretPatterns() {}

    public static boolean containsCredential(String text) {
        if (text == null || text.isEmpty()) return false;
        if (CREDENTIAL.matcher(text).find()) return true;
        String collapsed = PlainTextSanitizer.collapseBounded(text, text.length());
        if (!collapsed.isEmpty() && CREDENTIAL.matcher(collapsed).find()) return true;
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\t') >= 0) {
            String spaced = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
            String spacedCollapsed = PlainTextSanitizer.collapseBounded(spaced, spaced.length());
            return !spacedCollapsed.isEmpty() && CREDENTIAL.matcher(spacedCollapsed).find();
        }
        return false;
    }
}
