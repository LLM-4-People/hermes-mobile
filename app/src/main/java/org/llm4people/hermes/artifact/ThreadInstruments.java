package org.llm4people.hermes.artifact;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One explicitly pinned assistant page per unlocked account and logical conversation. */
public final class ThreadInstruments {
    private final Account account;
    private final String root;
    private final String key;

    public ThreadInstruments(Account account, String stableThreadId) {
        this.account = Objects.requireNonNull(account);
        HermesApi.segment(stableThreadId);
        root = stableThreadId;
        key = "thread.instrument.v1." + root;
    }

    public static final class Pin {
        public final String pageId, title, pageJson, messageId;
        public final int fenceOrdinal, version;
        public final boolean nearbyPlaces;
        public final String nearbyCategory;
        public final int nearbyRadiusMeters;
        public final int nearbyLimit;
        private final ThreadInstruments owner;
        private final String sourceSession;

        private Pin(ThreadInstruments owner, WorkspaceLayout layout, String pageJson, String messageId,
                    String sourceSession, int fenceOrdinal, int version) {
            this.owner = owner;
            this.pageId = layout.surface;
            WorkspaceLayout.Block nearbyBlock = null;
            for (WorkspaceLayout.Block block : layout.blocks) {
                if ("nearby_places".equals(block.type)) { nearbyBlock = block; break; }
            }
            this.nearbyPlaces = nearbyBlock != null;
            this.nearbyCategory = nearbyBlock == null ? "" : nearbyBlock.category;
            this.nearbyRadiusMeters = nearbyBlock == null ? 0 : nearbyBlock.radiusMeters;
            this.nearbyLimit = nearbyBlock == null ? 0 : nearbyBlock.limit;
            this.title = layout.title;
            this.pageJson = pageJson;
            this.messageId = messageId;
            this.sourceSession = sourceSession;
            this.fenceOrdinal = fenceOrdinal;
            this.version = version;
        }

        /** Source identity is independent of the number of older versions loaded in the UI. */
        public boolean sameSource(Pin other) {
            return other != null && owner.owns(other.owner) && messageId.equals(other.messageId)
                    && sourceSession.equals(other.sourceSession) && fenceOrdinal == other.fenceOrdinal
                    && pageJson.equals(other.pageJson);
        }

        /** Match the transcript row even if two segments happen to reuse a row identifier. */
        public boolean fromMessage(JSONObject row) {
            return row != null && "assistant".equals(row.optString("role"))
                    && !"hidden".equals(row.optString("display_kind"))
                    && messageId.equals(rowId(row)) && sourceSession.equals(segmentOf(row));
        }

        private JSONObject json() throws JSONException {
            return new JSONObject().put("message", messageId).put("session", sourceSession)
                    .put("fence", fenceOrdinal).put("version", version).put("page", pageJson);
        }
    }

    public static final class State {
        public final Pin current, previous;
        public final boolean canUndo;
        private final ThreadInstruments owner;
        private final String revision;

        private State(ThreadInstruments owner, String revision, Pin current, Pin previous, boolean canUndo) {
            this.owner = owner;
            this.revision = revision;
            this.current = current;
            this.previous = previous;
            this.canUndo = canUndo;
        }
    }

    /** An opaque confirmation bound to this unlock, logical discussion and exact damaged revision. */
    public static final class Damaged extends IllegalStateException {
        private final ThreadInstruments owner;
        private final Account.UnreadableRecord record;
        private Damaged(ThreadInstruments owner, Account.UnreadableRecord record) {
            super("Unreadable thread instrument");
            this.owner = owner;
            this.record = record;
        }
    }

    public static final class Conflict extends IllegalStateException {
        private Conflict() { super("Thread instrument changed"); }
    }

    private boolean owns(ThreadInstruments other) {
        return other != null && account == other.account && root.equals(other.root);
    }

    /** Pass the currently selected logical discussion's server transcript, in chronological order. */
    public List<Pin> proposals(JSONArray serverTranscript) {
        try { account.read(key); }
        catch (Account.UnreadableRecord damage) { throw new Damaged(this, damage); }
        return collect(serverTranscript);
    }

