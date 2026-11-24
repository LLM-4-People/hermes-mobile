package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.test.InstrumentationTestCase;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.R;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.voice.VoiceDrafts;

/** Regression test for R5.3.1: dual-pane index dialogs must be dismissed on dismissDialogs/onBackground/close. */
@SuppressWarnings("deprecation")
public final class ThreadWorkspaceDualPaneDialogTest extends InstrumentationTestCase {
    public void testDismissDialogsOnBackgroundAndCloseDismissDualPaneIndexDialog() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            Activity activity = getInstrumentation().startActivitySync(
                    context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
            try {
                runTestOnUiThread(() -> {
                    ThreadWorkspace workspace = new ThreadWorkspace(
                            activity, null, fixture.account, null, new VoiceDrafts(activity));
                    try {
                        // Simulate dual-pane state where screen is a distinct discussion view while index hosts a shared-thread dialog.
                        workspace.screen = new ThreadViews(activity);
                        assertNotSame(workspace.index, workspace.screen);

                        AlertDialog d1 = workspace.index.show(new AlertDialog.Builder(activity)
                                .setTitle("Shared thread 1").setNegativeButton(R.string.ws_close, null));
                        assertTrue("Index dialog should be showing before dismissDialogs()", d1.isShowing());
                        workspace.dismissDialogs();
                        assertFalse("dismissDialogs() must dismiss index dialog even when screen != index", d1.isShowing());

                        AlertDialog d2 = workspace.index.show(new AlertDialog.Builder(activity)
                                .setTitle("Shared thread 2").setNegativeButton(R.string.ws_close, null));
                        assertTrue("Index dialog should be showing before onBackground()", d2.isShowing());
                        workspace.onBackground();
                        assertFalse("onBackground() must dismiss index dialog even when screen != index", d2.isShowing());

                        AlertDialog d3 = workspace.index.show(new AlertDialog.Builder(activity)
                                .setTitle("Shared thread 3").setNegativeButton(R.string.ws_close, null));
                        assertTrue("Index dialog should be showing before close()", d3.isShowing());
                        workspace.close();
                        assertFalse("close() must dismiss index dialog even when screen != index", d3.isShowing());
                    } finally {
                        workspace.close();
                    }
                });
            } finally {
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }
}
