package org.llm4people.hermes.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.llm4people.hermes.HermesConfig;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class GithubReleaseClientTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static String sha256(byte[] data) throws Exception {
        return ApkUpdateInstaller.toHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String validReleaseJson(String tag, String apkName, String apkUrl, long apkSize,
                                           String apkDigest, String checksumName, String checksumUrl) {
        String digestEntry = apkDigest == null ? "" : ",\"digest\":\"sha256:" + apkDigest + "\"";
        String checksumAsset = checksumName == null ? "" :
                ",{\"name\":\"" + checksumName + "\",\"size\":128,\"browser_download_url\":\"" + checksumUrl + "\"}";
        return "{"
                + "\"tag_name\":\"" + tag + "\","
                + "\"name\":\"Hermes Mobile " + tag + "\","
                + "\"draft\":false,"
                + "\"html_url\":\"https://github.com/LLM-4-People/hermes-mobile/releases/tag/" + tag + "\","
                + "\"published_at\":\"2026-11-22T18:00:00Z\","
                + "\"body\":\"- Nouvelles mises à jour\\n- Améliorations de sécurité\","
                + "\"assets\":["
                + "{\"name\":\"" + apkName + "\",\"size\":" + apkSize
                + ",\"browser_download_url\":\"" + apkUrl + "\"" + digestEntry + "}"
                + checksumAsset
                + "]}";
    }

    @Test
    public void parsesValidSignedReleaseDownloadsChecksumAndVerifiesApk() throws Exception {
        byte[] fakeApkBytes = "PK\003\004signed-hermes-mobile-apk-bytes".getBytes(StandardCharsets.UTF_8);
        String expectedHex = sha256(fakeApkBytes);
        String releaseUrl = "https://api.github.com/repos/LLM-4-People/hermes-mobile/releases/latest";
        String apkName = "hermes-mobile-v1.2.0.apk";
        String apkUrl = "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.2.0/" + apkName;
        String cdnUrl = "https://objects.githubusercontent.com/github-production-release-asset/" + apkName;
        String sumsUrl = "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.2.0/SHA256SUMS";

        String json = validReleaseJson("v1.2.0", apkName, apkUrl, fakeApkBytes.length,
                expectedHex, "SHA256SUMS", sumsUrl);
        String sumsBody = expectedHex + "  " + apkName + "\n";

        Map<String, GithubReleaseClient.Response> responses = new LinkedHashMap<>();
        responses.put(releaseUrl, new GithubReleaseClient.Response(200, null, json.getBytes(StandardCharsets.UTF_8)));
        responses.put(sumsUrl, new GithubReleaseClient.Response(302, cdnUrl + ".sums", new byte[0]));
        responses.put(cdnUrl + ".sums", new GithubReleaseClient.Response(200, null, sumsBody.getBytes(StandardCharsets.UTF_8)));

        GithubReleaseClient client = new GithubReleaseClient(HermesConfig.defaults(),
                (url, accept, max) -> responses.get(url));
        GithubReleaseClient.ReleaseInfo release = client.fetchLatestRelease();

        assertEquals("v1.2.0", release.tagName());
        assertEquals(apkName, release.apkAssetName());
        assertEquals("SHA256SUMS", release.checksumAssetName());
        assertTrue(release.isNewerThan("0.1.0"));
        assertFalse(release.isNewerThan("1.2.0"));
        assertFalse(release.isNewerThan("2.0.0"));
        assertFalse(release.isNewerThan(null));

        String fetchedHex = client.fetchExpectedSha256(release);
        assertEquals(expectedHex, fetchedHex);

        ApkUpdateInstaller.StreamSource streamSource = url -> {
            if (apkUrl.equals(url)) {
                return new ApkUpdateInstaller.StreamResponse(302, cdnUrl, -1L, null);
            }
            if (cdnUrl.equals(url)) {
                return new ApkUpdateInstaller.StreamResponse(200, null, fakeApkBytes.length,
                        new ByteArrayInputStream(fakeApkBytes));
            }
            throw new IOException("Unexpected URL: " + url);
        };

        File updatesDir = tmp.newFolder("updates");
        AtomicInteger maxPct = new AtomicInteger(-1);
        File verified = ApkUpdateInstaller.downloadAndVerifyChecksum(
                streamSource,
                release.apkDownloadUrl(),
                fetchedHex,
                updatesDir,
                UpdateApkProvider.fileNameForTag(release.tagName()),
                release.apkSizeBytes(),
                HermesConfig.defaults().updateMaxApkBytes,
                (pct, downloaded, total) -> maxPct.set(Math.max(maxPct.get(), pct)),
                new AtomicBoolean(false));

        assertTrue(verified.isFile());
        assertEquals("hermes-update-v1.2.0.apk", verified.getName());
        assertEquals(100, maxPct.get());
    }

    @Test
    public void prefersExactVersionedApkOverGenericAndRejectsUnsignedVariants() throws Exception {
        assertEquals("hermes-mobile-v0.3.0.apk", GithubReleaseClient.preferredApkName("v0.3.0"));
        assertEquals("hermes-mobile-v0.3.0-rc.1.apk", GithubReleaseClient.preferredApkName("0.3.0-rc.1"));
        assertEquals("", GithubReleaseClient.preferredApkName("invalid"));

        String h1 = "0123456789abcdef".repeat(4);
        String base = "https://github.com/LLM-4-People/hermes-mobile/releases/download/v0.3.0/";
        String multiAssetJson = "{"
                + "\"tag_name\":\"v0.3.0\","
                + "\"name\":\"Hermes Mobile v0.3.0\","
                + "\"draft\":false,"
                + "\"html_url\":\"https://github.com/LLM-4-People/hermes-mobile/releases/tag/v0.3.0\","
                + "\"published_at\":\"2026-11-23T15:00:00Z\","
                + "\"body\":\"Release notes\","
                + "\"assets\":["
                + "{\"name\":\"other.apk\",\"size\":1024,\"browser_download_url\":\"" + base + "other.apk\"},"
                + "{\"name\":\"hermes-mobile-legacy.apk\",\"size\":1024,\"browser_download_url\":\"" + base + "hermes-mobile-legacy.apk\"},"
                + "{\"name\":\"hermes-mobile-v0.3.0-unsigned.apk\",\"size\":1024,\"browser_download_url\":\"" + base + "hermes-mobile-v0.3.0-unsigned.apk\"},"
                + "{\"name\":\"hermes-mobile-v0.3.0.apk\",\"size\":2048,\"browser_download_url\":\"" + base + "hermes-mobile-v0.3.0.apk\",\"digest\":\"sha256:" + h1 + "\"},"
                + "{\"name\":\"SHA256SUMS\",\"size\":128,\"browser_download_url\":\"" + base + "SHA256SUMS\"}"
                + "]}";
        GithubReleaseClient.ReleaseInfo info = GithubReleaseClient.parseLatestRelease(multiAssetJson, 50_000_000L);
        assertEquals("hermes-mobile-v0.3.0.apk", info.apkAssetName());
        assertEquals(h1, info.assetDigestSha256());
        assertEquals(h1, GithubReleaseClient.parseSha256ForAsset(
                h1 + "  hermes-mobile-v0.3.0.apk\n", info.apkAssetName()));
    }

    @Test
    public void rejectsUnsignedOnlyMissingChecksumDraftAndMalformedReleases() {
        String apkUrl = "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.2.0/hermes-mobile-v1.2.0-unsigned.apk";
        String sumsUrl = "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.2.0/SHA256SUMS";

        // Unsigned-only APK must fail closed.
        try {
            GithubReleaseClient.parseLatestRelease(
                    validReleaseJson("v1.2.0", "hermes-mobile-v1.2.0-unsigned.apk", apkUrl, 1024, null, "SHA256SUMS", sumsUrl),
                    50_000_000L);
            fail("Accepted unsigned-only release");
        } catch (IOException expected) {
            // ok
        }

        // Missing checksum asset must fail closed.
        String signedUrl = "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.2.0/hermes-mobile-v1.2.0.apk";
        try {
            GithubReleaseClient.parseLatestRelease(
                    validReleaseJson("v1.2.0", "hermes-mobile-v1.2.0.apk", signedUrl, 1024, null, null, null),
                    50_000_000L);
            fail("Accepted release without SHA256SUMS / .sha256");
        } catch (IOException expected) {
            // ok
        }

        // Draft release must fail closed.
        String draftJson = validReleaseJson("v1.2.0", "hermes-mobile-v1.2.0.apk", signedUrl, 1024, null, "SHA256SUMS", sumsUrl)
                .replace("\"draft\":false", "\"draft\":true");
        try {
            GithubReleaseClient.parseLatestRelease(draftJson, 50_000_000L);
            fail("Accepted draft release");
        } catch (IOException expected) {
            // ok
        }

        // Malformed JSON payloads must fail cleanly.
        for (String bad : new String[]{"", "[]", "{broken", "{\"tag_name\":\"v1.0.0\"", "{\"tag_name\":\"bad\"}"}) {
            try {
                GithubReleaseClient.parseLatestRelease(bad, 50_000_000L);
                fail("Accepted malformed JSON: " + bad);
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void rejectsSpoofedDownloadHostsAndRedirectsFailClosed() throws Exception {
        assertTrue(GithubReleaseClient.isAllowedDownloadUrl("https://github.com/LLM-4-People/hermes-mobile/releases/latest"));
        assertTrue(GithubReleaseClient.isAllowedDownloadUrl("https://objects.githubusercontent.com/asset.apk"));
        assertTrue(GithubReleaseClient.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/asset.apk"));
        assertTrue(GithubReleaseClient.isAllowedDownloadUrl("https://api.github.com/repos/LLM-4-People/hermes-mobile/releases/latest"));

        String[] badUrls = {
                null, "", "http://github.com/a/b", "https://evil.example/hermes-mobile-v1.2.0.apk",
                "https://github.com.evil.example/hermes-mobile-v1.2.0.apk",
                "https://user:pass@github.com/hermes-mobile-v1.2.0.apk",
                "javascript:alert(1)", "file:///data/local/tmp/a.apk"
        };
        for (String bad : badUrls) {
            assertFalse("Must reject host: " + bad, GithubReleaseClient.isAllowedDownloadUrl(bad));
        }

        // Release JSON pointing APK to spoofed host must fail closed.
        try {
            GithubReleaseClient.parseLatestRelease(
                    validReleaseJson("v1.2.0", "hermes-mobile-v1.2.0.apk", "https://evil.example/hermes-mobile-v1.2.0.apk",
                            1024, null, "SHA256SUMS",
                            "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.2.0/SHA256SUMS"),
                    50_000_000L);
            fail("Accepted spoofed APK download host");
        } catch (IOException expected) {
            // ok
        }

        // Redirect from github.com to evil host must fail closed.
        String apkUrl = "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.2.0/hermes-mobile-v1.2.0.apk";
        ApkUpdateInstaller.StreamSource evilRedirect = url ->
                new ApkUpdateInstaller.StreamResponse(302, "https://evil.example/hijack.apk", -1L, null);
        File updatesDir = tmp.newFolder("redirect_updates");
        try {
            ApkUpdateInstaller.downloadAndVerifyChecksum(
                    evilRedirect, apkUrl, "a".repeat(64), updatesDir,
                    "hermes-update-v1.2.0.apk", 100, 10_000, null, new AtomicBoolean(false));
            fail("Followed redirect to disallowed host");
        } catch (IOException expected) {
            assertEquals(0, updatesDir.listFiles().length);
        }
    }

    @Test
    public void checksumMismatchOversizeAndCancelImmediatelyDeleteDownloadedFiles() throws Exception {
        byte[] tamperedBytes = "tampered-apk-payload".getBytes(StandardCharsets.UTF_8);
        String wrongHex = sha256("original-apk-payload".getBytes(StandardCharsets.UTF_8));
        String apkUrl = "https://objects.githubusercontent.com/hermes-mobile-v1.2.0.apk";
        File dir = tmp.newFolder("mismatch_dir");

        // 1. Checksum mismatch must delete partial and final file.
        try {
            ApkUpdateInstaller.downloadAndVerifyChecksum(
                    url -> new ApkUpdateInstaller.StreamResponse(200, null, tamperedBytes.length,
                            new ByteArrayInputStream(tamperedBytes)),
                    apkUrl, wrongHex, dir, "hermes-update-v1.2.0.apk",
                    tamperedBytes.length, 1024 * 1024, null, new AtomicBoolean(false));
            fail("Accepted checksum mismatch");
        } catch (ApkUpdateInstaller.VerificationException expected) {
            assertEquals(ApkUpdateInstaller.VerificationException.Reason.CHECKSUM_MISMATCH, expected.reason);
        }
        assertEquals("Mismatch must leave no files behind", 0, dir.listFiles().length);

        // 2. Oversized stream must abort and delete file.
        byte[] largeBytes = new byte[2048];
        try {
            ApkUpdateInstaller.downloadAndVerifyChecksum(
                    url -> new ApkUpdateInstaller.StreamResponse(200, null, -1L,
                            new ByteArrayInputStream(largeBytes)),
                    apkUrl, sha256(largeBytes), dir, "hermes-update-v1.2.0.apk",
                    largeBytes.length, 512, null, new AtomicBoolean(false));
            fail("Accepted oversized APK");
        } catch (ApkUpdateInstaller.VerificationException expected) {
            assertEquals(ApkUpdateInstaller.VerificationException.Reason.TOO_LARGE, expected.reason);
        }
        assertEquals("Oversize must leave no files behind", 0, dir.listFiles().length);

        // 3. Cancellation must abort and delete file.
        AtomicBoolean cancelled = new AtomicBoolean(true);
        try {
            ApkUpdateInstaller.downloadAndVerifyChecksum(
                    url -> new ApkUpdateInstaller.StreamResponse(200, null, tamperedBytes.length,
                            new ByteArrayInputStream(tamperedBytes)),
                    apkUrl, sha256(tamperedBytes), dir, "hermes-update-v1.2.0.apk",
                    tamperedBytes.length, 1024 * 1024, null, cancelled);
            fail("Completed cancelled download");
        } catch (ApkUpdateInstaller.VerificationException expected) {
            assertEquals(ApkUpdateInstaller.VerificationException.Reason.CANCELLED, expected.reason);
        }
        assertEquals("Cancel must leave no files behind", 0, dir.listFiles().length);
    }

    @Test
    public void checksumParserAndSignatureCompatibilityAndFilenameValidationEnforceBounds() {
        String h1 = "0123456789abcdef".repeat(4);
        String h2 = "fedcba9876543210".repeat(4);
        assertEquals(h1, GithubReleaseClient.parseSha256ForAsset(h1 + "  hermes-mobile-v1.2.0.apk\n", "hermes-mobile-v1.2.0.apk"));
        assertEquals(h1, GithubReleaseClient.parseSha256ForAsset(h1 + " *./dist/hermes-mobile-v1.2.0.apk\n", "hermes-mobile-v1.2.0.apk"));
        assertEquals(h1, GithubReleaseClient.parseSha256ForAsset(h1 + "\n", "hermes-mobile-v1.2.0.apk"));
        // Conflicting duplicate hashes for the same asset fail closed.
        assertNull(GithubReleaseClient.parseSha256ForAsset(
                h1 + "  hermes-mobile-v1.2.0.apk\n" + h2 + "  hermes-mobile-v1.2.0.apk\n", "hermes-mobile-v1.2.0.apk"));
        assertNull(GithubReleaseClient.parseSha256ForAsset(h1 + "  other.apk\n", "hermes-mobile-v1.2.0.apk"));

        assertTrue(ApkUpdateInstaller.signaturesCompatible(Set.of(h1), Set.of(h1.toUpperCase())));
        assertFalse(ApkUpdateInstaller.signaturesCompatible(Set.of(h1), Set.of(h2)));
        assertFalse(ApkUpdateInstaller.signaturesCompatible(Set.of(), Set.of(h1)));
        assertFalse(ApkUpdateInstaller.signaturesCompatible(null, Set.of(h1)));

        assertTrue(UpdateApkProvider.isValidFileName("hermes-update-v1.2.0.apk"));
        assertFalse(UpdateApkProvider.isValidFileName("../hermes-update-v1.2.0.apk"));
        assertFalse(UpdateApkProvider.isValidFileName("hermes-update-../evil.apk"));
        assertFalse(UpdateApkProvider.isValidFileName("hermes-update-v1.2.0.apk.part"));
        assertFalse(UpdateApkProvider.isValidFileName("other.apk"));
    }

    @Test
    public void coordinatorThrottlesSilentChecksAndDegradesSilentlyOffline() throws Exception {
        byte[] apk = "apk".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(apk);
        String releaseJson = validReleaseJson("v1.5.0", "hermes-mobile-v1.5.0.apk",
                "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.5.0/hermes-mobile-v1.5.0.apk",
                apk.length, hex, "SHA256SUMS",
                "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.5.0/SHA256SUMS");

        AtomicInteger networkCalls = new AtomicInteger(0);
        AtomicBoolean offline = new AtomicBoolean(false);
        GithubReleaseClient client = new GithubReleaseClient(HermesConfig.defaults(), (url, accept, max) -> {
            networkCalls.incrementAndGet();
            if (offline.get()) {
                throw new IOException("Offline / unreachable");
            }
            return new GithubReleaseClient.Response(200, null, releaseJson.getBytes(StandardCharsets.UTF_8));
        });

        UpdateCheckCoordinator.ThrottleStore memStore = new UpdateCheckCoordinator.ThrottleStore() {
            long last;
            String tag = "";
            @Override public long lastCheckMs() { return last; }
            @Override public String latestTag() { return tag; }
            @Override public void save(long lastCheckMs, String latestTag) { last = lastCheckMs; tag = latestTag; }
        };

        UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator(HermesConfig.defaults(), memStore, client);
        long t0 = 1_700_000_000_000L;
        assertTrue(coordinator.isDue(t0));
        UpdateCheckCoordinator.BadgeState first = coordinator.checkIfDue(t0, "0.1.0");
        assertTrue(first.updateAvailable());
        assertEquals("v1.5.0", first.latestTag());
        assertEquals(1, networkCalls.get());

        // Within 24h interval: no network call, returns cached badge.
        assertFalse(coordinator.isDue(t0 + 1_000L));
        UpdateCheckCoordinator.BadgeState cached = coordinator.checkIfDue(t0 + 1_000L, "0.1.0");
        assertTrue(cached.updateAvailable());
        assertEquals(1, networkCalls.get());

        // Equal installed version suppresses badge.
        assertFalse(coordinator.cachedBadge("1.5.0").updateAvailable());
        assertFalse(coordinator.cachedBadge(null).updateAvailable());

        // After 24h interval while offline: degrades silently without throwing and preserves cached badge.
        offline.set(true);
        long t1 = t0 + HermesConfig.DEFAULT_UPDATE_CHECK_INTERVAL_MS;
        assertTrue(coordinator.isDue(t1));
        UpdateCheckCoordinator.BadgeState degraded = coordinator.checkIfDue(t1, "0.1.0");
        assertNotNull(degraded);
        assertTrue(degraded.updateAvailable());
        assertEquals("v1.5.0", degraded.latestTag());
        assertEquals(2, networkCalls.get());

        // Fresh store offline also degrades silently with updateAvailable == false.
        UpdateCheckCoordinator.ThrottleStore emptyStore = new UpdateCheckCoordinator.ThrottleStore() {
            long last;
            String tag = "";
            @Override public long lastCheckMs() { return last; }
            @Override public String latestTag() { return tag; }
            @Override public void save(long lastCheckMs, String latestTag) { last = lastCheckMs; tag = latestTag; }
        };
        UpdateCheckCoordinator coldOffline = new UpdateCheckCoordinator(HermesConfig.defaults(), emptyStore, client);
        UpdateCheckCoordinator.BadgeState coldBadge = coldOffline.checkIfDue(t1, "0.1.0");
        assertFalse(coldBadge.updateAvailable());
        assertEquals(3, networkCalls.get());
    }

    @Test
    public void rejectsNon200MalformedJsonChecksumErrorsAndRedirectCapExceeded() throws Exception {
        // 1. Non-200 HTTP response fails closed.
        GithubReleaseClient status503 = new GithubReleaseClient(HermesConfig.defaults(),
                (url, accept, max) -> new GithubReleaseClient.Response(503, null, "down".getBytes(StandardCharsets.UTF_8)));
        try {
            status503.fetchLatestRelease();
            fail("Expected IOException on HTTP 503");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("503"));
        }

        // 2. Malformed JSON response fails closed.
        GithubReleaseClient badJson = new GithubReleaseClient(HermesConfig.defaults(),
                (url, accept, max) -> new GithubReleaseClient.Response(200, null, "{not-json".getBytes(StandardCharsets.UTF_8)));
        try {
            badJson.fetchLatestRelease();
            fail("Expected IOException on malformed JSON");
        } catch (IOException expected) {
            // ok
        }

        // 3. Redirect cap (>5 hops) fails closed.
        GithubReleaseClient redirectLoop = new GithubReleaseClient(HermesConfig.defaults(),
                (url, accept, max) -> new GithubReleaseClient.Response(
                        302, "https://api.github.com/repos/LLM-4-People/hermes-mobile/releases/latest", new byte[0]));
        try {
            redirectLoop.fetchLatestRelease();
            fail("Expected IOException when redirect cap is exceeded");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("redirect"));
        }

        // 4. fetchExpectedSha256 fails on non-200, missing entry, and API digest mismatch.
        String h1 = "0123456789abcdef".repeat(4);
        String h2 = "fedcba9876543210".repeat(4);
        String sumsUrl = "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.5.0/SHA256SUMS";
        GithubReleaseClient.ReleaseInfo rel = GithubReleaseClient.parseLatestRelease(
                validReleaseJson("v1.5.0", "hermes-mobile-v1.5.0.apk",
                        "https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.5.0/hermes-mobile-v1.5.0.apk",
                        1024, h1, "SHA256SUMS", sumsUrl),
                50_000_000L);

        GithubReleaseClient sums500 = new GithubReleaseClient(HermesConfig.defaults(),
                (url, accept, max) -> new GithubReleaseClient.Response(500, null, new byte[0]));
        try {
            sums500.fetchExpectedSha256(rel);
            fail("Expected IOException on SHA256SUMS HTTP 500");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("500"));
        }

        GithubReleaseClient sumsMissing = new GithubReleaseClient(HermesConfig.defaults(),
                (url, accept, max) -> new GithubReleaseClient.Response(
                        200, null, (h1 + "  unrelated.apk\n").getBytes(StandardCharsets.UTF_8)));
        try {
            sumsMissing.fetchExpectedSha256(rel);
            fail("Expected IOException when SHA256SUMS omits APK");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("does not list"));
        }

        GithubReleaseClient sumsConflict = new GithubReleaseClient(HermesConfig.defaults(),
                (url, accept, max) -> new GithubReleaseClient.Response(
                        200, null, (h2 + "  hermes-mobile-v1.5.0.apk\n").getBytes(StandardCharsets.UTF_8)));
        try {
            sumsConflict.fetchExpectedSha256(rel);
            fail("Expected IOException when SHA256SUMS disagrees with GitHub asset digest");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("disagrees"));
        }
    }
}
