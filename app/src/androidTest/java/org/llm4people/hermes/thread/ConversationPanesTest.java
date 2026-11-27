package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.voice.VoiceDrafts;

@SuppressWarnings("deprecation")
public final class ConversationPanesTest extends InstrumentationTestCase {
    public void testPhoneTabletAndSplitResizesPreserveComposerAndCoordinateWithInstrumentPane() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                ThreadViews index = new ThreadViews(activity, new org.llm4people.hermes.voice.VoiceDrafts(activity));
                index.inbox(() -> {}, () -> {}, row -> {}, () -> {}, null);
                ThreadViews chat = new ThreadViews(activity, new org.llm4people.hermes.voice.VoiceDrafts(activity));
                chat.chat("Brouillon à garder", text -> {}, () -> fail("Unexpected send"), () -> {}, () -> {});
                InstrumentViewport viewport = chat.attachInstrumentViewport();
                AtomicBoolean inspector = new AtomicBoolean(false);
                AtomicInteger notifications = new AtomicInteger();
                ConversationPanes panes = new ConversationPanes(activity, index.root, inspector::get,
                        notifications::incrementAndGet);
                activity.setContentView(panes);
                panes.select(chat.root);

                chat.focusComposer();
                EditText composer = (EditText) chat.root.findFocus();
                assertNotNull(composer);
                composer.setSelection(3, 10);

                // Phone portrait: single pane, detail visible, sidebar hidden, cannot toggle.
                size(panes, 360, 740);
                assertFalse(panes.dualPane());
                assertFalse(panes.canToggle());
                assertFalse(panes.sidebarVisible());
                assertEquals(View.GONE, index.root.getVisibility());
                assertEquals(View.VISIBLE, chat.root.getVisibility());
                assertSame(composer, chat.root.findFocus());
                assertEquals(3, composer.getSelectionStart());
                assertEquals(10, composer.getSelectionEnd());

                // Portrait tablet: dual pane, sidebar visible beside detail.
                size(panes, 800, 1200);
                assertTrue(panes.dualPane());
                assertTrue(panes.canToggle());
                assertTrue(panes.sidebarVisible());
                assertEquals(View.VISIBLE, index.root.getVisibility());
                assertEquals(View.VISIBLE, chat.root.getVisibility());
                assertTrue(index.root.getWidth() > 0);
                assertTrue(chat.root.getWidth() > 0);
                assertTrue(index.root.getRight() <= panes.findViewById(R.id.discussion_detail).getLeft());

                // Opening an instrument on portrait tablet auto-hides sidebar so transcript + tool split at 640dp+.
                EditText toolField = new EditText(activity);
                toolField.setText("facture.py");
                inspector.set(true);
                viewport.open(toolField);
                size(panes, 800, 1200);
                assertTrue(panes.canToggle());
                assertFalse(panes.sidebarVisible());
                assertTrue(viewport.wide());
                assertEquals(View.VISIBLE, chat.list.getVisibility());
                assertEquals("facture.py", toolField.getText().toString());

                // Landscape tablet (1200dp): sidebar + transcript + tool all coexist side by side.
                size(panes, 1200, 800);
                assertTrue(panes.dualPane());
                assertTrue(panes.sidebarVisible());
                assertTrue(viewport.wide());

                // Manual toggle hides sidebar and keeps composer + tool state intact.
                panes.toggle();
                size(panes, 1200, 800);
                assertFalse(panes.sidebarVisible());
                assertEquals("Brouillon à garder", chat.draft());
                assertEquals("facture.py", toolField.getText().toString());
                assertEquals(3, composer.getSelectionStart());
                assertEquals(10, composer.getSelectionEnd());

                // Split-screen narrow (550dp): collapses cleanly to single pane while preserving composer draft/selection.
                inspector.set(false);
                viewport.close();
                size(panes, 550, 800);
                assertFalse(panes.dualPane());
                assertFalse(panes.canToggle());
                assertFalse(panes.sidebarVisible());
                assertEquals("Brouillon à garder", chat.draft());
                assertEquals(3, composer.getSelectionStart());
                assertEquals(10, composer.getSelectionEnd());

                // Unselected inbox on tablet shows sidebar and empty detail placeholder.
                panes.select(null);
                size(panes, 1000, 720);
                assertTrue(panes.dualPane());
                assertFalse(panes.canToggle());
                assertTrue(panes.sidebarVisible());
                assertEquals(View.VISIBLE, panes.findViewById(R.id.discussion_detail).getVisibility());

