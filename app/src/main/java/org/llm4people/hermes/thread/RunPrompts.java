package org.llm4people.hermes.thread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** The authority fences canonical prompts by generation. Standalone approvals use exact request ids. */
final class RunPrompts {
    record Key(String id, long generation, boolean question) {}
    private final LinkedHashMap<Key, JSONObject> waiting = new LinkedHashMap<>();
    private List<JSONObject> snapshot = Collections.emptyList();
    private Object generation;

    static String id(JSONObject prompt) {
        if (prompt == null) return "";
        Object value = prompt.opt(prompt.has("prompt_id") ? "prompt_id" : "request_id");
        return value instanceof String ? (String) value : "";
    }

    static Key key(JSONObject prompt) {
        if (id(prompt).trim().isEmpty()) return null;
        boolean canonical = prompt.has("prompt_id");
        Object generation = prompt.opt("execution_generation");
        if (canonical && (!(generation instanceof Integer || generation instanceof Long)
                || ((Number) generation).longValue() < 0)) return null;
        if (!canonical && (generation != null || question(prompt))) return null;
        return new Key(id(prompt), canonical ? ((Number) generation).longValue() : -1, question(prompt));
    }

    void restore(JSONObject status) {
        waiting.clear();
        snapshot = Collections.emptyList();
        generation = status.opt("execution_generation");
        JSONArray entries = status.optJSONArray("pending_controls");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) add(entries.optJSONObject(i));
        } else if ("waiting_for_approval".equals(status.optString("status"))) {
            add(status.optJSONObject("approval"));
        }
    }

    void add(JSONObject prompt) {
        Key key = key(prompt);
        if (key == null) return;
        if (generation != null && (!(generation instanceof Long || generation instanceof Integer)
                || key.generation() != ((Number) generation).longValue())) return;
        if (prompt.has("kind") && !"approval".equals(prompt.opt("kind")) && !"clarify".equals(prompt.opt("kind"))) return;
        waiting.put(key, prompt);
        snapshot = null;
    }

    void settle(JSONObject event) {
        Key key = key(event);
        if (key != null && waiting.remove(key) != null) snapshot = null;
    }

    List<JSONObject> snapshot() {
        if (snapshot == null) snapshot = Collections.unmodifiableList(new ArrayList<>(waiting.values()));
        return snapshot;
    }

    static boolean question(JSONObject prompt) {
        return "clarify".equals(ThreadData.string(prompt, "kind"))
                || ThreadData.string(prompt, "event").startsWith("clarify.");
    }

    static boolean permits(JSONObject prompt, String choice) {
        JSONArray choices = prompt.optJSONArray("choices");
        if (choices == null) return !prompt.has("choices") && !prompt.has("prompt_id");
        for (int i = 0; i < choices.length(); i++) if (choice.equals(choices.opt(i))) return true;
        return false;
    }

    static JSONObject response(JSONObject prompt, String value) {
        Key key = key(prompt);
        if (key == null) throw new IllegalArgumentException("Missing prompt identity");
        JSONObject body = ThreadData.put(new JSONObject(), "request_id", key.id());
        if (key.generation() >= 0) ThreadData.put(body, "execution_generation", key.generation());
        return ThreadData.put(body, key.question() ? "answer" : "choice", value);
    }
}
