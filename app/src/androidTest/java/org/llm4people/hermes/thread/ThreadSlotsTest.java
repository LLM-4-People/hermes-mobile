package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public class ThreadSlotsTest extends InstrumentationTestCase {
    public void testRearrangingNativeSlotsKeepsEditorSelectionAndNeverSendsTheDraft() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                ThreadViews view = new ThreadViews(activity);
                AtomicInteger sends = new AtomicInteger();
                view.chat("texte déjà saisi", text -> {}, sends::incrementAndGet, () -> {}, () -> {});
                activity.setContentView(view.root);
                EditText editor = editor(view.root);
                assertNotNull(editor);
                editor.requestFocus();
                editor.setSelection(3, 8);
                view.rows(new JSONArray().put(new JSONObject()), false, false, 0);
                view.slots(Arrays.asList("composer", "transcript"));
                assertTrue(editor.hasFocus());
                assertEquals(3, editor.getSelectionStart());
                assertEquals(8, editor.getSelectionEnd());
                assertSame(editor, editor(view.root));
                assertEquals("texte déjà saisi", view.draft());
                assertSame(view.list, view.root.getChildAt(view.root.getChildCount() - 1));
                view.slots(Collections.singletonList("transcript"));
                assertFalse(editor.isShown());
                view.slots(Collections.emptyList());
                assertTrue(editor.isShown());
                assertEquals("texte déjà saisi", view.draft());
                assertEquals(0, sends.get());
            });
        } finally {
            runTestOnUiThread(activity::finish);
        }
    }

    private static EditText editor(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText result = editor(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}
