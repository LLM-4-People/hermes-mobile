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
            runTestOnUiThread(() -> {
                ThreadWorkspace workspace = new ThreadWorkspace(activity, null, fixture.account, null, new VoiceDrafts(activity));
                try {
                    // Stale failure reconciles safely whether permission is granted or denied (no crash).
                    workspace.reconcileNearbyPermission();

                    // ThreadStarters.applyCustomization updates heading, stages shortcut drafts (Editable Draft Rule),
                    // and resets cleanly to default heading on null/empty customization.
                    android.widget.ListView list = new android.widget.ListView(activity);
                    String[] staged = {""};
                    ThreadStarters starters = new ThreadStarters(list, text -> staged[0] = text);
                    starters.show(true);

                    org.llm4people.hermes.customization.AppCustomization custom =
                            org.llm4people.hermes.customization.AppCustomization.defaults()
                                    .withCompanionGreeting("Bonjour équipe")
                                    .withComposerShortcuts(java.util.List.of(
                                            new org.llm4people.hermes.customization.AppCustomization.ComposerShortcut(
                                                    "brief", "Brief matin", "Fais le point du matin.")));
                    starters.applyCustomization(custom);
                    workspace.applyAppCustomization(custom);

                    android.widget.Button shortcutChip = findButtonByText(list, "Brief matin");
                    assertNotNull("Expected custom composer shortcut chip", shortcutChip);
                    assertNotNull("Expected custom greeting heading", findTextViewByText(list, "Bonjour équipe"));

                    // Resetting customization restores default heading and hides custom shortcuts.
                    starters.applyCustomization(null);
                    assertNull("Custom shortcut chip should disappear when customization resets",
                            findButtonByText(list, "Brief matin"));
                    assertNotNull("Default starter heading should be restored",
                            findTextViewByText(list, activity.getString(org.llm4people.hermes.R.string.starter_heading)));
                    assertEquals("", staged[0]);
                } finally { workspace.close(); }
            });
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
