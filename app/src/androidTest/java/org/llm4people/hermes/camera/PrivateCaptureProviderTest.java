package org.llm4people.hermes.camera;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.test.InstrumentationTestCase;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("deprecation")
public final class PrivateCaptureProviderTest extends InstrumentationTestCase {
    public void testCreatesValidUuidSlotAndRejectsTraversalUris() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Uri slot = PrivateCaptureProvider.createSlot(context);
        try {
            File file = PrivateCaptureProvider.fileForSlot(context, slot);
            assertNotNull(file);
            assertTrue(file.isFile());

            assertNull(PrivateCaptureProvider.fileForSlot(context,
                    Uri.parse("content://" + PrivateCaptureProvider.authority(context) + "/../escape.jpg")));
            assertNull(PrivateCaptureProvider.fileForSlot(context,
                    Uri.parse("content://other.authority/00000000-0000-0000-0000-000000000000.jpg")));
            assertNull(PrivateCaptureProvider.fileForSlot(context, Uri.parse("file:///etc/passwd")));
        } finally {
            PrivateCaptureProvider.deleteSlot(context, slot);
        }
        assertFalse(PrivateCaptureProvider.fileForSlot(context, slot).exists());
    }

    public void testClaimReadySlotIsOneShotRejectsMismatchAndDeletesOnRead() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            Activity activity = getInstrumentation().startActivitySync(
                    context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
            try {
                // 1) Matching owner + session claims once, clears instance state, and second claim returns null.
                Uri slot1 = PrivateCaptureProvider.createSlot(context);
                File file1 = PrivateCaptureProvider.fileForSlot(context, slot1);
                assertNotNull(file1);
                assertTrue(file1.isFile());

                Bundle state1 = new Bundle();
                String nonce1 = "nonce-1";
                state1.putString("camera_nonce", nonce1);
                state1.putString("camera_owner", fixture.account.selectionBinding(nonce1, ""));
                state1.putString("camera_thread", fixture.account.selectionBinding(nonce1, "session-a"));
                state1.putString("camera_slot", slot1.toString());
                state1.putBoolean("camera_ready", true);

                ThreadCameraCapture capture1 = new ThreadCameraCapture(activity, state1);
                assertTrue(capture1.isReady());
                Uri claimed = capture1.claimReadySlot(fixture.account, "session-a");
                assertEquals(slot1, claimed);
                assertFalse("Claim must clear ready state immediately", capture1.isReady());
                assertNull("Second claim must return null (one-shot)",
                        capture1.claimReadySlot(fixture.account, "session-a"));

                // readAndDeleteSlot always deletes the claimed file even when empty/unreadable.
                try {
                    ThreadCameraCapture.readAndDeleteSlot(context, claimed);
                    fail("Expected empty slot read failure");
                } catch (IOException expected) {
                    // ok
                }
                assertFalse("Claimed slot file must be deleted after read attempt", file1.exists());

                // 2) Mismatched session returns null and deletes/clears the slot immediately.
                Uri slot2 = PrivateCaptureProvider.createSlot(context);
                File file2 = PrivateCaptureProvider.fileForSlot(context, slot2);
                assertNotNull(file2);
                assertTrue(file2.isFile());

                Bundle state2 = new Bundle();
                String nonce2 = "nonce-2";
                state2.putString("camera_nonce", nonce2);
                state2.putString("camera_owner", fixture.account.selectionBinding(nonce2, ""));
                state2.putString("camera_thread", fixture.account.selectionBinding(nonce2, "session-a"));
                state2.putString("camera_slot", slot2.toString());
                state2.putBoolean("camera_ready", true);

                ThreadCameraCapture capture2 = new ThreadCameraCapture(activity, state2);
                assertNull("Mismatched session must fail closed",
                        capture2.claimReadySlot(fixture.account, "session-b"));
                assertFalse("Mismatch must clear capture state", capture2.isReady());
                assertFalse("Mismatch must delete the private slot file", file2.exists());
            } finally {
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }
}
