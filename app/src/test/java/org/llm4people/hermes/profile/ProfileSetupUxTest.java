package org.llm4people.hermes.profile;

import org.junit.Test;
import org.llm4people.hermes.net.HermesApi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Deterministic host-JVM tests for first-use setup URL auto-splitting, passphrase hints, and connection probes. */
public class ProfileSetupUxTest {
    @Test public void splitsPastedProfileEndpointCleanlyWithoutFalseConflict() {
        ProfileGate.SplitEndpoint pasted = ProfileGate.splitPastedEndpoint(
                "https://hermes.example.com/p/alice", "");
        assertTrue(pasted.splitApplied());
        assertFalse(pasted.conflict());
        assertEquals("https://hermes.example.com", pasted.baseUrl());
        assertEquals("alice", pasted.profileSlug());
        assertEquals("https://hermes.example.com/p/alice", pasted.composedEndpoint());

        ProfileGate.SplitEndpoint matching = ProfileGate.splitPastedEndpoint(
                "https://hermes.example.com/p/ALICE/", "alice");
        assertTrue(matching.splitApplied());
        assertFalse(matching.conflict());
        assertEquals("https://hermes.example.com", matching.baseUrl());
        assertEquals("alice", matching.profileSlug());
        assertEquals("https://hermes.example.com/p/alice", matching.composedEndpoint());

        ProfileGate.SplitEndpoint subpath = ProfileGate.splitPastedEndpoint(
                "https://hermes.example.com/sub/p/p/bob_2/", "");
        assertTrue(subpath.splitApplied());
        assertFalse(subpath.conflict());
        assertEquals("https://hermes.example.com/sub", subpath.baseUrl());
        assertEquals("bob_2", subpath.profileSlug());
        assertEquals("https://hermes.example.com/sub/p/bob_2", subpath.composedEndpoint());
    }

