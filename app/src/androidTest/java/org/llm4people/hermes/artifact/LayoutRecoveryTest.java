package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;
import android.util.Base64;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

@SuppressWarnings("deprecation")
public final class LayoutRecoveryTest extends InstrumentationTestCase {
    public void testRecoveryDialogRequiresConfirmationBeforeChangingLayout() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(intent);
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(app)) {
            LayoutHistory history = new LayoutHistory(fixture.account, "home");
            fixture.account.write("workspace.v1.home", "{invalid history");
            boolean[] changed = {false};
            ArtifactDesk.Host host = new WorkspaceTestHost() {
                @Override public void onAction(String id) { fail("Unexpected navigation"); }
                @Override public void onLayoutChanged() { changed[0] = true; }
            };
            runTestOnUiThread(() -> {
                AlertDialog cancel = SurfaceRenderer.controls(activity, history, host);
                cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            getInstrumentation().waitForIdleSync();
            assertFalse(changed[0]);
            assertEquals("{invalid history", fixture.account.read("workspace.v1.home"));
            runTestOnUiThread(() -> {
                AlertDialog confirm = SurfaceRenderer.controls(activity, history, host);
                assertEquals("Rétablir", confirm.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            getInstrumentation().waitForIdleSync();
            assertTrue(changed[0]);
            assertEquals("", history.current());
            assertFalse(history.canUndo());
        } finally {
            runTestOnUiThread(() -> { ArtifactDesk.dismiss(activity); activity.finish(); });
        }
    }

    public void testNativeRecoveryNeverDiscardsNamedPageCatalog() {
        Context app = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(app)) {
            fixture.account.write("workspace.pages.v1", "{unreadable pages");
            LayoutHistory page = new LayoutHistory(fixture.account, "page/review");
            try { page.resetUnreadable(); fail("Discarded a shared catalog"); }
            catch (IllegalStateException expected) { }
            LayoutHistory home = new LayoutHistory(fixture.account, "home");
            fixture.account.write("workspace.v1.home", "[]");
            home.resetUnreadable();
            assertEquals("", home.current());
            assertEquals("{unreadable pages", fixture.account.read("workspace.pages.v1"));
        }
    }

    public void testConfirmedRecoveryRemovesOnlyUnreadableNativeScreenAfterUnlock() {
        Context app = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture first = new WorkspaceAccountFixture(app);
             WorkspaceAccountFixture other = new WorkspaceAccountFixture(app)) {
            LayoutHistory home = new LayoutHistory(first.account, "home");
            home.replace("original");
            SharedPreferences vault = app.getSharedPreferences("workspace.test." + first.account.id, Context.MODE_PRIVATE);
            String slot = vault.getAll().keySet().iterator().next();
            byte[] invalid = Base64.decode(vault.getString(slot, ""), Base64.NO_WRAP);
            invalid[invalid.length - 1] ^= 1;
            String damaged = Base64.encodeToString(invalid, Base64.NO_WRAP);
            new LayoutHistory(first.account, "stats").replace("statistics");
            new LayoutHistory(other.account, "home").replace("other profile");
            new LayoutHistory(first.account, "page/review").replace("named page");
            first.account.write("draft", "private text");
            assertTrue(vault.edit().putString(slot, damaged).commit());

            try { home.current(); fail("Unreadable layout accepted"); } catch (LayoutHistory.Unreadable expected) { }
            try { home.replace("new"); fail("Proposal replaced damaged history"); } catch (LayoutHistory.Unreadable expected) { }
            try { home.undo(); fail("Undo accepted damaged history"); } catch (LayoutHistory.Unreadable expected) { }
            assertEquals(damaged, vault.getString(slot, ""));
            first.account.close();
            try { home.resetUnreadable(); fail("Reset while locked"); } catch (IllegalStateException expected) { }
            assertEquals(damaged, vault.getString(slot, ""));

            try (Account reopened = first.reopen(); Account secondHandle = first.reopen()) {
                LayoutHistory restored = new LayoutHistory(reopened, "home");
                LayoutHistory staleDialog = new LayoutHistory(secondHandle, "home");
                try { staleDialog.canUndo(); fail("Corruption vanished on unlock"); }
                catch (LayoutHistory.Unreadable expected) { }
                restored.resetUnreadable();
                assertFalse(vault.contains(slot));
                assertEquals("", restored.current());
                assertFalse(restored.canUndo());
                assertEquals("statistics", new LayoutHistory(reopened, "stats").current());
                assertEquals("named page", new LayoutHistory(reopened, "page/review").current());
                assertEquals("private text", reopened.read("draft"));
                assertEquals("other profile", new LayoutHistory(other.account, "home").current());
                restored.replace("replacement");
                try { staleDialog.resetUnreadable(); fail("Discarded a readable layout"); }
                catch (IllegalStateException expected) { }
                assertEquals("replacement", restored.current());
                restored.replace("");
                assertTrue(restored.canUndo());
                restored.undo();
                assertEquals("replacement", restored.current());
                assertFalse(restored.canUndo());
            }
        }
    }
}
