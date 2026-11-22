package org.llm4people.hermes.panel;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.llm4people.hermes.R;

@SuppressWarnings("deprecation")
public final class PanelNavigationTest extends InstrumentationTestCase {
    public void testPanelRefreshRetainsDraftAndNestedSystemBackReturnsToParent() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        SessionPanel panels = new SessionPanel(activity, null, null);
        boolean[] back = {false};
        try {
            runTestOnUiThread(() -> {
                LinearLayout nested = panels.page("reminders", R.string.panel_new_reminder, () -> {
                    back[0] = true;
                    panels.page("reminders", R.string.panel_reminders, null);
                });
                EditText draft = new EditText(activity);
                nested.addView(draft);
                draft.setText("Appeler demain");
                assertTrue(panels.refreshLayout());
                assertTrue(draft.isAttachedToWindow());
                assertEquals("Appeler demain", draft.getText().toString());
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) nested.getRootView().getLayoutParams();
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                activity.getSystemService(android.view.inputmethod.InputMethodManager.class)
                        .hideSoftInputFromWindow(draft.getWindowToken(), 0);
            });
            getInstrumentation().waitForIdleSync();
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                assertTrue(back[0]);
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
            runTestOnUiThread(() -> { panels.close(); activity.finish(); });
        }
    }
}
