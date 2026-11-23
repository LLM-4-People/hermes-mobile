package org.llm4people.hermes.onboarding;

import org.junit.Test;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.net.HermesApi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Contract & negative tests for Track 19 onboarding, pairing, multi-profile management,
 * and admin settings models + wire transport.
 */
public final class OnboardingApiContractTest {

    @Test
    public void modelsParsePairingTicketClaimProfilesOnboardingAndAdminSettings() {
        OnboardingModels.PairingTicket ticket = OnboardingModels.parsePairingTicketMap(Map.of(
                "ticket_id", "tkt_001",
                "code", "pair_AbCdEf1234567890",
                "expires_at", 1_800_000_300L,
                "ttl_seconds", 300,
                "deep_link", "hermes://pair?host=https%3A%2F%2Fhermes.example.com&code=pair_AbCdEf1234567890",
                "profile_slug", "alex",
                "display_name", "Alex Martin",
                "mode", "invite"
        ));
        assertEquals("tkt_001", ticket.ticketId());
        assertEquals("invite", ticket.mode());
        assertEquals(200L, ticket.remainingSeconds(1_800_000_100L));
        assertFalse(ticket.isExpired(1_800_000_100L));
        assertTrue(ticket.isExpired(1_800_000_300L));

        OnboardingModels.PairingClaim claim = OnboardingModels.parsePairingClaimMap(Map.of(
                "server_url", "https://hermes.example.com",
                "profile_slug", "alex",
                "display_name", "Alex Martin",
                "api_key", "hm_live_key_1234567890",
                "preferred_language", "fr-CA",
                "default_model", "gemini-2.5-pro",
                "capabilities_summary", Map.of("active_capabilities", 58),
                "onboarding", Map.of(
                        "ready", true,
                        "active_model", "gemini-2.5-pro",
                        "active_capabilities", 58,
                        "endpoint_count", 78,
                        "language", "fr",
                        "is_admin", true,
                        "profiles_manageable", true,
                        "starters", List.of(
                                Map.of("id", "s1", "title", "Planifier la journée", "prompt", "Aide-moi à organiser ma journée."))
                )
        ));
        assertEquals("https://hermes.example.com/p/alex", claim.resolvedEndpoint());
        assertEquals("fr", claim.preferredLanguage());
        assertEquals(58, claim.activeCapabilities());
        assertNotNull(claim.onboarding());
        assertTrue(claim.onboarding().isAdmin());
        assertTrue(claim.onboarding().profilesManageable());
        assertEquals(1, claim.onboarding().starters().size());

        List<OnboardingModels.RemoteProfile> profiles = OnboardingModels.parseProfilesMap(Map.of(
                "profiles", List.of(
                        Map.of("slug", "default", "display_name", "Jean Admin", "preferred_language", "fr",
                                "default_model", "m1", "badge_color", "#225c50", "active_runs", 2,
                                "thread_count", 14, "is_admin", true, "isolated_db_ok", true),
                        Map.of("slug", "alex", "display_name", "Alex", "role", "member", "badge_color", "not-hex")
                )
        ));
        assertEquals(2, profiles.size());
        assertEquals("JA", profiles.get(0).initials());
        assertTrue(profiles.get(0).isAdmin());
        assertEquals("#225C50", profiles.get(0).badgeColor());
        assertEquals("A", profiles.get(1).initials());
        assertFalse(profiles.get(1).isAdmin());
        assertEquals("", profiles.get(1).badgeColor());

        OnboardingModels.AdminSettings settings = OnboardingModels.parseAdminSettingsMap(Map.of(
                "settings", Map.of(
                        "default_language", "en-US",
                        "default_model", "claude-opus",
                        "max_concurrent_runs", 999,
                        "per_profile_concurrent_limit", 500,
                        "pairing_ttl_seconds", 10,
                        "allow_self_device_pairing", false
                ),
                "summary", Map.of(
                        "profile_count", 4,
                        "active_runs_count", 2,
                        "active_pairing_tickets", 1,
                        "active_capabilities", 58,
                        "endpoint_count", 78
                )
        ));
        assertEquals("en", settings.defaultLanguage());
        assertEquals(OnboardingModels.AdminSettings.MAX_CONCURRENT_RUNS, settings.maxConcurrentRuns());
        assertEquals(OnboardingModels.AdminSettings.MAX_PER_PROFILE_RUNS, settings.perProfileConcurrentLimit());
        assertEquals(OnboardingModels.AdminSettings.MIN_PAIRING_TTL_SEC, settings.pairingTtlSeconds());
        assertFalse(settings.allowSelfDevicePairing());
        assertEquals(4, settings.profileCount());
        assertEquals(60, settings.toPatchMap().get("pairing_ttl_seconds"));
    }

