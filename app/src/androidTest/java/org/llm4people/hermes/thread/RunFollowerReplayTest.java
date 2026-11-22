package org.llm4people.hermes.thread;

import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;

import java.util.ArrayList;
import java.util.List;

public class RunFollowerReplayTest extends TestCase {
    public void testNewSnapshotConfirmsLivePromptsAndRejectsReplayedSettledRequests() throws Exception {
        JSONObject previous = prompt("old");
        JSONObject oldApproval = prompt("old-approval").put("kind", "approval");
        JSONObject current = prompt("current");
        JSONObject fresh = prompt("new");
        String events = frame(previous, 3, "clarify.request") + frame(previous, 4, "clarify.settled")
                + frame(new JSONObject().put("delta", "texte"), 5, "message.delta")
                + frame(oldApproval, 6, "approval.request") + frame(oldApproval, 7, "approval.settled")
                + frame(fresh, 8, "clarify.request") + frame(new JSONObject(), 9, "run.completed");
        List<RunFollower.Frame> frames = new ArrayList<>();
        boolean[] finished = {false};
        try (Exchange gateway = new Exchange("200 OK", status("running", current),
                new Exchange.Reply("Content-Type: text/event-stream\r\n", events),
                status("running", current), status("running", current),
                status("waiting_for_input", current, fresh), status("completed"))) {
            RunFollower follower = new RunFollower(new HermesApi(gateway.endpoint(), "test"),
                    "run", new JSONObject().put("seq", 2), -1, new RunFollower.Listener() {
                public void update(RunFollower.Frame frame) {
                    for (JSONObject prompt : frame.prompts)
                        assertFalse("Replayed settled control became actionable", RunPrompts.id(prompt).startsWith("old"));
                    frames.add(frame);
                }
                public void complete(JSONObject status, long first, long elapsed) { finished[0] = true; }
                public void disconnected() { fail("Unexpected disconnect"); }
                public void unknown(JSONObject status) { fail("Unexpected run state"); }
            });
            follower.follow();
            assertTrue(gateway.observed().contains("Last-Event-ID: 2"));
        }
        assertTrue(finished[0]);
        assertFalse(frames.isEmpty());
        assertEquals("current", RunPrompts.id(frames.get(0).prompts.get(0)));
        RunFollower.Frame last = frames.get(frames.size() - 1);
        assertEquals("texte", last.text);
        assertEquals(2, last.prompts.size());
        assertEquals("new", RunPrompts.id(last.prompts.get(1)));
    }

    public void testStandaloneSnapshotAuthorizesEveryCurrentWaiterAndNeverReopensAnOldApproval() throws Exception {
        JSONObject old = new JSONObject().put("request_id", "old");
        JSONObject first = new JSONObject().put("request_id", "first");
        JSONObject second = new JSONObject().put("request_id", "second");
        String events = frame(old, 1, "approval.request") + frame(old, 2, "approval.responded")
                + frame(first, 3, "approval.request") + frame(second, 4, "approval.request")
                + frame(first, 5, "approval.responded")
                + frame(new JSONObject().put("resolved", 1), 6, "approval.responded")
                + frame(new JSONObject(), 7, "run.completed");
        Exchange.Reply noWaiters = standalone("running", new JSONArray(), null);
        try (Exchange gateway = new Exchange("200 OK", noWaiters,
                new Exchange.Reply("Content-Type: text/event-stream\r\n", events), noWaiters,
                standalone("waiting_for_approval", null, first),
                standalone("running", new JSONArray().put(first).put(second), second), noWaiters,
                standalone("completed", new JSONArray(), null))) {
            List<List<String>> snapshots = new ArrayList<>();
            new RunFollower(new HermesApi(gateway.endpoint(), "test"), "run", new JSONObject(), -1,
                    new RunFollower.Listener() {
                        public void update(RunFollower.Frame frame) {
                            List<String> ids = new ArrayList<>();
                            for (JSONObject p : frame.prompts) ids.add(RunPrompts.id(p));
                            assertFalse("Settled standalone approval was shown", ids.contains("old"));
                            snapshots.add(ids);
                        }
                        public void complete(JSONObject status, long firstText, long elapsed) {}
                        public void disconnected() { fail("Unexpected disconnect"); }
                        public void unknown(JSONObject status) { fail("Unexpected run state"); }
                    }).follow();
            assertTrue(snapshots.contains(java.util.Collections.singletonList("first")));
            assertTrue(snapshots.contains(java.util.Arrays.asList("first", "second")));
            assertEquals(java.util.Collections.singletonList("second"), snapshots.get(snapshots.size() - 2));
            assertTrue(snapshots.get(snapshots.size() - 1).isEmpty());
            gateway.observed();
        }
    }

    public void testSilentStandaloneRetirementRefreshesOnHeartbeatAndNewWork() throws Exception {
        JSONObject first = new JSONObject().put("request_id", "expired");
        JSONObject second = new JSONObject().put("request_id", "withdrawn");
        Exchange.Reply both = standalone("waiting_for_approval", new JSONArray().put(first).put(second), first);
        Exchange.Reply one = standalone("waiting_for_approval", new JSONArray().put(second), second);
        Exchange.Reply none = standalone("running", new JSONArray(), null);
        String work = frame(new JSONObject().put("text", "Suite"), 1, "message.interim");
        String events = ": keepalive\n\n" + work + ": keepalive\n\n" + frame(new JSONObject(), 2, "run.completed");
        try (Exchange gateway = new Exchange("200 OK", both,
                new Exchange.Reply("Content-Type: text/event-stream\r\n", events), one, none, status("completed"))) {
            long[] time = {0};
            List<Integer> counts = new ArrayList<>();
            new RunFollower(new HermesApi(gateway.endpoint(), "test"), "run", new JSONObject(), -1,
                    new RunFollower.Listener() {
                        public void update(RunFollower.Frame frame) {
                            counts.add(frame.prompts.size());
                            time[0] += counts.size() == 2 ? 2_000 : 15_000;
                        }
                        public void complete(JSONObject status, long firstText, long elapsed) {}
                        public void disconnected() { fail("Unexpected disconnect"); }
                        public void unknown(JSONObject status) { fail("Unexpected run state"); }
                    }, () -> time[0]).follow();
            assertEquals(java.util.Arrays.asList(2, 1, 0), counts);
            gateway.observed();
        }
    }

    private static Exchange.Reply standalone(String state, JSONArray controls, JSONObject approval) throws Exception {
        JSONObject body = new JSONObject().put("run_id", "run").put("status", state);
        if (controls != null) body.put("pending_controls", controls);
        if (approval != null) body.put("approval", approval);
        return new Exchange.Reply("Content-Type: application/json\r\n", body.toString());
    }

    private static JSONObject prompt(String id) throws Exception {
        return new JSONObject().put("prompt_id", id).put("kind", "clarify")
                .put("question", id).put("execution_generation", 3);
    }

    private static Exchange.Reply status(String state, JSONObject... controls) throws Exception {
        JSONArray pending = new JSONArray();
        for (JSONObject prompt : controls) pending.put(prompt);
        JSONObject body = new JSONObject().put("run_id", "run").put("status", state)
                .put("execution_generation", 3).put("pending_controls", pending);
        return new Exchange.Reply("Content-Type: application/json\r\n", body.toString());
    }

    private static String frame(JSONObject fields, int seq, String event) throws Exception {
        JSONObject payload = new JSONObject(fields.toString()).put("run_id", "run").put("seq", seq).put("event", event);
        return "id: " + seq + "\ndata: " + payload + "\n\n";
    }
}
