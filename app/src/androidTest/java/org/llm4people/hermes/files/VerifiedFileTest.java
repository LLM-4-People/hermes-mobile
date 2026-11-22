package org.llm4people.hermes.files;

import android.test.AndroidTestCase;
import org.json.JSONObject;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@SuppressWarnings("deprecation")
public class VerifiedFileTest extends AndroidTestCase {
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private PublishedFile item(String digest) throws Exception {
        JSONObject row = new JSONObject().put("id", "cc7706cdc48a49328bbadb5ce68c6b7e")
                .put("name", "a.txt").put("mime", "text/plain").put("size", 3).put("sha256", digest);
        return new PublishedFile("/api/mobile/files/s1", row);
    }

    private void transfer(String digest, boolean success) throws Exception {
        try (Exchange server = new Exchange("200 OK", "Content-Type: application/octet-stream\r\n", "abc")) {
            HermesApi api = new HermesApi(server.endpoint(), "fixture");
            try (VerifiedFile file = VerifiedFile.fetch(getContext().getCacheDir(), api, item(digest))) {
                assertTrue("Corrupt content was accepted", success);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                file.save(out);
                assertEquals("abc", out.toString("UTF-8"));
            } catch (IOException ex) {
                assertFalse(ex.toString(), success);
                assertEquals("File integrity check failed", ex.getMessage());
            }
            assertTrue(server.observed().startsWith("GET /p/travail" + item(digest).path + " HTTP/1.1"));
        }
    }

    public void testValidArtifactCanBeExported() throws Exception { transfer(HASH, true); }
    public void testAlteredArtifactRejectedBeforeExport() throws Exception {
        transfer("0" + HASH.substring(1), false);
    }
}
