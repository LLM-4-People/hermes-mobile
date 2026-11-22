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
