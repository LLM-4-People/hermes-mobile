package org.llm4people.hermes.share;

import org.junit.Test;
import org.llm4people.hermes.profile.IncomingShare;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ClipboardPastePillTest {
    @Test
    public void neverReadsClipboardUntilExplicitUserTapAndTracksTimestamp() {
        AtomicInteger readCalls = new AtomicInteger();
        long[] timestamp = {100L};
        String[] payload = {"Bonjour Hermes"};

        ClipboardPastePill pill = new ClipboardPastePill(new ClipboardPastePill.ClipboardSource() {
            @Override public boolean hasPlainTextClip() { return payload[0] != null; }
            @Override public long clipTimestamp() { return timestamp[0]; }
            @Override public CharSequence readPrimaryText() {
                readCalls.incrementAndGet();
                return payload[0];
            }
        });

        assertTrue(pill.shouldOffer());
        assertEquals("Privacy violation: clipboard was read before user tap", 0, readCalls.get());

        assertEquals("Bonjour Hermes", pill.consumeOnUserTap());
        assertEquals(1, readCalls.get());
        assertFalse("Same timestamp should not re-appear after paste", pill.shouldOffer());

        timestamp[0] = 200L;
        payload[0] = "Nouveau texte";
        assertTrue("Newer clip timestamp should re-offer pill", pill.shouldOffer());
        assertEquals(1, readCalls.get());
        assertEquals("Nouveau texte", pill.consumeOnUserTap());
        assertFalse(pill.shouldOffer());
        assertEquals(2, readCalls.get());
    }

    @Test
    public void sanitizesControlCharsBlankTextAndOversizedClips() {
        assertNull(ClipboardPastePill.sanitizeClipboardText(null));
        assertNull(ClipboardPastePill.sanitizeClipboardText("   \t\n  "));
        assertNull("NBSP-only clipboard must be rejected", ClipboardPastePill.sanitizeClipboardText("\u00A0\u2003\u202F"));
        assertNull("Hangul filler-only clipboard must be rejected", ClipboardPastePill.sanitizeClipboardText("\u3164\u115F"));
        assertNull("BiDi + CGJ-only clipboard must be rejected", ClipboardPastePill.sanitizeClipboardText("\u202E\u2066\u034F"));
        assertEquals("ab\nc", ClipboardPastePill.sanitizeClipboardText("a\u0000b\nc"));
        assertEquals("hello world", ClipboardPastePill.sanitizeClipboardText("hel\u202Elo \u2066wor\u3164ld\u034F"));
        assertEquals("👨‍👩‍👧‍👦 ok", ClipboardPastePill.sanitizeClipboardText("👨‍👩‍👧‍👦 ok"));
        String huge = "x".repeat(IncomingShare.MAX_TEXT + 500);
        assertEquals(IncomingShare.MAX_TEXT, ClipboardPastePill.sanitizeClipboardText(huge).length());
    }
}
