package org.llm4people.hermes;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public final class HermesConfigTest {
    @Test
    public void defaultsMatchServerArtifactLimitAndEnforceCeiling() {
        HermesConfig cfg = HermesConfig.defaults();
        assertEquals(16L * 1024 * 1024, cfg.artifactDefaultMaxBytes);
        assertEquals(64L * 1024 * 1024, cfg.artifactHardMaxBytes);
        assertEquals(8L * 1024 * 1024, cfg.resolveArtifactLimit(8L * 1024 * 1024));
        assertEquals(32L * 1024 * 1024, cfg.resolveArtifactLimit(Integer.valueOf(32 * 1024 * 1024)));
        assertEquals(cfg.artifactHardMaxBytes, cfg.resolveArtifactLimit(128L * 1024 * 1024));
        assertEquals("LLM-4-People/hermes-mobile", cfg.updateRepo);
        assertEquals("https://api.github.com", cfg.updateApiBase);
        assertEquals(52_428_800L, cfg.updateMaxApkBytes);
        assertEquals(86_400_000L, cfg.updateCheckIntervalMs);
        assertEquals("https://github.com", cfg.githubWebBaseUrl);
        assertEquals("https://www.openstreetmap.org", cfg.openStreetMapBaseUrl);
        assertEquals(8_000, cfg.doctorCheckTimeoutMs);
        assertEquals(1_500, cfg.slowLatencyThresholdMs);
        assertEquals(51, cfg.expectedForkCapabilities);
        assertEquals(8_000, cfg.probeTimeoutMs);
        assertEquals(15_000, cfg.voiceServiceTimeoutMs);
        assertEquals(12_000, cfg.ttsInitTimeoutMs);
        assertEquals(HermesConfig.DEFAULT_DOCTOR_CHECK_TIMEOUT_MS, cfg.doctorCheckTimeoutMs);
        assertEquals(HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS, cfg.slowLatencyThresholdMs);
        assertEquals(HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES, cfg.expectedForkCapabilities);
        assertEquals(HermesConfig.DEFAULT_PROBE_TIMEOUT_MS, cfg.probeTimeoutMs);
        assertEquals(HermesConfig.DEFAULT_VOICE_SERVICE_TIMEOUT_MS, cfg.voiceServiceTimeoutMs);
        assertEquals(HermesConfig.DEFAULT_TTS_INIT_TIMEOUT_MS, cfg.ttsInitTimeoutMs);
        assertEquals(120_000, cfg.voiceModelReleaseTimeoutMs);
        assertEquals(5, cfg.httpMaxRedirects);
        assertEquals(HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, cfg.voiceModelReleaseTimeoutMs);
        assertEquals(HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS, cfg.httpMaxRedirects);
    }

    @Test
    public void constructorPreservesCustomOperationalValues() {
        HermesConfig custom = new HermesConfig(
                1000, 2000, 1024, 2048, 3000, 500, 4096, 8192, 10, 20, 2,
                "custom/repo", "https://api.example.com/", 1024, 60_000L,
                "https://git.example.com/", "https://maps.example.com/",
                4_500, 750, 25, 3_500, 9_000, 6_500, 90_000, 0);
        assertEquals("https://git.example.com", custom.githubWebBaseUrl);
        assertEquals("https://maps.example.com", custom.openStreetMapBaseUrl);
        assertEquals(4_500, custom.doctorCheckTimeoutMs);
        assertEquals(750, custom.slowLatencyThresholdMs);
        assertEquals(25, custom.expectedForkCapabilities);
        assertEquals(3_500, custom.probeTimeoutMs);
        assertEquals(9_000, custom.voiceServiceTimeoutMs);
        assertEquals(6_500, custom.ttsInitTimeoutMs);
        assertEquals(90_000, custom.voiceModelReleaseTimeoutMs);
        assertEquals(0, custom.httpMaxRedirects);
    }

    @Test
    public void constructorRejectsInsecureUrlsAndNonPositiveOperationalTimeouts() {
        try {
            new HermesConfig(1000, 1000, 1000, 1000, 1000, 500, 1024, 2048, 10, 10, 1,
                    "owner/repo", "https://api.github.com", 1024, 1000,
                    "http://github.com", "https://www.openstreetmap.org",
                    1000, 500, 1, 1000, 1000, 1000, HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
            fail("Expected rejection for non-HTTPS githubWebBaseUrl");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new HermesConfig(1000, 1000, 1000, 1000, 1000, 500, 1024, 2048, 10, 10, 1,
                    "owner/repo", "https://api.github.com", 1024, 1000,
                    "https://github.com", "http://www.openstreetmap.org",
                    1000, 500, 1, 1000, 1000, 1000, HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
            fail("Expected rejection for non-HTTPS openStreetMapBaseUrl");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        int[][] badOperationalCases = {
                {0, 500, 1, 1000, 1000, 1000},
                {1000, 0, 1, 1000, 1000, 1000},
                {1000, 500, 0, 1000, 1000, 1000},
                {1000, 500, 1, 0, 1000, 1000},
                {1000, 500, 1, 1000, -1, 1000},
                {1000, 500, 1, 1000, 1000, -5},
        };
        for (int[] c : badOperationalCases) {
            try {
                new HermesConfig(1000, 1000, 1000, 1000, 1000, 500, 1024, 2048, 10, 10, 1,
                        "owner/repo", "https://api.github.com", 1024, 1000,
                        "https://github.com", "https://www.openstreetmap.org",
                        c[0], c[1], c[2], c[3], c[4], c[5], HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
                fail("Expected rejection for non-positive operational bound");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            new HermesConfig(1000, 1000, 1000, 1000, 1000, 500, 1024, 2048, 10, 10, 1,
                    "owner/repo", "https://api.github.com", 1024, 1000,
                    "https://github.com", "https://www.openstreetmap.org",
                    1000, 500, 1, 1000, 1000, 1000, 0, 5);
            fail("Expected rejection for zero voiceModelReleaseTimeoutMs");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new HermesConfig(1000, 1000, 1000, 1000, 1000, 500, 1024, 2048, 10, 10, 1,
                    "owner/repo", "https://api.github.com", 1024, 1000,
                    "https://github.com", "https://www.openstreetmap.org",
                    1000, 500, 1, 1000, 1000, 1000, 1000, -1);
            fail("Expected rejection for negative httpMaxRedirects");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void invalidAdvertisedArtifactLimitsFallBackToDefault() {
        HermesConfig cfg = HermesConfig.defaults();
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit(0L));
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit(-5L));
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit((Object) null));
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit("16777216"));
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit(Boolean.TRUE));
    }

    @Test
    public void constructorRejectsNonPositiveOrInvertedBounds() {
        try {
            new HermesConfig(0, 1000, 1000, 1000, 1000, 500, 1024, 2048, 10, 10, 1, HermesConfig.DEFAULT_UPDATE_REPO, HermesConfig.DEFAULT_UPDATE_API_BASE, HermesConfig.DEFAULT_UPDATE_MAX_APK_BYTES, HermesConfig.DEFAULT_UPDATE_CHECK_INTERVAL_MS, HermesConfig.DEFAULT_GITHUB_WEB_BASE_URL, HermesConfig.DEFAULT_OSM_BASE_URL, HermesConfig.DEFAULT_DOCTOR_CHECK_TIMEOUT_MS, HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS, HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES, HermesConfig.DEFAULT_PROBE_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_SERVICE_TIMEOUT_MS, HermesConfig.DEFAULT_TTS_INIT_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
            fail("Expected rejection for zero connect timeout");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new HermesConfig(1000, 1000, 1000, 1000, 1000, 2000, 1024, 2048, 10, 10, 1, HermesConfig.DEFAULT_UPDATE_REPO, HermesConfig.DEFAULT_UPDATE_API_BASE, HermesConfig.DEFAULT_UPDATE_MAX_APK_BYTES, HermesConfig.DEFAULT_UPDATE_CHECK_INTERVAL_MS, HermesConfig.DEFAULT_GITHUB_WEB_BASE_URL, HermesConfig.DEFAULT_OSM_BASE_URL, HermesConfig.DEFAULT_DOCTOR_CHECK_TIMEOUT_MS, HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS, HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES, HermesConfig.DEFAULT_PROBE_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_SERVICE_TIMEOUT_MS, HermesConfig.DEFAULT_TTS_INIT_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
            fail("Expected rejection when fused grace exceeds nearby timeout");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new HermesConfig(1000, 1000, 1000, 1000, 1000, 500, 4096, 2048, 10, 10, 1,
                    HermesConfig.DEFAULT_UPDATE_REPO, HermesConfig.DEFAULT_UPDATE_API_BASE,
                    HermesConfig.DEFAULT_UPDATE_MAX_APK_BYTES, HermesConfig.DEFAULT_UPDATE_CHECK_INTERVAL_MS,
                    HermesConfig.DEFAULT_GITHUB_WEB_BASE_URL, HermesConfig.DEFAULT_OSM_BASE_URL,
                    HermesConfig.DEFAULT_DOCTOR_CHECK_TIMEOUT_MS, HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS,
                    HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES, HermesConfig.DEFAULT_PROBE_TIMEOUT_MS,
                    HermesConfig.DEFAULT_VOICE_SERVICE_TIMEOUT_MS, HermesConfig.DEFAULT_TTS_INIT_TIMEOUT_MS,
                    HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
            fail("Expected rejection when hard artifact limit is below default");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void loadCachedMemoizesPerContextKeyAndResetsCleanly() {
        HermesConfig.clearCacheForTest();
        try {
            Object appA = new Object();
            Object appB = new Object();
            AtomicInteger loads = new AtomicInteger();

            HermesConfig first = HermesConfig.loadCached(appA, () -> {
                loads.incrementAndGet();
                return new HermesConfig(5000, 10000, 1024, 1024, 4000, 1000, 4096, 8192, 50, 20, 3, HermesConfig.DEFAULT_UPDATE_REPO, HermesConfig.DEFAULT_UPDATE_API_BASE, HermesConfig.DEFAULT_UPDATE_MAX_APK_BYTES, HermesConfig.DEFAULT_UPDATE_CHECK_INTERVAL_MS, HermesConfig.DEFAULT_GITHUB_WEB_BASE_URL, HermesConfig.DEFAULT_OSM_BASE_URL, HermesConfig.DEFAULT_DOCTOR_CHECK_TIMEOUT_MS, HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS, HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES, HermesConfig.DEFAULT_PROBE_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_SERVICE_TIMEOUT_MS, HermesConfig.DEFAULT_TTS_INIT_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
            });
            for (int i = 0; i < 100; i++) {
                HermesConfig hit = HermesConfig.loadCached(appA, () -> {
                    loads.incrementAndGet();
                    return HermesConfig.defaults();
                });
                assertSame(first, hit);
            }
            assertEquals("Memoized load must invoke resource loader exactly once", 1, loads.get());

            HermesConfig second = HermesConfig.loadCached(appB, () -> {
                loads.incrementAndGet();
                return new HermesConfig(6000, 12000, 2048, 2048, 5000, 1000, 4096, 8192, 50, 20, 3, HermesConfig.DEFAULT_UPDATE_REPO, HermesConfig.DEFAULT_UPDATE_API_BASE, HermesConfig.DEFAULT_UPDATE_MAX_APK_BYTES, HermesConfig.DEFAULT_UPDATE_CHECK_INTERVAL_MS, HermesConfig.DEFAULT_GITHUB_WEB_BASE_URL, HermesConfig.DEFAULT_OSM_BASE_URL, HermesConfig.DEFAULT_DOCTOR_CHECK_TIMEOUT_MS, HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS, HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES, HermesConfig.DEFAULT_PROBE_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_SERVICE_TIMEOUT_MS, HermesConfig.DEFAULT_TTS_INIT_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
            });
            assertNotSame(first, second);
            assertEquals(2, loads.get());

            HermesConfig.clearCacheForTest();
            HermesConfig reloaded = HermesConfig.loadCached(appA, () -> {
                loads.incrementAndGet();
                return new HermesConfig(7000, 14000, 2048, 2048, 5000, 1000, 4096, 8192, 50, 20, 3, HermesConfig.DEFAULT_UPDATE_REPO, HermesConfig.DEFAULT_UPDATE_API_BASE, HermesConfig.DEFAULT_UPDATE_MAX_APK_BYTES, HermesConfig.DEFAULT_UPDATE_CHECK_INTERVAL_MS, HermesConfig.DEFAULT_GITHUB_WEB_BASE_URL, HermesConfig.DEFAULT_OSM_BASE_URL, HermesConfig.DEFAULT_DOCTOR_CHECK_TIMEOUT_MS, HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS, HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES, HermesConfig.DEFAULT_PROBE_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_SERVICE_TIMEOUT_MS, HermesConfig.DEFAULT_TTS_INIT_TIMEOUT_MS, HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
            });
            assertEquals(3, loads.get());
            assertEquals(7000, reloaded.httpConnectTimeoutMs);

            assertSame(HermesConfig.defaults(), HermesConfig.loadCached(null, () -> first));
            assertSame(HermesConfig.defaults(), HermesConfig.loadCached(appA, null));
            assertSame(HermesConfig.defaults(), HermesConfig.load(null));
        } finally {
            HermesConfig.clearCacheForTest();
        }
    }
}
