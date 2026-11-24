package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import java.io.File;
import org.llm4people.hermes.journey.JourneyUi;
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
        String[] source = {"Revue du lecteur de code"}, staged = {null}, link = {null};
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
                assertTrue(hasText(page,"Revue du lecteur de code"));
            });
            getInstrumentation().waitForIdleSync();
            capture(activity, "13-outil-revue");
            runTestOnUiThread(() -> {
                View page = activity.findViewById(android.R.id.content);
                String prepareDraft = activity.getString(org.llm4people.hermes.R.string.ws_form_prepare_draft);
                button(page, prepareDraft).performClick();
                assertTrue(hasText(page, activity.getString(org.llm4people.hermes.R.string.workspace_missing_field, "Fichier")));
                List<View> edits = views(page,v -> v instanceof EditText);
                assertEquals(2,edits.size());
                ((EditText)edits.get(0)).setText("src/Code.java");
                ((Spinner)views(page,v -> v instanceof Spinner).get(0)).setSelection(2);
                source[0] = "Vérifier le parseur";
                ArtifactDesk.refreshBindings(page,host);
                assertTrue(hasText(page,"Vérifier le parseur"));
                assertEquals("src/Code.java",((EditText)edits.get(0)).getText().toString());
                button(page, prepareDraft).performClick();
                assertNull(staged[0]);
            });
            getInstrumentation().waitForIdleSync();
            if (Build.VERSION.SDK_INT >= 29) {
                capture(activity, "14-relecture-brouillon");
                runTestOnUiThread(() -> {
                    String toDraft = activity.getString(org.llm4people.hermes.R.string.ws_form_send_to_draft);
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        List<View> transfer = views(root,v -> v instanceof Button && toDraft.contentEquals(
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

    public void testTransientInstrumentUsesRealControlsWithoutApplyingThePage() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch);
        String[] staged = {null};
        WorkspaceTestHost host = new WorkspaceTestHost() {
            @Override public String workspaceValue(String key) { return "Discussion active"; }
            @Override public void stageDraft(String text) { staged[0] = text; }
        };
        try (WorkspaceAccountFixture account = new WorkspaceAccountFixture(app)) {
            runTestOnUiThread(() -> {
                String raw = LiveLayoutContractTest.REVIEW;
                View page = InstrumentPreview.render(activity, raw, host);
                assertFalse(page instanceof android.widget.ScrollView);
                activity.setContentView(page);
                List<View> text = views(page, v -> v instanceof EditText);
                assertEquals(2, text.size());
                ((EditText)text.get(0)).setText("source.java");
                ((Spinner)views(page, v -> v instanceof Spinner).get(0)).setSelection(1);
                button(page, activity.getString(org.llm4people.hermes.R.string.ws_form_prepare_draft)).performClick();
                assertNull(staged[0]);
                assertNull(ArtifactDesk.page(activity, account.account, "page/revue", host));
            });
            getInstrumentation().waitForIdleSync();
            if (Build.VERSION.SDK_INT >= 29) {
                runTestOnUiThread(() -> {
                    String toDraft = activity.getString(org.llm4people.hermes.R.string.ws_form_send_to_draft);
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        List<View> buttons = views(root, v -> v instanceof Button
                                && toDraft.contentEquals(((Button)v).getText()));
                        if (buttons.isEmpty()) continue;
                        EditText review = (EditText)views(root, v -> v instanceof EditText).get(0);
                        assertTrue(review.getText().toString().contains("source.java"));
                        review.setText("Relis source.java en priorité.");
                        buttons.get(0).performClick();
                        return;
                    }
                    fail("Missing native review");
                });
                assertEquals("Relis source.java en priorité.", staged[0]);
            }
            runTestOnUiThread(() -> {
                String raw = LiveLayoutContractTest.REVIEW;
                View changed = InstrumentPreview.compare(activity, raw, raw.replace("Fichier", "Module"));
                String fieldChanged = activity.getString(org.llm4people.hermes.R.string.ws_diff_field_changed);
                assertEquals(1, views(changed, v -> v instanceof TextView && ((TextView)v).getText()
                        .toString().contains(fieldChanged)).size());
                View invalid = InstrumentPreview.render(activity, "{}", host);
                assertTrue(views(invalid, v -> v instanceof Button).isEmpty());
                assertTrue(PageLayouts.titles(account.account, true).isEmpty());
            });
        } finally {
            runTestOnUiThread(() -> { ArtifactDesk.dismiss(activity); activity.finish(); });
        }
    }

    public void testFiveArgInstrumentPreviewAppliesOppositePolarityAccountTheme() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        WorkspaceTestHost host = new WorkspaceTestHost() {
            @Override public String workspaceValue(String key) { return "Polarity check"; }
        };
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(app)) {
            boolean activityNight = ThemePalette.isNight(activity);
            boolean expectedNight = !activityNight;
            String oppositeTheme = expectedNight ? "dark" : "light";
            ThemePalette.OverrideSpec override = ThemePalette.OverrideSpec.validated(
                    oppositeTheme, null, "comfortable", 0, "paper", expectedNight);
            ThemePalette.save(fixture.account, override);
            ThemePalette expected = ThemePalette.resolve(override, activityNight);
            String pageJson = LiveLayoutContractTest.REVIEW.replace(
                    "\"mode\":\"replace\",", "\"mode\":\"replace\",\"theme\":\"system\",");
            runTestOnUiThread(() -> {
                View rendered = InstrumentPreview.render(activity, pageJson, host, fixture.account, "scope");
                assertNotNull(rendered);
                assertTrue(rendered.getBackground() instanceof ColorDrawable);
                assertEquals(expected.paper, ((ColorDrawable) rendered.getBackground()).getColor());
                assertEquals(expectedNight, ThemePalette.isNight(rendered.getContext()));
            });
        } finally {
            runTestOnUiThread(() -> { ArtifactDesk.dismiss(activity); activity.finish(); });
        }
    }

    private void capture(Activity activity, String name) {
        if (!(getInstrumentation() instanceof InstrumentationTestRunner runner)
                || !"true".equals(runner.getArguments().getString("captureLivePage")) || Build.VERSION.SDK_INT < 29)
            return;
        File dir = new File(activity.getCacheDir(),"synthetic-live-pages");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new AssertionError("Cannot create fixture evidence directory");
        new JourneyUi(runner,activity,dir).capture(name);
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
