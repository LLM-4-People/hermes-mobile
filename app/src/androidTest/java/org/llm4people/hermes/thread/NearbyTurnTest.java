package org.llm4people.hermes.thread;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NearbyTurnTest extends TestCase {
    private static JSONObject area() throws Exception {
        return new JSONObject().put("source", "device").put("latitude", 45.5).put("longitude", -73.6)
                .put("accuracy_m", 4000).put("age_s", 14);
    }

    public void testExplicitContextKeepsTheTypedMessageAndOtherInstructionsIntact() throws Exception {
        String text = "  Où manger près de moi ?  ";
        JSONObject original = ThreadData.body("thread", text, new JSONObject().put("instructions", "Assistant mobile."));
        NearbyTurn.attach(original, null);
        assertEquals("Assistant mobile.", original.getString("instructions"));
        assertEquals(text, original.getString("input"));
        JSONObject manual = NearbyTurn.manual(" Côte-des-Neiges, Montréal ");
        assertEquals("Côte-des-Neiges, Montréal", manual.getString("area"));
        assertFalse(manual.has("latitude"));
        assertNull(NearbyTurn.manual("Rue\nDirective"));
        assertNull(NearbyTurn.manual(" {}"));
        NearbyTurn.attach(original, manual);
        assertEquals(text, original.getString("input"));
        assertTrue(original.getString("instructions").startsWith("Assistant mobile.\n"));
        assertTrue(original.getString("instructions").endsWith("\nnearby_context=" + manual));
        assertTrue(NearbyTurn.suggests(text));
        assertTrue(NearbyTurn.suggests("Restaurants NEAR ME?"));
        assertFalse(NearbyTurn.suggests("Near Memphis"));
    }

    public void testLateStagingAndCoordinatePrecisionFailClosed() throws Exception {
        JSONObject fix = area();
        assertEquals(15, NearbyTurn.fresh(fix, 50, 51).getInt("age_s"));
        assertEquals(300, NearbyTurn.fresh(fix, 50, 286050).getInt("age_s"));
        assertNull(NearbyTurn.fresh(fix, 50, 286051));
        assertNull(NearbyTurn.fresh(fix, 50, 49));
        assertNull(NearbyTurn.fresh(area().put("age_s", -1), 0, 0));
        assertNull(NearbyTurn.fresh(area().put("accuracy_m", 5), 0, 0));
        assertNull(NearbyTurn.fresh(area().put("longitude", -73.60003), 0, 0));
        assertNull(NearbyTurn.fresh(area().put("latitude", "45.5"), 0, 0));
        assertFalse(NearbyTurn.fresh(area().put("altitude", 10), 0, 1).has("altitude"));
    }

    public void testFrozenAdmissionSurvivesRestartAndObservedRunsCannotBorrowPermission() throws Exception {
        AtomicReference<String> saved = new AtomicReference<>();
        ThreadJournal source = new ThreadJournal("thread", "owner", null, saved::set);
        JSONObject frozen = NearbyTurn.fresh(area(), 100, 1100);
        JSONObject body = ThreadData.body("thread", "  près de moi  ", null);
        NearbyTurn.attach(body, frozen);
        source.draft(body.getString("input"));
        JSONObject receipt = source.stage(body, 5000L, 3, 600L, true, frozen);
        String key = receipt.getString("key");
        ThreadJournal reopened = new ThreadJournal("thread", "owner", saved.get(), saved::set);
        assertEquals(body.toString(), reopened.pending().getJSONObject("body").toString());
        assertEquals(15, reopened.pending().getJSONObject("nearby_guide").getInt("age_s"));
        assertEquals(key, reopened.pending().getString("key"));
        reopened.observe("run_other");
        assertFalse(reopened.pending().has("nearby_guide"));
        reopened.finish();
        assertEquals(key, reopened.pending().getString("key"));
        reopened.reject();
        assertEquals("  près de moi  ", reopened.draft());
        assertFalse(saved.get().contains("latitude"));
        assertNull(reopened.pending());
    }

    public void testDraftRestoresManualChoiceAndDeviceReselectionWithoutPersistingCoordinates() throws Exception {
        AtomicReference<String> disk = new AtomicReference<>();
        AtomicBoolean fail = new AtomicBoolean(false);
        ThreadJournal draft = new ThreadJournal("thread", "owner", null, value -> {
            if (fail.get()) throw new IllegalStateException();
            disk.set(value);
        });
        draft.draft("Bonjour");
        draft.nearbyDraft("manual", "Rosemont");
        assertEquals("Rosemont", new ThreadJournal("thread", "owner", disk.get(), disk::set).nearbyDraft().getString("area"));
        assertNull(new ThreadJournal("thread", "other", disk.get(), disk::set).nearbyDraft());
        String saved = disk.get();
        fail.set(true);
        try { draft.nearbyDraft("omit", null); fail(); } catch (IllegalStateException expected) {}
        assertEquals(saved, disk.get());
        assertEquals("Rosemont", draft.nearbyDraft().getString("area"));
        fail.set(false);
        draft.nearbyDraft("confirm", null);
        assertFalse(disk.get().contains("Rosemont"));
        assertFalse(disk.get().contains("latitude"));
        JSONObject body = ThreadData.body("thread", draft.draft(), null);
        NearbyTurn.attach(body, area());
        draft.stage(body, 20L, 1, 60L, true, area());
        assertNull(draft.nearbyDraft());
        draft.reject();
        assertEquals("confirm", draft.nearbyDraft().getString("mode"));
        assertEquals("Bonjour", draft.draft());
        assertFalse(disk.get().contains("latitude"));
        draft.nearbyDraft("omit", null);
        draft.stage(ThreadData.body("thread", draft.draft(), null), 40L, 1, 60L, true);
        draft.finish();
        assertNull(draft.nearbyDraft());
        assertFalse(disk.get().contains("nearby"));
    }

    public void testOnlyTheEnrolledTerminalTurnCanStartAGuide() throws Exception {
        JSONObject grant = new JSONObject().put("run_id", "run1").put("nearby_guide", area());
        JSONObject status = new JSONObject().put("status", "completed").put("run_id", "run1")
                .put("admission_id", "admit1").put("session_id", "tip").put("output", "Same reply");
        JSONObject user = new JSONObject().put("role", "user").put("id", 2).put("session_id", "tip").put("api_admission_id", "admit1");
        JSONObject assistant = new JSONObject().put("role", "assistant").put("id", 3).put("session_id", "tip").put("content", "Same reply");
        assertSame(assistant, NearbyTurn.response(grant, status, new JSONArray().put(assistant).put(user).put(assistant)));
        assertNull(NearbyTurn.response(grant, status, new JSONArray().put(assistant)));
        assertNull(NearbyTurn.response(grant, status, new JSONArray().put(user).put(assistant).put(assistant)));
        assertNull(NearbyTurn.response(grant, status, new JSONArray().put(user).put(new JSONObject().put("role", "user")).put(assistant)));
        assertNull(NearbyTurn.response(grant, status, new JSONArray().put(user).put(new JSONObject(assistant.toString()).put("session_id", "fork"))));
        assertNull(NearbyTurn.response(grant, status, new JSONArray().put(new JSONObject(user.toString()).put("api_admission_id", "other")).put(assistant)));
        assertNull(NearbyTurn.response(grant, new JSONObject(status.toString()).put("run_id", "other"), new JSONArray().put(user).put(assistant)));
    }
}
