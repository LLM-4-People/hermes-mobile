package org.llm4people.hermes.profile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical HTTP base for a private gateway, including a single-host {@code /p/<profile>} prefix. */
public final class GatewayAddress {
    private static final Pattern PROFILE_SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9_-]{0,30}[a-z0-9])?");
    private static final Pattern TRAILING_PROFILE = Pattern.compile("^(.*)/p/([^/]+)$");

    private GatewayAddress() {}

    /** Normalize a direct gateway base URL (with optional {@code /p/<profile>} path prefix). */
    public static String normalize(String input) {
        try {
            URI uri = new URI(input.trim());
            String host = uri.getHost();
            String path = uri.getRawPath();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean loopback = "127.0.0.1".equals(host) || "[::1]".equals(host);
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":") || path == null
                    || !(scheme.equals("https") || scheme.equals("http") && loopback))
                throw new IllegalArgumentException("Invalid gateway address");
            for (String segment : path.split("/")) {
                if (segment.equals("..") || segment.equals(".") || !segment.matches("[A-Za-z0-9._~-]*"))
                    throw new IllegalArgumentException("Invalid gateway path");
            }
            if (path.contains("//")) throw new IllegalArgumentException("Invalid gateway path");
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            path = collapseDuplicateProfilePrefixes(path);
            return scheme + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + path;
        } catch (URISyntaxException | NullPointerException ex) {
            throw new IllegalArgumentException("Invalid gateway address", ex);
        }
    }

    /**
     * Compose one shared server URL and an optional server profile slug into a canonical endpoint.
     * Leaving {@code serverProfile} blank preserves direct URLs as-is.
     */
    public static String normalize(String serverInput, String serverProfile) {
        String base = normalize(serverInput);
        String slug = normalizeProfileSlug(serverProfile);
        if (slug.isEmpty()) return base;

        URI uri = URI.create(base);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        if (path.equals("/p") || path.endsWith("/p")) {
            path = path.substring(0, path.length() - 2);
        } else {
            Matcher existing = TRAILING_PROFILE.matcher(path);
            if (existing.matches()) {
                String current = existing.group(2);
                if (!current.equalsIgnoreCase(slug)) {
                    throw new IllegalArgumentException("Server URL already targets a different profile");
                }
                path = existing.group(1);
            }
        }
        return origin + path + "/p/" + slug;
    }

    /**
     * Normalize an optional server profile slug (e.g. {@code "alice"}, {@code "/p/alice/"}, or
     * {@code "/p/p/alice"} -> {@code "alice"}). Returns {@code ""} when blank; throws on traversal
     * or malformed slugs.
     */
    public static String normalizeProfileSlug(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.contains("..") || trimmed.contains("//") || trimmed.indexOf('\\') >= 0
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid server profile");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("/p") || lower.equals("/p/") || lower.equals("p/")) {
            throw new IllegalArgumentException("Invalid server profile");
        }
        String work = trimmed;
        boolean hadLeadingSlash = work.startsWith("/");
        while (work.startsWith("/")) work = work.substring(1);
        boolean strippedPrefix = false;
        while (work.length() >= 2 && (work.startsWith("p/") || work.startsWith("P/"))) {
            work = work.substring(2);
            strippedPrefix = true;
            if (work.startsWith("/")) throw new IllegalArgumentException("Invalid server profile");
        }
        if (work.endsWith("/")) work = work.substring(0, work.length() - 1);
        if (work.isEmpty() || ((hadLeadingSlash || strippedPrefix) && work.equalsIgnoreCase("p"))) {
            throw new IllegalArgumentException("Invalid server profile");
        }
        String slug = work.toLowerCase(Locale.ROOT);
        if (!PROFILE_SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("Invalid server profile");
        }
        return slug;
    }

    private static String collapseDuplicateProfilePrefixes(String path) {
        if (path.isEmpty()) return "";
        String[] parts = path.substring(1).split("/");
        StringBuilder out = new StringBuilder(path.length());
        boolean previousWasPrefix = false;
        for (String part : parts) {
            boolean isPrefix = part.equalsIgnoreCase("p");
            if (isPrefix && previousWasPrefix) continue;
            out.append('/').append(isPrefix ? "p" : part);
            previousWasPrefix = isPrefix;
        }
        return out.toString();
    }
}
