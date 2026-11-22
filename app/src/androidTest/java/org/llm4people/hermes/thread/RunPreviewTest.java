package org.llm4people.hermes.thread;

import junit.framework.TestCase;
import org.json.JSONObject;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import java.util.Locale;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class RunPreviewTest extends TestCase {
    private static String repeat(String value, int count) {
        char[] copies = new char[count];
        Arrays.fill(copies, 'x');
        return new String(copies).replace("x", value);
    }

    private static String visible(LiveTranscript view) {
        return (view.preceding == null ? "" : view.preceding.text.toString()) + view.latest.text;
    }

    public void testTailAdvancesAcrossSeveralEvictionsAndNeverCutsASurrogatePair() {
        RunPreview preview = new RunPreview(null);
        LiveTranscript view = new LiveTranscript();
        String text = repeat("a", RunPreview.LIMIT / 2 - 1) + "🌿" + repeat("a", RunPreview.LIMIT / 2);
        preview.append(text);
        assertEquals(text.substring((int) preview.origin().offset), preview.toString());
        assertFalse(Character.isLowSurrogate(preview.toString().charAt(0)));
        for (int i = 0; i < 18; i++) {
            String marker = " Fin " + i + "🌿";
            preview.append(repeat("texte ", 3000) + marker);
            assertTrue(view.update(preview.toString(), preview.origin()));
            assertTrue(visible(view).endsWith(marker));
            assertTrue(view.excerpt());
            assertTrue(preview.toString().length() <= RunPreview.LIMIT);
            assertTrue(view.latest.text.length() <= LiveTranscript.PAGE_CHARS + 1);
        }
        assertFalse(view.update(preview.toString(), preview.origin()));
    }

    public void testOpenDiffAndItsLineSurviveCheckpointRestartAndCloseAtTheOriginalFence() throws Exception {
        AtomicReference<String> saved = new AtomicReference<>();
        ThreadJournal journal = new ThreadJournal("chat", "owner", null, saved::set);
        journal.stage(ThreadData.body("chat", "demande", null), 10, 1, 60, true);
        journal.setRunId("run");
        RunPreview first = new RunPreview(journal.pending());
        first.append("~~~~diff\n+" + repeat("code ", 25_000));
        journal.checkpoint(40, first.toString(), first.origin(), first.pendingHighSurrogate());
        journal.flush();
        JSONObject receipt = new ThreadJournal("chat", "owner", saved.get(), saved::set).pending();
        assertEquals(40L, receipt.getLong("seq"));
        RunPreview restored = new RunPreview(receipt);
        assertEquals(first.origin().offset, restored.origin().offset);
        restored.append("marque");
        LiveTranscript view = new LiveTranscript();
        view.update(restored.toString(), restored.origin());
        assertTrue(visible(view).endsWith("marque"));
        assertTrue(view.latest.marks.stream().anyMatch(mark -> mark.style == TranscriptPage.Style.INSERTION));
        TranscriptPage reader = new TranscriptPage(view.source(), view.readerStart(), true, 200);
        assertTrue(reader.marks.stream().anyMatch(mark -> mark.style == TranscriptPage.Style.INSERTION));
        restored.append("\n~~~\n+ autre\n~~~~\n**Terminé**");
        view.update(restored.toString(), restored.origin());
        assertTrue(visible(view).endsWith("Terminé"));
        assertTrue(view.latest.marks.stream().anyMatch(mark -> mark.style == TranscriptPage.Style.BOLD));
        String canonical = "Ouverture de la réponse\n" + repeat("page complète ", 8000) + "FIN";
        view.update(canonical);
        assertSame(canonical, view.source());
        assertFalse(view.excerpt());
        assertTrue(view.latest.text.toString().startsWith("Ouverture de la réponse"));
        assertTrue(view.later);
    }

    public void testImageDataRemainsCollapsedAfterEvictionAndReplayGapDropsPriorMarkup() {
        RunPreview preview = new RunPreview(null);
        preview.append("![graphique](data:image/png;base64," + repeat("QUJD", 36_000));
        LiveTranscript view = new LiveTranscript();
        view.update(preview.toString(), preview.origin());
        assertEquals("[Image jointe]", visible(view));
        preview.append(")\n**Suite**");
        view.update(preview.toString(), preview.origin());
        assertEquals("[Image jointe]\nSuite", visible(view));
        preview.gap();
        preview.append("**Reprise**");
        view.update(preview.toString(), preview.origin());
        assertEquals("Reprise", visible(view));
        assertTrue(view.excerpt());
    }

    private static final class Observer implements RunFollower.Listener {
        RunFollower.Frame last;
        JSONObject terminal;
        boolean disconnected;
        public void update(RunFollower.Frame frame) { last = frame; }
        public void complete(JSONObject status, long first, long elapsed) { terminal = status; }
        public void disconnected() { disconnected = true; }
        public void unknown(JSONObject status) { throw new AssertionError("Unexpected unknown status"); }
    }

    private static String event(int seq, String delta) throws Exception {
        return "data: " + new JSONObject().put("seq", seq).put("event", "message.delta")
                .put("run_id", "r").put("delta", delta) + "\n\n";
    }

    public void testRealEventStreamCheckpointsNewestTailAndReconcilesFullReply() throws Exception {
        String running = "{\"run_id\":\"r\",\"status\":\"running\"}";
        String headers = "Content-Type: text/event-stream\r\n";
        String initial = repeat("a", RunPreview.LIMIT);
        JSONObject receipt = new JSONObject();
        try (Exchange wire = new Exchange("200 OK", headers, running, event(1, initial) + event(2, "marque"), running)) {
            Observer listener = new Observer();
            new RunFollower(new HermesApi(wire.endpoint(), "test-only"), "r", receipt, -1, listener).follow();
            assertTrue(listener.disconnected);
            assertEquals(2L, listener.last.seq);
            assertTrue(listener.last.text.endsWith("marque"));
            assertTrue(listener.last.origin.offset > 0);
            assertTrue(wire.observed().contains("GET /p/travail/v1/runs/r/events"));
            receipt.put("seq", listener.last.seq).put("preview", listener.last.text)
                    .put("preview_origin", listener.last.origin.json());
        }
        String complete = new JSONObject().put("run_id", "r").put("status", "completed")
                .put("output", initial + "marque fin").toString();
        try (Exchange wire = new Exchange("200 OK", headers, running, event(2, "doublon") + event(3, " fin"), complete)) {
            Observer listener = new Observer();
            new RunFollower(new HermesApi(wire.endpoint(), "test-only"), "r", receipt, -1, listener).follow();
            assertEquals(3L, listener.last.seq);
            assertTrue(listener.last.text.endsWith("marque fin"));
            assertFalse(listener.last.text.contains("doublon"));
            assertEquals(initial + "marque fin", listener.terminal.getString("output"));
            assertTrue(wire.observed().toLowerCase(Locale.ROOT).contains("last-event-id: 2"));
        }
    }

    public void testSplitUnicodeDeltaCompletesAfterRestoringItsCursor() throws Exception {
        RunPreview first = new RunPreview(null);
        first.append("Suite " + Character.highSurrogate(0x1f33f));
        assertEquals("Suite ", first.toString());
        JSONObject receipt = new JSONObject().put("seq", 51).put("preview", first.toString())
                .put("preview_origin", first.origin().json()).put("preview_high_surrogate", first.pendingHighSurrogate());
        RunPreview resumed = new RunPreview(receipt);
        resumed.append(String.valueOf(Character.lowSurrogate(0x1f33f)));
        assertEquals("Suite 🌿", resumed.toString());
        assertEquals(0, resumed.pendingHighSurrogate());
    }

    public void testOldCappedPrefixCannotBeJoinedToLaterReplayAsContiguousText() throws Exception {
        JSONObject legacy = new JSONObject().put("seq", 91).put("preview", repeat("z", RunPreview.LIMIT));
        RunPreview preview = new RunPreview(legacy);
        preview.append("suite");
        assertEquals("suite", preview.toString());
        assertTrue(preview.origin().offset > 0);
    }
}
