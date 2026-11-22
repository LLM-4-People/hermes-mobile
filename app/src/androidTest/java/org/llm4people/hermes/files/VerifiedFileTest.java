package org.llm4people.hermes.files;

import android.test.AndroidTestCase;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class VerifiedFileTest extends AndroidTestCase {
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private PublishedFile item(String digest) throws Exception {
        JSONObject row = new JSONObject().put("id", "cc7706cdc48a49328bbadb5ce68c6b7e")
                .put("name", "a.txt").put("mime", "text/plain").put("size", 3).put("sha256", digest);
        return new PublishedFile("/api/mobile/files/s1", row);
    }

    private void transfer(String digest, boolean success) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            FutureTask<Void> response = new FutureTask<>(() -> {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(3000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(peer.getInputStream(),
                            StandardCharsets.US_ASCII));
                    assertEquals("GET " + item(digest).path + " HTTP/1.1", input.readLine());
                    String header;
                    while ((header = input.readLine()) != null && !header.isEmpty()) { }
                    OutputStream out = peer.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc"
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                return null;
            });
            Thread server = new Thread(response, "file-test-server");
            server.setDaemon(true);
            server.start();
            HermesApi api = new HermesApi("http://127.0.0.1:" + listener.getLocalPort(), "fixture");
            try (VerifiedFile file = VerifiedFile.fetch(getContext().getCacheDir(), api, item(digest))) {
                assertTrue("Corrupt content was accepted", success);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                file.save(out);
                assertEquals("abc", out.toString("UTF-8"));
            } catch (IOException ex) {
                assertFalse(ex.toString(), success);
                assertEquals("File integrity check failed", ex.getMessage());
            }
            response.get(4, TimeUnit.SECONDS);
        }
    }

    public void testValidArtifactCanBeExported() throws Exception { transfer(HASH, true); }
    public void testAlteredArtifactRejectedBeforeExport() throws Exception {
        transfer("0" + HASH.substring(1), false);
    }
}
