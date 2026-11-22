package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

@SuppressWarnings("deprecation")
public class LivePageJourneyTest extends InstrumentationTestCase {
    public void testNewScreenLinksLiveValuesAndDraftReviewInRealWindows() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        String[] source = {"Fil initial"}, staged = {null}, link = {null};
        WorkspaceTestHost host = new WorkspaceTestHost() {
            @Override public String workspaceValue(String key) { assertEquals("thread.title",key); return source[0]; }
            @Override public void stageDraft(String text) { staged[0] = text; }
            @Override public void openWorkspace(String id) { link[0] = id; }
        };
        try (WorkspaceAccountFixture account = new WorkspaceAccountFixture(app)) {
            LayoutHistory history = new LayoutHistory(account.account,"page/revue");
            history.replace(LiveLayoutContractTest.REVIEW);
            runTestOnUiThread(() -> {
                View gallery = ArtifactDesk.pages(activity,account.account,host);
                button(gallery,"Revue").performClick();
                assertEquals("page/revue",link[0]);
                View page = ArtifactDesk.page(activity,account.account,link[0],host);
                assertNotNull(page);
                activity.setContentView(page);
                button(page,"Suite").performClick();
                assertEquals("page/suite",link[0]);
                assertNull(ArtifactDesk.page(activity,account.account,link[0],host));
                assertTrue(hasText(page,"Fil initial"));
                button(page,"Préparer un brouillon…").performClick();
                assertTrue(hasText(page,"Renseignez le champ « Fichier »."));
                List<View> edits = views(page,v -> v instanceof EditText);
                assertEquals(2,edits.size());
                ((EditText)edits.get(0)).setText("src/Code.java");
                ((Spinner)views(page,v -> v instanceof Spinner).get(0)).setSelection(2);
                source[0] = "Fil renommé";
                ArtifactDesk.refreshBindings(page,host);
                assertTrue(hasText(page,"Fil renommé"));
                assertEquals("src/Code.java",((EditText)edits.get(0)).getText().toString());
                button(page,"Préparer un brouillon…").performClick();
                assertNull(staged[0]);
            });
            getInstrumentation().waitForIdleSync();
            if (Build.VERSION.SDK_INT >= 29) {
                runTestOnUiThread(() -> {
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        List<View> transfer = views(root,v -> v instanceof Button && "Vers le brouillon".contentEquals(
                                ((Button)v).getText()));
                        if (transfer.isEmpty()) continue;
                        EditText review = (EditText)views(root,v -> v instanceof EditText).get(0);
                        assertTrue(review.getText().toString().contains("src/Code.java"));
                        assertTrue(review.getText().toString().contains("Fiabilité"));
                        review.setText("Relire seulement Code.java");
                        transfer.get(0).performClick();
                        return;
                    }
                    fail("Draft review window missing");
                });
                assertEquals("Relire seulement Code.java",staged[0]);
            }
        } finally {
            runTestOnUiThread(() -> { ArtifactDesk.dismiss(activity); activity.finish(); });
        }
    }

    private static Button button(View root,String label) {
        List<View> matches = views(root,v -> v instanceof Button && label.contentEquals(((Button)v).getText()));
        assertEquals(label,1,matches.size());
        return (Button)matches.get(0);
    }
    private static boolean hasText(View root,String label) {
        return !views(root,v -> v instanceof TextView && label.contentEquals(((TextView)v).getText())).isEmpty();
    }
    private static List<View> views(View root, Predicate<View> predicate) {
        List<View> matches = new ArrayList<>();
        if (predicate.test(root)) matches.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup)root;
            for (int i=0; i<g.getChildCount(); i++) matches.addAll(views(g.getChildAt(i),predicate));
        }
        return matches;
    }
}