    private List<Pin> collect(JSONArray messages) {
        Objects.requireNonNull(messages);
        List<Pin> result = new ArrayList<>();
        Map<String, Pin> lastByPage = new HashMap<>();
        for (ArtifactCatalog.Block block : ArtifactCatalog.scan(messages).blocks) {
            if (!WorkspaceLayout.FENCE.equals(block.language)) continue;
            JSONObject row = messages.optJSONObject(block.messageIndex);
            String messageId = rowId(row);
            String session = segmentOf(row);
            if (messageId == null || session == null) continue;
            try {
                WorkspaceLayout layout = WorkspaceLayout.parse(block.content);
                if (!WorkspaceLayout.custom(layout.surface)) continue;
                Pin previous = lastByPage.get(layout.surface);
                int version = previous == null ? 1 : previous.version + (previous.pageJson.equals(block.content) ? 0 : 1);
                Pin next = new Pin(this, layout, block.content, messageId, session, block.ordinal, version);
                lastByPage.put(layout.surface, next);
                result.add(next);
            } catch (IOException invalid) {
                // Invalid and built-in proposals still belong to the artifact reader.
                org.llm4people.hermes.Diagnostics.debug("workspace", "proposal_skipped", invalid);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String rowId(JSONObject row) {
        Object value = row.opt("id");
        if (!(value instanceof String || value instanceof Integer || value instanceof Long)) return null;
        if (value instanceof Number number && number.longValue() <= 0) return null;
        String id = String.valueOf(value);
        return validId(id) ? id : null;
    }

    private static String segmentOf(JSONObject row) {
        if (row.isNull("session_id")) return "";
        Object value = row.opt("session_id");
        return value instanceof String id && validId(id) ? id : null;
    }

    private static boolean validId(String value) {
        try { HermesApi.segment(value); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public State state() {
        try {
            State state = account.read(key, this::decodeState);
            return state == null ? new State(this, "", null, null, false) : state;
        } catch (Account.UnreadableRecord damage) { throw new Damaged(this, damage); }
    }

    private State decodeState(String raw) {
        try {
            JSONObject data = new JSONObject(raw);
            Object revision = data.get("revision");
            if (!(revision instanceof String token) || !UUID.fromString(token).toString().equals(token))
                throw new JSONException("Invalid instrument revision");
            Pin current = decode(data.get("current"));
            boolean canUndo = data.has("previous");
            Pin previous = canUndo ? decode(data.get("previous")) : null;
            return new State(this, token, current, previous, canUndo);
        } catch (IOException | JSONException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid thread instrument record", invalid);
        }
    }

    private Pin decode(Object value) throws IOException, JSONException {
        if (JSONObject.NULL.equals(value)) return null;
        if (!(value instanceof JSONObject record) || !(record.get("message") instanceof String message)
                || !(record.get("session") instanceof String segment) || !(record.get("page") instanceof String raw)
                || !(record.get("fence") instanceof Integer fence) || fence < 0
                || !(record.get("version") instanceof Integer version) || version < 1
                || !validId(message) || (!segment.isEmpty() && !validId(segment))) throw new JSONException("Invalid pin");
        WorkspaceLayout layout = WorkspaceLayout.parse(raw);
        if (!WorkspaceLayout.custom(layout.surface)) throw new JSONException("Expected a named page");
        return new Pin(this, layout, raw, message, segment, fence, version);
    }

    public State pin(State expected, Pin proposal, JSONArray currentTranscript) {
        if (proposal == null || !owns(proposal.owner)) throw new IllegalArgumentException("Invalid proposal owner");
        synchronized (ThreadInstruments.class) {
            State old = check(expected);
            for (Pin candidate : collect(currentTranscript)) {
                if (candidate.sameSource(proposal)) {
                    if (proposal.sameSource(old.current)) return old;
                    return save(proposal, old.current, true);
                }
            }
            throw new IllegalArgumentException("Assistant proposal is no longer present");
        }
    }

    public State clear(State expected) {
        synchronized (ThreadInstruments.class) {
            State old = check(expected);
            return old.current == null ? old : save(null, old.current, true);
        }
    }

    public State undo(State expected) {
        synchronized (ThreadInstruments.class) {
            State old = check(expected);
            return old.canUndo ? save(old.previous, null, false) : old;
        }
    }

    public State recover(Damaged damage) {
        synchronized (ThreadInstruments.class) {
            if (damage == null || !owns(damage.owner)) throw new Conflict();
            String revision = UUID.randomUUID().toString();
            try {
                JSONObject empty = new JSONObject().put("revision", revision).put("current", JSONObject.NULL);
                if (!account.replaceUnreadable(key, damage.record, empty.toString())) throw new Conflict();
            } catch (JSONException invalid) { throw new IllegalStateException("Cannot encode thread instrument", invalid); }
            return new State(this, revision, null, null, false);
        }
    }

    private State check(State expected) {
        State current = state();
        if (expected == null || !owns(expected.owner) || !current.revision.equals(expected.revision)) throw new Conflict();
        return current;
    }

    private State save(Pin current, Pin previous, boolean canUndo) {
        String revision = UUID.randomUUID().toString();
        try {
            JSONObject data = new JSONObject().put("revision", revision)
                    .put("current", current == null ? JSONObject.NULL : current.json());
            if (canUndo) data.put("previous", previous == null ? JSONObject.NULL : previous.json());
            // Keep the new revision even when undo restores an empty thread. Old confirmations stay stale.
            account.write(key, data.toString());
        } catch (JSONException invalid) { throw new IllegalStateException("Cannot encode thread instrument", invalid); }
        return new State(this, revision, current, previous, canUndo);
    }
}