    @Test public void detectsConflictAndRejectsMalformedSplitInputs() {
        ProfileGate.SplitEndpoint conflict = ProfileGate.splitPastedEndpoint(
                "https://hermes.example.com/p/alice", "bob");
        assertTrue(conflict.conflict());
        assertFalse(conflict.splitApplied());

        ProfileGate.SplitEndpoint plainHost = ProfileGate.splitPastedEndpoint(
                "https://hermes.example.com", "");
        assertFalse(plainHost.splitApplied());
        assertFalse(plainHost.conflict());
        assertEquals("https://hermes.example.com", plainHost.composedEndpoint());

        ProfileGate.SplitEndpoint hostPlusSlug = ProfileGate.splitPastedEndpoint(
                "https://hermes.example.com", "alice");
        assertFalse(hostPlusSlug.splitApplied());
        assertFalse(hostPlusSlug.conflict());
        assertEquals("https://hermes.example.com/p/alice", hostPlusSlug.composedEndpoint());

        assertThrows(IllegalArgumentException.class,
                () -> ProfileGate.splitPastedEndpoint("http://remote.example.com/p/alice", ""));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileGate.splitPastedEndpoint("https://hermes.example.com/p/../other", ""));
    }

    @Test public void evaluatesLivePassphraseStrengthAndMatchStates() {
        assertEquals(ProfileLockPolicy.CredentialState.EMPTY,
                ProfileLockPolicy.evaluatePassphrase("", ""));
        assertEquals(ProfileLockPolicy.CredentialState.TOO_SHORT,
                ProfileLockPolicy.evaluatePassphrase("12345678", ""));
        assertEquals(ProfileLockPolicy.CredentialState.TOO_SHORT,
                ProfileLockPolicy.evaluatePassphrase("short-pass!", "short-pass!"));
        assertEquals(ProfileLockPolicy.CredentialState.AWAITING_CONFIRM,
                ProfileLockPolicy.evaluatePassphrase("twelve-chars", ""));
        assertEquals(ProfileLockPolicy.CredentialState.MISMATCH,
                ProfileLockPolicy.evaluatePassphrase("twelve-chars", "other"));
        assertEquals(ProfileLockPolicy.CredentialState.READY,
                ProfileLockPolicy.evaluatePassphrase("twelve-chars", "twelve-chars"));

        assertTrue(ProfileGate.sameChars("correct horse", "correct horse"));
        assertFalse(ProfileGate.sameChars("correct horse", "correct horsE"));
        assertFalse(ProfileGate.sameChars("short", "shorter"));
        assertTrue(ProfileGate.sameChars(null, null));
        assertFalse(ProfileGate.sameChars("a", null));
    }

    @Test public void classifiesProbeOutcomesAndHandlesRealHttpErrorsAndRevocation() throws Exception {
        ProfileGate.ProbeResult ok = ProfileGate.classifyProbeOutcome(true, true, null, 24);
        assertEquals(ProfileGate.ProbeOutcome.READY, ok.outcome());
        assertEquals(24L, ok.latencyMs());
        assertEquals(200, ok.httpStatus());

        ProfileGate.ProbeResult old = ProfileGate.classifyProbeOutcome(true, false, null, 18);
        assertEquals(ProfileGate.ProbeOutcome.OUTDATED_SERVER, old.outcome());
        assertEquals(18L, old.latencyMs());
        assertEquals(200, old.httpStatus());

        ProfileGate.ProbeResult offline = ProfileGate.classifyProbeOutcome(
                false, false, new IOException("connect failed"), -5);
        assertEquals(ProfileGate.ProbeOutcome.UNREACHABLE, offline.outcome());
        assertEquals(0L, offline.latencyMs());

        try (LoopbackStatusServer server = new LoopbackStatusServer()) {
            String base = "http://127.0.0.1:" + server.port();
            HermesApi.Limits limits = new HermesApi.Limits(org.llm4people.hermes.HermesConfig.DEFAULT_PROBE_TIMEOUT_MS,
                    org.llm4people.hermes.HermesConfig.DEFAULT_PROBE_TIMEOUT_MS, 256 * 1024, 256 * 1024);

            HermesApi badKey401 = new HermesApi(base + "/p/auth401", "wrong-secret", limits);
            try {
                Throwable err = assertThrows(HermesApi.HttpError.class, () -> badKey401.get("/v1/capabilities"));
                ProfileGate.ProbeResult rejected = ProfileGate.classifyProbeOutcome(false, false, err, 15);
                assertEquals(ProfileGate.ProbeOutcome.AUTH_REJECTED, rejected.outcome());
                assertEquals(401, rejected.httpStatus());
            } finally {
                badKey401.revoke();
            }

            HermesApi badKey403 = new HermesApi(base + "/p/auth403", "wrong-secret", limits);
            try {
                Throwable err = assertThrows(HermesApi.HttpError.class, () -> badKey403.get("/v1/capabilities"));
                ProfileGate.ProbeResult forbidden = ProfileGate.classifyProbeOutcome(false, false, err, 12);
                assertEquals(ProfileGate.ProbeOutcome.AUTH_REJECTED, forbidden.outcome());
                assertEquals(403, forbidden.httpStatus());
            } finally {
                badKey403.revoke();
            }

            HermesApi missingApi = new HermesApi(base + "/p/missing", "good-secret", limits);
            try {
                Throwable err = assertThrows(HermesApi.HttpError.class, () -> missingApi.get("/v1/capabilities"));
                ProfileGate.ProbeResult notFound = ProfileGate.classifyProbeOutcome(false, false, err, 9);
                assertEquals(ProfileGate.ProbeOutcome.NOT_FOUND, notFound.outcome());
                assertEquals(404, notFound.httpStatus());
            } finally {
                missingApi.revoke();
            }

            HermesApi boomApi = new HermesApi(base + "/p/boom", "good-secret", limits);
            try {
                Throwable err = assertThrows(HermesApi.HttpError.class, () -> boomApi.get("/v1/capabilities"));
                ProfileGate.ProbeResult srvErr = ProfileGate.classifyProbeOutcome(false, false, err, 11);
                assertEquals(ProfileGate.ProbeOutcome.SERVER_ERROR, srvErr.outcome());
                assertEquals(503, srvErr.httpStatus());
            } finally {
                boomApi.revoke();
            }

            HermesApi revokedApi = new HermesApi(base + "/p/auth401", "good-secret", limits);
            revokedApi.revoke();
            assertTrue(revokedApi.isRevoked());
            Throwable revokedErr = assertThrows(IOException.class, () -> revokedApi.get("/v1/capabilities"));
            assertEquals(ProfileGate.ProbeOutcome.UNREACHABLE,
                    ProfileGate.classifyProbeOutcome(false, false, revokedErr, 1).outcome());
        }
    }

    @Test public void cancellingInFlightProbeRestoresUiRevokesClientAndBlocksStaleCompletion() {
        ProfileGate.ProbeController controller = new ProfileGate.ProbeController();
        HermesApi.Limits limits = new HermesApi.Limits(org.llm4people.hermes.HermesConfig.DEFAULT_PROBE_TIMEOUT_MS,
                org.llm4people.hermes.HermesConfig.DEFAULT_PROBE_TIMEOUT_MS, 256 * 1024, 256 * 1024);

        // 1. Cancel before attach (e.g., Save tapped during rotate()'s store.open phase): resets UI & invalidates epoch.
        AtomicBoolean buttonEnabled = new AtomicBoolean(false);
        AtomicReference<String> status = new AtomicReference<>("Testing connection…");
        AtomicInteger resets = new AtomicInteger();
        int preAttachEpoch = controller.begin(() -> {
            resets.incrementAndGet();
            buttonEnabled.set(true);
            status.set("");
        });
        assertTrue(controller.isCurrent(preAttachEpoch));
        controller.cancel(true);
        assertEquals(1, resets.get());
        assertTrue(buttonEnabled.get());
        assertEquals("", status.get());
        assertFalse(controller.isCurrent(preAttachEpoch));
        assertFalse(controller.finishUi(preAttachEpoch));
        HermesApi lateApi = new HermesApi("http://127.0.0.1:9", "secret", limits);
        assertFalse(controller.attach(preAttachEpoch, lateApi));
        lateApi.revoke();

        // 2. Cancel after attach (e.g., Save tapped during /v1/capabilities): revokes live HermesApi & resets UI once.
        buttonEnabled.set(false);
        status.set("Testing connection…");
        int activeEpoch = controller.begin(() -> {
            resets.incrementAndGet();
            buttonEnabled.set(true);
            status.set("");
        });
        HermesApi liveApi = new HermesApi("http://127.0.0.1:9", "secret", limits);
        assertTrue(controller.attach(activeEpoch, liveApi));
        controller.cancel(true);
        assertTrue(liveApi.isRevoked());
        assertEquals(2, resets.get());
        assertTrue(buttonEnabled.get());
        assertEquals("", status.get());
        assertFalse(controller.finishUi(activeEpoch));
        // Repeated cancel is idempotent and does not re-run resetUi.
        controller.cancel(true);
        assertEquals(2, resets.get());

        // 3. Normal UI completion clears resetUi so a subsequent Save cancel preserves the finished probe status.
        int completedEpoch = controller.begin(() -> {
            resets.incrementAndGet();
            status.set("");
        });
        assertTrue(controller.finishUi(completedEpoch));
        status.set("Connection ready (14 ms)");
        controller.cancel(true);
        assertEquals(2, resets.get());
        assertEquals("Connection ready (14 ms)", status.get());

        // 4. Screen teardown cancel(false) revokes active client and drops UI callback without running it.
        int teardownEpoch = controller.begin(resets::incrementAndGet);
        HermesApi teardownApi = new HermesApi("http://127.0.0.1:9", "secret", limits);
        assertTrue(controller.attach(teardownEpoch, teardownApi));
        controller.cancel(false);
        assertTrue(teardownApi.isRevoked());
        assertEquals(2, resets.get());
        controller.cancel(true);
        assertEquals(2, resets.get());
    }

    @Test public void screenTransitionCleanupRunsAllActionsEvenWhenOneThrows() {
        org.llm4people.hermes.Diagnostics.clear();
        org.llm4people.hermes.Diagnostics.setSink((level, tag, msg) -> {});
        try {
            AtomicInteger ran = new AtomicInteger();
            ProfileGate.runScreenCleanup(
                    ran::incrementAndGet,
                    () -> { throw new IllegalStateException("dialog already detached"); },
                    null,
                    ran::incrementAndGet,
                    ran::incrementAndGet);
            assertEquals("Every remaining cleanup step must execute even if an earlier step throws",
                    3, ran.get());
            assertTrue(org.llm4people.hermes.Diagnostics.exportRedacted().contains("profile.screen_cleanup_failed"));

            // Null array is a safe no-op.
            ProfileGate.runScreenCleanup((Runnable[]) null);
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
            org.llm4people.hermes.Diagnostics.setSink(null);
        }
    }

    @Test public void reannouncesRepeatedDiscreteStatusErrorsWithoutNoiseOnInfo() {
        assertTrue(ProfileScreen.shouldReannounceStatus("Clé invalide", "Clé invalide", true));
        assertFalse(ProfileScreen.shouldReannounceStatus("Clé invalide", "Clé invalide", false));
        assertFalse(ProfileScreen.shouldReannounceStatus("Clé invalide", "Autre erreur", true));
        assertFalse(ProfileScreen.shouldReannounceStatus("", "", true));
        assertFalse(ProfileScreen.shouldReannounceStatus("", null, true));
        assertFalse(ProfileScreen.shouldReannounceStatus(null, "Clé invalide", true));
    }

    private static final class LoopbackStatusServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread worker;
        private volatile boolean closed;

        LoopbackStatusServer() throws IOException {
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "profile-probe-test-server");
            worker.setDaemon(true);
            worker.start();
        }

        int port() { return socket.getLocalPort(); }

        private void serve() {
            while (!closed) {
                try (Socket client = socket.accept()) {
                    handle(client);
                } catch (IOException ignored) {
                    if (closed) return;
                }
            }
        }

        private static void handle(Socket client) throws IOException {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String path = parts.length >= 2 ? parts[1] : "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Consume request headers cleanly.
            }
            int code = switch (path) {
                case "/p/auth401/v1/capabilities" -> 401;
                case "/p/auth403/v1/capabilities" -> 403;
                case "/p/missing/v1/capabilities" -> 404;
                default -> 503;
            };
            String head = "HTTP/1.1 " + code + " Test\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override public void close() throws IOException {
            closed = true;
            socket.close();
        }
    }
}
