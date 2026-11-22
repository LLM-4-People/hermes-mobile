package org.llm4people.hermes.thread;

import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One run receipt owns its controls. Only a fresh status check can reopen an unconfirmed response. */
final class RunDecisions {
    private final ThreadWorkspace page;
    private final ThreadJournal journal;
    private final JSONObject receipt;
    private final int token;
    private final String run;
    private final String path;
    private List<JSONObject> prompts = Collections.emptyList();
    private final Map<RunPrompts.Key, PromptCard.State> attempts = new HashMap<>();
    private final Set<RunPrompts.Key> settled = new HashSet<>();
    private boolean connected;

    RunDecisions(ThreadWorkspace page, JSONObject receipt) {
        this.page = page;
        this.journal = page.journal;
        this.receipt = receipt;
        token = page.token();
        run = ThreadData.string(receipt, "run_id");
        path = "/v1/runs/" + HermesApi.segment(run);
    }

    boolean current() {
        return token == page.token() && journal == page.journal && receipt == journal.pending()
                && run.equals(ThreadData.string(receipt, "run_id")) && journal.sameOwner();
    }

    void present(List<JSONObject> fresh) {
        if (connected && fresh == prompts) return;
        prompts = fresh;
        connected = true;
        paint();
    }

    void disconnected() {
        connected = false;
        paint();
    }

    void clear() {
        connected = false;
        prompts = Collections.emptyList();
        page.screen.promptRows.clear();
    }

    private void paint() {
        if (current()) page.screen.promptRows.render(prompts, this);
    }

    PromptCard.State state(RunPrompts.Key key) {
        if (settled.contains(key)) return null;
        if (!connected) return PromptCard.State.OFFLINE;
        return attempts.getOrDefault(key, PromptCard.State.READY);
    }

    private boolean available(RunPrompts.Key key, PromptCard.State state) {
        if (!current() || !connected || state(key) != state) return false;
        for (JSONObject prompt : prompts) if (key.equals(RunPrompts.key(prompt))) return true;
        return false;
    }

    void respond(JSONObject prompt, String value) {
        RunPrompts.Key key = RunPrompts.key(prompt);
        if (!available(key, PromptCard.State.READY)) return;
        if (!key.question() && (!("once".equals(value) || "deny".equals(value))
                || !RunPrompts.permits(prompt, value))) return;
        attempts.put(key, PromptCard.State.SENDING);
        paint();
        JSONObject body = RunPrompts.response(prompt, value);
        String endpoint = path + (key.question() ? "/clarify" : "/approval");
        page.request(() -> page.api.post(endpoint, body), reply -> {
            boolean confirmed = run.equals(reply.opt("run_id")) && key.id().equals(RunPrompts.id(reply))
                    && ("resolved".equals(reply.opt("status")) || "already_resolved".equals(reply.opt("status"))
                    || reply.optInt("resolved") > 0);
            if (confirmed) settled.add(key);
            else attempts.put(key, PromptCard.State.VERIFY);
            paint();
        }, error -> {
            attempts.put(key, PromptCard.State.VERIFY);
            paint();
        });
    }

    void verify(JSONObject prompt) {
        RunPrompts.Key key = RunPrompts.key(prompt);
        if (!available(key, PromptCard.State.VERIFY)) return;
        attempts.put(key, PromptCard.State.CHECKING);
        paint();
        page.request(() -> page.api.get(path), status -> {
            if (run.equals(status.opt("run_id")) && (ThreadData.terminal(status.optString("status"))
                    || status.optJSONArray("pending_controls") != null || key.generation() < 0)) {
                RunPrompts fresh = new RunPrompts();
                fresh.restore(status);
                boolean waiting = false;
                for (JSONObject row : fresh.snapshot()) if (key.equals(RunPrompts.key(row))) waiting = true;
                if (!waiting) settled.add(key);
                attempts.remove(key);
            } else {
                attempts.put(key, PromptCard.State.VERIFY);
            }
            paint();
        }, error -> {
            attempts.put(key, PromptCard.State.VERIFY);
            paint();
        });
    }
}
