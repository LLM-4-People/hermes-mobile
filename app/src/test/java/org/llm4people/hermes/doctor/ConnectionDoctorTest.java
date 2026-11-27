package org.llm4people.hermes.doctor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.RouteRegistry;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class ConnectionDoctorTest {

    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void resetDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void sanitizeHostStripsCredentialsPathsQueriesAndRejectsUnsafeSchemes() {
        assertEquals("https://home.example.com:8443",
                DoctorCheck.sanitizeHost("https://alice:supersecret@home.example.com:8443/p/work?token=abc#frag"));
        assertEquals("https://home.example.com",
                DoctorCheck.sanitizeHost("https://HOME.EXAMPLE.COM:443/p/private-profile"));
        assertEquals("http://127.0.0.1:8642",
                DoctorCheck.sanitizeHost("http://127.0.0.1:8642/p/travail"));
        assertEquals("http://example.local",
                DoctorCheck.sanitizeHost("http://example.local:80/api/health"));

        assertEquals("none", DoctorCheck.sanitizeHost(null));
        assertEquals("none", DoctorCheck.sanitizeHost(""));
        assertEquals("none", DoctorCheck.sanitizeHost("   "));
        assertEquals("none", DoctorCheck.sanitizeHost("javascript:alert(1)"));
        assertEquals("none", DoctorCheck.sanitizeHost("file:///etc/passwd"));
        assertEquals("none", DoctorCheck.sanitizeHost("ftp://files.example.com/secret"));
        assertEquals("none", DoctorCheck.sanitizeHost("https://host.example.com\r\nX-Injected: true"));
        assertEquals("none", DoctorCheck.sanitizeHost("not a valid url"));
    }

    @Test
    public void redactSecretsAndSafeReportNeverLeakTokensProfilesOrPrivateContent() {
        String hostile = "Bearer sk-ant-supersecret12345 ghp_abcdef1234567890 "
                + "https://bob:pass123@home.local/p/my-secret-profile?token=tok999&api_key=k888 "
                + "password=my_pw key=xyz123\u0007\u0000";
        String redacted = DoctorCheck.redactSecrets(hostile);
        assertFalse(redacted.contains("sk-ant-supersecret12345"));
        assertFalse(redacted.contains("ghp_abcdef1234567890"));
        assertFalse(redacted.contains("bob:pass123"));
        assertFalse(redacted.contains("tok999"));
        assertFalse(redacted.contains("k888"));
        assertFalse(redacted.contains("my_pw"));
        assertFalse(redacted.contains("xyz123"));
        assertFalse(redacted.contains("\u0000"));
        assertFalse(redacted.contains("\u0007"));
        assertTrue(redacted.contains("[REDACTED]"));

        DoctorCheck.Report report = new DoctorCheck.Report(
                "https://user:pw@home.backend.local:8443/p/secret-profile?token=abc",
                "1.4.2",
                "fr-CA",
                1700000000000L,
                List.of(
                        new DoctorCheck.Item(DoctorCheck.CheckId.SERVER, DoctorCheck.Status.OK,
                                DoctorCheck.DetailCode.SERVER_OK, 42L, 0, 51, false, "", DoctorCheck.FixAction.NONE),
                        new DoctorCheck.Item(DoctorCheck.CheckId.CAPABILITIES, DoctorCheck.Status.OK,
                                DoctorCheck.DetailCode.CAPABILITIES_FULL, -1L, 51, 51, true, "",
                                DoctorCheck.FixAction.NONE),
                        new DoctorCheck.Item(DoctorCheck.CheckId.LANGUAGE, DoctorCheck.Status.OK,
                                DoctorCheck.DetailCode.LANGUAGE_SYNCED, -1L, 0, 51, false,
                                "Bearer leak_attempt_999", DoctorCheck.FixAction.NONE)
                ));
        String safeText = report.toSafeReportText();
        assertTrue(safeText.contains("host=https://home.backend.local:8443"));
        assertTrue(safeText.contains("count=51/51, companion_fork=true"));
        assertTrue(safeText.contains("latency_ms=42"));
        assertFalse(safeText.contains("secret-profile"));
        assertFalse(safeText.contains("user:pw"));
        assertFalse(safeText.contains("token=abc"));
        assertFalse(safeText.contains("leak_attempt_999"));
    }

    @Test
    public void parseCapabilitiesDistinguishesFullCompanionPartialAndUpstreamNonFork() {
        Map<String, Boolean> fullFeatures = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            fullFeatures.put("feature_" + i, Boolean.TRUE);
        }
        fullFeatures.put("smart_notes", Boolean.TRUE);

        ConnectionDoctor.CapabilitiesProbe full = ConnectionDoctor.evaluateCapabilityFlags(fullFeatures, org.llm4people.hermes.HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES);
        assertTrue(full.fetched());
        assertTrue(full.companionForkDetected());
        assertFalse(full.upstreamNonFork());
        assertEquals(51, full.enabledCount());
        assertEquals(51, full.expectedCount());

        Map<String, Boolean> partialFlags = new LinkedHashMap<>();
        partialFlags.put("smart_notes", Boolean.TRUE);
        partialFlags.put("memory_api", Boolean.TRUE);
        partialFlags.put("disabled_feature", Boolean.FALSE);
        ConnectionDoctor.CapabilitiesProbe partial = ConnectionDoctor.evaluateCapabilityFlags(partialFlags, org.llm4people.hermes.HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES);
        assertTrue(partial.companionForkDetected());
        assertFalse(partial.upstreamNonFork());
        assertEquals(2, partial.enabledCount());

        ConnectionDoctor.CapabilitiesProbe upstream = ConnectionDoctor.evaluateCapabilityFlags(Collections.emptyMap(), org.llm4people.hermes.HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES);
        assertTrue(upstream.fetched());
        assertTrue(upstream.upstreamNonFork());
        assertFalse(upstream.companionForkDetected());
        assertEquals(0, upstream.enabledCount());

        ConnectionDoctor.CapabilitiesProbe nullPayload = ConnectionDoctor.evaluateCapabilityFlags(null, org.llm4people.hermes.HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES);
        assertTrue(nullPayload.upstreamNonFork());
        assertFalse(nullPayload.companionForkDetected());

        // Companion fork exposing more than the baseline 51 flags must scale expectedCount (never render 56/51).
        Map<String, Boolean> expandedFeatures = new LinkedHashMap<>();
        for (int i = 0; i < 55; i++) {
            expandedFeatures.put("feature_" + i, Boolean.TRUE);
        }
        expandedFeatures.put("smart_notes", Boolean.TRUE);
        ConnectionDoctor.CapabilitiesProbe expanded = ConnectionDoctor.evaluateCapabilityFlags(expandedFeatures, org.llm4people.hermes.HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES);
        assertTrue(expanded.companionForkDetected());
        assertFalse(expanded.upstreamNonFork());
        assertEquals(56, expanded.enabledCount());
        assertEquals(56, expanded.expectedCount());
    }

    @Test
    public void closeRevokesAndroidProbesApiAndCapsExpectedCountAboveBaseline() {
        org.llm4people.hermes.net.HermesApi api = new org.llm4people.hermes.net.HermesApi(
                "https://home.example.com", "secret-token");
        assertFalse(api.isRevoked());
        ConnectionDoctor.AndroidProbes androidProbes = new ConnectionDoctor.AndroidProbes(null, null, api);
        try (ConnectionDoctor doctor = new ConnectionDoctor(androidProbes)) {
            assertFalse(api.isRevoked());
        }
        assertTrue("ConnectionDoctor.close() must revoke its dedicated HermesApi", api.isRevoked());

        // Custom probes reporting enabledCount > expectedCount must still never emit enabled > expected.
        AtomicInteger closedCalls = new AtomicInteger();
        ConnectionDoctor.Probes probes = new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() { return "https://home.example.com"; }
            @Override public String appVersion() { return "2.1.0"; }
            @Override public String languageTag() { return "en-US"; }
            @Override public ConnectionDoctor.ServerProbe probeServer() {
                return new ConnectionDoctor.ServerProbe(true, false, false, 20L);
            }
            @Override public ConnectionDoctor.CapabilitiesProbe probeCapabilities() {
                return new ConnectionDoctor.CapabilitiesProbe(true, false, 56, 51, true);
            }
            @Override public ConnectionDoctor.PushProbe probePush() {
                return new ConnectionDoctor.PushProbe(true, true);
            }
            @Override public ConnectionDoctor.UpdateProbe probeUpdates() {
                return new ConnectionDoctor.UpdateProbe("2.1.0", false, "", true);
            }
            @Override public ConnectionDoctor.AmbientProbe probeAmbient() {
                return new ConnectionDoctor.AmbientProbe(true, true);
            }
            @Override public ConnectionDoctor.LanguageProbe probeLanguage() {
                return new ConnectionDoctor.LanguageProbe("en-US", "en-US, en;q=0.9", true);
            }
            @Override public void close() { closedCalls.incrementAndGet(); }
        };
        try (ConnectionDoctor doctor = new ConnectionDoctor(probes)) {
            DoctorCheck.Report report = doctor.runAll();
            DoctorCheck.Item caps = report.find(DoctorCheck.CheckId.CAPABILITIES);
            assertNotNull(caps);
            assertEquals(DoctorCheck.DetailCode.CAPABILITIES_FULL, caps.detailCode());
            assertEquals(56, caps.capabilityCount());
            assertEquals(56, caps.expectedCapabilities());
            assertTrue(report.toSafeReportText().contains("count=56/56"));
        }
        assertEquals(1, closedCalls.get());
    }

    @Test
    public void runAllExecutesProbesInParallelAndIsolatesFailures() {
        CountDownLatch parallelBarrier = new CountDownLatch(2);
        AtomicInteger concurrentObserved = new AtomicInteger();

        ConnectionDoctor.Probes probes = new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() { return "https://home.example.com/p/allison"; }
            @Override public String appVersion() { return "2.0.0"; }
            @Override public String languageTag() { return "en-US"; }

            @Override
            public ConnectionDoctor.ServerProbe probeServer() {
                parallelBarrier.countDown();
                try {
                    if (parallelBarrier.await(2, TimeUnit.SECONDS)) {
                        concurrentObserved.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new ConnectionDoctor.ServerProbe(true, false, false, 35L);
            }

            @Override
            public ConnectionDoctor.CapabilitiesProbe probeCapabilities() {
                parallelBarrier.countDown();
                try {
                    if (parallelBarrier.await(2, TimeUnit.SECONDS)) {
                        concurrentObserved.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new ConnectionDoctor.CapabilitiesProbe(true, false, 51, 51, true);
            }

            @Override public ConnectionDoctor.PushProbe probePush() {
                return new ConnectionDoctor.PushProbe(true, true);
            }

            @Override public ConnectionDoctor.UpdateProbe probeUpdates() {
                return new ConnectionDoctor.UpdateProbe("2.0.0", false, "", true);
            }

            @Override public ConnectionDoctor.AmbientProbe probeAmbient() {
                return new ConnectionDoctor.AmbientProbe(true, true);
            }

            @Override public ConnectionDoctor.LanguageProbe probeLanguage() {
                return new ConnectionDoctor.LanguageProbe("en-US", "en-US, en;q=0.9", true);
            }
        };

        try (ConnectionDoctor doctor = new ConnectionDoctor(probes)) {
            DoctorCheck.Report report = doctor.runAll();
            assertEquals(2, concurrentObserved.get());
            assertEquals(DoctorCheck.Status.OK, report.overallStatus());
            assertEquals(6, report.items().size());
            assertEquals(6, report.count(DoctorCheck.Status.OK));
            assertEquals(0, report.count(DoctorCheck.Status.WARN));
            assertEquals(0, report.count(DoctorCheck.Status.ERROR));
            assertEquals("https://home.example.com", report.sanitizedHost());
        }
    }

    @Test
    public void runAllClassifiesUpstreamWarningsAuthErrorsAndRuntimeExceptionsCleanly() {
        ConnectionDoctor.Probes probes = new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() { return "https://upstream.example.com"; }
            @Override public String appVersion() { return "1.9.0"; }
            @Override public String languageTag() { return "fr-CA"; }

            @Override public ConnectionDoctor.ServerProbe probeServer() throws IOException {
                throw new IOException("connection refused");
            }

            @Override public ConnectionDoctor.CapabilitiesProbe probeCapabilities() {
                return new ConnectionDoctor.CapabilitiesProbe(true, true, 0, 51, false);
            }

            @Override public ConnectionDoctor.PushProbe probePush() {
                throw new RuntimeException("simulated unexpected push error");
            }

            @Override public ConnectionDoctor.UpdateProbe probeUpdates() {
                return new ConnectionDoctor.UpdateProbe("1.9.0", true, "v2.0.0", true);
            }

            @Override public ConnectionDoctor.AmbientProbe probeAmbient() {
                return new ConnectionDoctor.AmbientProbe(true, false);
            }

            @Override public ConnectionDoctor.LanguageProbe probeLanguage() {
                return new ConnectionDoctor.LanguageProbe("fr-CA", "en-US", false);
            }
        };

        try (ConnectionDoctor doctor = new ConnectionDoctor(probes)) {
            DoctorCheck.Report report = doctor.runAll();
            assertEquals(DoctorCheck.Status.ERROR, report.overallStatus());
            assertEquals(1, report.count(DoctorCheck.Status.ERROR));
            assertEquals(5, report.count(DoctorCheck.Status.WARN));

            DoctorCheck.Item server = report.find(DoctorCheck.CheckId.SERVER);
            assertNotNull(server);
            assertEquals(DoctorCheck.Status.ERROR, server.status());
            assertEquals(DoctorCheck.DetailCode.SERVER_UNREACHABLE, server.detailCode());
            assertEquals(DoctorCheck.FixAction.RETRY, server.fixAction());

            DoctorCheck.Item caps = report.find(DoctorCheck.CheckId.CAPABILITIES);
            assertNotNull(caps);
            assertEquals(DoctorCheck.Status.WARN, caps.status());
            assertEquals(DoctorCheck.DetailCode.CAPABILITIES_UPSTREAM, caps.detailCode());
            assertFalse(caps.companionForkDetected());

            DoctorCheck.Item updates = report.find(DoctorCheck.CheckId.UPDATES);
            assertNotNull(updates);
            assertEquals(DoctorCheck.DetailCode.UPDATES_AVAILABLE, updates.detailCode());
            assertEquals("v2.0.0", updates.value());
            assertEquals(DoctorCheck.FixAction.OPEN_UPDATES, updates.fixAction());

            DoctorCheck.Item ambient = report.find(DoctorCheck.CheckId.AMBIENT);
            assertNotNull(ambient);
            assertEquals(DoctorCheck.DetailCode.AMBIENT_WAITING, ambient.detailCode());
            assertEquals(DoctorCheck.FixAction.OPEN_AMBIENT, ambient.fixAction());

            DoctorCheck.Item lang = report.find(DoctorCheck.CheckId.LANGUAGE);
            assertNotNull(lang);
            assertEquals(DoctorCheck.DetailCode.LANGUAGE_MISMATCH, lang.detailCode());
            assertEquals(DoctorCheck.FixAction.OPEN_LANGUAGE, lang.fixAction());
        }
    }

    @Test
    public void routeIdMatchesRegistryContractUnderSettings() {
        RouteRegistry registry = new RouteRegistry();
        AtomicInteger opened = new AtomicInteger();
        registry.register(new RouteRegistry.Destination(
                ConnectionDoctorPanel.ROUTE_ID,
                RouteRegistry.Section.SETTINGS,
                "Connection doctor",
                "Verify server, features, notifications, and language",
                0,
                false,
                null,
                opened::incrementAndGet));

        List<RouteRegistry.Destination> settings = registry.destinationsIn(
                RouteRegistry.Section.SETTINGS, Collections.emptySet());
        assertEquals(1, settings.size());
        assertEquals("connection_doctor", settings.get(0).id());
        assertTrue(registry.dispatch(ConnectionDoctorPanel.ROUTE_ID));
        assertEquals(1, opened.get());
    }

    @Test
    public void customHermesConfigControlsSlowThresholdExpectedCapabilitiesAndTimeout() {
        HermesConfig customCfg = new HermesConfig(
                1000, 2000, 1024, 2048, 3000, 500, 4096, 8192, 10, 20, 2,
                "LLM-4-People/hermes-mobile", "https://api.github.com", 1024, 60_000L,
                "https://github.com", "https://www.openstreetmap.org",
                120, 200, 12, 1000, 1000, 1000, HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS, HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);

        Map<String, Boolean> twelveFlags = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) twelveFlags.put("f_" + i, Boolean.TRUE);
        twelveFlags.put("smart_notes", Boolean.TRUE);
        ConnectionDoctor.CapabilitiesProbe evaluated = ConnectionDoctor.evaluateCapabilityFlags(
                twelveFlags, customCfg.expectedForkCapabilities);
        assertEquals(12, evaluated.enabledCount());
        assertEquals(12, evaluated.expectedCount());
        assertTrue(evaluated.companionForkDetected());

        ConnectionDoctor.Probes probes = new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() { return "https://home.example.com"; }
            @Override public String appVersion() { return "2.2.0"; }
            @Override public String languageTag() { return "en-US"; }
            @Override public ConnectionDoctor.ServerProbe probeServer() {
                return new ConnectionDoctor.ServerProbe(true, false, false, 250L);
            }
            @Override public ConnectionDoctor.CapabilitiesProbe probeCapabilities() {
                return evaluated;
            }
            @Override public ConnectionDoctor.PushProbe probePush() {
                return new ConnectionDoctor.PushProbe(true, true);
            }
            @Override public ConnectionDoctor.UpdateProbe probeUpdates() {
                return new ConnectionDoctor.UpdateProbe("2.2.0", false, "", true);
            }
            @Override public ConnectionDoctor.AmbientProbe probeAmbient() {
                return new ConnectionDoctor.AmbientProbe(true, true);
            }
            @Override public ConnectionDoctor.LanguageProbe probeLanguage() {
                return new ConnectionDoctor.LanguageProbe("en-US", "en-US", true);
            }
        };

        try (ConnectionDoctor doctor = new ConnectionDoctor(probes, customCfg)) {
            DoctorCheck.Report report = doctor.runAll();
            DoctorCheck.Item server = report.find(DoctorCheck.CheckId.SERVER);
            assertNotNull(server);
            assertEquals(DoctorCheck.Status.WARN, server.status());
            assertEquals(DoctorCheck.DetailCode.SERVER_SLOW, server.detailCode());
            assertEquals(12, server.expectedCapabilities());

            DoctorCheck.Item caps = report.find(DoctorCheck.CheckId.CAPABILITIES);
            assertNotNull(caps);
            assertEquals(DoctorCheck.Status.OK, caps.status());
            assertEquals(DoctorCheck.DetailCode.CAPABILITIES_FULL, caps.detailCode());
            assertEquals(12, caps.capabilityCount());
            assertEquals(12, caps.expectedCapabilities());
        }

        // Verify custom doctorCheckTimeoutMs aborts a stalling probe rapidly instead of waiting 8 seconds.
        CountDownLatch blocker = new CountDownLatch(1);
        ConnectionDoctor.Probes stallingProbes = new ConnectionDoctor.Probes() {
            @Override public String rawEndpoint() { return "https://home.example.com"; }
            @Override public String appVersion() { return "2.2.0"; }
            @Override public String languageTag() { return "en-US"; }
            @Override public ConnectionDoctor.ServerProbe probeServer() {
                try { blocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new ConnectionDoctor.ServerProbe(true, false, false, 10L);
            }
            @Override public ConnectionDoctor.CapabilitiesProbe probeCapabilities() { return evaluated; }
            @Override public ConnectionDoctor.PushProbe probePush() { return new ConnectionDoctor.PushProbe(true, true); }
            @Override public ConnectionDoctor.UpdateProbe probeUpdates() { return new ConnectionDoctor.UpdateProbe("2.2.0", false, "", true); }
            @Override public ConnectionDoctor.AmbientProbe probeAmbient() { return new ConnectionDoctor.AmbientProbe(true, true); }
            @Override public ConnectionDoctor.LanguageProbe probeLanguage() { return new ConnectionDoctor.LanguageProbe("en-US", "en-US", true); }
        };
        long startedNs = System.nanoTime();
        try (ConnectionDoctor doctor = new ConnectionDoctor(stallingProbes, customCfg)) {
            DoctorCheck.Report report = doctor.runAll();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
            assertTrue("Expected timeout near 120ms, took " + elapsedMs + "ms", elapsedMs < 2_500L);
            DoctorCheck.Item server = report.find(DoctorCheck.CheckId.SERVER);
            assertNotNull(server);
            assertEquals(DoctorCheck.Status.ERROR, server.status());
            assertEquals(DoctorCheck.DetailCode.SERVER_UNREACHABLE, server.detailCode());
            assertEquals(12, server.expectedCapabilities());
        } finally {
            blocker.countDown();
        }
    }
}
