package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
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

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public class PromptCardTest extends InstrumentationTestCase {
    private Activity activity;
    private final List<String> answers = new ArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        activity = getInstrumentation().startActivitySync(launch);
    }

    @Override protected void tearDown() throws Exception {
        try { getInstrumentation().runOnMainSync(activity::finish); }
        finally { super.tearDown(); }
    }

    public void testNativeChoicesAndCustomTextPreserveTheDraftAcrossDisconnects() throws Throwable {
        JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "q")
                .put("execution_generation", 2).put("question", "Quel format ?")
                .put("choices", new JSONArray().put("Liste (Recommended)").put("Texte"));
        runTestOnUiThread(() -> {
            PromptCard card = mount(prompt);
            find(card, activity.getString(R.string.prompt_choice_recommended, "Liste")).performClick();
            assertEquals("Liste", answers.get(0));
            find(card, activity.getString(R.string.prompt_write_answer)).performClick();
            EditText input = (EditText) find(card, activity.getString(R.string.prompt_your_answer_hint));
            input.setText("Une carte");
            input.setSelection(2, 6);
            card.state(PromptCard.State.OFFLINE);
            assertFalse(find(card, activity.getString(R.string.prompt_send_answer)).isEnabled());
            card.state(PromptCard.State.VERIFY);
            assertTrue(find(card, activity.getString(R.string.prompt_verify_request)).isShown());
            card.state(PromptCard.State.READY);
            assertEquals(2, input.getSelectionStart());
            assertEquals(6, input.getSelectionEnd());
            assertEquals(1, answers.size());
            find(card, activity.getString(R.string.prompt_send_answer)).performClick();
            find(card, activity.getString(R.string.prompt_skip_question)).performClick();
            assertEquals("Une carte", answers.get(1));
            assertEquals("", answers.get(2));
        });
    }

    public void testMultipleSelectionEncodesOriginalLabelsAndOptionalWordsTogether() throws Throwable {
        JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "q")
                .put("execution_generation", 4).put("multi_select", true)
                .put("choices", new JSONArray().put("Un, deux").put("Trois (Recommended)"));
        runTestOnUiThread(() -> {
            PromptCard card = mount(prompt);
            assertFalse(find(card, activity.getString(R.string.prompt_send_answer)).isEnabled());
            find(card, "Un, deux").performClick();
            find(card, activity.getString(R.string.prompt_choice_recommended, "Trois")).performClick();
            find(card, activity.getString(R.string.prompt_write_answer)).performClick();
            ((EditText) find(card, activity.getString(R.string.prompt_your_answer_hint))).setText("Autre");
            assertTrue(answers.isEmpty());
            find(card, activity.getString(R.string.prompt_send_answer)).performClick();
            assertEquals(new JSONArray().put("Un, deux").put("Trois").put("Autre").toString(), answers.get(0));
        });
    }

    public void testInvisibleAnswersAndChoicesLeaveOnlyAnExplicitSkip() throws Throwable {
        String[] invisible = {"\u00a0", "\u2003", "\u200b", "\ufe0f", "\u00a0\ufe0f",
                "\u180f", new String(Character.toChars(0xe0100))};
        JSONArray choices = new JSONArray().put("(Recommended)").put("\u00a0 (Recommended)");
        for (String blank : invisible) choices.put(blank);
        JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "empty-choices")
                .put("execution_generation", 3).put("question", "Que souhaitez-vous ?")
                .put("choices", choices);
        runTestOnUiThread(() -> {
            PromptCard card = mount(prompt);
            assertNull(find(card, activity.getString(R.string.prompt_write_answer)));
            assertTrue(find(card, activity.getString(R.string.prompt_your_answer_hint)).isShown());
            View submit = find(card, activity.getString(R.string.prompt_send_answer));
            EditText input = (EditText) find(card, activity.getString(R.string.prompt_your_answer_hint));
            for (String blank : invisible) {
                input.setText(blank);
                assertFalse(submit.isEnabled());
                submit.performClick();
            }
            assertTrue(answers.isEmpty());
            find(card, activity.getString(R.string.prompt_skip_question)).performClick();
            assertEquals("", answers.get(0));
            String visible = "  Préparer un vol ✈️\u202f? 👩🏽‍💻  ";
            input.setText(visible);
            assertTrue(submit.isEnabled());
            submit.performClick();
            assertEquals(visible, answers.get(1));
        });
    }

    public void testInvisibleCustomTextAddsNoEntryToMultipleChoices() throws Throwable {
        String option = "Réunion ✈️";
        JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "mixed-choices")
                .put("execution_generation", 4).put("question", "Quel choix ?")
                .put("multi_select", true).put("choices", new JSONArray().put("\u200b (Recommended)")
                        .put(option + " (Recommended)"));
        runTestOnUiThread(() -> {
            PromptCard card = mount(prompt);
            assertNull(find(card, activity.getString(R.string.prompt_choice_recommended, "\u200b")));
            find(card, activity.getString(R.string.prompt_write_answer)).performClick();
            EditText input = (EditText) find(card, activity.getString(R.string.prompt_your_answer_hint));
            input.setText("\u00a0\ufe0f");
            View submit = find(card, activity.getString(R.string.prompt_send_answer));
            assertFalse(submit.isEnabled());
            find(card, activity.getString(R.string.prompt_choice_recommended, option)).performClick();
            assertTrue(submit.isEnabled());
            submit.performClick();
            assertEquals(new JSONArray().put(option).toString(), answers.get(0));
            String visible = "  👩🏽‍💻\u2003à midi  ";
            input.setText(visible);
            submit.performClick();
            assertEquals(new JSONArray().put(option).put(visible).toString(), answers.get(1));
        });
    }

    public void testMultipleChoiceWithNoVisibleOptionsStillEncodesACustomAnswer() throws Throwable {
        JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "no-valid-options")
                .put("execution_generation", 2).put("question", "Que proposez-vous ?")
                .put("multi_select", true).put("choices", new JSONArray().put("\u00a0").put("(Recommended)"));
        runTestOnUiThread(() -> {
            PromptCard card = mount(prompt);
            EditText input = (EditText) find(card, activity.getString(R.string.prompt_your_answer_hint));
            View submit = find(card, activity.getString(R.string.prompt_send_answer));
            input.setText("\u200b");
            assertFalse(submit.isEnabled());
            String visible = "Une réponse libre";
            input.setText(visible);
            submit.performClick();
            assertEquals(new JSONArray().put(visible).toString(), answers.get(0));
        });
    }

    public void testAuthorizationRequiresAnOfferedOneTimeChoiceAndDetails() throws Throwable {
        JSONObject prompt = new JSONObject().put("kind", "approval").put("prompt_id", "a")
                .put("execution_generation", 1).put("choices", new JSONArray().put("once").put("deny").put("always"));
        runTestOnUiThread(() -> {
            String allowOnce = activity.getString(R.string.share_bridge_allow_once);
            String deny = activity.getString(R.string.share_bridge_deny);
            PromptCard unnamed = mount(prompt);
            assertNull(find(unnamed, allowOnce));
            assertNotNull(find(unnamed, deny));
            ThreadData.put(prompt, "description", "Modifier la note");
            ThreadData.put(prompt, "edit", ThreadData.object("{\"path\":\"note.txt\",\"new_text\":\"Ligne ajoutée\"}"));
            PromptCard edit = mount(prompt);
            find(edit, activity.getString(R.string.thread_see_details)).performClick();
            assertTrue(textContains(edit, "Ligne ajoutée"));
            find(edit, activity.getString(R.string.prompt_hide_details)).performClick();
            assertFalse(textContains(edit, "Ligne ajoutée"));
            find(edit, allowOnce).performClick();
            find(edit, deny).performClick();
            assertEquals("once", answers.get(0));
            assertEquals("deny", answers.get(1));
            ThreadData.put(prompt, "choices", new JSONArray().put("deny"));
            assertNull(find(mount(prompt), allowOnce));
        });
    }

    public void testApprovalChoicesStackWhenLabelsNoLongerFitSideBySide() throws Throwable {
        JSONObject prompt = new JSONObject().put("kind", "approval").put("description", "Modifier la note")
                .put("choices", new JSONArray().put("once").put("deny"));
        runTestOnUiThread(() -> {
            PromptCard card = mount(prompt);
            TextView allow = (TextView) find(card, activity.getString(R.string.share_bridge_allow_once));
            TextView deny = (TextView) find(card, activity.getString(R.string.share_bridge_deny));
            int narrow = Ui.dp(activity, 260);
            int wide = Ui.dp(activity, 800);
            for (int width : new int[]{wide, narrow, wide}) {
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                card.layout(0, 0, width, card.getMeasuredHeight());
                assertTrue(Math.abs(allow.getWidth() - deny.getWidth()) <= 1);
                assertTrue(allow.getHeight() >= Ui.dp(activity, 48));
                assertTrue(deny.getHeight() >= Ui.dp(activity, 48));
                assertEquals(width == wide, allow.getTop() == deny.getTop());
                assertEquals(0, allow.getLayout().getEllipsisCount(allow.getLineCount() - 1));
                assertEquals(allow.length(), allow.getLayout().getLineEnd(allow.getLineCount() - 1));
            }
            assertTrue(answers.isEmpty());
        });
    }

    public void testLongChoiceWrapsAndAllControlsCanScrollIntoAShortViewport() throws Throwable {
        String choice = "Préparer une version détaillée pour les collègues qui participent à la réunion de lundi";
        JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "layout")
                .put("execution_generation", 3).put("question", "Comment préparer la réunion ?")
                .put("choices", new JSONArray().put(choice));
        runTestOnUiThread(() -> {
            ThreadViews ui = new ThreadViews(activity);
            ui.chat("Brouillon", ignored -> {}, () -> {}, () -> {}, () -> {});
            FrameLayout window = new FrameLayout(activity);
            int width = Ui.dp(activity, 320);
            int height = Ui.dp(activity, 200);
            window.addView(ui.root, new FrameLayout.LayoutParams(width, height));
            activity.setContentView(window);
            PromptCard card = new PromptCard(ui, prompt, answers::add, () -> {});
            ui.list.addFooterView(card, null, true);
            ui.list.setSelectionFromTop(ui.list.getCount() - 1, 0);
            window.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            window.layout(0, 0, width, height);
            TextView option = (TextView) find(card, choice);
            assertTrue("Choice lines=" + option.getLineCount() + " width=" + option.getWidth()
                    + " card=" + card.getWidth() + " list=" + ui.list.getWidth() + "x" + ui.list.getHeight()
                    + " rows=" + ui.list.getChildCount(), option.getLineCount() > 1);
            assertEquals(choice.length(), option.getLayout().getLineEnd(option.getLineCount() - 1));
            assertEquals(0, option.getLayout().getEllipsisCount(option.getLineCount() - 1));
            assertTrue(option.getHeight() >= Ui.dp(activity, 48));
            assertTrue(option.isClickable());
            View title = find(card, "Comment préparer la réunion ?");
            assertTrue(title.isAccessibilityHeading());
            View skip = find(card, activity.getString(R.string.prompt_skip_question));
            assertTrue(skip.isClickable());
            for (int i = 0; i < 24; i++) ui.list.scrollListBy(Ui.dp(activity, 36));
            Rect visible = new Rect();
            assertTrue("Last action must be reachable by scrolling", skip.getGlobalVisibleRect(visible));
            assertTrue("Visible action height=" + visible.height(), visible.height() >= Ui.dp(activity, 48));
            assertEquals("Brouillon", ui.draft());
            assertTrue(answers.isEmpty());
        });
    }

    private PromptCard mount(JSONObject prompt) {
        PromptCard card = new PromptCard(new ThreadViews(activity), prompt, answers::add, () -> {});
        activity.setContentView(card);
        return card;
    }

    private static View find(View view, String label) {
        if (view instanceof TextView text && (label.contentEquals(text.getText())
                || (text.getHint() != null && label.contentEquals(text.getHint())))) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static boolean textContains(View view, String value) {
        if (!view.isShown()) return false;
        if (view instanceof TextView text && text.getText().toString().contains(value)) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++)
                if (textContains(group.getChildAt(i), value)) return true;
        }
        return false;
    }
}
