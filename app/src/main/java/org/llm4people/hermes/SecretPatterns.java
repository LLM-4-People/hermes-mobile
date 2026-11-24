package org.llm4people.hermes;

import java.util.regex.Pattern;

/**
 * Single Source of Truth for credential/secret pattern detection across widgets, notifications,
 * Live UI patches, workflow templates, customization, presence, and location labels.
 * Uses {@link PlainTextSanitizer.DetectionView} so zero-width characters, NUL bytes, control
 * whitespace ({@code \n}, {@code \r}, {@code \t}, {@code \u000B}, {@code \u000C}, {@code \u0085}),
 * Unicode space characters ({@code \u00A0}, {@code \u2007}, {@code \u202F}, {@code \u3000}), bidi
 * controls, and Plane 14 tags cannot bypass detection, while avoiding word-interior false positives.
 */
public final class SecretPatterns {
    private static final Pattern BOUNDED_CREDENTIAL = Pattern.compile(
            "(?i)(?:bearer\\s+[a-z0-9._\\-]{8,}|sk-[a-z0-9_\\-]{12,}|gh[pousr]_[a-z0-9_]{12,})"
                    + "|eyJ[A-Za-z0-9_\\-]{12,}");
    private static final Pattern PEM_HEADER = Pattern.compile("(?i)-----BEGIN\\s+");

    private SecretPatterns() {}

    public static boolean containsCredential(String text) {
        if (text == null || text.isEmpty()) return false;
        return containsCredentialInView(PlainTextSanitizer.DetectionView.of(text));
    }

    public static boolean containsCredentialInView(PlainTextSanitizer.DetectionView view) {
        if (view == null || view.isEmpty()) return false;
        return view.matches(BOUNDED_CREDENTIAL, true, false)
                || view.matches(PEM_HEADER, false, false);
    }
}
