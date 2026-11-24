package org.llm4people.hermes;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;

import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumentation regression test for R5.3.4 / R6.8.5:
 * Verifies {@link WorkspaceDialogs#showSecurity} sets FLAG_SECURE on both the parent security
 * dialog and its nested "Change lock mode" child dialog, and that closing the returned
 * {@link AutoCloseable} handle synchronously dismisses both windows and runs {@code onDismissed}.
 */
@SuppressWarnings("deprecation")
public final class WorkspaceSecurityDialogTest extends InstrumentationTestCase {

    public void testShowSecurityClosesParentAndChildDialogSynchronouslyAndEnforcesFlagSecure() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        AtomicReference<AutoCloseable> handleRef = new AtomicReference<>();
        AtomicReference<ProfileGate> gateRef = new AtomicReference<>();
        AtomicBoolean dismissed = new AtomicBoolean(false);
        String parentTitle = context.getString(R.string.security_dialog_title);
        String changeModeLabel = context.getString(R.string.security_lock_mode_change);
        String childSaveLabel = context.getString(R.string.security_lock_mode_save);

        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            runTestOnUiThread(() -> {
                ProfileGate gate = new ProfileGate(activity);
                gateRef.set(gate);
                handleRef.set(WorkspaceDialogs.showSecurity(
                        activity, fixture.account, gate, () -> {}, () -> {}, () -> dismissed.set(true)));
            });
            getInstrumentation().waitForIdleSync();

            View parentWindow = waitForWindowContaining(parentTitle, 3000L);
            assertNotNull("Expected security parent dialog window", parentWindow);
            runTestOnUiThread(() -> assertWindowSecure(parentWindow));

            // Tap "Change lock mode" to open the nested child dialog.
            runTestOnUiThread(() -> {
                Button changeBtn = findButton(parentWindow, changeModeLabel);
                assertNotNull("Expected Change lock mode button inside security dialog", changeBtn);
                assertTrue(changeBtn.performClick());
            });
            getInstrumentation().waitForIdleSync();

            View childWindow = waitForWindowContaining(childSaveLabel, 3000L);
            assertNotNull("Expected Change lock mode child dialog window", childWindow);
            assertNotSame(parentWindow, childWindow);
            runTestOnUiThread(() -> assertWindowSecure(childWindow));

            // Synchronously close handle (as MainActivity.dismissOverlays() does on lock/destroy).
            runTestOnUiThread(() -> {
                AutoCloseable handle = handleRef.getAndSet(null);
                assertNotNull(handle);
                try {
                    handle.close();
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
            getInstrumentation().waitForIdleSync();

            assertTrue("Expected parent dialog window to dismiss",
                    waitUntilWindowGone(parentTitle, 3000L));
            assertTrue("Expected child dialog window to dismiss",
                    waitUntilWindowGone(childSaveLabel, 3000L));
            assertTrue("Expected onDismissed callback to execute",
                    waitUntil(dismissed::get, 3000L));
        } finally {
            runTestOnUiThread(() -> {
                AutoCloseable handle = handleRef.getAndSet(null);
                if (handle != null) {
                    try { handle.close(); } catch (Exception ignored) {}
                }
                ProfileGate gate = gateRef.getAndSet(null);
                if (gate != null) gate.close();
                activity.finish();
            });
        }
    }

    private static void assertWindowSecure(View root) {
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        assertTrue("Expected WindowManager.LayoutParams on dialog root", lp instanceof WindowManager.LayoutParams);
        int flags = ((WindowManager.LayoutParams) lp).flags;
        assertTrue("Expected FLAG_SECURE on security dialog window",
                (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    private View waitForWindowContaining(String text, long timeoutMs) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            View found = findWindowContainingOnUiThread(text);
            if (found != null) return found;
            SystemClock.sleep(40L);
        }
        return findWindowContainingOnUiThread(text);
    }

    private boolean waitUntilWindowGone(String text, long timeoutMs) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findWindowContainingOnUiThread(text) == null) return true;
            SystemClock.sleep(40L);
        }
        return findWindowContainingOnUiThread(text) == null;
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            SystemClock.sleep(40L);
        }
        return condition.getAsBoolean();
    }

    private View findWindowContainingOnUiThread(String text) throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return null;
        AtomicReference<View> found = new AtomicReference<>();
        runTestOnUiThread(() -> {
            List<View> windows = WindowInspector.getGlobalWindowViews();
            for (int i = windows.size() - 1; i >= 0; i--) {
                View root = windows.get(i);
                if (root != null && root.isShown() && hasText(root, text)) {
                    found.set(root);
                    return;
                }
            }
        });
        return found.get();
    }

    private static boolean hasText(View root, String text) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : matches) {
            if (v.isShown()) return true;
        }
        return false;
    }

    private static Button findButton(View root, String text) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : matches) {
            if (v instanceof Button b && b.isShown()) return b;
        }
        return null;
    }
}
