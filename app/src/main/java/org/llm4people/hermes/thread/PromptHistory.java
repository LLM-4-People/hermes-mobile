package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Project persisted clarify tool receipts onto ordinary conversation turns, only for display. */
final class PromptHistory {
    private record Call(String session, String id) {}
    private PromptHistory() {}

    static JSONArray display(JSONArray source) {
        Set<Call> questions = new HashSet<>();
        Map<Integer, JSONArray> receipts = new HashMap<>();
        Set<Call> answered = new HashSet<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            if (row == null) continue;
            String role = ThreadData.string(row, "role");
            String session = ThreadData.string(row, "session_id");
            if ("assistant".equals(role)) {
                JSONArray calls = row.optJSONArray("tool_calls");
                for (int j = 0; calls != null && j < calls.length(); j++) {
                    JSONObject item = calls.optJSONObject(j);
                    String id = ThreadData.string(item, "id");
                    if (!id.isEmpty() && "clarify".equals(ThreadData.string(item.optJSONObject("function"), "name")))
                        questions.add(new Call(session, id));
                }
            } else if ("tool".equals(role)) {
                Call call = new Call(session, ThreadData.string(row, "tool_call_id"));
                String name = ThreadData.string(row, "tool_name");
                if (!"clarify".equals(name) && !(name.isEmpty() && questions.contains(call))) continue;
                JSONArray turns = turns(ThreadData.content(row));
                if (turns != null) {
                    receipts.put(i, turns);
                    if (!call.id().isEmpty()) answered.add(call);
                }
            }
        }
        if (receipts.isEmpty()) return source;
        JSONArray display = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            JSONArray turns = receipts.get(i);
            if (turns != null) {
                for (int j = 0; j < turns.length(); j++) display.put(turns.optJSONObject(j));
            } else if (row != null && !represented(row, answered)) {
                display.put(row);
            }
        }
        return display;
    }

    private static boolean represented(JSONObject row, Set<Call> answered) {
        JSONArray calls = row.optJSONArray("tool_calls");
        if (!"assistant".equals(row.opt("role")) || calls == null || calls.length() == 0
                || !ThreadData.content(row).trim().isEmpty()) return false;
        String session = ThreadData.string(row, "session_id");
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null || !"clarify".equals(ThreadData.string(call.optJSONObject("function"), "name"))
                    || !answered.contains(new Call(session, ThreadData.string(call, "id")))) return false;
        }
        return true;
    }

    private static JSONArray turns(String encoded) {
        try {
            JSONArray rows = new JSONObject(encoded).optJSONArray("responses");
            if (rows == null || rows.length() == 0) return null;
            JSONArray turns = new JSONArray();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!(row.opt("question") instanceof String question) || question.trim().isEmpty()) return null;
                String status = ThreadData.string(row, "status");
                String answer;
                String role = "user";
                if ("answered".equals(status)) {
                    answer = answerText(row.opt("user_response"));
                    if (answer == null || answer.trim().isEmpty()) return null;
                } else if (row.isNull("user_response") && ("skipped".equals(status) || "unanswered".equals(status))) {
                    role = "prompt_status";
                    answer = "skipped".equals(status) ? "Question passée." : "Aucune réponse reçue.";
                } else return null;
                turns.put(new JSONObject().put("role", "assistant").put("content", question));
                turns.put(new JSONObject().put("role", role).put("content", answer));
            }
            return turns;
        } catch (JSONException invalidReceipt) {
            return null;
        }
    }

    private static String answerText(Object value) {
        if (value instanceof String text) return text;
        if (!(value instanceof JSONArray choices) || choices.length() == 0) return null;
        StringBuilder selected = new StringBuilder();
        for (int i = 0; i < choices.length(); i++) {
            if (!(choices.opt(i) instanceof String choice) || choice.trim().isEmpty()) return null;
            if (i > 0) selected.append('\n');
            selected.append("• ").append(choice);
        }
        return selected.toString();
    }
}
