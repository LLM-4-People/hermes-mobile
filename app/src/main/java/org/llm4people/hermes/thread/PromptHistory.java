package org.llm4people.hermes.thread;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Project persisted clarify tool receipts onto ordinary conversation turns, only for display. */
final class PromptHistory {
    private record Call(String session, String id) {}
    private record Tool(String name, int owner) {}
    private PromptHistory() {}

    static JSONArray display(JSONArray source) {
        return display(null, source);
    }

    static JSONArray display(Context context, JSONArray source) {
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
                            new Tool(ThreadData.string(function, "name"), i));
                }
            } else if ("tool".equals(role)) {
                Call call = new Call(session, ThreadData.string(row, "tool_call_id"));
                String name = ThreadData.string(row, "tool_name");
                Tool tool = tools.remove(call);
                if (tool != null && !name.isEmpty() && !tool.name().equals(name)) continue;
                if (name.isEmpty() && tool != null) name = tool.name();
                JSONArray turns = switch (name) {
                    case "clarify" -> turns(context, ThreadData.content(context, row));
                    case "terminal" -> approval(context, ThreadData.content(context, row));
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
    private static JSONArray approval(Context context, String encoded) {
        if (!encoded.contains("\"user_summary\"") && !encoded.contains("\"approval\"")) return null;
        try {
            JSONObject result = new JSONObject(encoded);
            String note = ThreadData.string(result, "approval");
            boolean allowed = note.startsWith("Command required approval (")
                    && note.endsWith(") and was approved by the user.");
            boolean denied = "blocked".equals(result.opt("status"))
                    && "You denied this command — it did not run.".equals(result.opt("user_summary"));
            if (allowed == denied) return null;
            String summary = ThreadStrings.get(context,
                    allowed ? R.string.prompt_history_approved : R.string.prompt_history_denied);
            String output = ThreadData.string(result, "output");
            if (!output.isEmpty()) summary += "\n\n" + output;
            JSONObject row = new JSONObject().put("role", "approval_result").put("content", summary)
                    .put("receipt_details", encoded);
            return new JSONArray().put(row);
        } catch (JSONException invalidReceipt) {
            return null;
        }
    }

    private static JSONArray turns(Context context, String encoded) {
        try {
            JSONObject result = new JSONObject(encoded);
            JSONArray rows = result.optJSONArray("responses");
            if (rows == null || rows.length() == 0) return null;
            JSONArray turns = new JSONArray();
            boolean unanswered = false;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!(row.opt("question") instanceof String question) || question.trim().isEmpty()) return null;
                String status = ThreadData.string(row, "status");
                String answer;
                String role = "user";
                if ("answered".equals(status)) {
                    answer = answerText(row.opt("user_response"));
                    if (answer == null || answer.trim().isEmpty()) return null;
                } else if ("skipped".equals(status) && row.isNull("user_response")) {
                    role = "prompt_status";
                    answer = ThreadStrings.get(context, R.string.prompt_history_skipped);
                } else if ("unanswered".equals(status) && row.isNull("user_response")) {
                    // Sequential delivery can stop before reaching later entries in the same batch.
                    unanswered = true;
                    continue;
                } else return null;
                turns.put(new JSONObject().put("role", "assistant").put("content", question));
                turns.put(new JSONObject().put("role", role).put("content", answer));
            }
            String summary = switch (result.optString("outcome")) {
                case "submitted" -> unanswered ? ThreadStrings.get(context, R.string.prompt_history_missing) : "";
                case "undelivered" -> ThreadStrings.get(context, R.string.prompt_history_undelivered);
                case "timed_out" -> ThreadStrings.get(context, R.string.prompt_history_timed_out);
                case "cancelled" -> ThreadStrings.get(context, R.string.prompt_history_cancelled);
                default -> null;
            };
            if (summary == null) return null;
            String notice = ThreadData.string(result, "notice").trim();
            if (!notice.isEmpty()) summary = summary.isEmpty() ? notice : summary + "\n" + notice;
            if (!summary.isEmpty()) turns.put(new JSONObject().put("role", "prompt_status").put("content", summary));
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
