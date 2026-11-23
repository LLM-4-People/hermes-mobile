package org.llm4people.hermes.net;

import org.llm4people.hermes.profile.GatewayAddress;
import java.net.URI;
import java.net.URL;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Constructs every request underneath the configured origin and profile path. */
final class Endpoint {
    private final String base;

    Endpoint(String endpoint) {
        base = GatewayAddress.normalize(endpoint);
    }

    URL url(String path) throws MalformedURLException {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("Expected a profile-relative API path");
        }
        URI uri = URI.create(path);
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid API path");
        }
        validatePath(uri);
        return URI.create(base + uri.toASCIIString()).toURL();
    }

    static String segment(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals(".") || raw.equals("..") ||
                raw.indexOf('/') >= 0 || raw.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid path segment");
        }
        StringBuilder out = new StringBuilder();
        for (byte item : raw.getBytes(StandardCharsets.UTF_8)) {
            int b = item & 255;
            if (b < 32 || b == 127) throw new IllegalArgumentException("Invalid path segment");
            if (b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z' || b >= '0' && b <= '9' ||
                    b == '-' || b == '_' || b == '.' || b == '~') {
                out.append((char) b);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(b >>> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 15, 16)));
            }
        }
        return out.toString();
    }

    /** Encode a multi-segment relative API path such as a nested skill name ("travail/revue"). */
    static String relativePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.endsWith("/") || raw.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid relative path");
        }
        String[] parts = raw.split("/", -1);
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('/');
            out.append(segment(parts[i]));
        }
        return out.toString();
    }

    /** RFC 3986 percent-encoding for UTF-8 query parameter values. */
    static String queryValue(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (byte item : raw.getBytes(StandardCharsets.UTF_8)) {
            int b = item & 255;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~') {
                out.append((char) b);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(b >>> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 15, 16)));
            }
        }
        return out.toString();
    }

    private static void validatePath(URI uri) {
        String raw = uri.getRawPath().toLowerCase(Locale.ROOT);
        if (raw.contains("%2f") || raw.contains("%5c") || raw.contains("//")) {
            throw new IllegalArgumentException("Invalid path separator");
        }
        for (String part : uri.getPath().split("/")) {
            if (part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0 ||
                    part.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid path segment");
            }
        }
    }

}
