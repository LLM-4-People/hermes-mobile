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
                "text/*", "image/*", "*/*",
                "application/vnd.android.package-archive", "application/octet-stream",
                "application/x-sh", "text/plain;".repeat(20),
                "text/plain\r\nX-Injected: evil",
                "text/plain\r\n",
                "\r\ntext/plain",
                "image/png\nX-Evil: 1",
                "image/png; charset=utf-8",
                "text/plain; charset=utf-8",
                "text/plain extra",
                "image/png\u0000",
                "text/plain\u0000",
                "text/plain\t",
                "image/",
                "/png"}) {
            assertFalse("Expected rejected MIME: " + bad, CapturedMedia.isAllowedMime(bad));
        }
        assertEquals("text/markdown", CapturedMedia.resolveMime("text/*", "text/markdown"));
        assertEquals("text/plain", CapturedMedia.resolveMime("text/*", null));
        assertEquals("application/pdf", CapturedMedia.resolveMime("*/*", "application/pdf"));
        assertEquals("image/jpeg", CapturedMedia.resolveMime("image/*", "image/jpeg"));

        // R10.2.5 & R15.2.1: Always validate concrete resolver MIME and reject CRLF/param/control injection.
        assertEquals("text/plain", CapturedMedia.resolveAndValidateMime("text/plain", null));
        assertEquals("image/jpeg", CapturedMedia.resolveAndValidateMime("text/plain", "image/jpeg"));
        assertEquals("application/pdf", CapturedMedia.resolveAndValidateMime("*/*", "application/pdf"));
        org.junit.Assert.assertNull(CapturedMedia.resolveAndValidateMime("text/plain", "application/vnd.android.package-archive"));
        org.junit.Assert.assertNull(CapturedMedia.resolveAndValidateMime("text/plain", "image/svg+xml"));
        org.junit.Assert.assertNull(CapturedMedia.resolveAndValidateMime("text/plain", "application/octet-stream"));
        org.junit.Assert.assertNull(CapturedMedia.resolveAndValidateMime("image/svg+xml", "text/plain"));
        org.junit.Assert.assertNull(CapturedMedia.resolveAndValidateMime("text/plain\r\nX-Injected: evil", null));
        org.junit.Assert.assertNull(CapturedMedia.resolveAndValidateMime("text/plain", "image/png; charset=utf-8"));
        org.junit.Assert.assertNull(CapturedMedia.resolveAndValidateMime("*/*", null));
    }

    @Test
    public void sanitizeFilenameStripsTraversalAndControlChars() {
        assertEquals("rapport.pdf", CapturedMedia.sanitizeFilename("rapport.pdf", "Document"));
        assertEquals("..etcpasswd", CapturedMedia.sanitizeFilename("../etc/passwd", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("..", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename(".", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("../", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("..\\", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\u0000\u202E", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\u034F", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\u00A0", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\uFE0F", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\u115F\u3164", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\uD83D", "Document"));
        assertEquals("photo.jpg", CapturedMedia.sanitizeFilename("photo\u034F\uD83D\uDB40\uDC20.jpg", "Document"));
        assertEquals("photo.jpg", CapturedMedia.sanitizeFilename("\u3164".repeat(140) + "photo.jpg", "Document"));
        assertEquals("photo.jpg", CapturedMedia.sanitizeFilename("\u180Fphoto\u115F.jpg", "Document"));
        assertEquals("photo.jpg", CapturedMedia.sanitizeFilename("\uFE0F".repeat(140) + "photo.jpg", "Document"));
        assertEquals("☕\uFE0F.png", CapturedMedia.sanitizeFilename("☕\uFE0F\uFE0F.png", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\u3164".repeat(140), "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\uFE0F".repeat(140), "Document"));
        // R20.2.3: Strips Unicode line/paragraph separators (U+2028 / U+2029).
        assertEquals("photo.jpg", CapturedMedia.sanitizeFilename("photo\u2028\u2029.jpg", "Document"));
        assertEquals("Document", CapturedMedia.sanitizeFilename("\u2028\u2029", "Document"));
        // Surrogate pair straddling index 128 must never leave an unpaired high surrogate.
        String splitBoundary = CapturedMedia.sanitizeFilename("a".repeat(127) + "\uD83D\uDE00", "Document");
        assertEquals("a".repeat(127), splitBoundary);
        assertFalse(Character.isHighSurrogate(splitBoundary.charAt(splitBoundary.length() - 1)));
        assertEquals("a".repeat(126) + "\uD83D\uDE00",
                CapturedMedia.sanitizeFilename("a".repeat(126) + "\uD83D\uDE00", "Document"));
        assertEquals("Photo", CapturedMedia.sanitizeFilename(null, "Photo"));
        assertEquals("Photo", CapturedMedia.queryDisplayName(null, null, "Photo"));
    }

    @Test
    public void sha256HexMatchesStandardDigest() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                CapturedMedia.sha256Hex(new byte[0]));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                CapturedMedia.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void effectiveByteLimitHonorsRaisedCallerCeilingsAndDefaultsOnlyWhenNonPositive() {
        long twentyFourMb = 24L * 1024 * 1024;
        long eightyMb = 80L * 1024 * 1024;
        assertEquals(twentyFourMb, CapturedMedia.effectiveByteLimit(twentyFourMb));
        assertEquals(eightyMb, CapturedMedia.effectiveByteLimit(eightyMb));
        assertEquals(org.llm4people.hermes.HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES,
                CapturedMedia.effectiveByteLimit(0L));
        assertEquals(org.llm4people.hermes.HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES,
                CapturedMedia.effectiveByteLimit(-1024L));

        // Negative bounds on stripImageExif overload reject empty/oversized payloads fail-closed.
        try {
            CapturedMedia.stripImageExif(new byte[0], "empty.jpg", eightyMb);
            org.junit.Assert.fail("Expected empty image rejection");
        } catch (java.io.IOException expected) {
            // ok
        }
        try {
            CapturedMedia.stripImageExif(new byte[32], "too-big.jpg", 16L);
            org.junit.Assert.fail("Expected caller-limit image rejection");
        } catch (java.io.IOException expected) {
            // ok
        }
    }
}
