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
import org.llm4people.hermes.ActivityTestLifecycle;
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
        try { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
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
            PromptCard card = new PromptCard(new ThreadViews(activity, new org.llm4people.hermes.voice.VoiceDrafts(activity)), prompt, ignored -> {}, () -> {});
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
            PromptCard legacyCard = new PromptCard(new ThreadViews(activity, new org.llm4people.hermes.voice.VoiceDrafts(activity)), legacy, ignored -> {}, () -> {});
            activity.setContentView(legacyCard);
            assertTrue(containsVisible(legacyCard, "README.md"));
            assertTrue(containsVisible(legacyCard, "+1 / -1"));
            findExact(legacyCard, activity.getString(R.string.thread_see_details)).performClick();
            assertTrue(containsVisible(legacyCard, "-Avant"));
            assertTrue(containsVisible(legacyCard, "+Après"));
            assertFalse(containsVisible(legacyCard, "\"old_text\""));

            PromptCard malformedCard = new PromptCard(new ThreadViews(activity, new org.llm4people.hermes.voice.VoiceDrafts(activity)), malformed, ignored -> {}, () -> {});
            activity.setContentView(malformedCard);
            assertTrue(containsVisible(malformedCard, "Action personnalisée"));
        });
    }

    public void testStructuredDiffSaturatesOverflowNegativeFractionalAndNonFiniteCountsAndHunkStarts() throws Exception {
        JSONArray lines = new JSONArray().put(" ctx").put("-old").put("+new");

        // 1. 64-bit overflow and negative underflow saturate without 32-bit wrap-around.
        JSONObject overflowHunk = new JSONObject()
                .put("old_start", 4_294_967_296L)
                .put("new_start", -4_294_967_200L)
                .put("lines", lines);
        JSONObject overflowPrompt = new JSONObject().put("diff", new JSONObject()
                .put("path", "src/Overflow.java")
                .put("hunks", new JSONArray().put(overflowHunk))
                .put("additions", 4_294_967_296L)
                .put("deletions", -4_294_967_200L));
        DiffRenderer.DiffModel overflow = DiffRenderer.parse(overflowPrompt);
        assertNotNull(overflow);
        assertEquals(Integer.MAX_VALUE, overflow.additions());
        assertEquals(0, overflow.deletions());
        assertEquals("@@ -" + Integer.MAX_VALUE + " +0 @@", overflow.lines().get(0).text());

        // 2. Fractional values round deterministically.
        JSONObject fracHunk = new JSONObject()
                .put("old_start", 9.6d)
                .put("new_start", 11.4d)
                .put("lines", lines);
        JSONObject fracPrompt = new JSONObject().put("diff", new JSONObject()
                .put("path", "src/Frac.java")
                .put("hunks", new JSONArray().put(fracHunk))
                .put("additions", 3.6d)
                .put("deletions", 2.4d));
        DiffRenderer.DiffModel frac = DiffRenderer.parse(fracPrompt);
        assertNotNull(frac);
        assertEquals(4, frac.additions());
        assertEquals(2, frac.deletions());
        assertEquals("@@ -10 +11 @@", frac.lines().get(0).text());

        // 3. Non-number / null values fall back cleanly to counted lines (+1/-1) and hunk start 1.
        JSONObject badHunk = new JSONObject()
                .put("old_start", "bad")
                .put("new_start", JSONObject.NULL)
                .put("lines", lines);
        JSONObject badPrompt = new JSONObject().put("diff", new JSONObject()
                .put("path", "src/Bad.java")
                .put("hunks", new JSONArray().put(badHunk))
                .put("additions", "not-a-number")
                .put("deletions", JSONObject.NULL));
        DiffRenderer.DiffModel bad = DiffRenderer.parse(badPrompt);
        assertNotNull(bad);
        assertEquals(1, bad.additions());
        assertEquals(1, bad.deletions());
        assertEquals("@@ -1 +1 @@", bad.lines().get(0).text());

        // 4. Non-finite Double.NaN / Double.POSITIVE_INFINITY fall back cleanly.
        JSONObject nonFiniteHunk = new JSONObject() {
            @Override public Object opt(String name) {
                if ("old_start".equals(name)) return Double.NaN;
                if ("new_start".equals(name)) return Double.POSITIVE_INFINITY;
                return super.opt(name);
            }
        }.put("lines", lines);
        JSONObject nonFiniteDiff = new JSONObject() {
            @Override public boolean has(String name) {
                return "additions".equals(name) || "deletions".equals(name) || super.has(name);
            }
            @Override public Object opt(String name) {
                if ("additions".equals(name)) return Double.NaN;
                if ("deletions".equals(name)) return Double.POSITIVE_INFINITY;
                return super.opt(name);
            }
        }.put("path", "src/NonFinite.java").put("hunks", new JSONArray().put(nonFiniteHunk));
        DiffRenderer.DiffModel nonFinite = DiffRenderer.parse(new JSONObject().put("diff", nonFiniteDiff));
        assertNotNull(nonFinite);
        assertEquals(1, nonFinite.additions());
        assertEquals(1, nonFinite.deletions());
        assertEquals("@@ -1 +1 @@", nonFinite.lines().get(0).text());
    }

    public void testSummaryHeaderAndAccessibleDescriptionIncludeRiskAndHideDuplicateChildren() throws Throwable {
        runTestOnUiThread(() -> {
            try {
                // 1. High / medium / low risk with path and stats.
                String[][] cases = {
                        {"high", activity.getString(R.string.diff_risk_high)},
                        {"medium", activity.getString(R.string.diff_risk_medium)},
                        {"low", activity.getString(R.string.diff_risk_low)}
                };
                String addedOne = activity.getResources().getQuantityString(R.plurals.diff_lines_added, 1, 1);
                String removedOne = activity.getResources().getQuantityString(R.plurals.diff_lines_removed, 1, 1);
                for (String[] entry : cases) {
                    JSONObject prompt = new JSONObject().put("preview", new JSONObject()
                            .put("risk", entry[0])
                            .put("diff", new JSONObject()
                                    .put("path", "src/Risk.java")
                                    .put("additions", 1)
                                    .put("deletions", 1)
                                    .put("hunks", new JSONArray().put(new JSONObject()
                                            .put("old_start", 1).put("new_start", 1)
                                            .put("lines", new JSONArray().put("-old").put("+new"))))));
                    DiffRenderer.DiffModel model = DiffRenderer.parse(prompt);
                    assertNotNull(model);
                    String expected = "src/Risk.java, " + entry[1] + ", " + addedOne + ", " + removedOne;
                    assertEquals(expected, DiffRenderer.statsAccessibleDescription(activity, model));

                    ViewGroup header = (ViewGroup) DiffRenderer.summaryHeader(activity, model);
                    assertEquals(expected, String.valueOf(header.getContentDescription()));
                    assertEquals(2, header.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            header.getChildAt(0).getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            header.getChildAt(1).getImportantForAccessibility());
                }

                // 2. Risk-only model (no path, no stats) still announces risk.
                DiffRenderer.DiffModel riskOnly = new DiffRenderer.DiffModel(
                        "", "", "", "", "high", 0, 0, false, java.util.Collections.emptyList(), "");
                String highLabel = activity.getString(R.string.diff_risk_high);
                assertEquals(highLabel, DiffRenderer.statsAccessibleDescription(activity, riskOnly));
                ViewGroup riskHeader = (ViewGroup) DiffRenderer.summaryHeader(activity, riskOnly);
                assertEquals(highLabel, String.valueOf(riskHeader.getContentDescription()));
                assertEquals(1, riskHeader.getChildCount());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        riskHeader.getChildAt(0).getImportantForAccessibility());

                // 3. Empty risk omits risk from accessible description cleanly.
                DiffRenderer.DiffModel noRisk = new DiffRenderer.DiffModel(
                        "src/Plain.java", "", "", "", "", 1, 0, false, java.util.Collections.emptyList(), "");
                String expectedNoRisk = "src/Plain.java, " + addedOne;
                assertEquals(expectedNoRisk, DiffRenderer.statsAccessibleDescription(activity, noRisk));
                ViewGroup plainHeader = (ViewGroup) DiffRenderer.summaryHeader(activity, noRisk);
                assertEquals(expectedNoRisk, String.valueOf(plainHeader.getContentDescription()));
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        plainHeader.getChildAt(0).getImportantForAccessibility());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        plainHeader.getChildAt(1).getImportantForAccessibility());
            } catch (Exception err) {
                throw new AssertionError(err);
            }
        });
    }

    public void testRunDecisionsConfirmedReplySaturatesResolvedCount() throws Exception {
        // 4_294_967_296L saturates to Integer.MAX_VALUE (> 0 -> confirmed); -4_294_967_200L saturates to MIN_VALUE (<= 0 -> unconfirmed).
        assertTrue(RunDecisions.isConfirmedReply("run-1", "p-1", new JSONObject()
                .put("run_id", "run-1").put("prompt_id", "p-1").put("resolved", 4_294_967_296L)));
        assertFalse(RunDecisions.isConfirmedReply("run-1", "p-1", new JSONObject()
                .put("run_id", "run-1").put("prompt_id", "p-1").put("resolved", -4_294_967_200L)));
        assertFalse(RunDecisions.isConfirmedReply("run-1", "p-1", new JSONObject()
                .put("run_id", "run-1").put("prompt_id", "p-1").put("resolved", "NaN")));
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
