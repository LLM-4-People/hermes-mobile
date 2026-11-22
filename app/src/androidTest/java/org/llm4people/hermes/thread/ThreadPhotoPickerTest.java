package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

public final class ThreadPhotoPickerTest extends InstrumentationTestCase {
    public void testResultAfterRecreationStillBelongsToTheOriginAndSavedStateContainsNoPrivateDetail() {
        Context context = getInstrumentation().getTargetContext();
        String thread = "private-thread-42";
        Uri uri = Uri.parse("content://fixture/private-image-42");
        try (WorkspaceAccountFixture owner = new WorkspaceAccountFixture(context);
             WorkspaceAccountFixture another = new WorkspaceAccountFixture(context);
             Account reopened = owner.reopen()) {
            ThreadPhotoPicker first = new ThreadPhotoPicker(null, null);
            first.begin(owner.account, thread);
            Bundle waiting = new Bundle();
            first.save(waiting);
            ThreadPhotoPicker recreated = new ThreadPhotoPicker(null, waiting);
            recreated.unlocked(reopened);
            assertTrue(recreated.owns(reopened, thread));
            assertFalse(recreated.owns(reopened, "other"));
            assertFalse(recreated.owns(another.account, thread));
            assertTrue(recreated.result(waiting.getInt("photo_request"), Activity.RESULT_OK, new Intent().setData(uri)));
            Bundle completed = new Bundle();
            recreated.save(completed);
            assertTrue(completed.getBoolean("photo_reselect"));
            for (String key : completed.keySet()) {
                String value = String.valueOf(completed.get(key));
                assertFalse(value.contains(thread));
                assertFalse(value.contains(uri.toString()));
                assertFalse(value.contains(reopened.id));
                assertFalse(value.contains(reopened.bearer));
            }
            ThreadPhotoPicker wrongAccount = new ThreadPhotoPicker(null, waiting);
            wrongAccount.unlocked(another.account);
            assertFalse(wrongAccount.owns(reopened, thread));
        }
    }
}
