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

    static boolean same(JSONObject expected, JSONObject current) {
        return expected != null && current != null && !id(expected).isEmpty() && id(expected).equals(id(current))
                && question(expected) == question(current)
                && (expected.isNull("execution_generation") && current.isNull("execution_generation")
                    || ThreadData.generation(expected) >= 0 && ThreadData.generation(expected) == ThreadData.generation(current));
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

    JSONObject match(JSONObject expected) {
        JSONObject current = waiting.get(id(expected));
        return same(expected, current) ? current : null;
    }

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
