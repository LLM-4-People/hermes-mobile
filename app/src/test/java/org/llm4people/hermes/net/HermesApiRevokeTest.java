package org.llm4people.hermes.net;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies HermesApi.revoke() immediately aborts active HTTP request/download sockets
 * and rejects subsequent calls fail-closed.
 */
public final class HermesApiRevokeTest {

    @Before
    public void setUpDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, msg) -> {});
    }

    @After
    public void tearDownDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void limitsCarriesConfiguredArtifactByteCeilingAndPostMultipartEnforcesIt() {
        org.llm4people.hermes.HermesConfig tight = new org.llm4people.hermes.HermesConfig(
                1000, 2000, 512, 512, 3000, 500, 1024L, 2048L, 10, 20, 2,
                org.llm4people.hermes.HermesConfig.DEFAULT_UPDATE_REPO,
                org.llm4people.hermes.HermesConfig.DEFAULT_UPDATE_API_BASE,
                org.llm4people.hermes.HermesConfig.DEFAULT_UPDATE_MAX_APK_BYTES,
                org.llm4people.hermes.HermesConfig.DEFAULT_UPDATE_CHECK_INTERVAL_MS,
                org.llm4people.hermes.HermesConfig.DEFAULT_GITHUB_WEB_BASE_URL,
                org.llm4people.hermes.HermesConfig.DEFAULT_OSM_BASE_URL,
                org.llm4people.hermes.HermesConfig.DEFAULT_DOCTOR_CHECK_TIMEOUT_MS,
                org.llm4people.hermes.HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS,
                org.llm4people.hermes.HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES,
                org.llm4people.hermes.HermesConfig.DEFAULT_PROBE_TIMEOUT_MS,
                org.llm4people.hermes.HermesConfig.DEFAULT_VOICE_SERVICE_TIMEOUT_MS,
                org.llm4people.hermes.HermesConfig.DEFAULT_TTS_INIT_TIMEOUT_MS,
                org.llm4people.hermes.HermesConfig.DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS,
                org.llm4people.hermes.HermesConfig.DEFAULT_HTTP_MAX_REDIRECTS);
        HermesApi.Limits limits = HermesApi.Limits.from(tight);
        assertEquals(1024L, limits.artifactDefaultMaxBytes);

        HermesApi api = new HermesApi("http://127.0.0.1:18099", "test-bearer", limits);
        byte[] oversized = new byte[1024 + 4096 + 1];
        try {
            api.postMultipart("/api/upload", "boundary123", oversized);
            fail("Expected postMultipart to reject payload exceeding configured artifactDefaultMaxBytes");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("byte limit"));
        }

        try {
            new HermesApi.Limits(1000, 1000, 1024, 1024, 0L);
            fail("Expected rejection for non-positive artifactDefaultMaxBytes");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void revokeImmediatelyAbortsInFlightDownloadAndBlocksNewCalls() throws Exception {
        CountDownLatch chunkReceived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (Socket client = server.accept()) {
                    // Consume HTTP request headers minimally before sending a stalled response body.
                    client.getInputStream().read(new byte[512]);
                    OutputStream out = client.getOutputStream();
                    // Omit Content-Length + set Connection: close to avoid OpenJDK KeepAliveStream/MeteredStream
                    // readLock deadlock when disconnect() races with a blocked read().
                    String headers = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/octet-stream\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(headers.getBytes(StandardCharsets.US_ASCII));
                    out.write("partial-bytes".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    releaseServer.await(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Socket closed by client revoke().
                }
            }, "hermes-api-revoke-test-server");
            responder.setDaemon(true);
            responder.start();

            HermesApi api = new HermesApi(
                    "http://127.0.0.1:" + server.getLocalPort(),
                    "test-bearer-secret",
                    new HermesApi.Limits(5_000, 15_000, 65_536, 65_536));

            OutputStream signalingSink = new ByteArrayOutputStream() {
                @Override
                public synchronized void write(byte[] b, int off, int len) {
                    super.write(b, off, len);
                    if (len > 0) chunkReceived.countDown();
                }
            };

            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(1);
            Thread downloader = new Thread(() -> {
                try {
                    api.download("/api/sessions/s1/export?format=markdown", signalingSink, 8192);
                } catch (Throwable err) {
                    failure.set(err);
                } finally {
                    finished.countDown();
                }
            }, "hermes-api-revoke-test-client");
            downloader.setDaemon(true);
            downloader.start();

            assertTrue("Client should receive initial partial chunk", chunkReceived.await(5, TimeUnit.SECONDS));
            long startedRevoke = System.nanoTime();
            api.revoke();
            assertTrue("In-flight download must abort promptly on revoke()", finished.await(8, TimeUnit.SECONDS));
            long abortMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedRevoke);
            assertTrue("Download aborted too slowly (" + abortMs + "ms)", abortMs < 8_000L);
            assertNotNull("Revoked download must throw IOException", failure.get());
            assertTrue("Expected IOException, got " + failure.get(), failure.get() instanceof IOException);
            assertTrue(api.isRevoked());

            // Subsequent HTTP calls fail closed immediately.
            try {
                api.get("/v1/capabilities");
                fail("Expected IOException after revoke()");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("revoked"));
            }
            try {
                api.download("/api/sessions/s1/export?format=markdown", new ByteArrayOutputStream(), 1024);
                fail("Expected IOException on download after revoke()");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("revoked"));
            }
            releaseServer.countDown();
        }
    }

    @Test
    public void revokedDownloadNeverReturnsPartialBytesOnCleanEof() throws Exception {
        CountDownLatch serverClosed = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.getInputStream().read(new byte[512]);
                    OutputStream out = client.getOutputStream();
                    String headers = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/octet-stream\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(headers.getBytes(StandardCharsets.US_ASCII));
                    out.write("partial-bytes".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                } finally {
                    serverClosed.countDown();
                }
            }, "hermes-api-clean-eof-server");
            responder.setDaemon(true);
            responder.start();

            HermesApi api = new HermesApi(
                    "http://127.0.0.1:" + server.getLocalPort(),
                    "test-bearer-secret",
                    new HermesApi.Limits(5_000, 15_000, 65_536, 65_536));

            OutputStream revokingSink = new ByteArrayOutputStream() {
                @Override
                public synchronized void write(byte[] b, int off, int len) {
                    super.write(b, off, len);
                    // Wait for the server to finish its clean FIN close so the next read() hits EOF (-1),
                    // then revoke the client so post-loop revocation MUST throw rather than returning partial bytes.
                    try {
                        serverClosed.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    api.revoke();
                }
            };

            try {
                api.download("/api/sessions/s1/export?format=markdown", revokingSink, 8192);
                fail("Revoked download must throw IOException even if stream reaches clean EOF");
            } catch (IOException expected) {
                assertTrue(api.isRevoked());
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void revokeAbortsInFlightSseStreamAndDisconnectSafelySwallowsRuntimeException() throws Exception {
        // disconnectSafely must never propagate RuntimeException during revoke() / RunEvents.close().
        HttpURLConnection throwingConn = new HttpURLConnection(new URL("http://127.0.0.1:1/")) {
            @Override public void disconnect() { throw new IllegalStateException("already torn down"); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() {}
        };
        HermesApi.disconnectSafely(throwingConn);
        HermesApi.disconnectSafely(null);

        CountDownLatch streamOpened = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.getInputStream().read(new byte[512]);
                    OutputStream out = client.getOutputStream();
                    // Send valid SSE headers + comment frame so SseReader stays blocked waiting for events
                    // (avoiding host-JVM org.json.JSONObject stubs while exercising the live RunEvents socket).
                    String headers = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream; charset=utf-8\r\n"
                            + "Connection: close\r\n\r\n"
                            + ": open\n\n";
                    out.write(headers.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    streamOpened.countDown();
                    releaseServer.await(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Socket closed by client revoke().
                }
            }, "hermes-api-sse-revoke-server");
            responder.setDaemon(true);
            responder.start();

            HermesApi api = new HermesApi(
                    "http://127.0.0.1:" + server.getLocalPort(),
                    "test-bearer-secret",
                    new HermesApi.Limits(5_000, 15_000, 65_536, 65_536));

            try (HermesApi.RunEvents events = api.runEvents("r-revoke", -1)) {
                CountDownLatch readerFinished = new CountDownLatch(1);
                AtomicReference<Throwable> readerError = new AtomicReference<>();
                Thread reader = new Thread(() -> {
                    try {
                        events.next();
                    } catch (Throwable err) {
                        readerError.set(err);
                    } finally {
                        readerFinished.countDown();
                    }
                }, "hermes-api-sse-revoke-reader");
                reader.setDaemon(true);
                reader.start();

                assertTrue("Server should open SSE stream", streamOpened.await(5, TimeUnit.SECONDS));
                api.revoke();
                assertTrue("In-flight SSE stream must abort promptly on revoke()",
                        readerFinished.await(8, TimeUnit.SECONDS));
                Throwable err = readerError.get();
                assertTrue("Revoked SSE reader must return null or IOException, got " + err,
                        err == null || err instanceof IOException);

                // Subsequent reads on revoked RunEvents fail closed with IOException("Client revoked").
                try {
                    events.next();
                    fail("Expected IOException on revoked RunEvents.next()");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("revoked"));
                }
            }
            try (HermesApi.RunEvents fresh = api.runEvents("r-after-revoke", -1)) {
                fresh.next();
                fail("Expected IOException on new RunEvents after revoke()");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("revoked"));
            }
            releaseServer.countDown();
        }
    }
}
