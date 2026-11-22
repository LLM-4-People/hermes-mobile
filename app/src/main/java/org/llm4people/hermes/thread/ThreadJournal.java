package org.llm4people.hermes.thread;

import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;

import java.util.UUID;
import java.util.function.Consumer;

/** One encrypted commit moves a draft into the outbox before its first network send. */
final class ThreadJournal {
    private final Consumer<String> commit;
    private final JSONObject state;
    private boolean dirty;

    ThreadJournal(Account account, String id) {
        this(account.read("thread:" + id), value -> account.write("thread:" + id, value));
    }

    ThreadJournal(String encoded, Consumer<String> commit) {
        this.commit = commit;
        state = ThreadData.object(encoded);
    }

    JSONObject pending() { return state.optJSONObject("pending"); }

    String draft() { return ThreadData.string(state, "draft"); }

    void draft(String text) {
        if (!text.equals(draft())) {
            ThreadData.put(state, "draft", text);
            dirty = true;
        }
    }

    JSONObject stage(JSONObject body, long elapsed, int boot, long ttl, boolean durable) {
        if (pending() != null) throw new IllegalStateException("Pending run");
        JSONObject run = new JSONObject();
        ThreadData.put(run, "key", UUID.randomUUID().toString());
        ThreadData.put(run, "body", body);
        ThreadData.put(run, "elapsed", elapsed);
        ThreadData.put(run, "boot", boot);
        ThreadData.put(run, "ttl", ttl);
        ThreadData.put(run, "durable", durable);
        String oldDraft = draft();
        ThreadData.put(state, "pending", run);
        draft("");
        try {
            save();
        } catch (RuntimeException e) {
            state.remove("pending");
            draft(oldDraft);
            throw e;
        }
        return run;
    }

    void setRunId(String runId) {
        ThreadData.put(pending(), "run_id", runId);
        save();
    }

    void checkpoint(long seq, String text) {
        ThreadData.put(pending(), "seq", seq);
        ThreadData.put(pending(), "preview", text);
        save();
    }

    void finish() {
        state.remove("pending");
        save();
    }

    void flush() { if (dirty) save(); }

    private void save() {
        commit.accept(state.toString());
        dirty = false;
    }
}
