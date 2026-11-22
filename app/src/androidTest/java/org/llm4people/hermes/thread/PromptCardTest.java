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
            find(card, "Liste (choix recommandé)").performClick();
            assertEquals("Liste", answers.get(0));
            find(card, "Écrire une réponse").performClick();
            EditText input = (EditText) find(card, "Votre réponse");
            input.setText("Une carte");
            input.setSelection(2, 6);
            card.state(PromptCard.State.OFFLINE);
            assertFalse(find(card, "Envoyer la réponse").isEnabled());
            card.state(PromptCard.State.VERIFY);
            assertTrue(find(card, "Vérifier la demande").isShown());
            card.state(PromptCard.State.READY);
            assertEquals(2, input.getSelectionStart());
            assertEquals(6, input.getSelectionEnd());
            assertEquals(1, answers.size());
            find(card, "Envoyer la réponse").performClick();
            find(card, "Passer la question").performClick();
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
            assertFalse(find(card, "Envoyer la réponse").isEnabled());
            find(card, "Un, deux").performClick();
            find(card, "Trois (choix recommandé)").performClick();
            find(card, "Écrire une réponse").performClick();
            ((EditText) find(card, "Votre réponse")).setText("Autre");
            assertTrue(answers.isEmpty());
            find(card, "Envoyer la réponse").performClick();
            assertEquals(new JSONArray().put("Un, deux").put("Trois").put("Autre").toString(), answers.get(0));
        });
    }

    public void testAuthorizationRequiresAnOfferedOneTimeChoiceAndDetails() throws Throwable {
        JSONObject prompt = new JSONObject().put("kind", "approval").put("prompt_id", "a")
                .put("execution_generation", 1).put("choices", new JSONArray().put("once").put("deny").put("always"));
        runTestOnUiThread(() -> {
            PromptCard unnamed = mount(prompt);
            assertNull(find(unnamed, "Autoriser une fois"));
            assertNotNull(find(unnamed, "Refuser"));
            ThreadData.put(prompt, "description", "Modifier la note");
            ThreadData.put(prompt, "edit", ThreadData.object("{\"path\":\"note.txt\",\"new_text\":\"Ligne ajoutée\"}"));
            PromptCard edit = mount(prompt);
            find(edit, "Voir les détails").performClick();
            assertTrue(textContains(edit, "Ligne ajoutée"));
            find(edit, "Masquer les détails").performClick();
            assertFalse(textContains(edit, "Ligne ajoutée"));
            find(edit, "Autoriser une fois").performClick();
            find(edit, "Refuser").performClick();
            assertEquals("once", answers.get(0));
            assertEquals("deny", answers.get(1));
            ThreadData.put(prompt, "choices", new JSONArray().put("deny"));
            assertNull(find(mount(prompt), "Autoriser une fois"));
        });
    }

    public void testLongChoiceWrapsAndAllControlsCanScrollIntoAShortViewport() throws Throwable {
        String choice = "Préparer une version détaillée pour les collègues qui participent à la réunion de lundi";
        JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "layout")
                .put("execution_generation", 3).put("question", "Comment préparer la réunion ?")
                .put("choices", new JSONArray().put(choice));
        ThreadViews[] ui = new ThreadViews[1];
        PromptCard[] card = new PromptCard[1];
        runTestOnUiThread(() -> {
            ui[0] = new ThreadViews(activity);
            ui[0].chat("Brouillon", ignored -> {}, () -> {}, () -> {}, () -> {});
            FrameLayout window = new FrameLayout(activity);
            window.addView(ui[0].root, new FrameLayout.LayoutParams(Ui.dp(activity, 320), Ui.dp(activity, 200)));
            activity.setContentView(window);
            card[0] = new PromptCard(ui[0], prompt, answers::add, () -> {});
            ui[0].list.addFooterView(card[0], null, true);
            ui[0].list.setSelectionFromTop(ui[0].list.getCount() - 1, 0);
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            TextView option = (TextView) find(card[0], choice);
            assertTrue(option.getLineCount() > 1);
            assertEquals(choice.length(), option.getLayout().getLineEnd(option.getLineCount() - 1));
            assertEquals(0, option.getLayout().getEllipsisCount(option.getLineCount() - 1));
            assertTrue(option.getHeight() >= Ui.dp(activity, 48));
            assertTrue(option.isClickable());
            View title = find(card[0], "Comment préparer la réunion ?");
            assertTrue(title.isAccessibilityHeading());
            View skip = find(card[0], "Passer la question");
            assertTrue(skip.isClickable());
            for (int i = 0; i < 24; i++) ui[0].list.scrollListBy(Ui.dp(activity, 36));
            Rect visible = new Rect();
            assertTrue("Last action must be reachable by scrolling", skip.getGlobalVisibleRect(visible));
            assertTrue(visible.height() >= Ui.dp(activity, 48));
            assertEquals("Brouillon", ui[0].draft());
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
