package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import org.llm4people.hermes.R;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.voice.VoiceDrafts;
import java.util.ArrayList;

@SuppressWarnings("deprecation")
public final class ThreadInstrumentRecoveryTest extends InstrumentationTestCase {
    public void testRecoveryRequiresConfirmationForTheObservedThreadRecord() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        String slot = "thread.instrument.v1.local-root";
        ThreadWorkspace[] workspace = new ThreadWorkspace[1];
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app)) {
            profile.account.write(slot, "{broken");
            profile.account.write("thread:local-root", "brouillon privé");
            ThreadInstruments store = new ThreadInstruments(profile.account, "local-root");
            runTestOnUiThread(() -> {
                VoiceDrafts voice = new VoiceDrafts(activity);
                workspace[0] = new ThreadWorkspace(activity, null, profile.account, null, voice);
                ThreadViews screen = new ThreadViews(activity, voice);
                workspace[0].screen = screen;
                screen.chat("brouillon privé", text -> {}, () -> fail("Unexpected send"), () -> {}, () -> {});
                screen.instruments = new ThreadInstrumentPanel(workspace[0], store, null);
                activity.setContentView(screen.root);
            });
            String repairLabel = activity.getString(R.string.instrument_repair);
            String confirmLabel = activity.getString(R.string.instrument_repair_confirm);
            String cancelLabel = activity.getString(R.string.instrument_cancel);
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> tap(repairLabel));
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                View confirm = button(confirmLabel);
                WindowManager.LayoutParams params = (WindowManager.LayoutParams)confirm.getRootView().getLayoutParams();
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                tap(cancelLabel);
            });
            getInstrumentation().waitForIdleSync();
            assertEquals("{broken", profile.account.read(slot));

            runTestOnUiThread(() -> tap(repairLabel));
            getInstrumentation().waitForIdleSync();
            // A second writer has changed the record since the dialog was displayed.
            profile.account.write(slot, "{different");
            runTestOnUiThread(() -> tap(confirmLabel));
            getInstrumentation().waitForIdleSync();
            assertEquals("{different", profile.account.read(slot));

            runTestOnUiThread(() -> tap(repairLabel));
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> tap(confirmLabel));
            getInstrumentation().waitForIdleSync();
            assertNull(store.state().current);
            assertFalse(store.state().canUndo);
            assertEquals("brouillon privé", profile.account.read("thread:local-root"));
            runTestOnUiThread(() -> assertEquals("brouillon privé", workspace[0].screen.draft()));
        } finally {
            runTestOnUiThread(() -> {
                if (workspace[0] != null) workspace[0].close();
                activity.finish();
            });
        }
    }

    private static void tap(String label) { assertTrue(button(label).performClick()); }

    private static Button button(String label) {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            ArrayList<View> matches = new ArrayList<>();
            root.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT);
            for (View match : matches) {
                if (match instanceof Button button && button.isShown() && label.contentEquals(button.getText())) return button;
            }
        }
        throw new AssertionError("Missing control " + label);
    }
}
