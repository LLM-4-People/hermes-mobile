package org.llm4people.hermes.profile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Canonical HTTP base for a private gateway, including a deployment or named-profile prefix. */
public final class GatewayAddress {
    private GatewayAddress() {}

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
                throw new IllegalArgumentException();
            for (String segment : path.split("/")) {
                if (segment.equals("..") || segment.equals(".") || !segment.matches("[A-Za-z0-9._~-]*"))
                    throw new IllegalArgumentException();
            }
            if (path.contains("//")) throw new IllegalArgumentException();
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return scheme + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + path;
        } catch (URISyntaxException | NullPointerException ex) {
            throw new IllegalArgumentException("Invalid gateway address", ex);
        }
    }
}
