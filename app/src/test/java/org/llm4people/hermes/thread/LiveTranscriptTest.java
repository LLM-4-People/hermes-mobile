package org.llm4people.hermes.thread;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LiveTranscriptTest {
    @Test public void largeIncrementalReplyPaintsTheNewestCodeAndRecoversFromReplayReset() {
        LiveTranscript stream = new LiveTranscript();
        String source = "```java\n" + "    a *= 2; ".repeat(5300);
        for (int n = 251; n < source.length(); n += 251) {
            assertTrue(stream.update(source.substring(0, n)));
            String shown = (stream.preceding == null ? "" : stream.preceding.text.toString()) + stream.latest.text;
            assertTrue(source.substring(0, n).endsWith(shown));
            assertTrue(shown.length() <= 2 * LiveTranscript.PAGE_CHARS);
        }
        stream.update(source + "\n```\n**Dernière instruction**");
        assertTrue(stream.earlier);
        assertTrue(stream.latest.text.toString().endsWith("Dernière instruction"));
        assertTrue(stream.update("**Nouveau**"));
        assertEquals("Nouveau", stream.latest.text.toString());
        assertFalse(stream.earlier);
        assertNull(stream.preceding);
        assertFalse(stream.update("**Nouveau**"));
        assertTrue(stream.update(""));
        assertEquals("", stream.latest.text.toString());
    }

    @Test public void aLargeTerminalOutputKeepsItsCompleteSourceAvailableForTheReader() {
        LiveTranscript stream = new LiveTranscript();
        String source = " **résultat** ".repeat(10000);
        stream.update(source);
        assertTrue(stream.later);
        assertSame(source, stream.source());
        assertTrue(stream.latest.text.length() <= LiveTranscript.PAGE_CHARS);
    }

    @org.junit.Before public void bindStrings() {
        ThreadStrings.setResolverForTest(res -> res == org.llm4people.hermes.R.string.thread_attached_image_bracket
                ? "[Image jointe]" : "");
    }

    @org.junit.After public void clearStrings() {
        ThreadStrings.setResolverForTest(null);
    }

    @Test public void incompleteMediaMarkupNeverPaintsTheGrowingPayload() {
        LiveTranscript stream = new LiveTranscript();
        String prefix = "Résumé. ".repeat(1499) + "![image](data:image/png;base64,";
        String payload = "QUJD".repeat(12000);
        for (int end = 4; end <= payload.length(); end += 203) {
            stream.update(prefix + payload.substring(0, end));
            assertTrue(stream.latest.text.toString().endsWith("[Image jointe]"));
            assertFalse(stream.latest.text.toString().contains("QUJD"));
        }
        stream.update(prefix + payload + ")\n**Suite**");
        String visible = (stream.preceding == null ? "" : stream.preceding.text.toString()) + stream.latest.text;
        assertTrue(visible.endsWith("[Image jointe]\nSuite"));
    }
}
