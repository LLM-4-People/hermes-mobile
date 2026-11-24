package org.llm4people.hermes.panel;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.R;

@SuppressWarnings("deprecation")
public final class PanelNavigationTest extends InstrumentationTestCase {
    public void testPanelRefreshRetainsDraftAndNestedSystemBackReturnsToParent() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        SessionPanel panels = new SessionPanel(activity, null, null);
        EditText[] draft = new EditText[1];
        CountDownLatch drawn = new CountDownLatch(1), returned = new CountDownLatch(1);
        try {
            runTestOnUiThread(() -> {
                LinearLayout nested = panels.page("reminders", R.string.panel_new_reminder, () -> {
                    panels.page("reminders", R.string.panel_reminders, null);
                    returned.countDown();
                });
                draft[0] = new EditText(activity);
                draft[0].setShowSoftInputOnFocus(false);
                nested.addView(draft[0]);
                draft[0].setText("Appeler demain");
                assertTrue(panels.refreshLayout());
                afterWindowDraw(draft[0], drawn);
            });
            assertTrue("Refreshed panel never acquired its visible window", drawn.await(10, TimeUnit.SECONDS));
            runTestOnUiThread(() -> {
                assertTrue(draft[0].isShown());
                assertTrue(draft[0].isAttachedToWindow());
                assertEquals("Appeler demain", draft[0].getText().toString());
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) draft[0].getRootView().getLayoutParams();
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            assertTrue("System Back did not return to the reminders panel", returned.await(10, TimeUnit.SECONDS));
            runTestOnUiThread(() -> {
                assertTrue(panels.refreshLayout());
                DatePickerDialog child = new DatePickerDialog(activity, null, 2027, 1, 2);
                panels.present(child);
                assertTrue(child.isShowing());
                assertTrue((child.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                panels.close();
                assertFalse(child.isShowing());
                assertFalse(panels.refreshLayout());
            });
        } finally {
            try { runTestOnUiThread(() -> { panels.close(); }); }
            finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
        }
    }

    private static void afterWindowDraw(View view, CountDownLatch ready) {
        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (view.hasWindowFocus() && view.isShown()) {
                    view.getViewTreeObserver().removeOnPreDrawListener(this);
                    ready.countDown();
                } else view.postInvalidateOnAnimation();
                return true;
            }
        });
    }
}
