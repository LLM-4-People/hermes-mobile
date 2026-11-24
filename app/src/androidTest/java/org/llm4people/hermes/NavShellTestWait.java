package org.llm4people.hermes;

import android.app.Activity;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;

import junit.framework.Assert;

import java.util.ArrayList;

/** Shared reactive wait for cold-emulator toolbar overflow attachment across nav shell tests. */
@SuppressWarnings("deprecation")
final class NavShellTestWait {
    private static final long TIMEOUT_MS = 5_000L;
    private static final long POLL_INTERVAL_MS = 50L;

    private NavShellTestWait() {}

    static void waitUntilOverflowAttached(InstrumentationTestCase test, Activity activity, WorkspaceShell shell)
            throws Throwable {
        test.getInstrumentation().waitForIdleSync();
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        boolean attached = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] ready = new boolean[1];
            test.runTestOnUiThread(() -> {
                ArrayList<View> more = new ArrayList<>();
                shell.root.findViewsWithText(more, activity.getString(R.string.ws_more_actions),
                        View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                ready[0] = more.size() == 1
                        && more.get(0).getVisibility() == View.VISIBLE
                        && more.get(0).isAttachedToWindow();
            });
            if (ready[0]) {
                attached = true;
                break;
            }
            SystemClock.sleep(POLL_INTERVAL_MS);
            test.getInstrumentation().waitForIdleSync();
        }
        Assert.assertTrue("More overflow button did not attach visibly within 5s", attached);
    }
}
