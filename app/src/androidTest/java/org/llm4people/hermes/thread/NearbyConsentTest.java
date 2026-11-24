package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.test.InstrumentationTestCase;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.voice.VoiceDrafts;

@SuppressWarnings("deprecation")
public final class NearbyConsentTest extends InstrumentationTestCase {
    public void testReplacingAManualAreaWithOneDeviceFixRetiresTheOldConsentBeforeAcquisition() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        String preference = "nearby-future-sends-v1";
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
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
        } finally { runTestOnUiThread(activity::finish); }
    }

    public void testReconcilePermissionStateAndApplyAppCustomization() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            ThreadWorkspace[] workspaceRef = new ThreadWorkspace[1];
            android.widget.ListView[] listRef = new android.widget.ListView[1];
            ThreadStarters[] startersRef = new ThreadStarters[1];
            String[] staged = {""};
            try {
                runTestOnUiThread(() -> {
                    ThreadWorkspace workspace = new ThreadWorkspace(activity, null, fixture.account, null, new VoiceDrafts(activity));
                    workspaceRef[0] = workspace;
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

                    // ThreadStarters attaches its header view to ListView; ListView only mounts header views
                    // as children after an adapter is bound and a layout pass runs.
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
                    listRef[0] = list;
                    startersRef[0] = starters;
                });

                // Wait for Android layout pass to attach the ListView header and render the custom chip.
                android.widget.Button[] shortcutChipRef = new android.widget.Button[1];
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    getInstrumentation().waitForIdleSync();
                    runTestOnUiThread(() -> shortcutChipRef[0] = findButtonByText(listRef[0], "Brief matin"));
                    if (shortcutChipRef[0] != null) break;
                    android.os.SystemClock.sleep(50);
                }

                runTestOnUiThread(() -> {
                    android.widget.Button shortcutChip = shortcutChipRef[0];
                    assertNotNull("Expected custom composer shortcut chip", shortcutChip);
                    assertNotNull("Expected custom greeting heading", findTextViewByText(listRef[0], "Bonjour équipe"));

                    // Editable Draft Rule: tapping shortcut chip stages draft text directly.
                    shortcutChip.performClick();
                    assertEquals("Fais le point du matin.", staged[0]);

                    // Resetting customization restores default heading and hides custom shortcuts.
                    startersRef[0].applyCustomization(null);
                    assertNull("Custom shortcut chip should disappear when customization resets",
                            findButtonByText(listRef[0], "Brief matin"));
                    assertNotNull("Default starter heading should be restored",
                            findTextViewByText(listRef[0], activity.getString(org.llm4people.hermes.R.string.starter_heading)));
                });
            } finally {
                runTestOnUiThread(() -> {
                    if (workspaceRef[0] != null) workspaceRef[0].close();
                });
            }
        } finally { runTestOnUiThread(activity::finish); }
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
