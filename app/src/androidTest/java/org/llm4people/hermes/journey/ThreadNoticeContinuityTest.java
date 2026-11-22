package org.llm4people.hermes.journey;

import android.app.Activity;
import android.content.Context;
import android.test.InstrumentationTestCase;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.thread.ThreadWorkspace;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
public final class ThreadNoticeContinuityTest extends InstrumentationTestCase {
    static JSONObject proposal(int id, String instruction) throws Exception {
        String page = """
                {"version":1,"surface":"page/revue","mode":"replace","title":"Revue","blocks":[
                {"type":"form","title":"Relecture","prompt":"%s","fields":[{"id":"file","label":"Fichier","kind":"text"}]}]}""";
        return new JSONObject().put("role","assistant").put("session_id","root").put("id",id)
                .put("content","Outil proposé.\n```hermes-workspace\n" + String.format(java.util.Locale.ROOT, page, instruction) + "\n```");
    }

    static String transcript(String root, String tip, String title, JSONArray rows) throws Exception {
        return new JSONObject().put("thread_id",root).put("session_id",tip).put("thread_title",title)
                .put("pagination",new JSONObject().put("visible_only",true).put("total",rows.length()))
                .put("data",rows).toString();
    }

    public void testAuthenticatedLineageRefreshPreservesBothUnstagedFormsAndComposer() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName()));
        ThreadWorkspace[] workspace = new ThreadWorkspace[1];
        EditText[] editor = new EditText[1];
        JSONArray versions = new JSONArray().put(proposal(11,"Relire.")).put(proposal(12,"Vérifier."));
        String before = transcript("root","tip-before","Avant",versions);
        String updated = transcript("root","tip-after","Après",versions);
        String fork = transcript("fork","fork","Fourche",new JSONArray());
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context);
             Exchange gateway = new Exchange("200 OK", "Content-Type: application/json\r\n", before, updated, updated, fork, fork)) {
            fixture.account.write("last-thread","root");
            ThreadInstruments pins = new ThreadInstruments(fixture.account,"root");
            pins.pin(pins.state(),pins.proposals(versions).get(0),versions);
            CountDownLatch refreshed = new CountDownLatch(1), switched = new CountDownLatch(1);
            ThreadWorkspace.Host host = new ThreadWorkspace.Host() {
                public void selected(String session, String title) {
                    if ("Après".equals(title)) refreshed.countDown();
                    if ("fork".equals(session) && "Fourche".equals(title)) switched.countDown();
                }
                public void pendingChanged() {}
                public JSONObject options(String session) { throw new AssertionError("Unexpected send"); }
                public void timing(String session, long first, long elapsed, long tokens) {}
            };
            runTestOnUiThread(() -> {
                ThreadWorkspace page = new ThreadWorkspace(activity,new HermesApi(gateway.endpoint(),fixture.account.bearer),
                        fixture.account,host,new VoiceDrafts(activity));
                workspace[0] = page;
                page.setInstrumentHost((ArtifactDesk.Host)activity);
                activity.setContentView(page.view());
                page.resumeLast();
            });
            JourneyUi ui = new JourneyUi(getInstrumentation(),activity,context.getCacheDir());
            String compare = activity.getString(org.llm4people.hermes.R.string.instrument_compare);
            String composerHint = activity.getString(org.llm4people.hermes.R.string.thread_message_hint);
            String tabProposed = activity.getString(org.llm4people.hermes.R.string.instrument_tab_next_role);
            String tabPinned = activity.getString(org.llm4people.hermes.R.string.instrument_tab_pin_role);
            ui.see(compare);
            ui.fill(composerHint,"brouillon privé");
            runTestOnUiThread(() -> {
                workspace[0].focusComposer();
                editor[0] = (EditText)activity.getCurrentFocus();
                editor[0].setSelection(2,6);
            });
            ui.tap(compare);
            ui.tap(tabProposed);
            ui.fill("Fichier","candidate.py");
            ui.tap(tabPinned);
            ui.fill("Fichier","pinned.py");
            runTestOnUiThread(() -> {
                workspace[0].openNotice("intermediate","completed-run","run_completed");
                assertTrue("Opening the same logical thread retired the original composer", editor[0].isAttachedToWindow());
            });
            assertTrue(refreshed.await(5,TimeUnit.SECONDS));
            assertEquals("pinned.py",ui.fieldText("Fichier"));
            ui.tap(tabProposed);
            assertEquals("candidate.py",ui.fieldText("Fichier"));
            runTestOnUiThread(() -> {
                assertEquals("tip-after",workspace[0].remoteSessionId());
                assertTrue(editor[0].isAttachedToWindow());
                assertEquals("brouillon privé",editor[0].getText().toString());
                assertEquals(2,editor[0].getSelectionStart());
                assertEquals(6,editor[0].getSelectionEnd());
                workspace[0].openNotice("fork","another-run","run_completed");
            });
            assertTrue(switched.await(5,TimeUnit.SECONDS));
            String wire = gateway.observed();
            assertTrue(wire.contains("/intermediate/messages?order=latest&limit=0"));
            assertTrue(wire.contains("Bearer " + fixture.account.bearer));
            assertFalse(wire.contains("POST "));
            runTestOnUiThread(() -> {
                assertFalse(editor[0].isAttachedToWindow());
                assertEquals("fork",workspace[0].sessionId());
                workspace[0].close();
                workspace[0] = null;
            });
            assertEquals("brouillon privé",new JSONObject(fixture.account.read("thread:root")).getString("draft"));
        } finally {
            runTestOnUiThread(() -> {
                if (workspace[0] != null) workspace[0].close();
                activity.finish();
            });
        }
    }
}
