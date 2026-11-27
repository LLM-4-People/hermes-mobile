package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.EditText;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Ui;

@SuppressWarnings("deprecation")
public class InstrumentViewportTest extends InstrumentationTestCase {
    public void testResizingAnOpenInstrumentPreservesItsControlAndTheRealComposer() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                ThreadViews view = new ThreadViews(activity, new org.llm4people.hermes.voice.VoiceDrafts(activity));
                view.chat("garder ce brouillon", text -> {}, () -> fail("Unexpected send"), () -> {}, () -> {});
                InstrumentViewport viewport = view.attachInstrumentViewport();
                activity.setContentView(view.root);
                view.focusComposer();
                EditText composer = (EditText) view.root.findFocus();
                composer.setSelection(2, 6);
                EditText field = new EditText(activity);
                field.setText("facture.py");
                viewport.open(field);

                size(viewport, 320);
                assertFalse(viewport.wide());
                assertEquals(View.GONE, view.list.getVisibility());
                assertTrue(field.isShown());
                assertTrue(composer.isShown());
                assertEquals(2, composer.getSelectionStart());
                assertEquals(6, composer.getSelectionEnd());

                size(viewport, 720);
                assertTrue(viewport.wide());
                assertEquals(View.VISIBLE, view.list.getVisibility());
                int[] discussion = new int[2], detail = new int[2];
                view.list.getLocationInWindow(discussion);
                field.getLocationInWindow(detail);
                assertTrue(view.list.getWidth() > 0);
                assertTrue(field.getWidth() > 0);
                assertTrue(discussion[0] + view.list.getWidth() <= detail[0]);

                viewport.close();
                assertFalse(viewport.open());
                assertNull(field.getParent());
                assertEquals("garder ce brouillon", view.draft());
                viewport.open(field);
                assertEquals("facture.py", field.getText().toString());
                assertTrue(field.isShown());
            });
        } finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
    }

    private static void size(View view, int widthDp) {
        int width = Ui.dp(view.getContext(), widthDp);
        int height = Ui.dp(view.getContext(), 400);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