                panes.retire();
            });
        } finally {
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
        }
    }

    public void testDiscussionIndexMergesPagesAndPreservesOrderOrPinsOffPageSelection() throws Exception {
        JSONObject first = new JSONObject().put("id", "tip-1").put("_lineage_root_id", "root-1").put("title", "Un");
        JSONObject second = new JSONObject().put("id", "tip-2").put("_lineage_root_id", "root-2").put("title", "Deux");
        JSONObject updatedSecond = new JSONObject().put("id", "tip-2b").put("_lineage_root_id", "root-2").put("title", "Deux modifié");
        JSONObject third = new JSONObject().put("id", "root-3").put("title", "Trois");

        JSONArray merged = DiscussionIndex.merge(
                new JSONArray().put(first).put(second),
                new JSONArray().put(updatedSecond).put(third).put(JSONObject.NULL));
        assertEquals(3, merged.length());
        assertEquals("root-1", ThreadIdentity.rootId(merged.getJSONObject(0)));
        assertEquals("root-2", ThreadIdentity.rootId(merged.getJSONObject(1)));
        assertEquals("Deux modifié", merged.getJSONObject(1).getString("title"));
        assertEquals("root-3", ThreadIdentity.rootId(merged.getJSONObject(2)));

        // Selecting an already-listed thread keeps gateway ordering and refreshes its metadata in place.
        JSONArray inPlace = DiscussionIndex.selectedFirst(merged, "root-2", "tip-2b", "Deux renommé", "Aperçu", 4);
        assertEquals(3, inPlace.length());
        assertEquals("root-1", ThreadIdentity.rootId(inPlace.getJSONObject(0)));
        assertEquals("root-2", ThreadIdentity.rootId(inPlace.getJSONObject(1)));
        assertEquals("Deux renommé", inPlace.getJSONObject(1).getString("title"));
        assertEquals("Aperçu", inPlace.getJSONObject(1).getString("preview"));

        // Passing the generic conversation fallback title preserves the existing thread's real title.
        String genericTitle = ThreadStrings.get(R.string.thread_conversation);
        assertFalse(genericTitle.isEmpty());
        JSONArray genericFallback = DiscussionIndex.selectedFirst(merged, "root-2", "tip-2b", genericTitle, "Aperçu", 4);
        assertEquals("Deux modifié", genericFallback.getJSONObject(1).getString("title"));

        // Selecting an off-page thread prepends it so the open discussion remains reachable.
        JSONArray pinned = DiscussionIndex.selectedFirst(merged, "root-9", "tip-9", "Hors page", "Début", 1);
        assertEquals(4, pinned.length());
        assertEquals("root-9", ThreadIdentity.rootId(pinned.getJSONObject(0)));
        assertEquals("Hors page", pinned.getJSONObject(0).getString("title"));

        // Null or empty inputs degrade safely.
        assertEquals(0, DiscussionIndex.merge(null, null).length());
        assertEquals(0, DiscussionIndex.selectedFirst(null, null, null, null, null, 0).length());
    }

    public void testInboxClearsStaleRowsBeforeReloadingNewOrder() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        String firstPage = new JSONObject().put("data", new JSONArray()
                .put(new JSONObject().put("id", "first-1").put("title", "Première"))).toString();
        String secondPage = new JSONObject().put("data", new JSONArray()
                .put(new JSONObject().put("id", "second-2").put("title", "Seconde"))
                .put(new JSONObject().put("id", "first-1").put("title", "Première"))).toString();
        try (Exchange server = new Exchange("200 OK", "", firstPage, secondPage);
             WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context, server.endpoint())) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            Activity activity = getInstrumentation().startActivitySync(launch);
            ThreadWorkspace.Host host = new ThreadWorkspace.Host() {
                @Override public void selected(String id, String title) {}
                @Override public JSONObject options(String sessionId) { return new JSONObject(); }
                @Override public void timing(String id, long firstTextMs, long elapsedMs, long outputTokens) {}
                @Override public void noticeThread(boolean sameThread) {}
                @Override public void pendingChanged() {}
            };
            ThreadWorkspace[] holder = new ThreadWorkspace[1];
            try {
                runTestOnUiThread(() -> {
                    holder[0] = new ThreadWorkspace(activity, new HermesApi(server.endpoint(), "test", org.llm4people.hermes.net.HermesApi.Limits.defaults()),
                            fixture.account, host, new VoiceDrafts(activity));
                    activity.setContentView(holder[0].view());
                    holder[0].inbox();
                });
                awaitInboxCount(holder[0], 1);
                int[] immediateCount = {-1};
                runTestOnUiThread(() -> {
                    holder[0].inbox();
                    // Returning to inbox must clear stale pre-refresh rows immediately so an async
                    // reorder ([second, first]) cannot shift a row underneath an in-flight tap.
                    immediateCount[0] = dataRowCount(findListView(holder[0].view()));
                });
                assertEquals(0, immediateCount[0]);
                awaitInboxCount(holder[0], 2);
                runTestOnUiThread(() -> {
                    ListView list = findListView(holder[0].view());
                    assertEquals("second-2", ((JSONObject) list.getAdapter().getItem(0)).optString("id"));
                    assertEquals("first-1", ((JSONObject) list.getAdapter().getItem(1)).optString("id"));
                });
            } finally {
                try {
                    runTestOnUiThread(() -> {
                        if (holder[0] != null) holder[0].close();
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        }
    }

    private void awaitInboxCount(ThreadWorkspace workspace, int expected) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 5000;
        int[] count = {-1};
        do {
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> count[0] = dataRowCount(findListView(workspace.view())));
            if (count[0] == expected) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(expected, count[0]);
    }

    private static int dataRowCount(ListView list) {
        return list == null || list.getAdapter() == null ? -1
                : Math.max(0, list.getAdapter().getCount() - list.getHeaderViewsCount() - list.getFooterViewsCount());
    }

    private static ListView findListView(View view) {
        if (view instanceof ListView list) return list;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView found = findListView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void size(View view, int widthDp, int heightDp) {
        int w = Ui.dp(view.getContext(), widthDp);
        int h = Ui.dp(view.getContext(), heightDp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }
}
