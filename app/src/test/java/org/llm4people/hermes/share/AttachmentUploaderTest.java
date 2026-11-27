package org.llm4people.hermes.share;

import org.junit.Test;
import org.llm4people.hermes.Digests;
import org.llm4people.hermes.camera.CapturedMedia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class AttachmentUploaderTest {
    @Test
    public void buildsSanitizedMultipartBodyAndSessionPath() throws Exception {
        byte[] content = "print('bonjour')\n".getBytes(StandardCharsets.UTF_8);
        CapturedMedia.Item item = new CapturedMedia.Item(
                CapturedMedia.Kind.DOCUMENT,
                "evil\"name.py",
                "text/x-python",
                content,
                Digests.sha256Hex(content),
                null);

        byte[] body = AttachmentUploader.buildMultipartBody("HermesBoundary1234", item);
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("--HermesBoundary1234\r\n"));
        assertTrue(text.contains("filename=\"evil_name.py\""));
        assertTrue(text.contains("Content-Type: text/x-python"));
        assertTrue(text.contains("print('bonjour')"));
        assertTrue(text.endsWith("\r\n--HermesBoundary1234--\r\n"));
        assertEquals("/api/sessions/session-42/uploads", AttachmentUploader.uploadPath("session-42"));

        CapturedMedia.Item unnamed = new CapturedMedia.Item(
                CapturedMedia.Kind.DOCUMENT, "   ", "text/plain", content, Digests.sha256Hex(content), null);
        String unnamedBody = new String(
                AttachmentUploader.buildMultipartBody("HermesBoundary5678", unnamed), StandardCharsets.UTF_8);
        assertTrue(unnamedBody.contains("filename=\"attachment\""));
    }

    @Test
    public void rejectsOversizedAttachmentsInvalidBoundariesAndTraversalPaths() {
        byte[] bytes = new byte[2048];
        CapturedMedia.Item item = new CapturedMedia.Item(
                CapturedMedia.Kind.DOCUMENT, "notes.txt", "text/plain", bytes, Digests.sha256Hex(bytes), null);
        try {
            AttachmentUploader.upload((p, b, body) -> null, "s1", item, 1024);
            fail("Expected oversized attachment rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("size limit"));
        }
        try {
            AttachmentUploader.buildMultipartBody("bad boundary!", item);
            fail("Expected invalid boundary rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        } catch (IOException unexpected) {
            fail("Unexpected IOException: " + unexpected);
        }
        try {
            AttachmentUploader.uploadPath("../escape");
            fail("Expected traversal session rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void honorsCallerResolvedRaisedArtifactLimitsAndDefaultsNonPositive() {
        long twentyFourMb = 24L * 1024 * 1024;
        long eightyMb = 80L * 1024 * 1024;
        assertEquals(twentyFourMb, AttachmentUploader.effectiveLimit(twentyFourMb));
        assertEquals(eightyMb, AttachmentUploader.effectiveLimit(eightyMb));
        assertEquals(org.llm4people.hermes.HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES,
                AttachmentUploader.effectiveLimit(0L));
        assertEquals(org.llm4people.hermes.HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES,
                AttachmentUploader.effectiveLimit(-5L));
    }
}
