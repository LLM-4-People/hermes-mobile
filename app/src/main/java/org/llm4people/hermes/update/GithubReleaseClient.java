package org.llm4people.hermes.update;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.thread.SafeLinks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Privacy-isolated GitHub Releases client for signed Android APK update discovery.
 * Never transmits any profile identity, gateway endpoint, or Authorization header to GitHub.
 * Enforces HTTPS-only allowlisted hosts on both initial requests and every HTTP redirect.
 */
public final class GithubReleaseClient {
    public static final String APK_NAME_PREFIX = "hermes-mobile-";
    public static final String PREFERRED_CHECKSUMS_NAME = "SHA256SUMS";
    public static final int MAX_CHECKSUM_BYTES = 65_536;
    private static final int MAX_RELEASE_JSON_BYTES = 512 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern REPO_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "api.github.com");

    public record ReleaseInfo(
            String tagName,
            ReleaseVersion version,
            String title,
            String notes,
            String htmlUrl,
            String publishedAt,
            String apkAssetName,
            String apkDownloadUrl,
            long apkSizeBytes,
            String checksumAssetName,
            String checksumDownloadUrl,
            String assetDigestSha256) {

        public boolean isNewerThan(String installedVersionName) {
            ReleaseVersion current = ReleaseVersion.parseOrNull(installedVersionName);
            return current != null && version.isNewerThan(current);
        }
    }

    /** Injectable transport for deterministic host-JVM tests without network access. */
    public interface Transport {
        Response get(String url, String acceptHeader, int maxBytes) throws IOException;
    }

    public record Response(int statusCode, String locationHeader, byte[] body) {}

    private final HermesConfig config;
    private final Transport transport;

    public GithubReleaseClient(HermesConfig config) {
        this(config, null);
    }

    public GithubReleaseClient(HermesConfig config, Transport transport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = transport != null ? transport : new HttpsTransport(config);
    }

    public static boolean isValidRepo(String repo) {
        if (repo == null) return false;
        String trimmed = repo.trim();
        return REPO_PATTERN.matcher(trimmed).matches() && !trimmed.contains("..");
    }

    /** Strictly allowlists GitHub HTTPS download/API hosts; rejects HTTP, userinfo, and foreign hosts. */
    public static boolean isAllowedDownloadUrl(String rawUrl) {
        SafeLinks.Validated validated = SafeLinks.validate(rawUrl);
        return validated != null && ALLOWED_HOSTS.contains(validated.host());
    }

    /** Build the verified GitHub Releases API URL for the configured repository. */
    public String latestReleaseApiUrl() throws IOException {
        if (!isValidRepo(config.updateRepo)) {
            throw new IOException("Invalid update repository");
        }
        String url = config.updateApiBase + "/repos/" + config.updateRepo + "/releases/latest";
        if (!isAllowedDownloadUrl(url)) {
            throw new IOException("Disallowed update API host");
        }
        return url;
    }

    /** Fetch and validate the latest signed GitHub release (fail-closed if unsigned or missing checksum). */
    public ReleaseInfo fetchLatestRelease() throws IOException {
        String url = latestReleaseApiUrl();
        byte[] raw = fetchAllowedBytes(url, "application/vnd.github+json",
                Math.min(config.httpJsonMaxBytes, MAX_RELEASE_JSON_BYTES));
        ReleaseInfo info = parseLatestRelease(new String(raw, StandardCharsets.UTF_8), config.updateMaxApkBytes);
        logEvent("release_checked");
        return info;
    }

    /** Download and parse the release's SHA256SUMS / *.sha256 asset and cross-check any API digest. */
    public String fetchExpectedSha256(ReleaseInfo release) throws IOException {
        Objects.requireNonNull(release, "release");
        if (!isAllowedDownloadUrl(release.checksumDownloadUrl())) {
            throw new IOException("Disallowed checksum download host");
        }
        byte[] raw = fetchAllowedBytes(release.checksumDownloadUrl(), "application/octet-stream", MAX_CHECKSUM_BYTES);
        String parsed = parseSha256ForAsset(new String(raw, StandardCharsets.UTF_8), release.apkAssetName());
        if (parsed == null) {
            logWarning("checksum_entry_missing");
            throw new IOException("Release checksum asset does not list the APK");
        }
        if (release.assetDigestSha256() != null
                && !ApkUpdateInstaller.constantTimeHexEquals(parsed, release.assetDigestSha256())) {
            logWarning("checksum_digest_conflict");
            throw new IOException("Release checksum file disagrees with release asset digest");
        }
        return parsed;
    }

    /** Follow up to MAX_REDIRECTS strictly verifying every redirect target against {@link #isAllowedDownloadUrl}. */
    byte[] fetchAllowedBytes(String initialUrl, String accept, int maxBytes) throws IOException {
        String current = initialUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!isAllowedDownloadUrl(current)) {
                logWarning("disallowed_host");
                throw new IOException("Disallowed update host");
            }
            Response response = transport.get(current, accept, maxBytes);
            if (isRedirect(response.statusCode())) {
                String location = response.locationHeader();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Missing redirect location");
                }
                current = location.trim();
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logWarning("http_" + response.statusCode());
                throw new IOException("GitHub returned HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length > maxBytes) {
                throw new IOException("Response exceeds size limit");
            }
            return body;
        }
        throw new IOException("Too many redirects");
    }

    /** Parse GitHub's /releases/latest JSON strictly requiring a signed APK and a matching checksum asset. */
    public static ReleaseInfo parseLatestRelease(String rawJson, long maxApkBytes) throws IOException {
        if (maxApkBytes <= 0) throw new IllegalArgumentException("maxApkBytes must be positive");
        Map<String, Object> root = UpdateJson.parseObject(rawJson);
        if (boolField(root, "draft")) {
            throw new IOException("Draft releases are not installable");
        }
        String tagName = strField(root, "tag_name");
        ReleaseVersion version;
        try {
            version = ReleaseVersion.parse(tagName);
        } catch (IllegalArgumentException badTag) {
            throw new IOException("Invalid release tag", badTag);
        }
        String htmlUrl = strField(root, "html_url");
        if (!isAllowedDownloadUrl(htmlUrl)) {
            throw new IOException("Invalid release page URL");
        }
        String title = strField(root, "name");
        if (title.isEmpty()) title = tagName;
        String notes = strField(root, "body");
        if (notes.length() > 16_384) notes = notes.substring(0, 16_384).trim();
        String publishedAt = strField(root, "published_at");

        Object rawAssets = root.get("assets");
        if (!(rawAssets instanceof List<?> assetsList) || assetsList.isEmpty()) {
            throw new IOException("Release has no downloadable assets");
        }

        String preferredApk = preferredApkName(tagName);
        AssetCandidate chosenApk = null;
        int chosenApkPriority = -1;
        AssetCandidate chosenChecksum = null;
        for (Object entry : assetsList) {
            if (!(entry instanceof Map<?, ?> assetMap)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> asset = (Map<String, Object>) assetMap;
            String name = strField(asset, "name");
            String downloadUrl = strField(asset, "browser_download_url");
            long size = longField(asset, "size");
            if (name.isEmpty() || !isAllowedDownloadUrl(downloadUrl) || size <= 0) continue;

            if (isSignedApkAssetName(name)) {
                if (size > maxApkBytes) continue;
                String digest = parseGithubDigest(strField(asset, "digest"));
                AssetCandidate candidate = new AssetCandidate(name, downloadUrl, size, digest);
                int priority = apkAssetPriority(name, preferredApk);
                if (chosenApk == null || priority > chosenApkPriority) {
                    chosenApk = candidate;
                    chosenApkPriority = priority;
                }
            } else if (isChecksumAssetName(name) && size <= MAX_CHECKSUM_BYTES) {
                AssetCandidate candidate = new AssetCandidate(name, downloadUrl, size, null);
                if (chosenChecksum == null || PREFERRED_CHECKSUMS_NAME.equals(name)) {
                    chosenChecksum = candidate;
                }
            }
        }
        if (chosenApk == null) {
            throw new IOException("No signed release APK asset within size limit");
        }
        // Prefer exact <apkName>.sha256 if multiple checksum assets exist and SHA256SUMS was not found.
        if (chosenChecksum == null) {
            throw new IOException("Missing required SHA256SUMS or .sha256 checksum asset");
        }
        return new ReleaseInfo(
                tagName,
                version,
                title,
                notes,
                htmlUrl,
                publishedAt,
                chosenApk.name,
                chosenApk.url,
                chosenApk.size,
                chosenChecksum.name,
                chosenChecksum.url,
                chosenApk.digestSha256);
    }

    /**
     * Extract the expected lowercase 64-char SHA-256 hex digest for {@code apkAssetName} from a
     * {@code SHA256SUMS} manifest or single-hash {@code *.sha256} file. Fail-closed on malformed or
     * conflicting entries.
     */
    public static String parseSha256ForAsset(String body, String apkAssetName) {
        if (body == null || apkAssetName == null || apkAssetName.trim().isEmpty()) return null;
        String target = basename(apkAssetName.trim());
        String[] lines = body.split("\\r?\\n");
        String matched = null;
        String bareSingleHash = null;
        int nonEmptyLines = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            nonEmptyLines++;
            int space = findWhitespace(line);
            if (space < 0) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (SHA256_HEX.matcher(lower).matches()) {
                    bareSingleHash = lower;
                }
                continue;
            }
            String hash = line.substring(0, space).toLowerCase(Locale.ROOT);
            if (!SHA256_HEX.matcher(hash).matches()) continue;
            String rest = line.substring(space).trim();
            if (rest.startsWith("*")) rest = rest.substring(1).trim();
            if (target.equals(basename(rest))) {
                if (matched != null && !matched.equals(hash)) return null;
                matched = hash;
            }
        }
        if (matched != null) return matched;
        return nonEmptyLines == 1 ? bareSingleHash : null;
    }

    /** Expected canonical signed APK filename for a SemVer release tag (e.g. {@code hermes-mobile-v0.3.0.apk}). */
    public static String preferredApkName(String tagName) {
        if (tagName == null || ReleaseVersion.parseOrNull(tagName) == null) return "";
        String trimmed = tagName.trim();
        String normalizedTag = (trimmed.startsWith("v") || trimmed.startsWith("V"))
                ? "v" + trimmed.substring(1)
                : "v" + trimmed;
        return APK_NAME_PREFIX + normalizedTag + ".apk";
    }

    private static int apkAssetPriority(String name, String preferredApk) {
        if (!preferredApk.isEmpty() && preferredApk.equals(name)) return 3;
        if (name.toLowerCase(Locale.ROOT).startsWith(APK_NAME_PREFIX)) return 2;
        return 1;
    }

    static boolean isSignedApkAssetName(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".apk")) return false;
        if (lower.contains("unsigned")) return false;
        return !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    static boolean isChecksumAssetName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (PREFERRED_CHECKSUMS_NAME.equals(name)) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sha256") || lower.equals("sha256sums.txt");
    }

    static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static String parseGithubDigest(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("sha256:")) return null;
        String hex = lower.substring("sha256:".length());
        return SHA256_HEX.matcher(hex).matches() ? hex : null;
    }

    private static String basename(String path) {
        String clean = path.replace('\\', '/');
        while (clean.startsWith("./")) clean = clean.substring(2);
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private static int findWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static String strField(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s.trim() : "";
    }

    private static boolean boolField(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return Boolean.TRUE.equals(val);
    }

    private static long longField(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof Number n ? n.longValue() : -1L;
    }

    private static void logEvent(String action) {
        Diagnostics.event("update", action);
    }

    private static void logWarning(String action) {
        Diagnostics.warning("update", action);
    }

    private record AssetCandidate(String name, String url, long size, String digestSha256) {}

    private static final class HttpsTransport implements Transport {
        private final HermesConfig config;

        HttpsTransport(HermesConfig config) {
            this.config = config;
        }

        @Override
        public Response get(String url, String acceptHeader, int maxBytes) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(config.httpConnectTimeoutMs);
            conn.setReadTimeout(config.httpReadTimeoutMs);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", acceptHeader);
            conn.setRequestProperty("User-Agent", "Hermes-Mobile");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            try {
                int code = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                if (isRedirect(code)) {
                    return new Response(code, location, new byte[0]);
                }
                InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                byte[] body = stream == null ? new byte[0] : readBounded(stream, maxBytes);
                return new Response(code, location, body);
            } finally {
                conn.disconnect();
            }
        }

        private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
            try (InputStream stream = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int read;
                int total = 0;
                while ((read = stream.read(buf)) != -1) {
                    total += read;
                    if (total > maxBytes) throw new IOException("Response exceeds byte limit");
                    out.write(buf, 0, read);
                }
                return out.toByteArray();
            }
        }
    }
}
