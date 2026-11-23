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
}