    @Test
    public void modelsRejectMalformedClaimAndRotatePayloads() {
        try {
            OnboardingModels.parsePairingClaimMap(Map.of(
                    "server_url", "http://remote.example.com",
                    "api_key", "hm_valid_key_123456789"));
            fail("Expected cleartext remote claim rejection");
        } catch (IllegalArgumentException expected) {}

        try {
            OnboardingModels.parsePairingClaimMap(Map.of(
                    "server_url", "https://hermes.example.com",
                    "api_key", "short"));
            fail("Expected invalid API key rejection");
        } catch (IllegalArgumentException expected) {}

        try {
            OnboardingModels.parseRotateKeyMap(Map.of("profile_slug", "alex", "api_key", "bad key with spaces"));
            fail("Expected invalid rotated key rejection");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void modelsParseLiveGatewayListAndProfileEnvelopes() {
        // Exact live GET /api/mobile/profiles envelope from hermes-agent api_server_profiles.py
        List<OnboardingModels.RemoteProfile> profiles = OnboardingModels.parseProfilesMap(Map.of(
                "object", "list",
                "caller_profile", "default",
                "is_admin", true,
                "data", List.of(
                        Map.of("slug", "default", "display_name", "Default", "preferred_language", "fr",
                                "default_model", "gemini-2.5-pro", "badge_color", "#225c50",
                                "active_runs", 1, "thread_count", 9, "is_admin", true, "isolated_db_ok", true),
                        Map.of("slug", "alex", "display_name", "Alex", "preferred_language", "en",
                                "default_model", "gemini-2.5-pro", "role", "member",
                                "active_runs", 0, "thread_count", 2, "is_admin", false, "isolated_db_ok", true)
                )
        ));
        assertEquals(2, profiles.size());
        assertEquals("default", profiles.get(0).slug());
        assertTrue(profiles.get(0).isAdmin());
        assertEquals("alex", profiles.get(1).slug());
        assertFalse(profiles.get(1).isAdmin());

        // Exact live GET /api/mobile/pairing/active envelope (metadata only, ttl_remaining_seconds)
        List<OnboardingModels.PairingTicket> tickets = OnboardingModels.parseActivePairingTicketsMap(Map.of(
                "object", "list",
                "data", List.of(
                        Map.of("ticket_id", "tkt_live_1", "profile_slug", "alex", "display_name", "Alex",
                                "mode", "invite", "created_by", "default",
                                "created_at", 1_800_000_000L, "expires_at", 1_800_000_300L,
                                "ttl_remaining_seconds", 240),
                        Map.of("bad_row_without_id", true)
                )
        ));
        assertEquals(1, tickets.size());
        assertEquals("tkt_live_1", tickets.get(0).ticketId());
        assertEquals("", tickets.get(0).code());
        assertEquals(240, tickets.get(0).ttlSeconds());
        assertEquals("alex", tickets.get(0).profileSlug());
        assertEquals("invite", tickets.get(0).mode());

        // Negative list envelopes stay fail-closed and return empty lists
        assertTrue(OnboardingModels.parseProfilesMap(Map.of("object", "list", "data", List.of())).isEmpty());
        assertTrue(OnboardingModels.parseProfilesMap(Map.of("object", "list", "data", "not-a-list")).isEmpty());
        assertTrue(OnboardingModels.parseActivePairingTicketsMap(Map.of("object", "list", "data", List.of())).isEmpty());
        assertTrue(OnboardingModels.parseActivePairingTicketsMap(Map.of("object", "list", "data", 42)).isEmpty());

        // Exact live PATCH /api/mobile/profiles/{slug} wrapped response
        OnboardingModels.RemoteProfile updated = OnboardingModels.parseRemoteProfileMap(Map.of(
                "object", "hermes.mobile.profile",
                "profile", Map.of("slug", "alex", "display_name", "Alex Updated", "preferred_language", "fr",
                        "default_model", "gemini-2.5-pro", "is_admin", false)
        ));
        assertNotNull(updated);
        assertEquals("alex", updated.slug());
        assertEquals("Alex Updated", updated.displayName());
        assertEquals("fr", updated.preferredLanguage());

        // Default profile claim resolves to root base URL without /p/default; named profile keeps /p/<slug>
        OnboardingModels.PairingClaim defaultClaim = OnboardingModels.parsePairingClaimMap(Map.of(
                "server_url", "https://hermes.example.com",
                "profile_slug", "default",
                "display_name", "Default",
                "api_key", "hm_live_key_1234567890"
        ));
        assertEquals("https://hermes.example.com", defaultClaim.resolvedEndpoint());

        OnboardingModels.PairingClaim namedClaim = OnboardingModels.parsePairingClaimMap(Map.of(
                "server_url", "https://hermes.example.com/p/alex",
                "profile_slug", "alex",
                "display_name", "Alex",
                "api_key", "hm_live_key_1234567890"
        ));
        assertEquals("https://hermes.example.com/p/alex", namedClaim.resolvedEndpoint());

        // Non-consuming POST /api/mobile/pairing/inspect envelope (never contains api_key)
        OnboardingModels.PairingInspect inspect = OnboardingModels.parsePairingInspectMap(Map.of(
                "object", "hermes.mobile.pairing.inspect",
                "valid", true,
                "server_url", "https://chat.ai.localserver.ca/mobile",
                "base_host", "https://chat.ai.localserver.ca/mobile",
                "profile_slug", "default",
                "display_name", "Jacob",
                "mode", "device",
                "expires_at", 1_800_000_300L,
                "ttl_remaining_seconds", 295
        ));
        assertTrue(inspect.valid());
        assertEquals("https://chat.ai.localserver.ca/mobile", inspect.resolvedEndpoint());
        assertEquals("default", inspect.profileSlug());
        assertEquals("Jacob", inspect.displayName());
        assertEquals(295, inspect.ttlRemainingSeconds());

        // Reject valid=false or cleartext remote inspect responses fail-closed
        try {
            OnboardingModels.parsePairingInspectMap(Map.of(
                    "valid", false, "server_url", "https://chat.ai.localserver.ca/mobile"));
            fail("Expected valid=false inspect rejection");
        } catch (IllegalArgumentException expected) {}
        try {
            OnboardingModels.parsePairingInspectMap(Map.of(
                    "valid", true, "server_url", "http://remote.example.com/mobile"));
            fail("Expected cleartext inspect rejection");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void claimPairingSendsNoAuthNoStoreAndDeleteProfileEnforcesConfirmSlug() throws Exception {
        AtomicReference<String> observedRequestLine = new AtomicReference<>();
        AtomicReference<Map<String, String>> observedHeaders = new AtomicReference<>();

        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            String base = "http://127.0.0.1:" + server.getLocalPort();
            AppLanguage.setActiveAcceptLanguageForTest("fr-CA, fr;q=0.9");

            // 1. Unauthenticated POST /api/mobile/pairing/inspect -> 410 Gone (no Authorization, no-store)
            Thread t0 = serveSingle(server, 410, "Gone", "", "", observedRequestLine, observedHeaders);
            try {
                HermesApi.inspectPairing(base, "pair_AbCdEf1234567890");
                fail("Expected HttpError 410");
            } catch (HermesApi.HttpError err) {
                assertEquals(410, err.status);
            }
            t0.join(3000);
            assertEquals("POST /api/mobile/pairing/inspect HTTP/1.1", observedRequestLine.get());
            assertNull("Unauthenticated pairing inspect must NEVER send Authorization header",
                    observedHeaders.get().get("authorization"));
            assertEquals("no-store", observedHeaders.get().get("cache-control"));
            assertEquals("fr", observedHeaders.get().get("x-hermes-lang"));

            // 1b. Unauthenticated POST /api/mobile/pairing/claim -> 410 Gone with Retry-After
            Thread t1 = serveSingle(server, 410, "Gone", "Retry-After: 45\r\n", "", observedRequestLine, observedHeaders);
            try {
                HermesApi.claimPairing(base, "pair_AbCdEf1234567890");
                fail("Expected HttpError 410");
            } catch (HermesApi.HttpError err) {
                assertEquals(410, err.status);
                assertEquals(45L, err.retryAfterSeconds);
            }
            t1.join(3000);
            assertEquals("POST /api/mobile/pairing/claim HTTP/1.1", observedRequestLine.get());
            assertNull("Unauthenticated pairing claim must NEVER send Authorization header",
                    observedHeaders.get().get("authorization"));
            assertEquals("no-store", observedHeaders.get().get("cache-control"));
            assertEquals("fr", observedHeaders.get().get("x-hermes-lang"));

            // 2. deleteProfile rejects mismatched confirmSlug BEFORE network call
            HermesApi api = new HermesApi(base, "member_bearer_token_12345");
            try {
                api.deleteProfile("alex", "other");
                fail("Expected confirm_slug mismatch rejection");
            } catch (IllegalArgumentException expected) {}

            // 3. Matching confirmSlug sends DELETE /api/mobile/profiles/alex?confirm_slug=alex with Bearer auth
            Thread t2 = serveSingle(server, 403, "Forbidden", "", "", observedRequestLine, observedHeaders);
            try {
                api.deleteProfile("alex", "alex");
                fail("Expected HttpError 403");
            } catch (HermesApi.HttpError err) {
                assertEquals(403, err.status);
            }
            t2.join(3000);
            assertEquals("DELETE /api/mobile/profiles/alex?confirm_slug=alex HTTP/1.1", observedRequestLine.get());
            assertEquals("Bearer member_bearer_token_12345", observedHeaders.get().get("authorization"));

            // 4. Member hitting GET /api/mobile/admin/settings gets 403 cleanly
            Thread t3 = serveSingle(server, 403, "Forbidden", "", "", observedRequestLine, observedHeaders);
            try {
                api.adminSettings();
                fail("Expected HttpError 403");
            } catch (HermesApi.HttpError err) {
                assertEquals(403, err.status);
            }
            t3.join(3000);
            assertEquals("GET /api/mobile/admin/settings HTTP/1.1", observedRequestLine.get());
            assertEquals("no-store", observedHeaders.get().get("cache-control"));

            // 5. activePairings(false) hits /api/mobile/pairing/active; activePairings(true) appends ?all=1
            Thread t4 = serveSingle(server, 403, "Forbidden", "", "", observedRequestLine, observedHeaders);
            try {
                api.activePairings();
                fail("Expected HttpError 403");
            } catch (HermesApi.HttpError err) {
                assertEquals(403, err.status);
            }
            t4.join(3000);
            assertEquals("GET /api/mobile/pairing/active HTTP/1.1", observedRequestLine.get());

            Thread t5 = serveSingle(server, 403, "Forbidden", "", "", observedRequestLine, observedHeaders);
            try {
                api.activePairings(true);
                fail("Expected HttpError 403");
            } catch (HermesApi.HttpError err) {
                assertEquals(403, err.status);
            }
            t5.join(3000);
            assertEquals("GET /api/mobile/pairing/active?all=1 HTTP/1.1", observedRequestLine.get());
            assertEquals("Bearer member_bearer_token_12345", observedHeaders.get().get("authorization"));
        } finally {
            AppLanguage.setActiveAcceptLanguageForTest("");
        }
    }

    @Test
    public void claimPairingRejectsCleartextRemoteHostAndShortCodeBeforeNetwork() {
        try {
            HermesApi.claimPairing("http://example.com", "pair_AbCdEf1234567890");
            fail("Expected cleartext host rejection");
        } catch (IllegalArgumentException | IOException expected) {
            assertTrue(expected instanceof IllegalArgumentException);
        }
        try {
            HermesApi.claimPairing("https://hermes.example.com", "short");
            fail("Expected short code rejection");
        } catch (IllegalArgumentException | IOException expected) {
            assertTrue(expected instanceof IllegalArgumentException);
        }
    }

    private static Thread serveSingle(
            ServerSocket server,
            int status,
            String reason,
            String extraHeaders,
            String body,
            AtomicReference<String> requestLineOut,
            AtomicReference<Map<String, String>> headersOut) {
        Thread worker = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line = in.readLine();
                requestLineOut.set(line);
                Map<String, String> headers = new LinkedHashMap<>();
                int contentLength = 0;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String v = line.substring(colon + 1).trim();
                        headers.put(k, v);
                        if ("content-length".equals(k)) {
                            try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                for (int i = 0; i < contentLength; i++) {
                    if (in.read() < 0) break;
                }
                headersOut.set(headers);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                String resp = "HTTP/1.1 " + status + " " + reason + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + bytes.length + "\r\n"
                        + "Connection: close\r\n"
                        + extraHeaders
                        + "\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(resp.getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.flush();
            } catch (IOException ignored) {
                // test socket closed
            }
        });
        worker.setDaemon(true);
        worker.start();
        return worker;
    }
}
