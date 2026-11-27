package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import org.json.JSONObject;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class InfiniteChatStressAndroidTest extends InstrumentationTestCase {
    public void testAutoPrefetchJumpPillUnreadCounterCellCacheAndEncryptedTailIsolation() throws Throwable {
        Context context = getInstrumentation().getTargetContext();

        // 1. Encrypted Account vault tail snapshot isolation across two unlocked profiles.
        try (WorkspaceAccountFixture first = new WorkspaceAccountFixture(context);
             WorkspaceAccountFixture second = new WorkspaceAccountFixture(context)) {
            ThreadTailCache.VaultBacking vaultA = ThreadTailCache.fromAccount(first.account);
            ThreadTailCache.VaultBacking vaultB = ThreadTailCache.fromAccount(second.account);

            List<String> rows = new ArrayList<>();
            for (int i = 1; i <= 15; i++) {
                JSONObject msg = new JSONObject()
                        .put("id", i)
                        .put("session_id", "s-alpha")
                        .put("role", i % 2 == 0 ? "assistant" : "user")
                        .put("content", "Message chiffré #" + i);
                rows.add(msg.toString());
            }
            ThreadTailCache.Entry entryA = new ThreadTailCache.Entry(
                    "s-alpha", "s-alpha", "Discussion Alpha", rows, true, 1L, 15L, 15, 123456L);
            assertTrue(ThreadTailCache.saveSnapshot(vaultA, entryA));
            ThreadTailCache.Entry loadedA = ThreadTailCache.loadSnapshot(vaultA, "s-alpha");
            assertNotNull(loadedA);
            assertEquals(15, loadedA.encodedRows().size());
            assertNull("Profile B must never read Profile A's encrypted tail snapshot",
                    ThreadTailCache.loadSnapshot(vaultB, "s-alpha"));

            ThreadTailCache.wipeSnapshot(vaultA, "s-alpha");
            assertNull(ThreadTailCache.loadSnapshot(vaultA, "s-alpha"));
        }

        // 2. TranscriptCellCache caches real TranscriptPage + MessageText spans cleanly.
        TranscriptCellCache cellCache = new TranscriptCellCache();
        String md = "## Titre\nUn paragraphe **important** avec `code`.\n\n```java\nint x = 1;\n```";
        TranscriptCellCache.Key key = TranscriptCellCache.keyFor("s1#10", md, "", true, false, TranscriptPage.PAGE_CHARS);
        AtomicInteger builds = new AtomicInteger();
        TranscriptCellCache.Rendered firstRender = cellCache.getOrCompute(key, () -> {
            builds.incrementAndGet();
            TranscriptPage page = new TranscriptPage(md, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);
            return new TranscriptCellCache.Rendered(page, MessageText.styled(context, page), md, "");
        });
        TranscriptCellCache.Rendered cachedRender = cellCache.getOrCompute(key, () -> {
            builds.incrementAndGet();
            return null;
        });
        assertSame(firstRender, cachedRender);
        assertEquals(1, builds.get());
        assertTrue(firstRender.text().length() > 0);
        cellCache.clear();
        assertEquals(0, cellCache.size());

        // 3. Live Android ListView + InfiniteScrollController auto-prefetch + accessible >=48dp Jump Pill.
        Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        try {
            int minTouchPx = Ui.dp(activity, 48);
            AtomicInteger olderCalls = new AtomicInteger();
            AtomicBoolean snapped = new AtomicBoolean();
            AtomicBoolean hasOlder = new AtomicBoolean(true);
            AtomicBoolean hasNewer = new AtomicBoolean(false);

            runTestOnUiThread(() -> {
                List<String> items = new ArrayList<>();
                for (int i = 0; i < 60; i++) items.add("Msg " + i);
                ListView list = new ListView(activity);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, items);
                list.setAdapter(adapter);

                InfiniteScrollController controller = new InfiniteScrollController(new InfiniteScrollController.Callbacks() {
                    @Override public boolean hasOlder() { return hasOlder.get(); }
                    @Override public boolean hasNewer() { return hasNewer.get(); }
                    @Override public boolean isLoadingOlder() { return false; }
                    @Override public boolean isLoadingNewer() { return false; }
                    @Override public void loadOlder() { olderCalls.incrementAndGet(); }
                    @Override public void loadNewer() {}
                }, InfiniteScrollController.DEFAULT_TOP_THRESHOLD, InfiniteScrollController.DEFAULT_BOTTOM_THRESHOLD, 0, 0);
                Button pill = controller.createJumpPill(activity, () -> snapped.set(true));
                assertEquals(View.GONE, pill.getVisibility());
                assertTrue("Jump pill must be >= 48dp tall", pill.getMinimumHeight() >= minTouchPx);
                assertTrue("Jump pill must be >= 48dp wide", pill.getMinimumWidth() >= minTouchPx);

                // Simulate scrolling near top -> auto-prefetch older + pill becomes visible.
                controller.onScroll(list, 2, 10, 60);
                assertEquals(1, olderCalls.get());
                assertEquals(View.VISIBLE, pill.getVisibility());
                assertFalse(controller.isAtTail());

                // Simulate live incoming messages while scrolled up -> unread badge updates cleanly.
                controller.onNewMessagesArrived(1);
                assertEquals(1, controller.unreadCount());
                assertEquals(activity.getString(R.string.infinite_jump_latest_one), pill.getText().toString());
                controller.onNewMessagesArrived(2);
                assertEquals(3, controller.unreadCount());
                assertEquals(activity.getString(R.string.infinite_jump_latest_unread, 3), pill.getText().toString());

                // Tapping pill snaps to latest, resets unread count, and hides pill.
                pill.performClick();
                assertTrue(snapped.get());
                assertEquals(0, controller.unreadCount());
                assertEquals(View.GONE, pill.getVisibility());
                assertTrue(controller.isAtTail());
            });
            getInstrumentation().waitForIdleSync();
        } finally {
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
        }
    }
}
