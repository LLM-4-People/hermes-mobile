package org.llm4people.hermes.camera;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CapturedMediaTest {
    @Test
    public void mimeAllowlistAcceptsSafeMediaAndRejectsDangerousTypes() {
        for (String ok : new String[]{"image/png", "image/jpeg", "image/webp", "application/pdf",
                "text/plain", "text/x-python", "application/json", "application/yaml"}) {
            assertTrue("Expected allowed MIME: " + ok, CapturedMedia.isAllowedMime(ok));
        }
        for (String bad : new String[]{null, "", "   ", "image/svg+xml",
                "application/vnd.android.package-archive", "application/octet-stream",
                "application/x-sh", "text/plain;".repeat(20)}) {
            assertFalse("Expected rejected MIME: " + bad, CapturedMedia.isAllowedMime(bad));
        }
    }

    @Test
    public void sanitizeFilenameStripsTraversalAndControlChars() {
        assertEquals("rapport.pdf", CapturedMedia.sanitizeFilename("rapport.pdf", "Document"));
        assertEquals("..etcpasswd", CapturedMedia.sanitizeFilename("../etc/passwd", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("..", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename(".", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\u0000\u202E", "Document"));
        assertEquals("Photo", CapturedMedia.sanitizeFilename(null, "Photo"));
    }

    @Test
    public void sha256HexMatchesStandardDigest() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                CapturedMedia.sha256Hex(new byte[0]));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                CapturedMedia.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)));
    }
}
