package org.llm4people.hermes.thread;

import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Base64;

public final class PhotoJournalTest extends TestCase {
    private static final String PIXELS = "AQID";
    private static JSONObject info() throws Exception {
        return new JSONObject().put("name", "Photo privée.png").put("mime", "image/png").put("thumbnail", "")
                .put("digest", DiscussionPhoto.digest(Base64.getDecoder().decode(PIXELS)));
    }
    private static final class Store implements ThreadJournal.PhotoStore {
        String state, pixels;
        boolean fail;
        public String read() { return pixels; }
        public void pair(String state, String pixels) {
            if (fail) throw new IllegalStateException("disk unavailable");
            this.state = state;
            this.pixels = pixels;
        }
        ThreadJournal load(String owner) {
            return new ThreadJournal("root", owner, state, next -> state = next, this);
        }
    }

    public void testDurableExactRetryRestoresPhotoAndInterveningTextThenErasesBytes() throws Exception {
        Store store = new Store();
        ThreadJournal first = store.load("owner");
        first.draft("Quelle ligne ?");
        first.photo(info(), PIXELS);
        ThreadJournal restored = store.load("owner");
        assertFalse(store.load("changed credential").photoOwned());
        assertEquals("Quelle ligne ?", restored.draft());
        JSONObject body = ThreadData.body("compacted_tip", restored.draft(), new JSONObject().put("model", "vision"));
        JSONObject receipt = restored.stage(body, 100, 2, 600, true);
        assertNull(restored.photo());
        assertFalse(store.state.contains(PIXELS));
        ThreadJournal retry = store.load("owner");
        JSONObject wire = retry.wire(body, retry.pending().getJSONObject("photo"));
        assertEquals("compacted_tip", wire.getString("session_id"));
        assertEquals("vision", wire.getString("model"));
        JSONArray parts = wire.getJSONArray("input").getJSONObject(0).getJSONArray("content");
        assertEquals("Quelle ligne ?", parts.getJSONObject(0).getString("text"));
        assertEquals("data:image/png;base64," + PIXELS, parts.getJSONObject(1).getJSONObject("image_url").getString("url"));
        assertFalse(wire.toString().contains("Photo privée"));
        assertEquals(receipt.getString("key"), retry.pending().getString("key"));
        retry.draft("Précision.");
        retry.reject();
        assertEquals("Quelle ligne ?\nPrécision.", retry.draft());
        assertEquals(PIXELS, store.pixels);
        assertNotNull(store.load("owner").photo());
        retry.stage(body, 110, 2, 600, true);
        retry.setRunId("id");
        retry.observe("autre");
        retry.finish();
        assertEquals(PIXELS, store.pixels);
        store.fail = true;
        try { retry.finish(); fail(); } catch (IllegalStateException expected) { }
        assertNotNull(retry.pending());
        store.fail = false;
        retry.finish();
        assertNull(store.pixels);
        assertNull(store.load("owner").pending());
    }

    public void testFailedAttachAndRemovalDoNotLoseDraftOrPhoto() throws Exception {
        Store store = new Store();
        ThreadJournal journal = store.load("primary");
        journal.draft("Brouillon");
        store.fail = true;
        try { journal.photo(info(), PIXELS); fail(); } catch (IllegalStateException expected) { }
        assertNull(journal.photo());
        assertEquals("Brouillon", journal.draft());
        store.fail = false;
        journal.photo(info(), PIXELS);
        store.fail = true;
        try { journal.photo(null, null); fail(); } catch (IllegalStateException expected) { }
        assertNotNull(store.load("primary").photo());
        assertNotNull(journal.photo());
        try { DiscussionPhoto.wire(new JSONObject(), info(), "AA=="); fail(); }
        catch (DiscussionPhoto.Unavailable expected) { }
    }

    public void testTranscriptKeepsNormalUserPathsAndHidesInternalRetainedMedia() throws Exception {
        String digest = "ab".repeat(32);
        String reference = "[Image attached at: /srv/hermes/cache/documents/native-inputs/" + digest
                + "/api_" + digest.substring(0, 32) + ".png]";
        JSONObject user = new JSONObject().put("role", "user").put("content", "Question\n\n" + reference);
        assertEquals("Question\n\nImage jointe", ThreadData.content(user));
        assertEquals("Question", ThreadTitles.firstPrompt(new JSONArray().put(user)));
        assertEquals("Question", ThreadTitles.inboxPrompt(new JSONObject().put("preview", "Question [Image attached at: /srv/private")));
        user.put("content", "Ouvrir /tmp/work.png");
        assertEquals("Ouvrir /tmp/work.png", ThreadData.content(user));
    }
}
