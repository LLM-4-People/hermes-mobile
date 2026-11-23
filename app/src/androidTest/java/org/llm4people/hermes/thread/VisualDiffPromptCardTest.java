package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;

@SuppressWarnings("deprecation")
public final class VisualDiffPromptCardTest extends InstrumentationTestCase {
    private Activity activity;

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

    public void testStructuredPreviewDiffRendersVisualSummaryAndUnifiedLinesWithoutRawJson() throws Throwable {
        JSONObject hunk = new JSONObject()
                .put("old_start", 10)
                .put("new_start", 10)
                .put("lines", new JSONArray().put(" ctx").put("-int x = 1;").put("+int x = 2;").put("+int y = 3;"));
        JSONObject diff = new JSONObject()
                .put("path", "src/App.java")
                .put("language", "java")
                .put("hunks", new JSONArray().put(hunk))
                .put("additions", 2)
                .put("deletions", 1)
                .put("truncated", false);
        JSONObject preview = new JSONObject()
                .put("kind", "file_patch")
                .put("summary", "Mettre à jour la valeur initiale dans src/App.java")
                .put("risk", "medium")
                .put("diff", diff);
        JSONObject prompt = new JSONObject()
                .put("kind", "approval")
                .put("prompt_id", "diff-1")
                .put("execution_generation", 1)
                .put("preview", preview)
                .put("choices", new JSONArray().put("once").put("deny"));

        runTestOnUiThread(() -> {
            PromptCard card = new PromptCard(new ThreadViews(activity), prompt, ignored -> {}, () -> {});
            activity.setContentView(card);
            assertTrue(containsVisible(card, "src/App.java"));
            assertTrue(containsVisible(card, "+2 / -1"));
            assertTrue(containsVisible(card, activity.getString(R.string.diff_risk_medium)));
            assertFalse(containsVisible(card, "+int y = 3;"));

            findExact(card, activity.getString(R.string.thread_see_details)).performClick();
            assertTrue(containsVisible(card, "-int x = 1;"));
            assertTrue(containsVisible(card, "+int y = 3;"));
            assertFalse("Must never dump raw JSON keys", containsVisible(card, "\"hunks\""));
            assertNotNull(findExact(card, activity.getString(R.string.diff_open_full)));

            findExact(card, activity.getString(R.string.prompt_hide_details)).performClick();
            assertFalse(containsVisible(card, "+int y = 3;"));
        });
    }

    public void testLegacyEditAndMalformedPayloadsRenderSafely() throws Throwable {
        JSONObject legacy = new JSONObject()
                .put("kind", "approval")
                .put("prompt_id", "edit-1")
                .put("execution_generation", 1)
                .put("edit", new JSONObject().put("path", "README.md").put("old_text", "Avant").put("new_text", "Après"))
                .put("choices", new JSONArray().put("once").put("deny"));

        JSONObject malformed = new JSONObject()
                .put("kind", "approval")
                .put("prompt_id", "bad-1")
                .put("execution_generation", 1)
                .put("description", "Action personnalisée")
                .put("preview", new JSONObject().put("diff", new JSONObject().put("hunks", "not-an-array")))
                .put("choices", new JSONArray().put("once").put("deny"));

        runTestOnUiThread(() -> {
            PromptCard legacyCard = new PromptCard(new ThreadViews(activity), legacy, ignored -> {}, () -> {});
            activity.setContentView(legacyCard);
            assertTrue(containsVisible(legacyCard, "README.md"));
            assertTrue(containsVisible(legacyCard, "+1 / -1"));
            findExact(legacyCard, activity.getString(R.string.thread_see_details)).performClick();
            assertTrue(containsVisible(legacyCard, "-Avant"));
            assertTrue(containsVisible(legacyCard, "+Après"));
            assertFalse(containsVisible(legacyCard, "\"old_text\""));

            PromptCard malformedCard = new PromptCard(new ThreadViews(activity), malformed, ignored -> {}, () -> {});
            activity.setContentView(malformedCard);
            assertTrue(containsVisible(malformedCard, "Action personnalisée"));
        });
    }

    private static View findExact(View view, String label) {
        if (view instanceof TextView tv && tv.isShown() && label.contentEquals(tv.getText())) return tv;
        if (view instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findExact(g.getChildAt(i), label);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static boolean containsVisible(View view, String text) {
        if (!view.isShown()) return false;
        if (view instanceof TextView tv && tv.getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsVisible(g.getChildAt(i), text)) return true;
            }
        }
        return false;
    }
}
