package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import org.json.JSONObject;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

@SuppressWarnings("deprecation")
public final class PageRecoveryTest extends InstrumentationTestCase {
    private static final String KEY = "workspace.pages.v1";
    private static final String ID = "page/revue";

    private static PageLayouts.Damaged damage(Account account) {
        try { PageLayouts.titles(account, true); }
        catch (PageLayouts.Damaged error) { return error; }
        throw new AssertionError("Expected an unreadable catalog");
    }

    private static void refused(PageLayouts.Damaged observation) {
        try { observation.recover(); fail("Stale or locked catalog recovery succeeded"); }
        catch (IllegalStateException expected) { }
    }

    public void testCorruptEnvelopeRecoveryRequiresSameUnlockedRecord() {
        Context app = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture first = new WorkspaceAccountFixture(app);
             WorkspaceAccountFixture other = new WorkspaceAccountFixture(app);
             Account concurrent = first.reopen()) {
            new LayoutHistory(first.account, ID).replace(LiveLayoutContractTest.REVIEW);
            SharedPreferences vault = app.getSharedPreferences("workspace.test." + first.account.id, Context.MODE_PRIVATE);
            String slot = vault.getAll().keySet().iterator().next();
            byte[] bytes = Base64.decode(vault.getString(slot, ""), Base64.NO_WRAP);
            first.account.write("draft", "mes notes");
            new LayoutHistory(first.account, "home").replace("atelier");
            new LayoutHistory(other.account, ID).replace("autre profil");
            bytes[bytes.length - 1] ^= 1;
            assertTrue(vault.edit().putString(slot, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit());
            PageLayouts.Damaged locked = damage(first.account);
            PageLayouts.Damaged stale = damage(concurrent);
            assertFalse(stale.hasReadablePages());
            first.account.close();
            refused(locked);
            bytes[bytes.length - 2] ^= 1;
            String second = Base64.encodeToString(bytes, Base64.NO_WRAP);
            assertTrue(vault.edit().putString(slot, second).commit());
            refused(stale);
            assertEquals(second, vault.getString(slot, ""));
            PageLayouts.Damaged current = damage(concurrent);
            current.recover();
            assertFalse(vault.contains(slot));
            new LayoutHistory(concurrent, ID).replace(LiveLayoutContractTest.REVIEW);
            refused(current);
            assertEquals(LiveLayoutContractTest.REVIEW, new LayoutHistory(concurrent, ID).current());
            assertEquals("atelier", new LayoutHistory(concurrent, "home").current());
            assertEquals("mes notes", concurrent.read("draft"));
            assertEquals("autre profil", new LayoutHistory(other.account, ID).current());
        }
    }

    public void testRecoverReadableEntriesAndUndoFromAuthenticatedCatalog() throws Exception {
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(getInstrumentation().getTargetContext())) {
            JSONObject rows = new JSONObject()
                    .put(ID, new JSONObject().put("current", "révision B").put("previous", "révision A"))
                    .put("page/undo", new JSONObject().put("current", false).put("previous", "récupérable"))
                    .put("page/keep", new JSONObject().put("current", "actuel").put("previous", false))
                    .put("page/lost", 123);
            fixture.account.write(KEY, rows.toString());
            PageLayouts.Damaged saved = damage(fixture.account);
            assertTrue(saved.hasReadablePages());
            assertEquals(rows.toString(), fixture.account.read(KEY));
            saved.recover();
            JSONObject repaired = PageLayouts.read(fixture.account);
            assertEquals(3, repaired.length());
            assertEquals(rows.getJSONObject(ID).toString(), repaired.getJSONObject(ID).toString());
            assertEquals("actuel", new LayoutHistory(fixture.account, "page/keep").current());
            assertFalse(new LayoutHistory(fixture.account, "page/keep").canUndo());
            LayoutHistory history = new LayoutHistory(fixture.account, "page/undo");
            assertTrue(history.current().isEmpty());
            history.undo();
            assertEquals("récupérable", history.current());
        }
    }

    public void testPrivateConfirmationCanCancelBeforeRepairing() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(app)) {
            fixture.account.write(KEY, "{broken");
            LayoutHistory page = new LayoutHistory(fixture.account, ID);
            boolean[] changed = {false};
            ArtifactDesk.Host host = new WorkspaceTestHost() {
                @Override public void onLayoutChanged() { changed[0] = true; }
            };
            runTestOnUiThread(() -> {
                View gallery = ArtifactDesk.pages(activity, fixture.account, host);
                ArrayList<View> repairButtons = new ArrayList<>();
                gallery.findViewsWithText(repairButtons, activity.getString(org.llm4people.hermes.R.string.ws_pages_repair_action), View.FIND_VIEWS_WITH_TEXT);
                assertEquals(1, repairButtons.size());
                assertTrue(repairButtons.get(0).isClickable());
                AlertDialog cancel = SurfaceRenderer.controls(activity, page, host);
                assertTrue((cancel.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            getInstrumentation().waitForIdleSync();
            assertEquals("{broken", fixture.account.read(KEY));
            assertFalse(changed[0]);
            runTestOnUiThread(() -> {
                AlertDialog accept = SurfaceRenderer.controls(activity, page, host);
                assertEquals(activity.getString(org.llm4people.hermes.R.string.ws_repair), accept.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                accept.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            getInstrumentation().waitForIdleSync();
            assertTrue(changed[0]);
            assertTrue(PageLayouts.titles(fixture.account, true).isEmpty());
        } finally {
            try { runTestOnUiThread(() -> { ArtifactDesk.dismiss(activity); }); }
            finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
        }
    }
}
