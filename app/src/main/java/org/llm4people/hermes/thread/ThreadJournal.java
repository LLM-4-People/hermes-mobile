package org.llm4people.hermes.thread;

import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

/** One encrypted commit moves a draft into or out of the outbox. Mutable state belongs to the UI. */
final class ThreadJournal {
    private static final int MAX_RECONNECTS = 5;
    private final String session;
    private final String owner;
    private final Consumer<String> write;
    interface PhotoStore {
        String read();
        void pair(String journal, String pixels);
    }
    private final PhotoStore photos;
    private final JSONObject state;
    private long revision;
    private long committed;
    private boolean retired;

    ThreadJournal(Account account, String session) {
        this(session, scope(account.id, account.endpoint, account.bearer, session), account.read("thread:" + session),
                value -> account.write("thread:" + session, value), new PhotoStore() {
                    public String read() { return account.read("thread-photo:" + session); }
                    public void pair(String journal, String pixels) {
                        Map<String, String> changes = new HashMap<>();
                        changes.put("thread:" + session, journal);
                        changes.put("thread-photo:" + session, pixels);
                        account.writeTogether(changes);
                    }
                });
    }

    ThreadJournal(String session, String owner, String encoded, Consumer<String> write) {
        this(session, owner, encoded, write, null);
    }

    ThreadJournal(String session, String owner, String encoded, Consumer<String> write, PhotoStore photos) {
        this.photos = photos;
        this.session = session;
        this.owner = owner;
        this.write = write;
        state = ThreadData.object(encoded);
        if ((state.has("draft") && !(state.opt("draft") instanceof String))
                || (state.has("photo") && !DiscussionPhoto.valid(state.optJSONObject("photo")))
                || (state.has("pending") && !validPending(state.optJSONObject("pending")))
                || (state.has("observed") && !validObservation(state.optJSONObject("observed")))) {
            throw new IllegalStateException("Invalid thread record");
        }
    }

    private boolean validPending(JSONObject pending) {
        if (pending == null || pending.has("photo") && !DiscussionPhoto.valid(pending.optJSONObject("photo"))) return false;
        JSONObject body = pending.optJSONObject("body");
        if (!validReconnects(pending)) return false;
        return body != null && ThreadData.validId(ThreadData.string(body, "session_id")) && body.opt("input") instanceof String
                && session.equals(pending.opt("thread"))
                && !ThreadData.string(pending, "key").isEmpty()
                && (!pending.has("run_id") || ThreadData.validId(ThreadData.string(pending, "run_id")));
    }

    private static boolean validReconnects(JSONObject receipt) {
        return !receipt.has("disconnects") || receipt.opt("disconnects") instanceof Integer count
                && count >= 0 && count <= MAX_RECONNECTS + 1;
    }

