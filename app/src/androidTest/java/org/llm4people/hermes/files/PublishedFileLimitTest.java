package org.llm4people.hermes.files;

import android.test.AndroidTestCase;

import org.json.JSONObject;
import org.llm4people.hermes.HermesConfig;

import java.io.IOException;

@SuppressWarnings("deprecation")
public final class PublishedFileLimitTest extends AndroidTestCase {
    private static JSONObject row(long size) throws Exception {
        return new JSONObject()
                .put("id", "0123456789abcdef0123456789abcdef")
                .put("name", "rapport.bin")
                .put("mime", "application/octet-stream")
                .put("size", size)
                .put("sha256", "a".repeat(64));
    }

    public void testServerAdvertisedLimitIsEnforcedInsteadOfHardcoded64Mb() throws Exception {
        // Default client bound matches server DEFAULT_LIMIT (16 MB), rejecting a 20 MB row by default.
        try {
            new PublishedFile("/api/mobile/files/s1", row(20L * 1024 * 1024));
            fail("Expected default 16 MB bound to reject 20 MB file");
        } catch (IOException expected) {
            // ok
        }

        // When the gateway advertises a 32 MB limit (within the 64 MB ceiling), 20 MB is accepted.
        PublishedFile accepted = new PublishedFile("/api/mobile/files/s1", row(20L * 1024 * 1024), 32L * 1024 * 1024);
        assertEquals(20L * 1024 * 1024, accepted.size);

        // When the gateway advertises a tighter 1 MB limit, 2 MB is rejected.
        try {
            new PublishedFile("/api/mobile/files/s1", row(2L * 1024 * 1024), 1024 * 1024);
            fail("Expected server-advertised 1 MB bound to reject 2 MB file");
        } catch (IOException expected) {
            // ok
        }

        // Above-ceiling server advertisement is clamped by HermesConfig.resolveArtifactLimit to HARD_ARTIFACT_MAX_BYTES (64 MB).
        long resolvedHard = HermesConfig.defaults().resolveArtifactLimit(HermesConfig.HARD_ARTIFACT_MAX_BYTES * 2);
        assertEquals(HermesConfig.HARD_ARTIFACT_MAX_BYTES, resolvedHard);
        try {
            new PublishedFile("/api/mobile/files/s1", row(HermesConfig.HARD_ARTIFACT_MAX_BYTES + 1), resolvedHard);
            fail("Expected hard 64 MB ceiling to reject oversized file");
        } catch (IOException expected) {
            // ok
        }

        // Caller-resolved raised limit (> 64 MB from custom HermesConfig) is honored without re-clamping.
        long raisedLimit = 80L * 1024 * 1024;
        PublishedFile largeAccepted = new PublishedFile("/api/mobile/files/s1", row(70L * 1024 * 1024), raisedLimit);
        assertEquals(70L * 1024 * 1024, largeAccepted.size);
        try {
            new PublishedFile("/api/mobile/files/s1", row(raisedLimit + 1), raisedLimit);
            fail("Expected raised 80 MB bound to reject 80 MB + 1 file");
        } catch (IOException expected) {
            // ok
        }

        // Non-positive caller bound falls back to default 16 MB limit.
        try {
            new PublishedFile("/api/mobile/files/s1", row(20L * 1024 * 1024), 0L);
            fail("Expected non-positive bound to fall back to default 16 MB limit");
        } catch (IOException expected) {
            // ok
        }
    }
}
