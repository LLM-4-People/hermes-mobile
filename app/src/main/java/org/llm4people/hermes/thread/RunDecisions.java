package org.llm4people.hermes.thread;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One run receipt owns its controls. Only a fresh status check can reopen an unconfirmed response. */
final class RunDecisions {
    private final ThreadWorkspace page;
    private final RunAction receipt;
    private RunAction observation;
    private List<JSONObject> prompts = Collections.emptyList();
    private final Map<RunPrompts.Key, PromptCard.State> attempts = new HashMap<>();
    private final Set<RunPrompts.Key> settled = new HashSet<>();
    private boolean connected;

    RunDecisions(ThreadWorkspace page) {
        this.page = page;
        ThreadJournal journal = page.journal;
        receipt = RunAction.capture(journal, page::token).when(() -> journal == page.journal && journal.sameOwner());
    }

    boolean current() { return receipt.current(); }

    void observe(RunAction owner) {
        disconnected();
        observation = owner;
    }

    void present(List<JSONObject> fresh) {
        if (connected && fresh == prompts) return;
        prompts = fresh;
        connected = true;
        paint();
    }

    void disconnected() {
        observation = null;
        connected = false;
        attempts.replaceAll((key, state) -> PromptCard.State.VERIFY);
        paint();
    }

    void clear() {
        observation = null;
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
        if (!current() || observation == null || !observation.current() || !connected || state(key) != state) return false;
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
        RunAction action = observation.when(() -> available(key, PromptCard.State.SENDING));
        String endpoint = action.path() + (key.question() ? "/clarify" : "/approval");
        page.request(() -> page.api.post(endpoint, body), action.guard(reply -> {
            boolean confirmed = action.run.equals(reply.opt("run_id")) && key.id().equals(RunPrompts.id(reply))
                    && ("resolved".equals(reply.opt("status")) || "already_resolved".equals(reply.opt("status"))
                    || reply.optInt("resolved") > 0);
            if (confirmed) settled.add(key);
            else attempts.put(key, PromptCard.State.VERIFY);
            paint();
        }), action.guard(error -> {
            attempts.put(key, PromptCard.State.VERIFY);
            paint();
        }));
    }

    void verify(JSONObject prompt) {
        RunPrompts.Key key = RunPrompts.key(prompt);
        if (!available(key, PromptCard.State.VERIFY)) return;
        attempts.put(key, PromptCard.State.CHECKING);
        paint();
        RunAction action = observation.when(() -> available(key, PromptCard.State.CHECKING));
        page.request(() -> page.api.get(action.path()), action.guard(status -> {
            String state = status.optString("status");
            if (action.run.equals(status.opt("run_id")) && (ThreadData.terminal(state) || "unknown".equals(state))) {
                settled.add(key);
            } else if (action.stoppable(status)) {
                switch (RunPrompts.presence(status, prompt)) {
                    case PENDING -> attempts.remove(key);
                    case ABSENT -> settled.add(key);
                    case UNCONFIRMED -> attempts.put(key, PromptCard.State.VERIFY);
                }
            } else {
                attempts.put(key, PromptCard.State.VERIFY);
            }
            paint();
        }), action.guard(error -> {
            attempts.put(key, PromptCard.State.VERIFY);
            paint();
        }));
    }
}
