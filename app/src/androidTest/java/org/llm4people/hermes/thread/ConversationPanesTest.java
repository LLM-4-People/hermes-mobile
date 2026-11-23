package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.EditText;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

@SuppressWarnings("deprecation")
public final class ConversationPanesTest extends InstrumentationTestCase {
    public void testPhoneTabletAndSplitResizesPreserveComposerAndCoordinateWithInstrumentPane() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                ThreadViews index = new ThreadViews(activity);
                index.inbox(() -> {}, () -> {}, row -> {}, () -> {});
                ThreadViews chat = new ThreadViews(activity);
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
            runTestOnUiThread(activity::finish);
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

        // Selecting an off-page thread prepends it so the open discussion remains reachable.
        JSONArray pinned = DiscussionIndex.selectedFirst(merged, "root-9", "tip-9", "Hors page", "Début", 1);
        assertEquals(4, pinned.length());
        assertEquals("root-9", ThreadIdentity.rootId(pinned.getJSONObject(0)));
        assertEquals("Hors page", pinned.getJSONObject(0).getString("title"));

        // Null or empty inputs degrade safely.
        assertEquals(0, DiscussionIndex.merge(null, null).length());
        assertEquals(0, DiscussionIndex.selectedFirst(null, null, null, null, null, 0).length());
    }

    private static void size(View view, int widthDp, int heightDp) {
        int w = Ui.dp(view.getContext(), widthDp);
        int h = Ui.dp(view.getContext(), heightDp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }
}
