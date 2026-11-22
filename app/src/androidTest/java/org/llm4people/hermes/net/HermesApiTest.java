package org.llm4people.hermes.net;

import junit.framework.TestCase;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class HermesApiTest extends TestCase {
    private static final String TOKEN = "test-only";
    private static final String JSON = "Content-Type: application/json\r\n";

    private static final class Exchange implements Closeable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final FutureTask<String> observation;

        Exchange(String status, String headers, String reply) throws IOException {
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            observation = new FutureTask<>(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    StringBuilder wire = new StringBuilder();
                    String line;
                    int bytes = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        wire.append(line).append('\n');
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                            bytes = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    for (int i = 0; i < bytes; i++) wire.append((char) reader.read());
                    OutputStream out = socket.getOutputStream();
                    String head = "HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: " +
                            body.length + "\r\nConnection: close\r\n\r\n";
                    out.write(head.getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                    return wire.toString();
                }
            });
            Thread server = new Thread(observation, "hermes-test-server");
            server.setDaemon(true);
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + listener.getLocalPort() + "/p/travail/"; }
        HermesApi api() { return new HermesApi(endpoint(), TOKEN); }
        String observed() throws Exception { return observation.get(4, TimeUnit.SECONDS); }
        @Override public void close() throws IOException { listener.close(); }
    }

    public void testRunAdmissionUsesProfileAuthAndStableKey() throws Exception {
        try (Exchange server = new Exchange("202 Accepted", JSON, "{\"run_id\":\"r1\"}")) {
            JSONObject body = new JSONObject().put("input", "hello").put("session_id", "s1");
            JSONObject admitted = server.api().post("/v1/runs", body, "turn-1");
            assertEquals("r1", admitted.getString("run_id"));
            String wire = server.observed();
            assertTrue(wire.startsWith("POST /p/travail/v1/runs HTTP/1.1\n"));
            assertTrue(wire.toLowerCase(Locale.ROOT).contains("authorization: bearer " + TOKEN));
            assertTrue(wire.toLowerCase(Locale.ROOT).contains("idempotency-key: turn-1"));
            assertTrue(wire.endsWith(body.toString()));
        }
    }

    public void testRedirectIsReturnedWithoutFollowingLocation() throws Exception {
        try (Exchange server = new Exchange("307 Temporary Redirect", "Location: http://127.0.0.1:1/\r\n", "")) {
            try { server.api().get("/v1/capabilities"); fail("redirect accepted"); }
            catch (HermesApi.HttpError e) { assertEquals(307, e.status); }
            assertTrue(server.observed().startsWith("GET /p/travail/v1/capabilities "));
        }
    }

    public void testServiceErrorPreservesRetryAndCode() throws Exception {
        try (Exchange server = new Exchange("503 Service Unavailable", JSON + "Retry-After: 3\r\n",
                "{\"error\":{\"message\":\"sensitive prose\",\"code\":\"runtime_draining\"}}")) {
            try { server.api().get("/api/jobs"); fail("503 accepted"); }
            catch (HermesApi.HttpError e) {
                assertEquals(503, e.status);
                assertEquals(3L, e.retryAfterSeconds);
                assertEquals("runtime_draining", e.code);
                assertFalse(e.getMessage().contains("sensitive"));
            }
        }
    }

    public void testEmptyPut() throws Exception {
        try (Exchange server = new Exchange("204 No Content", "", "")) {
            assertEquals(0, server.api().put("/api/registration", new JSONObject()).length());
            assertTrue(server.observed().startsWith("PUT "));
        }
    }

    public void testPatch() throws Exception {
        try (Exchange server = new Exchange("200 OK", JSON, "{\"ok\":true}")) {
            assertTrue(server.api().patch("/api/sessions/thread", new JSONObject().put("title", "A")).getBoolean("ok"));
            assertTrue(server.observed().startsWith("PATCH "));
        }
    }

    public void testStreamResumesAndParsesMultilineAndComments() throws Exception {
        String stream = ": open\r\n\r\nid: 12\r\ndata: {\"event\":\"message.delta\",\r\n" +
                "data: \"delta\":\"é\",\"seq\":12}\r\n\r\n: keepalive\n\n";
        try (Exchange server = new Exchange("200 OK", "Content-Type: text/event-stream; charset=utf-8\r\n", stream);
             HermesApi.RunEvents events = server.api().runEvents("r1", 11)) {
            JSONObject frame = events.next();
            assertEquals(12L, frame.getLong("seq"));
            assertEquals("é", frame.getString("delta"));
            assertNull(events.next());
            String wire = server.observed();
            assertTrue(wire.startsWith("GET /p/travail/v1/runs/r1/events "));
            assertTrue(wire.toLowerCase(Locale.ROOT).contains("last-event-id: 11"));
        }
    }

    public void testMissingFrameBoundaryAndOversizedReplyAreRejected() throws Exception {
        byte[] partial = "data: {\"seq\":3}\n".getBytes(StandardCharsets.UTF_8);
        try { new SseReader(new ByteArrayInputStream(partial), 100).next(); fail("partial frame"); }
        catch (EOFException expected) { }
        byte[] large = "data: {\"seq\":1}\n\n".getBytes(StandardCharsets.UTF_8);
        try { new SseReader(new ByteArrayInputStream(large), 8).next(); fail("large frame"); }
        catch (IOException expected) { }
        try (Exchange server = new Exchange("200 OK", JSON, "{\"content\":\"12345678901234567890\"}")) {
            HermesApi client = new HermesApi(server.endpoint(), TOKEN, new HermesApi.Limits(2000, 2000, 16, 32));
            try { client.get("/api/jobs"); fail("large JSON"); } catch (IOException expected) { }
        }
    }

    public void testClosingAnActiveSubscriptionWakesTheReader() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch finish = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Future<?> server = workers.submit(() -> {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(2500);
                    BufferedReader headers = new BufferedReader(new InputStreamReader(peer.getInputStream(),
                            StandardCharsets.US_ASCII));
                    String line;
                    while ((line = headers.readLine()) != null && !line.isEmpty()) { }
                    OutputStream out = peer.getOutputStream();
                    String first = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                            "Connection: close\r\n\r\ndata: {\"seq\":0}\n\n";
                    out.write(first.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    finish.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            HermesApi client = new HermesApi("http://127.0.0.1:" + listener.getLocalPort(), TOKEN);
            try (HermesApi.RunEvents events = client.runEvents("r1", -1)) {
                assertEquals(0, events.next().getInt("seq"));
                CountDownLatch reading = new CountDownLatch(1);
                Future<?> reader = workers.submit(() -> {
                    reading.countDown();
                    try { assertNull(events.next()); } catch (IOException closed) { }
                    return null;
                });
                assertTrue(reading.await(2, TimeUnit.SECONDS));
                events.close();
                reader.get(3, TimeUnit.SECONDS);
                assertNull(events.next());
            } finally {
                finish.countDown();
            }
            server.get(3, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            workers.shutdownNow();
        }
    }

    public void testOriginAndPrefixCannotBeReplaced() throws Exception {
        String[] invalid = {"http://remote.example", "http://localhost", "http://127.0.0.1.evil",
                "file:///server", "https://user:password@server", "https://server?token=secret",
                "https://server/p/../x", "https://server/#fragment"};
        for (String value : invalid) {
            try { new HermesApi(value, TOKEN); fail("accepted endpoint " + value); }
            catch (IllegalArgumentException expected) { }
        }
        Endpoint endpoint = new Endpoint("https://example.org/p/travail/");
        for (String path : new String[]{"//evil.example/read", "https://evil.example/read", "/%2e%2e/x",
                "/x%2Fy", "/x%5cy", "/x%0dy", "/x#fragment"}) {
            try { endpoint.url(path); fail("accepted path " + path); } catch (IllegalArgumentException expected) { }
        }
        assertEquals("a%20b%C3%A9%3F", HermesApi.segment("a bé?"));
        assertEquals("https://example.org/p/travail/api/sessions?limit=3",
                endpoint.url("/api/sessions?limit=3").toString());
        HermesApi.RunEvents unopened = new HermesApi("https://example.org", TOKEN).runEvents("r1", -1);
        unopened.close();
        assertNull(unopened.next());
    }
}
