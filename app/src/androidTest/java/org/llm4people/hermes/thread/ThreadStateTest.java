package org.llm4people.hermes.thread;

import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThreadStateTest extends TestCase {
    public void testShareReceiptSurvivesReopenAndNeverReplacesTheDraft() {
        AtomicReference<String> saved = new AtomicReference<>();
        ThreadJournal journal = new ThreadJournal("thread", "owner", null, saved::set);
        journal.draft("avant");
        journal.appendShare("receipt", "  code();\n");
        ThreadJournal recovered = new ThreadJournal("thread", "owner", saved.get(), saved::set);
        recovered.appendShare("receipt", "  code();\n");
        assertEquals("avant\n  code();\n", recovered.draft());
        assertNull(recovered.pending());
        recovered.draft("modifié");
        recovered.appendShare("receipt", "ne doit pas réapparaître");
        assertEquals("modifié", recovered.draft());
    }

    public void testRemotePromptNoticeChecksKindSessionRunAndCurrentGeneration() throws Exception {
        String approval = ThreadIdentity.noticePromptKind("approval_requested");
        String question = ThreadIdentity.noticePromptKind("question_requested");
        assertEquals("approval", approval);
        assertEquals("clarify", question);
        assertNull(ThreadIdentity.noticePromptKind("run_completed"));
        JSONObject prompt = new JSONObject().put("kind", "approval").put("prompt_id", "a").put("execution_generation", 3);
        JSONObject status = new JSONObject().put("session_id", "thread").put("run_id", "run")
                .put("status", "waiting_for_approval").put("execution_generation", 3)
                .put("pending_controls", new JSONArray().put(prompt));
        AtomicReference<String> stored = new AtomicReference<>();
        ThreadJournal secondPhone = new ThreadJournal("thread", "owner", null, stored::set);
        assertNull(secondPhone.pending());
        assertTrue(ThreadIdentity.hasPendingPrompt("thread", "run", approval, status));
        secondPhone.observe("run");
        assertEquals("run", secondPhone.pending().getString("run_id"));
        assertFalse(secondPhone.pending().has("key"));
        assertFalse(secondPhone.pending().has("body"));
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", question, status));
        try { ThreadIdentity.hasPendingPrompt("other", "run", approval, status); fail("Wrong session accepted"); }
        catch (java.io.IOException expected) {}
        try { ThreadIdentity.hasPendingPrompt("thread", "other", approval, status); fail("Wrong run accepted"); }
        catch (java.io.IOException expected) {}
        prompt.put("execution_generation", 4);
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", approval, status));
        prompt.put("execution_generation", 3).put("kind", "clarify");
        assertTrue(ThreadIdentity.hasPendingPrompt("thread", "run", question, status));
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", approval, status));
        prompt.put("kind", "approval");
        status.put("status", "completed");
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", approval, status));
        status.put("status", "running").put("pending_controls", new JSONArray());
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", approval, status));
    }

    public void testStandaloneApprovalNoticeRequiresCurrentRequestId() throws Exception {
        JSONObject legacy = new JSONObject().put("session_id", "thread").put("run_id", "run")
                .put("status", "waiting_for_approval").put("approval", new JSONObject().put("request_id", "legacy"));
        assertTrue(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", legacy));
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "clarify", legacy));
        legacy.getJSONObject("approval").put("request_id", "");
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", legacy));
    }

    public void testStandaloneApprovalNoticeUsesAuthoritativeArrayBeforeCompatibilityApproval() throws Exception {
        JSONObject nativePrompt = new JSONObject().put("event", "approval.request").put("request_id", "a")
                .put("run_id", "run").put("choices", new JSONArray().put("once").put("deny"));
        JSONArray controls = new JSONArray().put(nativePrompt);
        JSONObject status = new JSONObject().put("session_id", "thread").put("run_id", "run")
                .put("status", "waiting_for_approval").put("pending_controls", controls)
                .put("approval", new JSONObject().put("request_id", "outdated"));
        assertTrue(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "clarify", status));
        nativePrompt.put("run_id", "foreign");
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
        nativePrompt.put("run_id", "run").put("session_id", "foreign");
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
        nativePrompt.remove("session_id");
        nativePrompt.put("event", "approval.settled");
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
        nativePrompt.put("event", "approval.request").put("type", "clarify");
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
        nativePrompt.remove("type");
        nativePrompt.put("request_id", "");
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
        nativePrompt.put("request_id", "a").put("execution_generation", 1);
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
        status.put("pending_controls", new JSONArray());
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
        status.put("pending_controls", JSONObject.NULL);
        assertFalse(ThreadIdentity.hasPendingPrompt("thread", "run", "approval", status));
    }

    public void testObservedRunSurvivesRestartAndRestoresOriginalAdmissionAndDraft() {
        AtomicReference<String> saved = new AtomicReference<>();
        ThreadJournal journal = new ThreadJournal("thread", "scope", null, saved::set);
        journal.stage(ThreadData.body("tip", "message", null), 100, 2, 60, true);
        journal.setRunId("mobile-run");
        assertEquals(1000L, journal.nextReconnectDelayMillis());
        journal.draft("unfinished draft");
        journal.observe("remote-run");
        ThreadJournal resumed = new ThreadJournal("thread", "scope", saved.get(), saved::set);
        assertTrue(resumed.sameOwner());
        assertEquals("remote-run", ThreadData.string(resumed.pending(), "run_id"));
        assertFalse(resumed.pending().has("key"));
        assertFalse(resumed.pending().has("body"));
        assertEquals(1000L, resumed.nextReconnectDelayMillis());
        assertEquals(2000L, resumed.nextReconnectDelayMillis());
        resumed.finish();
        assertEquals("mobile-run", ThreadData.string(resumed.pending(), "run_id"));
        assertEquals("unfinished draft", resumed.draft());
        assertEquals(2000L, resumed.nextReconnectDelayMillis());
        resumed.observe("mobile-run");
        assertTrue(resumed.pending().has("key"));
    }

    public void testUnacknowledgedSendRestoresSameKeyAndExactBodyAcrossRestart() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>();
        ThreadJournal first = new ThreadJournal("abc", "original", null, stored::set);
        first.draft("corrige le test");
        first.flush();
        JSONObject options = new JSONObject().put("model", "m").put("provider", "p")
                .put("allowed_toolsets", new JSONArray()).put("excluded_tools", new JSONArray().put("shell"))
                .put("session_id", "untrusted").put("input", "overwritten");
        JSONObject body = ThreadData.body("abc", first.draft(), options);
        JSONObject sent = first.stage(body, 10_000, 3, 60, true);
        ThreadJournal resumed = new ThreadJournal("abc", "original", stored.get(), stored::set);
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
        resumed.flush();
        ThreadJournal followed = new ThreadJournal("abc", "original", stored.get(), stored::set);
        assertEquals("r7", followed.pending().getString("run_id"));
        assertEquals(4, followed.pending().getInt("seq"));
        assertEquals("bonjour", followed.pending().getString("preview"));
        followed.finish();
        ThreadJournal done = new ThreadJournal("abc", "original", stored.get(), stored::set);
        assertNull(done.pending());
        assertEquals("autre message", done.draft());
    }

    public void testConsecutiveRunsReceiveIndependentReconnectBudgets() {
        AtomicReference<String> saved = new AtomicReference<>();
        ThreadJournal journal = new ThreadJournal("thread", "owner", null, saved::set);
        for (int turn = 1; turn <= 6; turn++) {
            journal.stage(ThreadData.body("thread", "turn " + turn, null), turn, 1, 60, true);
            journal.setRunId("run-" + turn);
            assertFalse(journal.reconnectsExhausted());
            assertEquals(1000L, journal.nextReconnectDelayMillis());
            journal.finish();
        }
    }

    public void testReopenAndReceiptReplayPreserveRetriesUntilExplicitResumeOrFinish() {
        AtomicReference<String> saved = new AtomicReference<>();
        ThreadJournal journal = new ThreadJournal("thread", "owner", null, saved::set);
        journal.stage(ThreadData.body("thread", "first", null), 1, 1, 60, true);
        journal.setRunId("first-run");
        long[] expected = {1000, 2000, 4000, 8000, 16000};
        for (long delay : expected) {
            assertFalse(journal.reconnectsExhausted());
            assertEquals(delay, journal.nextReconnectDelayMillis());
            journal.retire();
            journal = new ThreadJournal("thread", "owner", saved.get(), saved::set);
            journal.setRunId("first-run");
        }
        assertEquals(-1L, journal.nextReconnectDelayMillis());
        journal.retire();
        journal = new ThreadJournal("thread", "owner", saved.get(), saved::set);
        assertTrue(journal.reconnectsExhausted());
        assertEquals(-1L, journal.nextReconnectDelayMillis());
        journal.resetReconnects();
        journal.retire();
        journal = new ThreadJournal("thread", "owner", saved.get(), saved::set);
        assertFalse(journal.reconnectsExhausted());
        assertEquals(1000L, journal.nextReconnectDelayMillis());
        journal.finish();
        journal.stage(ThreadData.body("thread", "next", null), 2, 1, 60, true);
        journal.setRunId("second-run");
        assertEquals(1000L, journal.nextReconnectDelayMillis());
    }

    public void testFailedReconnectCommitPreservesPreviousBudgetAndReceipt() {
        AtomicReference<String> saved = new AtomicReference<>();
        AtomicBoolean writable = new AtomicBoolean(true);
        ThreadJournal journal = new ThreadJournal("thread", "owner", null, value -> {
            if (!writable.get()) throw new IllegalStateException("Storage unavailable");
            saved.set(value);
        });
        journal.stage(ThreadData.body("thread", "first", null), 1, 1, 60, true);
        journal.setRunId("run");
        writable.set(false);
        try { journal.nextReconnectDelayMillis(); fail("Scheduled a reconnect before recording it"); }
        catch (IllegalStateException expected) { /* Retry after recovering storage. */ }
        writable.set(true);
        assertEquals(1000L, journal.nextReconnectDelayMillis());
        writable.set(false);
        try { journal.resetReconnects(); fail("Failed reset appeared to succeed"); }
        catch (IllegalStateException expected) { /* The admitted run retains its budget. */ }
        writable.set(true);
        assertEquals(2000L, journal.nextReconnectDelayMillis());
        assertEquals("run", journal.pending().optString("run_id"));
    }

    public void testFailedOutboxCommitCannotAdmitAndRetainsDraft() {
        ThreadJournal journal = new ThreadJournal("thread", "original", null, ignored -> { throw new IllegalStateException("full"); });
        journal.draft("bonjour");
        try {
            journal.stage(ThreadData.body("thread", journal.draft(), null), 100, 2, 3, true);
            fail("Send without durable record");
        } catch (IllegalStateException expected) {
            assertNull(journal.pending());
            assertEquals("bonjour", journal.draft());
        }
    }

    public void testReplayRequiresContinuingDurableRetentionAndSameBootElapsedTime() {
        assertFalse(ThreadData.canReplay(20_000, 15_000, 4, 3, 10, true));
        assertFalse(ThreadData.canReplay(20_000, 15_000, -1, -1, 10, true));
        assertTrue(ThreadData.canReplay(19_999, 10_000, 3, 3, 10, true));
        assertFalse(ThreadData.canReplay(20_000, 10_000, 3, 3, 10, true));
        assertFalse(ThreadData.canReplay(10_001, 10_000, 3, 3, 10, false));
        assertFalse(ThreadData.canReplay(9_999, 10_000, 3, 3, 10, true));
        assertFalse(ThreadData.canReplay(10_000, 10_000, 3, 3, 0, true));
    }

    public void testBearerRotationAndEndpointSwitchFenceAmbiguousReceipt() {
        String initial = ThreadJournal.scope("account", "https://one/p/a", "bearer1", "s");
        AtomicReference<String> saved = new AtomicReference<>();
        ThreadJournal original = new ThreadJournal("s", initial, null, saved::set);
        original.stage(ThreadData.body("tip-id", "bonjour", null), 10, 1, 60, true);
        for (String other : new String[]{
                ThreadJournal.scope("account", "https://one/p/a", "bearer2", "s"),
                ThreadJournal.scope("account", "https://one/p/b", "bearer1", "s"),
                ThreadJournal.scope("account", "https://two/p/a", "bearer1", "s"),
                ThreadJournal.scope("different", "https://one/p/a", "bearer1", "s")}) {
            assertFalse(new ThreadJournal("s", other, saved.get(), saved::set).sameOwner());
        }
        assertTrue(new ThreadJournal("s", initial, saved.get(), saved::set).sameOwner());
        assertFalse(saved.get().contains("bearer1"));
    }

    public void testAtomicRejectionRetainsConcurrentDraftAndDiscardsStaleAsyncWrites() {
        AtomicReference<String> saved = new AtomicReference<>();
        ThreadJournal journal = new ThreadJournal("root", "owner", null, saved::set);
        journal.draft("first");
        Runnable late = journal.snapshot();
        journal.stage(ThreadData.body("tip", journal.draft(), null), 10, 1, 60, true);
        journal.draft("next");
        journal.reject();
        late.run();
        ThreadJournal restored = new ThreadJournal("root", "owner", saved.get(), saved::set);
        assertNull(restored.pending());
        assertEquals("first\nnext", restored.draft());
        journal.draft("old opened view");
        Runnable lateAfterClose = journal.snapshot();
        journal.retire();
        restored.draft("new opened view");
        restored.flush();
        lateAfterClose.run();
        assertEquals("new opened view", new ThreadJournal("root", "owner", saved.get(), saved::set).draft());
    }

    public void testMalformedStateIsRefusedBeforeAdmission() {
        for (String bad : new String[]{"{", "{\"pending\":null}", "{\"draft\":{}}",
                "{\"pending\":{\"body\":{\"session_id\":\"other\",\"input\":\"x\"},\"key\":\"id\"}}"}) {
            try {
                new ThreadJournal("s", "owner", bad, ignored -> fail("corrupt state overwritten"));
                fail("malformed state accepted");
            } catch (IllegalStateException expected) {}
        }
    }

    public void testCompactionPreservesLocalIdentityAndIndependentFork() throws Exception {
        JSONObject compacted = new JSONObject().put("id", "tip").put("_lineage_root_id", "root");
        JSONObject fork = new JSONObject().put("id", "fork").put("parent_session_id", "root");
        JSONArray listing = new JSONArray().put(compacted).put(fork);
        assertEquals("root", ThreadIdentity.find(listing, "tip").root);
        assertEquals("tip", ThreadIdentity.find(listing, "root").tip);
        assertEquals("fork", ThreadIdentity.find(listing, "fork").root);
        assertNull(ThreadIdentity.find(listing, "other"));
    }

    public void testUnknownAcknowledgementUsesExactGenerationAndReportedErrorsAreRedacted() throws Exception {
        JSONObject status = new JSONObject().put("status", "unknown").put("admission_id", "adm");
        assertNull(ThreadData.unknownReceipt(status));
        assertNull(ThreadData.unknownReceipt(status.put("execution_generation", "42")));
        JSONObject receipt = ThreadData.unknownReceipt(status.put("execution_generation", 42));
        assertEquals(2, receipt.length());
        assertEquals(42, receipt.getInt("execution_generation"));
        assertEquals("adm", receipt.getString("admission_id"));
        assertNull(ThreadData.unknownReceipt(status.put("status", "running")));
        status.put("error", "authorization: Bearer aaaa; api_key=bbbb; http error my-credential");
        String reason = ThreadData.failureDetail(status, "my-credential");
        for (String secret : new String[]{"aaaa", "bbbb", "my-credential"}) assertFalse(reason.contains(secret));
        assertTrue(reason.contains("http error"));
    }

    public void testCanonicalControlsRehydrateAndSettleExactlyOneRequest() throws Exception {
        RunPrompts prompts = new RunPrompts();
        JSONObject request = new JSONObject().put("kind", "approval").put("prompt_id", "a")
                .put("execution_generation", 8);
        JSONObject question = new JSONObject().put("kind", "clarify").put("prompt_id", "b")
                .put("execution_generation", 8);
        prompts.restore(new JSONObject().put("pending_controls", new JSONArray().put(request).put(question)));
        JSONObject consent = RunPrompts.response(prompts.snapshot().get(0), "once");
        assertEquals("a", consent.getString("request_id"));
        assertEquals("once", consent.getString("choice"));
        assertEquals(8, consent.getInt("execution_generation"));
        assertEquals(3, consent.length());
        prompts.settle(new JSONObject().put("prompt_id", "a").put("execution_generation", 8));
        assertEquals("b", RunPrompts.id(prompts.snapshot().get(0)));
        JSONObject response = RunPrompts.response(prompts.snapshot().get(0), "oui");
        assertEquals("oui", response.getString("answer"));
        assertEquals(3, response.length());
        JSONObject classic = new JSONObject().put("request_id", "c");
        prompts.restore(new JSONObject().put("status", "running"));
        assertTrue(prompts.needsSnapshot("approval.request", classic));
        assertNull(prompts.match(classic));
        prompts.restore(new JSONObject().put("status", "waiting_for_approval").put("approval", classic));
        assertEquals(2, RunPrompts.response(prompts.snapshot().get(0), "deny").length());
    }

    public void testPartialStandaloneStatusCannotConfirmAnAmbiguousAnswer() throws Exception {
        JSONObject prompt = new JSONObject().put("request_id", "a");
        JSONObject other = new JSONObject().put("request_id", "b");
        JSONObject status = new JSONObject().put("status", "waiting_for_approval");
        assertEquals(RunPrompts.Presence.UNCONFIRMED, RunPrompts.presence(status, prompt));
        status.put("approval", other);
        assertEquals(RunPrompts.Presence.UNCONFIRMED, RunPrompts.presence(status, prompt));
        status.put("approval", prompt);
        assertEquals(RunPrompts.Presence.PENDING, RunPrompts.presence(status, prompt));
        status.put("pending_controls", new JSONArray().put(other));
        assertEquals(RunPrompts.Presence.ABSENT, RunPrompts.presence(status, prompt));
    }

    public void testStaleGenerationOrSettledDifferentKindCannotRemoveAnActiveQuestion() throws Exception {
        RunPrompts prompts = new RunPrompts();
        JSONObject question = new JSONObject().put("kind", "clarify").put("prompt_id", "same")
                .put("execution_generation", 12);
        prompts.restore(new JSONObject().put("execution_generation", 12)
                .put("pending_controls", new JSONArray().put(question)));
        assertTrue(prompts.needsSnapshot("clarify.request",
                new JSONObject(question.toString()).put("execution_generation", 11)));
        assertFalse(prompts.needsSnapshot("clarify.request",
                new JSONObject(question.toString()).put("execution_generation", "12")));
        assertFalse(prompts.needsSnapshot("clarify.request",
                new JSONObject(question.toString()).put("prompt_id", JSONObject.NULL)));
        assertEquals(1, prompts.snapshot().size());
        prompts.settle(new JSONObject(question.toString()).put("execution_generation", 11));
        prompts.settle(new JSONObject().put("prompt_id", "same").put("execution_generation", 12)
                .put("event", "approval.settled"));
        assertEquals(1, prompts.snapshot().size());
        prompts.settle(new JSONObject().put("prompt_id", "same").put("execution_generation", 12)
                .put("event", "clarify.settled"));
        assertTrue(prompts.snapshot().isEmpty());
        assertNull(RunPrompts.key(new JSONObject().put("request_id", "q").put("kind", "clarify")));
        assertNull(RunPrompts.key(new JSONObject().put("prompt_id", "q")));
        prompts.restore(new JSONObject().put("status", "completed")
                .put("approval", new JSONObject().put("request_id", "stale")));
        assertTrue(prompts.snapshot().isEmpty());
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

    public void testTranscriptKeepsConversationAndToolCallsBetweenBookkeepingRows() throws Exception {
        JSONArray page = new JSONArray("""
                [{"id":1,"role":"user","content":"Que signifie session_meta ?"},
                 {"id":2,"role":"assistant","content":"Un enregistrement de session."},
                 {"id":3,"role":"session_meta","content":null},
                 {"id":4,"role":"system","content":"La conversation a repris."},
                 {"id":5,"role":"assistant","content":null,"tool_calls":[{"function":{"name":"lecture"}}]},
                 {"id":6,"role":"tool","tool_name":"lecture","content":"Résultat"},
                 {"id":7,"role":"user","display_kind":"hidden","content":""}]
                """);
        JSONArray visible = ThreadData.visibleMessages(page);
        int[] positions = {0, 1, 3, 4, 5};
        assertEquals(positions.length, visible.length());
        for (int i = 0; i < positions.length; i++) assertSame(page.get(positions[i]), visible.get(i));
        assertEquals(7, page.length());
    }

    public void testFullBookkeepingPageAndOverlappingOlderPageCountOnlyVisibleInsertions() throws Exception {
        JSONArray page = new JSONArray();
        for (int id = 1; id <= ThreadData.PAGE; id++) {
            page.put(new JSONObject().put("id", id).put("role", "session_meta"));
        }
        JSONArray visible = ThreadData.visibleMessages(page);
        assertEquals(ThreadData.PAGE, page.length());
        assertEquals(0, visible.length());

        JSONArray recent = ThreadData.visibleMessages(new JSONArray("""
                [{"id":41,"role":"assistant","content":"Reçu"},{"id":42,"role":"session_meta"}]
                """));
        page.put(0, new JSONObject().put("id", 1).put("role", "user").put("content", "Bonjour"));
        page.put(ThreadData.PAGE - 1, recent.get(0));
        JSONArray joined = ThreadData.visibleMessages(ThreadData.join(page, recent));
        assertEquals(1, joined.length() - recent.length());
        assertEquals("Bonjour", ThreadData.content(joined.getJSONObject(0)));
        assertSame(recent.get(0), joined.get(1));
        assertEquals(ThreadData.PAGE, page.length());
    }
}
