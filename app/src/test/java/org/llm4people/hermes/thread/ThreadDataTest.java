package org.llm4people.hermes.thread;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for ThreadData failure detail redaction and safe truncation. */
public final class ThreadDataTest {

    @Test
    public void sanitizeFailureDetailRedactsSecretsStripsBidiCgjAndNeverSplitsSurrogates() {
        assertEquals("", ThreadData.sanitizeFailureDetail(null, "secret"));
        assertEquals("", ThreadData.sanitizeFailureDetail("", "secret"));

        String raw = "Bearer topsecret123 api_key=mykey999\n\u202E\u034F\uDB40\uDC20token-xyz\u00A0failed";
        String sanitized = ThreadData.sanitizeFailureDetail(raw, "token-xyz");
        assertFalse(sanitized.contains("topsecret123"));
        assertFalse(sanitized.contains("mykey999"));
        assertFalse(sanitized.contains("token-xyz"));
        assertFalse(sanitized.contains("\u202E"));
        assertFalse(sanitized.contains("\u034F"));
        assertTrue(sanitized.contains("failed"));

        assertEquals("a".repeat(500) + "…", ThreadData.sanitizeFailureDetail("a".repeat(520), ""));

        String emojiBoundary = "a".repeat(499) + "\uD83D\uDE00tail";
        String clamped = ThreadData.sanitizeFailureDetail(emojiBoundary, "");
        assertTrue(clamped.length() <= 501);
        assertTrue(clamped.endsWith("…"));
        assertFalse("Must never split surrogate pair at 501-char boundary",
                Character.isHighSurrogate(clamped.charAt(clamped.length() - 2)));
    }
}
