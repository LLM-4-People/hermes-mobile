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
    enum Presence { PENDING, ABSENT, UNCONFIRMED }
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
        long generation = ThreadData.generation(prompt);
        if (canonical && generation < 0) return null;
        if (!canonical && (prompt.has("execution_generation") || question(prompt))) return null;
        return new Key(id(prompt), generation, question(prompt));
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

    /** Only status can confirm a replayed request or identify the waiters left by a FIFO/bulk reply. */
    boolean needsSnapshot(String type, JSONObject event) {
        switch (type) {
            case "approval.request":
            case "clarify.request":
                Key identity = key(event);
                return identity != null && !waiting.containsKey(identity);
            case "approval.responded":
                return !waiting.isEmpty() && id(event).isEmpty();
            default:
                return false;
        }
    }

    private void add(JSONObject prompt) {
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

    static Presence presence(JSONObject status, JSONObject prompt) {
        RunPrompts fresh = new RunPrompts();
        fresh.restore(status);
        if (fresh.match(prompt) != null) return Presence.PENDING;
        return status.optJSONArray("pending_controls") != null ? Presence.ABSENT : Presence.UNCONFIRMED;
    }

    JSONObject match(JSONObject expected) {
        Key identity = key(expected);
        JSONObject found = waiting.get(identity);
        return identity != null && identity.equals(key(found)) ? found : null;
    }

    boolean hasStandaloneApproval() {
        for (Key key : waiting.keySet()) if (key.generation() < 0) return true;
        return false;
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
