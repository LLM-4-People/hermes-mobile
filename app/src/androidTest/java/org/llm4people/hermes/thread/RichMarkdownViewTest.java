package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class RichMarkdownViewTest extends InstrumentationTestCase {
    private Activity activity;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context target = getInstrumentation().getTargetContext();
        Intent launch = target.getPackageManager().getLaunchIntentForPackage(target.getPackageName());
        activity = getInstrumentation().startActivitySync(launch);
    }

    @Override protected void tearDown() throws Exception {
        try { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
        finally {
            Diagnostics.clear();
            super.tearDown();
        }
    }

    public void testTablesReviewedHttpsLinksQuotesAndReasoningDisclosureRenderNatively() throws Throwable {
        String source = "<think>Vérification étape par étape.</think>\n"
                + "> Citation utile\n"
                + "- Parent\n  - Sous-élément\n"
                + "| Outil | État |\n| --- | ---: |\n| Build | OK |\n"
                + "Liens : [Guide](https://docs.example.org/fr) et [Mauvais](javascript:alert(1)) et [Local](file:///etc/passwd).";
        AlertDialog[] dialog = {null};
        try {
            runTestOnUiThread(() -> {
                TranscriptPage.ReasoningSplit split = TranscriptPage.splitReasoning(source);
                TranscriptPage page = new TranscriptPage(split.visible(), new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);

                LinearLayout root = Ui.column(activity);
                TextView body = Ui.text(activity, "", 17);
                Spanned styled = (Spanned) MessageText.excerpt(activity, page);
                body.setText(styled);
                assertTrue(styled.getSpans(0, styled.length(), LeadingMarginSpan.class).length >= 2);
                root.addView(body);
                root.addView(MessageText.reasoningCard(activity, split.reasoning(), false, b -> dialog[0] = Ui.secureDialog(b)));
                MessageText.addTables(root, page);
                MessageText.addLinkActions(root, page, b -> dialog[0] = Ui.secureDialog(b));
                activity.setContentView(root);

                // Native scrollable TableLayout rendered with cells.
                TableLayout table = findTable(root);
                assertNotNull(table);
                assertTrue(containsVisible(table, "Outil"));
                assertTrue(containsVisible(table, "Build"));

                // Collapsible reasoning starts hidden and expands on tap.
                assertFalse(containsVisible(root, "Vérification étape par étape."));
                findExact(root, activity.getString(R.string.reasoning_title)).performClick();
                assertTrue(containsVisible(root, "Vérification étape par étape."));
                findExact(root, activity.getString(R.string.reasoning_hide)).performClick();
                assertFalse(containsVisible(root, "Vérification étape par étape."));

                // Only safe HTTPS link button is rendered; unsafe schemes produce no clickable Button action.
                assertEquals(1, page.links.size());
                assertEquals(2, page.rejectedLinks);
                assertNull(findContains(root, "Mauvais"));
                assertNull(findContains(root, "Local"));
                assertNull(findContains(root, "javascript:"));
                assertNull(findContains(root, "file:"));
                View safeBtn = findContains(root, "docs.example.org");
                assertNotNull(safeBtn);
                safeBtn.performClick();
            });
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                assertNotNull(dialog[0]);
                View confirmRoot = dialog[0].getWindow().getDecorView();
                assertTrue(containsVisible(confirmRoot, "docs.example.org"));
                assertTrue(containsVisible(confirmRoot, "https://docs.example.org/fr"));
                assertFalse(MessageText.launchHttps(activity, "javascript:alert(1)"));
                assertFalse(MessageText.launchHttps(activity, "file:///etc/passwd"));
            });
        } finally {
            runTestOnUiThread(() -> { if (dialog[0] != null) dialog[0].dismiss(); });
        }
    }

    public void testRunFollowerStreamsReasoningDeltaIntoFrame() throws Exception {
        String events = "id: 1\ndata: " + new JSONObject()
                .put("run_id", "r1").put("seq", 1).put("event", "reasoning.delta").put("text", "Analyse en cours.") + "\n\n"
                + "id: 2\ndata: " + new JSONObject()
                .put("run_id", "r1").put("seq", 2).put("event", "message.delta").put("delta", "Réponse finale.") + "\n\n"
                + "id: 3\ndata: " + new JSONObject()
                .put("run_id", "r1").put("seq", 3).put("event", "run.completed") + "\n\n";
        Exchange.Reply running = new Exchange.Reply("Content-Type: application/json\r\n",
                new JSONObject().put("run_id", "r1").put("status", "running").toString());
        Exchange.Reply done = new Exchange.Reply("Content-Type: application/json\r\n",
                new JSONObject().put("run_id", "r1").put("status", "completed").put("output", "Réponse finale.").toString());
        List<RunFollower.Frame> frames = new ArrayList<>();
        try (Exchange gateway = new Exchange("200 OK", running,
                new Exchange.Reply("Content-Type: text/event-stream\r\n", events), done)) {
            new RunFollower(new HermesApi(gateway.endpoint(), "tok"), "r1", new JSONObject(), -1,
                    new RunFollower.Listener() {
                        @Override public void update(RunFollower.Frame frame) { frames.add(frame); }
                        @Override public void complete(JSONObject status, long firstTextMs, long elapsedMs) {}
                        @Override public void disconnected() { fail("Unexpected disconnect"); }
                        @Override public void unknown(JSONObject status) { fail("Unexpected unknown"); }
                    }).follow();
        }
        String expectedThinking = ThreadStrings.get(R.string.thread_hermes_thinking);
        assertFalse(expectedThinking.isEmpty());
        assertTrue(frames.stream().anyMatch(f ->
                "Analyse en cours.".equals(f.reasoning) && expectedThinking.equals(f.activity)));
    }

    private static TableLayout findTable(View view) {
        if (view instanceof TableLayout t) return t;
        if (view instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                TableLayout hit = findTable(g.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
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

    private static View findContains(View view, String snippet) {
        if (view instanceof android.widget.Button b && b.isShown() && b.getText().toString().contains(snippet)) return b;
        if (view instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findContains(g.getChildAt(i), snippet);
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
