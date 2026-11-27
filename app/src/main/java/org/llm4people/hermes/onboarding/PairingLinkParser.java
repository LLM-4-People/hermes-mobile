package org.llm4people.hermes.onboarding;

import org.llm4people.hermes.Digests;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.profile.GatewayAddress;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single Source of Truth parser for zero-friction onboarding links and smart paste.
 * Accepts {@code hermes://pair?host=...&code=...}, HTTPS invite links, {@code /p/<slug>} URLs,
 * multi-line or labelled {@code host + key} pastes, and JSON snippets.
 * Fail-closed: enforces HTTPS (or loopback for local dev/tests), rejects control characters,
 * embedded credentials, path traversal, and dangerous schemes. Never logs secrets.
 */
public final class PairingLinkParser {
    public static final int MAX_INPUT_CHARS = 4096;
    public static final int MAX_DISPLAY_NAME_CHARS = 64;

    private static final Pattern PAIRING_CODE = Pattern.compile("[A-Za-z0-9._-]{8,160}");
    private static final Pattern TRAILING_PAIR_PATH = Pattern.compile("^(.*?)/(?:pair|invite)/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_TOKEN = Pattern.compile("(?:hermes://[^\\s\"'<>]+|https?://[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE);

    public enum Kind {
        PAIRING_TICKET,
        DIRECT_CREDENTIALS,
        SERVER_ONLY
    }

    public record ParsedInput(
            Kind kind,
            String serverUrl,
            String profileSlug,
            String pairingCode,
            String apiKey,
            String displayName,
            String mode) {
        public ParsedInput {
            Objects.requireNonNull(kind, "kind");
            serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
            profileSlug = profileSlug == null ? "" : profileSlug;
            pairingCode = pairingCode == null ? "" : pairingCode;
            apiKey = apiKey == null ? "" : apiKey;
            displayName = displayName == null ? "" : displayName;
            mode = mode == null ? "" : mode;
        }

        /** Canonical gateway endpoint combining {@link #serverUrl} and optional {@link #profileSlug}. */
        public String composedEndpoint() {
            return profileSlug.isEmpty() ? serverUrl : GatewayAddress.normalize(serverUrl, profileSlug);
        }
    }

    private PairingLinkParser() {}

    /** Returns true when {@code code} matches the bounded high-entropy pairing ticket format. */
    public static boolean isValidPairingCode(String code) {
        return code != null && PAIRING_CODE.matcher(code.trim()).matches();
    }

    /** Returns true when {@code key} is a bounded visible-ASCII API bearer key without whitespace. */
    public static boolean isValidApiKey(String key) {
        if (key == null) return false;
        String trimmed = key.trim();
        if (trimmed.length() < 8 || trimmed.length() > 512) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 33 || c > 126) return false;
        }
        return true;
    }

    /** Parse any supported onboarding link, JSON snippet, or pasted setup text; returns null if invalid. */
    public static ParsedInput parseOrNull(String raw) {
        try {
            return parse(raw);
        } catch (IllegalArgumentException rejected) {
            return null;
        }
    }

    /**
     * Parse any supported onboarding link, JSON snippet, or pasted setup text.
     * Throws {@link IllegalArgumentException} on malformed, blank, or unsafe inputs.
     */
    public static ParsedInput parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing onboarding input");
        if (raw.length() > MAX_INPUT_CHARS) throw new IllegalArgumentException("Onboarding input too long");
        rejectUnsafeControls(raw);
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Blank onboarding input");

        if (trimmed.startsWith("{")) {
            if (!trimmed.endsWith("}")) {
                throw new IllegalArgumentException("Malformed JSON onboarding snippet");
            }
            return parseJsonSnippet(trimmed);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("hermes://")) {
            return parseDeepLink(trimmed);
        }
        if ((lower.startsWith("https://") || lower.startsWith("http://")) && !containsWhitespace(trimmed)) {
            return parseHttpUrl(trimmed);
        }
        return parseSmartText(trimmed);
    }

    private static ParsedInput parseDeepLink(String raw) {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (Exception bad) {
            throw new IllegalArgumentException("Malformed pairing link", bad);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String authority = uri.getRawAuthority() == null ? "" : uri.getRawAuthority().toLowerCase(Locale.ROOT);
        if (!"hermes".equals(scheme) || !"pair".equals(authority)) {
            throw new IllegalArgumentException("Unsupported Hermes link host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Userinfo not allowed in pairing link");
        }
        Map<String, String> params = parseQuery(uri.getRawQuery());
        String rawHost = firstNonEmpty(params, "host", "server", "server_url", "url");
        String code = firstNonEmpty(params, "code", "ticket", "token");
        String profile = firstNonEmpty(params, "profile", "profile_slug", "slug");
        String name = firstNonEmpty(params, "name", "display_name");
        String mode = firstNonEmpty(params, "mode");
        if (rawHost.isEmpty() || !isValidPairingCode(code)) {
            throw new IllegalArgumentException("Pairing link requires host and valid code");
        }
        SplitHost split = splitHostAndSlug(rawHost, profile);
        return new ParsedInput(
                Kind.PAIRING_TICKET,
                split.baseUrl,
                split.slug,
                code.trim(),
                "",
                sanitizeDisplayName(name),
                normalizeMode(mode));
    }

    private static ParsedInput parseHttpUrl(String raw) {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (Exception bad) {
            throw new IllegalArgumentException("Malformed URL", bad);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Embedded credentials are not allowed");
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.trim().isEmpty()
                || uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("Missing host in URL");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.putAll(parseQuery(uri.getRawQuery()));
        if (uri.getRawFragment() != null && uri.getRawFragment().contains("=")) {
            params.putAll(parseQuery(uri.getRawFragment()));
        }

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        Matcher pairPath = TRAILING_PAIR_PATH.matcher(path);
        String cleanPath = pairPath.matches() ? pairPath.group(1) : path;
        String baseInput = uri.getScheme() + "://" + uri.getRawAuthority() + cleanPath;
        String paramProfile = firstNonEmpty(params, "profile", "profile_slug", "slug");
        SplitHost split = splitHostAndSlug(baseInput, paramProfile);

        String code = firstNonEmpty(params, "code", "ticket", "token");
        if (!code.isEmpty()) {
            if (!isValidPairingCode(code)) {
                throw new IllegalArgumentException("Invalid pairing code in invite URL");
            }
            return new ParsedInput(
                    Kind.PAIRING_TICKET,
                    split.baseUrl,
                    split.slug,
                    code.trim(),
                    "",
                    sanitizeDisplayName(firstNonEmpty(params, "name", "display_name")),
                    normalizeMode(firstNonEmpty(params, "mode")));
        }

        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Unexpected query or fragment in server URL");
        }
        return new ParsedInput(Kind.SERVER_ONLY, split.baseUrl, split.slug, "", "", "", "");
    }

    private static ParsedInput parseJsonSnippet(String raw) {
        Map<String, String> fields = parseFlatJsonStrings(raw);
        String deepLink = firstNonEmpty(fields, "deep_link", "pairing_link", "invite_link", "link");
        if (!deepLink.isEmpty()) {
            if (deepLink.toLowerCase(Locale.ROOT).startsWith("hermes://")) {
                return parseDeepLink(deepLink);
            }
            if (deepLink.toLowerCase(Locale.ROOT).startsWith("https://")
                    || deepLink.toLowerCase(Locale.ROOT).startsWith("http://")) {
                return parseHttpUrl(deepLink);
            }
            throw new IllegalArgumentException("Unsupported link inside JSON snippet");
        }
        String host = firstNonEmpty(fields, "host", "server_url", "server", "endpoint", "url");
        if (host.isEmpty()) throw new IllegalArgumentException("Missing host in JSON snippet");
        String profile = firstNonEmpty(fields, "profile_slug", "profile", "slug");
        String code = firstNonEmpty(fields, "code", "pairing_code", "ticket");
        String apiKey = firstNonEmpty(fields, "api_key", "key", "bearer", "token");
        String name = sanitizeDisplayName(firstNonEmpty(fields, "display_name", "name", "label"));
        String mode = normalizeMode(firstNonEmpty(fields, "mode"));
        SplitHost split = splitHostAndSlug(host, profile);

        if (!code.isEmpty()) {
            if (!isValidPairingCode(code)) throw new IllegalArgumentException("Invalid pairing code");
            return new ParsedInput(Kind.PAIRING_TICKET, split.baseUrl, split.slug, code.trim(), "", name, mode);
        }
        if (!apiKey.isEmpty()) {
            if (!isValidApiKey(apiKey)) throw new IllegalArgumentException("Invalid API key");
            return new ParsedInput(Kind.DIRECT_CREDENTIALS, split.baseUrl, split.slug, "", apiKey.trim(), name, "");
        }
        return new ParsedInput(Kind.SERVER_ONLY, split.baseUrl, split.slug, "", "", name, "");
    }

    private static ParsedInput parseSmartText(String raw) {
        // First check if the pasted text contains a full hermes://pair link anywhere inside prose.
        Matcher urlMatcher = URL_TOKEN.matcher(raw);
        String foundHttpUrl = "";
        while (urlMatcher.find()) {
            String token = trimTrailingPunctuation(urlMatcher.group());
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("hermes://")) {
                return parseDeepLink(token);
            }
            if (foundHttpUrl.isEmpty() && (lower.startsWith("https://") || lower.startsWith("http://"))) {
                foundHttpUrl = token;
            }
        }
        if (foundHttpUrl.isEmpty()) {
            throw new IllegalArgumentException("No valid Hermes link or server address found");
        }

        // If the embedded HTTP URL itself is a pairing invite URL, honor it immediately.
        if (foundHttpUrl.contains("code=") || foundHttpUrl.contains("ticket=")) {
            return parseHttpUrl(foundHttpUrl);
        }

        String code = "";
        String key = "";
        String profile = "";
        String displayName = "";

        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int sep = indexOfLabelSeparator(trimmed);
            if (sep > 0) {
                String label = trimmed.substring(0, sep).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(sep + 1).trim();
                if (value.isEmpty()) continue;
                if (label.contains("code") || label.contains("ticket") || label.contains("pair") || label.contains("jumel")) {
                    if (code.isEmpty()) code = firstToken(value);
                    continue;
                }
                if (label.contains("key") || label.contains("cle") || label.contains("clé")
                        || label.contains("token") || label.contains("bearer") || label.contains("secret")) {
                    if (key.isEmpty()) key = stripBearerPrefix(value);
                    continue;
                }
                if (label.contains("profile") || label.contains("profil") || label.contains("slug")) {
                    if (profile.isEmpty()) profile = firstToken(value);
                    continue;
                }
                if (label.equals("name") || label.equals("display_name") || label.equals("nom")) {
                    if (displayName.isEmpty()) displayName = value;
                    continue;
                }
            }
            // Bare token lines or "https://host key" on a single line.
            if (trimmed.contains(foundHttpUrl)) {
                String remainder = trimmed.replace(foundHttpUrl, "").trim();
                if (!remainder.isEmpty()) {
                    String candidate = stripBearerPrefix(remainder);
                    if (key.isEmpty() && !containsWhitespace(candidate) && isValidApiKey(candidate)) {
                        key = candidate;
                    }
                }
            } else if (!containsWhitespace(trimmed) && !trimmed.contains("://")) {
                String candidate = stripBearerPrefix(trimmed);
                if (key.isEmpty() && isValidApiKey(candidate)) {
                    key = candidate;
                }
            }
        }

        SplitHost split = splitHostAndSlug(foundHttpUrl, profile);
        if (!code.isEmpty()) {
            if (!isValidPairingCode(code)) throw new IllegalArgumentException("Invalid pairing code");
            return new ParsedInput(Kind.PAIRING_TICKET, split.baseUrl, split.slug, code, "",
                    sanitizeDisplayName(displayName), "");
        }
        if (!key.isEmpty()) {
            if (!isValidApiKey(key)) throw new IllegalArgumentException("Invalid API key");
            return new ParsedInput(Kind.DIRECT_CREDENTIALS, split.baseUrl, split.slug, "", key,
                    sanitizeDisplayName(displayName), "");
        }
        return new ParsedInput(Kind.SERVER_ONLY, split.baseUrl, split.slug, "", "",
                sanitizeDisplayName(displayName), "");
    }

    private record SplitHost(String baseUrl, String slug) {}

    private static SplitHost splitHostAndSlug(String rawHost, String explicitProfile) {
        GatewayAddress.Split split = GatewayAddress.split(rawHost);
        String slug = GatewayAddress.normalizeProfileSlug(explicitProfile);
        if (split.profileSlug().isEmpty()) {
            return new SplitHost(split.baseUrl(), slug);
        }
        if (slug.isEmpty() || slug.equalsIgnoreCase(split.profileSlug())) {
            return new SplitHost(split.baseUrl(), split.profileSlug());
        }
        throw new IllegalArgumentException("Conflicting profile slug in host and parameters");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawVal = eq >= 0 ? pair.substring(eq + 1) : "";
            String key = urlDecode(rawKey).trim().toLowerCase(Locale.ROOT);
            String val = urlDecode(rawVal).trim();
            if (!key.isEmpty() && !out.containsKey(key)) {
                out.put(key, val);
            }
        }
        return out;
    }

    private static String urlDecode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
        } catch (Exception bad) {
            throw new IllegalArgumentException("Invalid percent encoding", bad);
        }
    }

    /** Minimal strict flat-JSON string extractor (pure Java, zero Android org.json dependency). */
    private static Map<String, String> parseFlatJsonStrings(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 1;
        int n = json.length() - 1;
        while (i < n) {
            i = skipWs(json, i, n);
            if (i >= n) break;
            if (json.charAt(i) == ',') { i++; continue; }
            if (json.charAt(i) != '"') throw new IllegalArgumentException("Expected JSON key");
            int[] next = new int[1];
            String key = readJsonString(json, i, next);
            i = skipWs(json, next[0], n);
            if (i >= n || json.charAt(i) != ':') throw new IllegalArgumentException("Expected ':' in JSON");
            i = skipWs(json, i + 1, n);
            if (i >= n) throw new IllegalArgumentException("Missing JSON value");
            char c = json.charAt(i);
            if (c == '"') {
                String value = readJsonString(json, i, next);
                out.put(key.trim().toLowerCase(Locale.ROOT), value);
                i = next[0];
            } else if (c == '{' || c == '[') {
                i = skipBalanced(json, i, n);
            } else {
                int start = i;
                while (i < n && json.charAt(i) != ',' && !Character.isWhitespace(json.charAt(i))) i++;
                String literal = json.substring(start, i);
                if (!"null".equals(literal)) {
                    out.put(key.trim().toLowerCase(Locale.ROOT), literal);
                }
            }
        }
        return out;
    }

    private static String readJsonString(String s, int startQuote, int[] nextOut) {
        StringBuilder sb = new StringBuilder();
        int i = startQuote + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i++);
            if (c == '"') {
                nextOut[0] = i;
                return sb.toString();
            }
            if (c == '\\') {
                if (i >= n) throw new IllegalArgumentException("Truncated JSON escape");
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > n) throw new IllegalArgumentException("Truncated JSON unicode escape");
                        sb.append((char) Digests.parseUnsignedHex(s, i, 4));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("Unsupported JSON escape");
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private static int skipBalanced(String s, int start, int limit) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < limit; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
        }
        throw new IllegalArgumentException("Unbalanced JSON container");
    }

    private static int skipWs(String s, int i, int n) {
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String firstNonEmpty(Map<String, String> map, String... keys) {
        for (String key : keys) {
            String value = map.get(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String sanitizeDisplayName(String raw) {
        return PlainTextSanitizer.collapseBounded(raw, MAX_DISPLAY_NAME_CHARS);
    }

    private static String normalizeMode(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return ("device".equals(lower) || "invite".equals(lower)) ? lower : "";
    }

    private static void rejectUnsafeControls(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\r' && c != '\t') || c == 0x7F) {
                throw new IllegalArgumentException("Control character rejected");
            }
        }
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return true;
        }
        return false;
    }

    private static int indexOfLabelSeparator(String line) {
        int colon = line.indexOf(':');
        int eq = line.indexOf('=');
        if (colon > 0 && line.regionMatches(colon, "://", 0, 3)) colon = -1;
        if (colon > 0 && (eq < 0 || colon < eq)) return colon;
        return eq;
    }

    private static String stripBearerPrefix(String raw) {
        String trimmed = raw.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private static String firstToken(String raw) {
        String trimmed = raw.trim();
        int sp = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) { sp = i; break; }
        }
        return sp < 0 ? trimmed : trimmed.substring(0, sp);
    }

    private static String trimTrailingPunctuation(String token) {
        int end = token.length();
        while (end > 0) {
            char last = token.charAt(end - 1);
            if (last == '.' || last == ',' || last == ';' || last == ')' || last == ']' || last == '>') {
                end--;
            } else {
                break;
            }
        }
        return token.substring(0, end);
    }
}
