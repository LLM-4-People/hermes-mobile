package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.test.InstrumentationTestCase;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.voice.VoiceDrafts;

@SuppressWarnings("deprecation")
public final class NearbyConsentTest extends InstrumentationTestCase {
    public void testReplacingAManualAreaWithOneDeviceFixRetiresTheOldConsentBeforeAcquisition() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        String preference = "nearby-future-sends-v1";
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            Activity activity = getInstrumentation().startActivitySync(
                    context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
            try {
                fixture.account.write(preference, fixture.account.selectionBinding(preference, "profile"));
                runTestOnUiThread(() -> {
                    ThreadWorkspace workspace = new ThreadWorkspace(activity, null, fixture.account, null, new VoiceDrafts(activity));
                    try {
                        workspace.journal = new ThreadJournal(fixture.account, "first");
                        workspace.journal.draft("Un déjeuner ?");
                        workspace.journal.nearbyDraft("manual", "Rosemont");
                        workspace.nearby.restore();
                        assertEquals("Rosemont", workspace.nearby.frozen().optString("area"));
                        // No permission result or successful location is needed to commit this opt-out.
                        assertTrue(workspace.nearby.prepareDeviceChoice(false));
                        assertNull(fixture.account.read(preference));
                        assertNull(workspace.nearby.frozen());
                        assertEquals("confirm", workspace.journal.nearbyDraft().optString("mode"));
                        workspace.nearby.restore();
                        assertNull(workspace.nearby.frozen());
                        assertEquals("Un déjeuner ?", workspace.journal.draft());
                    } finally { workspace.close(); }
                });
            } finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
        }
    }

    public void testReconcilePermissionStateAndApplyAppCustomization() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            Activity activity = getInstrumentation().startActivitySync(
                    context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
            try {
                runTestOnUiThread(() -> {
                    ThreadWorkspace workspace = new ThreadWorkspace(activity, null, fixture.account, null, new VoiceDrafts(activity));
                    try {
                        // Positive + negative NearbyConsent.reconcilePermissionState assertions.
                        workspace.nearby.setFailureForTest("Location blocked");
                        workspace.nearby.reconcilePermissionState(false);
                        assertEquals("Denied state must preserve failure banner",
                                "Location blocked", workspace.nearby.failureForTest());
                        workspace.nearby.reconcilePermissionState(true);
                        assertEquals("Granted state must clear stale failure banner",
                                "", workspace.nearby.failureForTest());
                        // Delegated no-arg entry point remains safe with no active failure.
                        workspace.reconcileNearbyPermission();
                        assertEquals("", workspace.nearby.failureForTest());

                        // ThreadStarters attaches its header view to ListView; mount list on the Activity and
                        // synchronously measure+layout in the same UI frame (avoiding any async ProfileGate
                        // setContentView race) so ListView attaches its header and content.isShown() is true.
                        String[] staged = {""};
                        android.widget.ListView list = new android.widget.ListView(activity);
                        ThreadStarters starters = new ThreadStarters(list, text -> staged[0] = text);
                        list.setAdapter(new android.widget.ArrayAdapter<>(
                                activity, android.R.layout.simple_list_item_1));
                        activity.setContentView(list);
                        starters.show(true);

                        org.llm4people.hermes.customization.AppCustomization custom =
                                org.llm4people.hermes.customization.AppCustomization.defaults()
                                        .withCompanionGreeting("Bonjour équipe")
                                        .withComposerShortcuts(java.util.List.of(
                                                new org.llm4people.hermes.customization.AppCustomization.ComposerShortcut(
                                                        "brief", "Brief matin", "Fais le point du matin.")));
                        starters.applyCustomization(custom);
                        workspace.applyAppCustomization(custom);
                        int w = org.llm4people.hermes.Ui.dp(activity, 360);
                        int h = org.llm4people.hermes.Ui.dp(activity, 780);
                        // First layout fires ThreadStarters' OnLayoutChangeListener -> alignEmptyList() ->
                        // ListView.setStackFromBottom(true) -> resetList() before View.layout() clears
                        // PFLAG_FORCE_LAYOUT; forceLayout() + second layout mounts the header synchronously.
                        measureAndLayout(list, w, h);
                        list.forceLayout();
                        measureAndLayout(list, w, h);

                        android.widget.Button shortcutChip = findButtonByText(list, "Brief matin");
                        assertNotNull("Expected custom composer shortcut chip", shortcutChip);
                        assertNotNull("Expected custom greeting heading", findTextViewByText(list, "Bonjour équipe"));

                        // Editable Draft Rule: tapping shortcut chip stages draft text directly.
                        shortcutChip.performClick();
                        assertEquals("Fais le point du matin.", staged[0]);

                        // Resetting customization restores default heading and hides custom shortcuts.
                        starters.applyCustomization(null);
                        workspace.applyAppCustomization(null);
                        assertNull("Custom shortcut chip should disappear when customization resets",
                                findButtonByText(list, "Brief matin"));
                        assertNotNull("Default starter heading should be restored",
                                findTextViewByText(list, activity.getString(org.llm4people.hermes.R.string.starter_heading)));
                    } finally { workspace.close(); }
                });
            } finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
        }
    }

    private static void measureAndLayout(android.view.View root, int widthPx, int heightPx) {
        root.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY));
        root.layout(0, 0, widthPx, heightPx);
    }

    private static android.widget.Button findButtonByText(android.view.View root, String text) {
        if (root instanceof android.widget.Button b && b.getVisibility() == android.view.View.VISIBLE
                && text.equals(String.valueOf(b.getText()))) return b;
        if (root instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.widget.Button found = findButtonByText(vg.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static android.widget.TextView findTextViewByText(android.view.View root, String text) {
        if (root instanceof android.widget.TextView tv && tv.getVisibility() == android.view.View.VISIBLE
                && text.equals(String.valueOf(tv.getText()))) return tv;
        if (root instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.widget.TextView found = findTextViewByText(vg.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
