package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;

/** The canonical session owner fences responses by generation; standalone runs use request ids. */
final class RunPrompts {
    private final LinkedHashMap<String, JSONObject> waiting = new LinkedHashMap<>();

    static String id(JSONObject prompt) {
        String canonical = ThreadData.string(prompt, "prompt_id");
        return canonical.isEmpty() ? ThreadData.string(prompt, "request_id") : canonical;
    }

    void restore(JSONObject status) {
        waiting.clear();
        JSONArray entries = status.optJSONArray("pending_controls");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) add(entries.optJSONObject(i));
        } else {
            add(status.optJSONObject("approval"));
        }
    }

    void add(JSONObject prompt) {
        String id = id(prompt);
        if (!id.isEmpty()) waiting.put(id, prompt);
    }

    void settle(JSONObject event) { waiting.remove(id(event)); }

    JSONObject next() { return waiting.isEmpty() ? null : waiting.values().iterator().next(); }

    static boolean question(JSONObject prompt) {
        return "clarify".equals(ThreadData.string(prompt, "kind"))
                || "clarify.request".equals(ThreadData.string(prompt, "event"));
    }

    static String activity(JSONObject prompt) {
        return question(prompt) ? "Question de Hermes" : "Approbation requise";
    }

    static JSONObject response(JSONObject prompt, String value) {
        if (id(prompt).isEmpty()) throw new IllegalArgumentException("Missing prompt id");
        JSONObject body = ThreadData.put(new JSONObject(), "request_id", id(prompt));
        if (!prompt.isNull("execution_generation"))
            ThreadData.put(body, "execution_generation", prompt.optLong("execution_generation"));
        return ThreadData.put(body, question(prompt) ? "answer" : "choice", value);
    }
}
