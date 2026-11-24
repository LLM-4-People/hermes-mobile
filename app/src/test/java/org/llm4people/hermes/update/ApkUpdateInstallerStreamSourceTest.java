package org.llm4people.hermes.update;

import org.junit.Test;
import org.llm4people.hermes.HermesConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ApkUpdateInstallerStreamSourceTest {
    private static final class FakeHttpURLConnection extends HttpURLConnection {
        private final int status;
        private final String location;
        private final InputStream inputStream;
        private final InputStream errorStream;
        private final IOException responseError;
        private final IOException streamError;
        final AtomicInteger disconnects = new AtomicInteger();

        FakeHttpURLConnection(
                URL url,
                int status,
                String location,
                InputStream inputStream,
                InputStream errorStream,
                IOException responseError,
                IOException streamError) {
            super(url);
            this.status = status;
            this.location = location;
            this.inputStream = inputStream;
            this.errorStream = errorStream;
            this.responseError = responseError;
            this.streamError = streamError;
        }

        @Override public void connect() {}
        @Override public boolean usingProxy() { return false; }

        @Override public void disconnect() {
            disconnects.incrementAndGet();
        }

        @Override public int getResponseCode() throws IOException {
            if (responseError != null) throw responseError;
            return status;
        }

        @Override public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }

        @Override public InputStream getInputStream() throws IOException {
            if (streamError != null) throw streamError;
            return inputStream;
        }

        @Override public InputStream getErrorStream() {
            return errorStream;
        }
    }

    @Test
    public void disconnectsImmediatelyWhenErrorStreamIsNull() throws Exception {
        URL url = new URL("https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.0.0/app.apk");
        FakeHttpURLConnection conn = new FakeHttpURLConnection(url, 502, null, null, null, null, null);
        ApkUpdateInstaller.HttpsStreamSource source =
                new ApkUpdateInstaller.HttpsStreamSource(HermesConfig.defaults(), ignored -> conn);

        try (ApkUpdateInstaller.StreamResponse resp = source.open(url.toString())) {
            assertEquals(502, resp.statusCode());
            assertNull(resp.body());
            assertEquals(1, conn.disconnects.get());
        }
        assertEquals(1, conn.disconnects.get());
    }

    @Test
    public void disconnectsImmediatelyWhenOpeningStreamThrows() throws Exception {
        URL url = new URL("https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.0.0/app.apk");
        FakeHttpURLConnection conn = new FakeHttpURLConnection(
                url, 200, null, null, null, null, new IOException("socket reset"));
        ApkUpdateInstaller.HttpsStreamSource source =
                new ApkUpdateInstaller.HttpsStreamSource(HermesConfig.defaults(), ignored -> conn);

        try {
            source.open(url.toString());
            fail("Expected IOException from getInputStream()");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("socket reset"));
        }
        assertEquals(1, conn.disconnects.get());
    }

    @Test
    public void disconnectsImmediatelyOnRedirect() throws Exception {
        URL url = new URL("https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.0.0/app.apk");
        FakeHttpURLConnection conn = new FakeHttpURLConnection(
                url, 302, "https://objects.githubusercontent.com/apk", null, null, null, null);
        ApkUpdateInstaller.HttpsStreamSource source =
                new ApkUpdateInstaller.HttpsStreamSource(HermesConfig.defaults(), ignored -> conn);

        try (ApkUpdateInstaller.StreamResponse resp = source.open(url.toString())) {
            assertEquals(302, resp.statusCode());
            assertEquals("https://objects.githubusercontent.com/apk", resp.locationHeader());
            assertNull(resp.body());
            assertEquals(1, conn.disconnects.get());
        }
        assertEquals(1, conn.disconnects.get());
    }

    @Test
    public void transfersOwnershipOnSuccessAndDisconnectsOnlyOnClose() throws Exception {
        URL url = new URL("https://github.com/LLM-4-People/hermes-mobile/releases/download/v1.0.0/app.apk");
        AtomicBoolean streamClosed = new AtomicBoolean();
        ByteArrayInputStream payload = new ByteArrayInputStream("apk-bytes".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };
        FakeHttpURLConnection conn = new FakeHttpURLConnection(url, 200, null, payload, null, null, null);
        ApkUpdateInstaller.HttpsStreamSource source =
                new ApkUpdateInstaller.HttpsStreamSource(HermesConfig.defaults(), ignored -> conn);

        ApkUpdateInstaller.StreamResponse resp = source.open(url.toString());
        assertEquals(200, resp.statusCode());
        assertNotNull(resp.body());
        assertEquals("Connection must remain open until caller closes body", 0, conn.disconnects.get());

        byte[] read = resp.body().readAllBytes();
        assertEquals("apk-bytes", new String(read, StandardCharsets.UTF_8));
        assertEquals(0, conn.disconnects.get());

        resp.close();
        assertTrue(streamClosed.get());
        assertEquals(1, conn.disconnects.get());
    }
}
