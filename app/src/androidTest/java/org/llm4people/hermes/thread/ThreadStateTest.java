package org.llm4people.hermes.thread;

import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicReference;

public class ThreadStateTest extends TestCase {
    public void testUnacknowledgedSendRestoresSameKeyAndExactBodyAcrossRestart() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>();
        ThreadJournal first = new ThreadJournal(null, stored::set);
        first.draft("corrige le test");
        first.flush();
        JSONObject options = new JSONObject().put("model", "m").put("provider", "p")
                .put("allowed_toolsets", new JSONArray()).put("excluded_tools", new JSONArray().put("shell"))
                .put("session_id", "untrusted").put("input", "overwritten");
        JSONObject body = ThreadData.body("abc", first.draft(), options);
        JSONObject sent = first.stage(body, 10_000, 60, true);
        ThreadJournal resumed = new ThreadJournal(stored.get(), stored::set);
        assertEquals("", resumed.draft());
        assertEquals(sent.getString("key"), resumed.pending().getString("key"));
        assertEquals(body.toString(), resumed.pending().getJSONObject("body").toString());
        assertEquals("abc", body.getString("session_id"));
        assertEquals("corrige le test", body.getString("input"));
        assertEquals(0, body.getJSONArray("allowed_toolsets").length());
        assertEquals("shell", body.getJSONArray("excluded_tools").getString(0));
        resumed.setRunId("r7");
        resumed.draft("autre message");
        resumed.checkpoint(4, "bonjour");
        ThreadJournal followed = new ThreadJournal(stored.get(), stored::set);
        assertEquals("r7", followed.pending().getString("run_id"));
        assertEquals(4, followed.pending().getInt("seq"));
        assertEquals("bonjour", followed.pending().getString("preview"));
        followed.finish();
        ThreadJournal done = new ThreadJournal(stored.get(), stored::set);
        assertNull(done.pending());
        assertEquals("autre message", done.draft());
    }

    public void testFailedOutboxCommitCannotAdmitAndRetainsDraft() {
        ThreadJournal journal = new ThreadJournal(null, ignored -> { throw new IllegalStateException("full"); });
        journal.draft("bonjour");
        try {
            journal.stage(ThreadData.body("thread", journal.draft(), null), 100, 3, true);
            fail("Send without durable record");
        } catch (IllegalStateException expected) {
            assertNull(journal.pending());
            assertEquals("bonjour", journal.draft());
        }
    }

    public void testReplayRequiresContinuingDurableRetentionAndMonotonicWallTime() {
        assertTrue(ThreadData.canReplay(19_999, 10_000, 10, true));
        assertFalse(ThreadData.canReplay(20_000, 10_000, 10, true));
        assertFalse(ThreadData.canReplay(10_001, 10_000, 10, false));
        assertFalse(ThreadData.canReplay(9_999, 10_000, 10, true));
        assertFalse(ThreadData.canReplay(10_000, 10_000, 0, true));
    }

    public void testPagedLineageOverlapsAndMalformedRows() throws Exception {
        JSONArray older = new JSONArray("[{\"id\":1},{\"id\":2},null]");
        JSONArray newer = new JSONArray("[{\"id\":2},{\"id\":3}]");
        JSONArray merged = ThreadData.join(older, newer);
        assertEquals(3, merged.length());
        for (int i = 0; i < 3; i++) assertEquals(i + 1, merged.getJSONObject(i).getInt("id"));
        assertFalse(ThreadData.validId("a/b"));
        assertTrue(ThreadData.validId("héllo-1"));
        assertEquals("", ThreadData.content(null));
        JSONObject row = new JSONObject("{\"content\":[null,{\"type\":\"text\",\"text\":\"one\"},{\"type\":\"text\",\"text\":\"two\"}]}");
        assertEquals("one\ntwo", ThreadData.content(row));
        assertEquals("Nouvelle conversation", ThreadData.title(new JSONObject().put("title", JSONObject.NULL)));
    }
}