    private boolean validObservation(JSONObject observed) {
        return observed != null && validReconnects(observed) && session.equals(observed.opt("thread"))
                && ThreadData.validId(ThreadData.string(observed, "run_id"))
                && !ThreadData.string(observed, "owner").isEmpty();
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

    JSONObject pending() {
        JSONObject observed = state.optJSONObject("observed");
        return observed != null ? observed : state.optJSONObject("pending");
    }

    /** Follow a verified gateway run while preserving the local admission receipt and draft. */
    void observe(String runId) {
        if (!ThreadData.validId(runId)) throw new IllegalArgumentException("Invalid run");
        if (sameOwner() && runId.equals(ThreadData.string(pending(), "run_id"))) return;
        JSONObject previous = state.optJSONObject("observed");
        JSONObject local = state.optJSONObject("pending");
        if (owner.equals(ThreadData.string(local, "owner")) && runId.equals(ThreadData.string(local, "run_id"))) {
            state.remove("observed");
        } else {
            JSONObject observed = new JSONObject();
            ThreadData.put(observed, "thread", session);
            ThreadData.put(observed, "owner", owner);
            ThreadData.put(observed, "run_id", runId);
            ThreadData.put(state, "observed", observed);
        }
        revision++;
        try { flush(); }
        catch (RuntimeException failure) {
            ThreadData.put(state, "observed", previous);
            revision++;
            throw failure;
        }
    }

    String draft() { return ThreadData.string(state, "draft"); }

    boolean containsShare(String receipt) { return receipt.equals(state.optString("imported_share")); }

    void appendShare(String receipt, String text) {
        if (containsShare(receipt)) return;
        String previous = draft();
        String previousReceipt = state.optString("imported_share", null);
        draft(previous.isEmpty() ? text : previous + "\n" + text);
        ThreadData.put(state, "imported_share", receipt);
        try { flush(); }
        catch (RuntimeException failure) {
            ThreadData.put(state, "imported_share", previousReceipt);
            draft(previous);
            throw failure;
        }
    }

    void draft(String text) {
        if (!text.equals(draft())) {
            ThreadData.put(state, "draft", text);
            revision++;
        }
    }

    JSONObject nearbyDraft() {
        JSONObject saved = state.optJSONObject("nearby_draft");
        return saved != null && owner.equals(saved.optString("owner")) ? saved : null;
    }

    void nearbyDraft(String mode, String area) {
        if (pending() != null) throw new IllegalStateException("Draft is pending");
        JSONObject previous = state.optJSONObject("nearby_draft");
        JSONObject saved = ThreadData.put(new JSONObject(), "owner", owner);
        ThreadData.put(saved, "mode", mode);
        ThreadData.put(saved, "area", area);
        ThreadData.put(state, "nearby_draft", saved);
        revision++;
        try { flush(); }
        catch (RuntimeException failed) {
            ThreadData.put(state, "nearby_draft", previous);
            revision++;
            throw failed;
        }
    }

    JSONObject photo() { return state.optJSONObject("photo"); }

    boolean photoOwned() { return photo() == null || owner.equals(photo().optString("owner")); }

    void photo(JSONObject info, String pixels) {
        if (pending() != null || info != null && (!DiscussionPhoto.valid(info) || pixels == null))
            throw new IllegalStateException("Invalid photo change");
        if (info != null) ThreadData.put(info, "owner", owner);
        JSONObject old = photo();
        ThreadData.put(state, "photo", info);
        revision++;
        try { commitPhoto(pixels); }
        catch (RuntimeException error) {
            ThreadData.put(state, "photo", old);
            revision++;
            throw error;
        }
    }

    private synchronized void commitPhoto(String pixels) {
        if (retired || photos == null) throw new IllegalStateException("Inactive thread");
        photos.pair(state.toString(), pixels);
        committed = revision;
    }

    JSONObject wire(JSONObject body, JSONObject info) throws IOException {
        if (info == null) return body;
        if (!owner.equals(info.optString("owner"))) throw new IOException("Image owner changed");
        try { return DiscussionPhoto.wire(body, info, photos.read()); }
        catch (Account.UnreadableRecord damaged) { throw new DiscussionPhoto.Unavailable(); }
    }

    JSONObject stage(JSONObject body, long elapsed, int boot, long ttl, boolean durable) {
        return stage(body, elapsed, boot, ttl, durable, null);
    }

    JSONObject stage(JSONObject body, long elapsed, int boot, long ttl, boolean durable, JSONObject guideArea) {
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
        if (NearbyTurn.validDevice(guideArea)) ThreadData.put(run, "nearby_guide", guideArea);
        String oldDraft = draft();
        JSONObject originalPhoto = photo();
        if (!photoOwned()) throw new IllegalStateException("Image owner changed");
        JSONObject originalNearby = state.optJSONObject("nearby_draft");
        ThreadData.put(run, "nearby_draft_retry", nearbyDraft());
        state.remove("nearby_draft");
        ThreadData.put(run, "photo", originalPhoto);
        state.remove("photo");
        ThreadData.put(state, "pending", run);
        draft("");
        revision++;
        try {
            flush();
        } catch (RuntimeException e) {
            state.remove("pending");
            ThreadData.put(state, "nearby_draft", originalNearby);
            ThreadData.put(state, "photo", originalPhoto);
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

    boolean reconnectsExhausted() {
        return pending().optInt("disconnects") > MAX_RECONNECTS;
    }

    /** Reserve one reconnect on the admitted run, alongside its replay cursor, before scheduling it. */
    long nextReconnectDelayMillis() {
        int previous = pending().optInt("disconnects");
        if (previous > MAX_RECONNECTS) return -1;
        saveDisconnects(previous + 1);
        return previous == MAX_RECONNECTS ? -1 : 1000L << previous;
    }

    void resetReconnects() { saveDisconnects(0); }

    private void saveDisconnects(int count) {
        JSONObject receipt = pending();
        Object previous = receipt.opt("disconnects");
        ThreadData.put(receipt, "disconnects", count);
        revision++;
        try {
            flush();
        } catch (RuntimeException error) {
            ThreadData.put(receipt, "disconnects", previous);
            revision++;
            throw error;
        }
    }

    void checkpoint(long seq, String text) { checkpoint(seq, text, RunPreview.Origin.FIRST, 0); }

    void checkpoint(long seq, String text, RunPreview.Origin origin, int highSurrogate) {
        ThreadData.put(pending(), "seq", seq);
        ThreadData.put(pending(), "preview", text);
        ThreadData.put(pending(), "preview_origin", origin.json());
        ThreadData.put(pending(), "preview_high_surrogate", highSurrogate == 0 ? null : highSurrogate);
        revision++;
    }

    void finish() { clearPending(draft(), false); }

    void reject() {
        String original = ThreadData.string(pending().optJSONObject("body"), "input");
        clearPending(draft().isEmpty() ? original : original + "\n" + draft(), true);
    }

    private void clearPending(String newDraft, boolean rejected) {
        String slot = state.has("observed") ? "observed" : "pending";
        JSONObject previous = pending();
        String oldDraft = draft();
        JSONObject originalPhoto = previous.optJSONObject("photo");
        JSONObject oldPhoto = photo();
        JSONObject oldNearby = state.optJSONObject("nearby_draft");
        state.remove(slot);
        if (rejected) ThreadData.put(state, "nearby_draft", previous.optJSONObject("nearby_draft_retry"));
        if (rejected && originalPhoto != null) ThreadData.put(state, "photo", originalPhoto);
        draft(newDraft);
        revision++;
        try {
            if (originalPhoto != null && !rejected) commitPhoto(null);
            else flush();
        } catch (RuntimeException error) {
            ThreadData.put(state, "nearby_draft", oldNearby);
            ThreadData.put(state, "photo", oldPhoto);
            ThreadData.put(state, slot, previous);
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
