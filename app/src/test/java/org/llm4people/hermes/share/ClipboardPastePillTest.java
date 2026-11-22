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

        pill.dismissCurrent();
        assertFalse(pill.shouldOffer());
        assertEquals(1, readCalls.get());
    }

    @Test
    public void sanitizesControlCharsBlankTextAndOversizedClips() {
        assertNull(ClipboardPastePill.sanitizeClipboardText(null));
        assertNull(ClipboardPastePill.sanitizeClipboardText("   \t\n  "));
        assertEquals("ab\nc", ClipboardPastePill.sanitizeClipboardText("a\u0000b\nc"));
        String huge = "x".repeat(IncomingShare.MAX_TEXT + 500);
        assertEquals(IncomingShare.MAX_TEXT, ClipboardPastePill.sanitizeClipboardText(huge).length());
    }
}
