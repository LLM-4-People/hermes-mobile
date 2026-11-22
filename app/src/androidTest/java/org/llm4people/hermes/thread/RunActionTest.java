package org.llm4people.hermes.thread;

import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class RunActionTest extends TestCase {
    private final ThreadJournal journal = new ThreadJournal("chat", "owner", null, ignored -> {});
    private int page = 1;
    private long observation = 1;

    private void admit(String id) {
        journal.stage(ThreadData.body("chat", "message", null), 10, 1, 60, true);
        journal.setRunId(id);
    }

    private RunAction action() { return RunAction.capture(journal, () -> page, () -> observation); }

    public void testBothDelayedResponseAndAlreadyDisplayedRetryBelongToOriginalReceipt() {
        admit("A");
        RunAction stop = action();
        List<String> posts = new ArrayList<>();
        AtomicReference<Runnable> retryButton = new AtomicReference<>();
        Consumer<String> failed = stop.guard(error -> retryButton.set(stop.guard(() -> posts.add(stop.path() + "/stop"))));
        failed.accept("503");
        Runnable alreadyVisible = retryButton.get();
        alreadyVisible.run();
        assertEquals(java.util.Collections.singletonList("/v1/runs/A/stop"), posts);
        retryButton.set(null);
        journal.finish();
        admit("B");
        failed.accept("503");
        assertNull(retryButton.get());
        alreadyVisible.run();
        assertEquals(1, posts.size());
        // Even a server or fixture that reuses an id cannot rebind the old local action.
        journal.finish();
        admit("A");
        assertFalse(stop.current());
    }

    public void testCallbackExpiresWhenTheViewOrRunObservationChangesBeforeTranscriptReconciliation() {
        admit("A");
        RunAction running = action();
        observation++;
        assertFalse(running.current());
        RunAction sameReceipt = action();
        page++;
        assertFalse(sameReceipt.current());
        assertEquals("A", journal.pending().optString("run_id"));
    }

    public void testRetryRequiresCurrentRemoteRunAndActiveStatus() throws Exception {
        admit("A");
        RunAction action = action();
        for (String state : new String[]{"running", "queued", "waiting_for_approval", "waiting_for_input"}) {
            JSONObject row = new JSONObject().put("run_id", "A").put("status", state);
            assertTrue(action.stoppable(row));
            row.put("run_id", "B");
            assertFalse(action.stoppable(row));
        }
        for (String state : new String[]{"unknown", "completed", "cancelled", "stopping", "failed", ""})
            assertFalse(action.stoppable(new JSONObject().put("run_id", "A").put("status", state)));
    }

    public void testDecisionRetryNeedsExactPromptIdGenerationAndKind() throws Exception {
        JSONObject expected = new JSONObject().put("prompt_id", "q").put("execution_generation", 7).put("kind", "clarify");
        JSONObject actual = new JSONObject(expected.toString());
        RunPrompts status = new RunPrompts();
        status.restore(new JSONObject().put("pending_controls", new JSONArray().put(actual)));
        assertSame(actual, status.match(expected));
        actual.put("kind", "approval");
        assertNull(status.match(expected));
        actual.put("kind", "clarify").put("execution_generation", 8);
        assertNull(status.match(expected));
        actual.put("execution_generation", 7).put("prompt_id", "other");
        assertNull(status.match(expected));
    }
}
