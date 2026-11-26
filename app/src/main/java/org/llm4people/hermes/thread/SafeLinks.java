package org.llm4people.hermes.thread;

import org.llm4people.hermes.PlainTextSanitizer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Single Source of Truth for validating external links from Markdown messages.
 * Permits only well-formed {@code https://} URLs with a valid host and rejects all dangerous schemes.
 */
public final class SafeLinks {
    static final int MAX_URL_LENGTH = 2048;

    public record Validated(String url, String host) {}

    private SafeLinks() {}

    /** Validate a raw URL. Returns null for any non-HTTPS, malformed, or deceptive URI. */
    public static Validated validate(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH) return null;
        if (PlainTextSanitizer.hasUnsafeSingleLineCodePoint(trimmed)) return null;
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            if (cp <= 0x20 || cp == 0x7f || cp == '\\'
                    || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                return null;
            }
            i += Character.charCount(cp);
        }
        if (!trimmed.regionMatches(true, 0, "https://", 0, 8)) return null;
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException invalid) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
        if (uri.getRawUserInfo() != null) return null;
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (!validHost(lowerHost)) return null;
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65535) return null;
        String normalized = "https" + trimmed.substring(5);
        return new Validated(normalized, lowerHost);
    }

    private static boolean validHost(String host) {
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) return false;
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.length() > 2;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
            if (!ok) return false;
        }
        return true;
    }
}
