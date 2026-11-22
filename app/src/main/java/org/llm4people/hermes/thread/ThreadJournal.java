package org.llm4people.hermes.thread;

import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

/** One encrypted commit moves a draft into or out of the outbox. Mutable state belongs to the UI. */
final class ThreadJournal {
    private final String session;
    private final String owner;
    private final Consumer<String> write;
    private final JSONObject state;
    private long revision;
    private long committed;
    private boolean retired;

    ThreadJournal(Account account, String session) {
        this(session, scope(account.id, account.endpoint, account.bearer, session), account.read("thread:" + session),
                value -> account.write("thread:" + session, value));
    }

    ThreadJournal(String session, String owner, String encoded, Consumer<String> write) {
        this.session = session;
        this.owner = owner;
        this.write = write;
        state = ThreadData.object(encoded);
        if ((state.has("draft") && !(state.opt("draft") instanceof String))
                || (state.has("pending") && !validPending(pending()))) {
            throw new IllegalStateException("Invalid thread record");
        }
    }

    private boolean validPending(JSONObject pending) {
        if (pending == null) return false;
        JSONObject body = pending.optJSONObject("body");
        return body != null && ThreadData.validId(ThreadData.string(body, "session_id")) && body.opt("input") instanceof String
                && session.equals(pending.opt("thread"))
                && !ThreadData.string(pending, "key").isEmpty()
                && (!pending.has("run_id") || ThreadData.validId(ThreadData.string(pending, "run_id")));
    }

    static String scope(String account, String endpoint, String bearer, String session) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{account, endpoint, bearer, session}) {
                hash.update(part.getBytes(StandardCharsets.UTF_8));
                hash.update((byte) 0);
            }
            // Only retained inside the account's authenticated encryption envelope.
            return Base64.getEncoder().encodeToString(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    boolean sameOwner() { return owner.equals(ThreadData.string(pending(), "owner")); }

    JSONObject pending() { return state.optJSONObject("pending"); }

    String draft() { return ThreadData.string(state, "draft"); }

    void draft(String text) {
        if (!text.equals(draft())) {
            ThreadData.put(state, "draft", text);
            revision++;
        }
    }

    JSONObject stage(JSONObject body, long elapsed, int boot, long ttl, boolean durable) {
        if (pending() != null || !ThreadData.validId(ThreadData.string(body, "session_id"))) throw new IllegalStateException("Invalid admission");
        JSONObject run = new JSONObject();
        ThreadData.put(run, "key", UUID.randomUUID().toString());
        ThreadData.put(run, "body", body);
        ThreadData.put(run, "owner", owner);
        ThreadData.put(run, "thread", session);
        ThreadData.put(run, "elapsed", elapsed);
        ThreadData.put(run, "boot", boot);
        ThreadData.put(run, "ttl", ttl);
        ThreadData.put(run, "durable", durable);
        String oldDraft = draft();
        ThreadData.put(state, "pending", run);
        draft("");
        revision++;
        try {
            flush();
        } catch (RuntimeException e) {
            state.remove("pending");
            draft(oldDraft);
            revision++;
            throw e;
        }
        return run;
    }

    void setRunId(String runId) {
        ThreadData.put(pending(), "run_id", runId);
        revision++;
        flush();
    }

    void checkpoint(long seq, String text) {
        ThreadData.put(pending(), "seq", seq);
        ThreadData.put(pending(), "preview", text);
        revision++;
    }

    void finish() { clearPending(draft()); }

    void reject() {
        String original = ThreadData.string(pending().optJSONObject("body"), "input");
        clearPending(draft().isEmpty() ? original : original + "\n" + draft());
    }

    private void clearPending(String newDraft) {
        JSONObject previous = pending();
        String oldDraft = draft();
        state.remove("pending");
        draft(newDraft);
        revision++;
        try {
            flush();
        } catch (RuntimeException error) {
            ThreadData.put(state, "pending", previous);
            draft(oldDraft);
            revision++;
            throw error;
        }
    }

    /** Capture on the UI thread; a delayed write cannot replace a newer commit or reopened journal. */
    Runnable snapshot() {
        long version = revision;
        String encoded = state.toString();
        return () -> commit(version, encoded);
    }

    void flush() { snapshot().run(); }

    private synchronized void commit(long version, String encoded) {
        if (retired || version <= committed) return;
        write.accept(encoded);
        committed = version;
    }

    synchronized void retire() { retired = true; }
}
