package org.llm4people.hermes.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.WindowManager;
import android.widget.ImageView;

/** Private progress, inventory and preview sheets all use the file controller's presentation path. */
@SuppressWarnings("deprecation")
public final class PublishedDialogTest extends InstrumentationTestCase {
    public void testPreviewIsSecureAndDisappearsOnLock() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        PublishedFiles files = new PublishedFiles(activity);
        try {
            runTestOnUiThread(() -> {
                AlertDialog file = files.present(new AlertDialog.Builder(activity)
                        .setTitle("résultat.png").setView(new ImageView(activity)));
                assertTrue(file.isShowing());
                assertTrue((file.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                files.lock();
                assertFalse(file.isShowing());
            });
        } finally {
            runTestOnUiThread(() -> { files.close(); activity.finish(); });
        }
    }
}
