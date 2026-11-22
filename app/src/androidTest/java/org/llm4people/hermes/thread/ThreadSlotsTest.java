package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.util.TypedValue;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.voice.FakeDictation;
import java.util.ArrayList;
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

    public void testComposerMicUsesTheSameEditableDraftAndExplicitSendButton() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                FakeDictation input = new FakeDictation();
                ThreadViews view = new ThreadViews(activity, input.controller(activity));
                AtomicInteger sends = new AtomicInteger();
                ArrayList<String> drafts = new ArrayList<>();
                view.chat("Bonjour", drafts::add, sends::incrementAndGet, () -> {}, () -> {});
                activity.setContentView(view.root);
                View mic = find(view.root, activity.getString(R.string.voice_dictate));
                assertNotNull(mic);
                assertEquals(Ui.dp(activity, 48), mic.getLayoutParams().width);
                mic.performClick();
                input.result("Hermes");
                assertEquals("Bonjour Hermes", view.draft());
                assertEquals(view.draft(), drafts.get(drafts.size() - 1));
                assertEquals(0, sends.get());
                editor(view.root).append(" !");
                find(view.root, "Envoyer").performClick();
                assertEquals(1, sends.get());
                assertEquals("Bonjour Hermes !", view.draft());
            });
        } finally { runTestOnUiThread(activity::finish); }
    }

    public void testOfflineLanguageOptionsLeaveAnEditableComposerInAShortViewport() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        ThreadViews[] chat = new ThreadViews[1];
        try {
            runTestOnUiThread(() -> {
                FakeDictation input = new FakeDictation();
                ThreadViews thread = new ThreadViews(activity, input.controller(activity));
                thread.chat("Brouillon", ignored -> {}, () -> fail("Unexpected send"), () -> {}, () -> {});
                FrameLayout frame = new FrameLayout(activity);
                chat[0] = thread;
                int width = Ui.dp(activity, 320), height = Ui.dp(activity, 140);
                frame.addView(thread.root, new FrameLayout.LayoutParams(width, height));
                activity.setContentView(frame);
                find(thread.root, activity.getString(R.string.voice_dictate)).performClick();
                input.missingFrenchModel();
                TextView options = (TextView) find(thread.root, activity.getString(R.string.voice_model_options));
                EditText field = editor(thread.root);
                View send = find(thread.root, "Envoyer");
                for (TextView text : new TextView[]{options, field, (TextView) send})
                    text.setTextSize(TypedValue.COMPLEX_UNIT_PX, text.getTextSize() * 1.5f);
            });
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                ThreadViews thread = chat[0];
                int width = Ui.dp(activity, 320), height = Ui.dp(activity, 140);
                TextView options = (TextView) find(thread.root, activity.getString(R.string.voice_model_options));
                EditText field = editor(thread.root);
                View send = find(thread.root, "Envoyer");
                thread.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                thread.root.layout(0, 0, width, height);
                for (View control : new View[]{options, field, send}) {
                    Rect visible = new Rect();
                    String detail = ((TextView) control).getText() + " measured " + control.getMeasuredWidth()
                            + "×" + control.getMeasuredHeight() + " in " + thread.root.getWidth() + "×" + thread.root.getHeight();
                    assertTrue(detail, control.getGlobalVisibleRect(visible));
                    assertTrue(detail + " visible " + visible, visible.height() >= Ui.dp(activity, 48));
                }
                field.append(" modifié");
                assertEquals("Brouillon modifié", thread.draft());
            });
        } finally { runTestOnUiThread(activity::finish); }
    }

    public void testEmptyStartersFollowAvailableHeightAndDraftChangesSendState() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                ThreadViews thread = new ThreadViews(activity);
                thread.chat("", ignored -> {}, () -> fail("Unexpected send"), () -> {}, () -> {});
                ThreadStarters starters = new ThreadStarters(thread.list, ignored -> {});
                activity.setContentView(thread.root);
                starters.show(true);
                layout(thread.root, 320, 540);
                assertTrue(thread.list.isStackFromBottom());
                layout(thread.root, 320, 140);
                assertFalse(thread.list.isStackFromBottom());
                layout(thread.root, 320, 540);
                assertTrue(thread.list.isStackFromBottom());
                starters.show(false);
                assertFalse(thread.list.isStackFromBottom());

                TextView send = (TextView) find(thread.root, "Envoyer");
                assertFalse(send.isEnabled());
                int disabledInk = send.getCurrentTextColor();
                thread.displayDraft("Une question");
                assertTrue(send.isEnabled());
                assertTrue(disabledInk != send.getCurrentTextColor());
                thread.busy(true, true);
                assertEquals(View.GONE, send.getVisibility());
                assertTrue(find(thread.root, "Arrêter").isShown());
                thread.busy(false, false);
                assertTrue(send.isShown());
                assertEquals("Une question", thread.draft());
            });
        } finally { runTestOnUiThread(activity::finish); }
    }

    public void testSendControlAndAdmissionTreatInvisibleDraftsIdentically() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                ThreadViews view = new ThreadViews(activity);
                AtomicInteger admissions = new AtomicInteger();
                ThreadWorkspace.Host host = new ThreadWorkspace.Host() {
                    public void selected(String id, String title) {}
                    public void pendingChanged() {}
                    public JSONObject options(String id) {
                        admissions.incrementAndGet();
                        return null; // Stop before network work.
                    }
                    public void timing(String id, long first, long elapsed, long tokens) {}
                };
                ThreadWorkspace page = new ThreadWorkspace(activity, null, null, host, view.voice);
                page.screen = view;
                page.journal = new ThreadJournal("discussion", "owner", null, ignored -> {});
                try {
                    ThreadRun run = new ThreadRun(page);
                    view.chat("", ignored -> {}, run::send, () -> {}, () -> {});
                    activity.setContentView(view.root);
                    View send = find(view.root, "Envoyer");
                    for (String blank : new String[]{"", " ", "\u00a0", "\u202f", "\u200b\ufe0f"}) {
                        view.displayDraft(blank);
                        assertFalse(send.isEnabled());
                        view.busy(false, false);
                        assertFalse(send.isEnabled());
                        run.send();
                    }
                    assertEquals(0, admissions.get());
                    String question = "\u00a0Déjà prêt\u202f? ✈️  ";
                    view.displayDraft(question);
                    assertEquals(question, view.draft());
                    assertTrue(send.isEnabled());
                    send.performClick();
                    assertEquals(1, admissions.get());
                    assertEquals(question, view.draft());
                } finally { page.close(); }
            });
        } finally { runTestOnUiThread(activity::finish); }
    }

    private static void layout(View view, int width, int height) {
        int w = Ui.dp(view.getContext(), width), h = Ui.dp(view.getContext(), height);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }

    private static View find(View view, String label) {
        ArrayList<View> matches = new ArrayList<>();
        view.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION | View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, matches.size());
        return matches.get(0);
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
