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
    private record Tool(String name, String arguments, int owner) {}
    private PromptHistory() {}

    static JSONArray display(JSONArray source) {
        Map<Call, Tool> tools = new HashMap<>();
        Map<Integer, JSONArray> receipts = new HashMap<>();
        Map<Integer, Set<String>> answered = new HashMap<>();
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
                    JSONObject function = item == null ? null : item.optJSONObject("function");
                    if (!id.isEmpty() && function != null) tools.put(new Call(session, id),
                            new Tool(ThreadData.string(function, "name"), ThreadData.string(function, "arguments"), i));
                }
            } else if ("tool".equals(role)) {
                Call call = new Call(session, ThreadData.string(row, "tool_call_id"));
                String name = ThreadData.string(row, "tool_name");
                Tool tool = tools.remove(call);
                if (tool != null && !name.isEmpty() && !tool.name().equals(name)) continue;
                if (name.isEmpty() && tool != null) name = tool.name();
                JSONArray turns = switch (name) {
                    case "clarify" -> turns(ThreadData.content(row));
                    case "terminal" -> approval(ThreadData.content(row), tool);
                    default -> null;
                };
                if (turns != null) {
                    receipts.put(i, turns);
                    if (tool != null) answered.computeIfAbsent(tool.owner(), ignored -> new HashSet<>()).add(call.id());
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
            } else if (row != null && !represented(row, answered.get(i))) {
                display.put(row);
            }
        }
        return display;
    }

    private static boolean represented(JSONObject row, Set<String> answered) {
        JSONArray calls = row.optJSONArray("tool_calls");
        if (answered == null || !"assistant".equals(row.opt("role")) || calls == null || calls.length() == 0
                || !ThreadData.content(row).trim().isEmpty()) return false;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null || !answered.contains(ThreadData.string(call, "id"))) return false;
        }
        return true;
    }

    /** Only explicit gateway provenance identifies user consent. Exit codes and stdout do not. */
    private static JSONArray approval(String encoded, Tool tool) {
        if (!encoded.contains("\"user_summary\"") && !encoded.contains("\"approval\"")) return null;
        try {
            JSONObject result = new JSONObject(encoded);
            String note = ThreadData.string(result, "approval");
            boolean allowed = note.startsWith("Command required approval (")
                    && note.endsWith(") and was approved by the user.");
            boolean denied = "blocked".equals(result.opt("status"))
                    && "You denied this command — it did not run.".equals(result.opt("user_summary"));
            if (allowed == denied) return null;
            String summary = allowed ? "Action autorisée." : "Action refusée. La commande n’a pas été exécutée.";
            String output = ThreadData.string(result, "output");
            if (!output.isEmpty()) summary += "\n\n" + output;
            String details = encoded;
            if (tool != null && !tool.arguments().isEmpty()) details = tool.arguments() + "\n\n" + details;
            JSONObject row = new JSONObject().put("role", "approval_result").put("content", summary)
                    .put("receipt_details", details);
            return new JSONArray().put(row);
        } catch (JSONException invalidReceipt) {
            return null;
        }
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
