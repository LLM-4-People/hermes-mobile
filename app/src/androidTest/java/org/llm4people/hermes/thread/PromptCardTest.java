package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

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
        runTestOnUiThread(() -> {
            JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "q")
                    .put("execution_generation", 2).put("question", "Quel format ?")
                    .put("choices", new JSONArray().put("Liste (Recommended)").put("Texte"));
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
        runTestOnUiThread(() -> {
            JSONObject prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "q")
                    .put("execution_generation", 4).put("multi_select", true)
                    .put("choices", new JSONArray().put("Un, deux").put("Trois (Recommended)"));
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
        runTestOnUiThread(() -> {
            JSONObject prompt = new JSONObject().put("kind", "approval").put("prompt_id", "a")
                    .put("execution_generation", 1).put("choices", new JSONArray().put("once").put("deny").put("always"));
            PromptCard unnamed = mount(prompt);
            assertNull(find(unnamed, "Autoriser une fois"));
            assertNotNull(find(unnamed, "Refuser"));
            prompt.put("description", "Modifier la note");
            prompt.put("edit", new JSONObject().put("path", "note.txt").put("new_text", "Ligne ajoutée"));
            PromptCard edit = mount(prompt);
            find(edit, "Voir les détails").performClick();
            assertTrue(textContains(edit, "Ligne ajoutée"));
            find(edit, "Masquer les détails").performClick();
            assertFalse(textContains(edit, "Ligne ajoutée"));
            find(edit, "Autoriser une fois").performClick();
            find(edit, "Refuser").performClick();
            assertEquals("once", answers.get(0));
            assertEquals("deny", answers.get(1));
            prompt.put("choices", new JSONArray().put("deny"));
            assertNull(find(mount(prompt), "Autoriser une fois"));
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
