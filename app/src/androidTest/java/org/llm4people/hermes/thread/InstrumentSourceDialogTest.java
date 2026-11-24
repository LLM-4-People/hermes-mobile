package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.AdapterView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.util.ArrayList;

@SuppressWarnings("deprecation")
public final class InstrumentSourceDialogTest extends InstrumentationTestCase {
    private MainActivity activity;
    private ThreadWorkspace workspace;
    private ThreadInstrumentPanel panel;
    private ThreadInstruments.Pin original;
    private JSONArray transcript;

    public void testChangedProposalClosesItsSourceAndPreservesLaterDialog() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        exercise(false);
    }

    public void testConfirmedPinKeepsSourceUntilItsPaneCloses() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        exercise(true);
    }

    private void exercise(boolean pinned) throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        workspace = null;
        panel = null;
        try {
            try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app)) {
                activity = (MainActivity) getInstrumentation().startActivitySync(
                        app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
                try {
                    transcript = transcript("Relecture");
                    JSONArray changed = transcript("Nouvelle relecture");
                    ThreadInstruments store = new ThreadInstruments(profile.account, "root");
                    original = store.proposals(transcript).get(0);
                    if (pinned) store.pin(store.state(), original, transcript);
                    runTestOnUiThread(() -> {
                        VoiceDrafts voice = new VoiceDrafts(activity);
                        workspace = new ThreadWorkspace(activity, null, profile.account, activity, voice);
                        ThreadViews screen = new ThreadViews(activity, voice);
                        workspace.screen = screen;
                        screen.chat("", s -> {}, () -> fail("Unexpected send"), () -> {}, () -> {});
                        panel = new ThreadInstrumentPanel(workspace, store, activity);
                        screen.instruments = panel;
                        activity.setContentView(screen.root);
                    });
                    openReader(pinned);
                    runTestOnUiThread(() -> {
                        panel.proposals(transcript);
                        assertTrue(sourceVisible());
                        panel.proposals(changed);
                        assertEquals(pinned, sourceVisible());
                        if (pinned) {
                            assertEquals(original.pageJson, store.state().current.pageJson);
                            assertTrue(panel.close());
                            assertFalse(sourceVisible());
                        }
                    });
                    openReader(pinned);
                    runTestOnUiThread(() -> {
                        AlertDialog unrelated = workspace.screen.show(new AlertDialog.Builder(activity)
                                .setTitle("Autre dialogue"));
                        panel.proposals(new JSONArray());
                        panel.retire();
                        assertTrue(unrelated.isShowing());
                    });
                } finally {
                    try {
                        runTestOnUiThread(() -> {
                            if (workspace != null) {
                                workspace.close();
                                workspace = null;
                            }
                        });
                    } finally {
                        ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                    }
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    private void openReader(boolean pinned) throws Throwable {
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            panel.proposals(transcript);
            workspace.screen.rows(transcript, false, true, 0);
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            String action = activity.getString(pinned ? R.string.instrument_open_pin : R.string.instrument_try);
            assertTrue(named(workspace.screen.root, action, View.FIND_VIEWS_WITH_TEXT).performClick());
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> assertTrue(named(workspace.screen.root, activity.getString(R.string.instrument_options),
                View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION).performClick()));
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            String action = activity.getString(R.string.instrument_source);
            for (View window : WindowInspector.getGlobalWindowViews()) {
                View item = named(window, action, View.FIND_VIEWS_WITH_TEXT);
                while (item != null && item.getParent() instanceof View parent) {
                    if (parent instanceof AdapterView<?> menu) {
                        int position = menu.getPositionForView(item);
                        assertTrue(menu.performItemClick(item, position, menu.getItemIdAtPosition(position)));
                        return;
                    }
                    item = parent;
                }
            }
            fail("Missing source menu item");
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> assertTrue(sourceVisible()));
    }

    private boolean sourceVisible() {
        String title = activity.getString(R.string.instrument_source_title, original.title, original.messageId);
        for (View window : WindowInspector.getGlobalWindowViews()) {
            if (named(window, title, View.FIND_VIEWS_WITH_TEXT) != null) return true;
        }
        return false;
    }

    private static View named(View root, String text, int flags) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, flags);
        for (View view : matches) if (view.isShown()) return view;
        return null;
    }

    private static JSONArray transcript(String title) throws Exception {
        JSONObject block = new JSONObject().put("type", "value").put("source", "thread.title").put("title", "Titre");
        JSONObject page = new JSONObject().put("version", 1).put("surface", "page/revue").put("mode", "replace")
                .put("title", title).put("blocks", new JSONArray().put(block));
        return new JSONArray().put(new JSONObject().put("role", "assistant").put("id", 5).put("session_id", "root")
                .put("content", "```hermes-workspace\n" + page + "\n```"));
    }
}
