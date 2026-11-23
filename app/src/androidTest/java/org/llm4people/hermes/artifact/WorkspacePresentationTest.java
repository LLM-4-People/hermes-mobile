package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import org.llm4people.hermes.R;

@SuppressWarnings("deprecation")
public final class WorkspacePresentationTest extends InstrumentationTestCase {
    private static WorkspaceLayout layout(String theme) throws Exception {
        return WorkspaceLayout.parse("{\"version\":1,\"surface\":\"artifacts\",\"title\":\"Atelier\",\"theme\":\""
                + theme + "\",\"blocks\":[{\"title\":\"Code\",\"actions\":[\"threads\"]},{\"type\":\"content\"}]}");
    }

    public void testPreviewOwnsBothForegroundAndBackgroundAcrossAppearanceChanges() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        WorkspaceLayout dark = layout("dark"), light = layout("light");
        runTestOnUiThread(() -> {
            assertPreviewColors(app, Configuration.UI_MODE_NIGHT_NO, dark, Configuration.UI_MODE_NIGHT_YES);
            assertPreviewColors(app, Configuration.UI_MODE_NIGHT_YES, light, Configuration.UI_MODE_NIGHT_NO);
        });
    }

    private void assertPreviewColors(Context app, int hostMode, WorkspaceLayout proposal, int proposedMode) {
        ViewGroup preview = (ViewGroup) SurfaceRenderer.preview(appearance(app,hostMode), proposal);
        assertTrue(preview.getBackground() instanceof ColorDrawable);
        Context palette = appearance(app, proposedMode);
        int paper = ((ColorDrawable)preview.getBackground()).getColor();
        int ink = ((TextView)preview.getChildAt(0)).getCurrentTextColor();
        assertEquals(palette.getColor(R.color.paper), paper);
        assertEquals(palette.getColor(R.color.ink), ink);
        float a = Color.luminance(paper), b = Color.luminance(ink);
        assertTrue((Math.max(a,b) + .05f) / (Math.min(a,b) + .05f) >= 4.5f);
    }

    private Context appearance(Context app, int mode) {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
        return app.createConfigurationContext(config);
    }

    public void testAThreadFormCanDismissOnlyItsOwnReview() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(intent);
        try {
            runTestOnUiThread(() -> {
                ArtifactDesk.Host thread = new WorkspaceTestHost();
                ArtifactDesk.Host foreground = new WorkspaceTestHost();
                AlertDialog threadReview = DeskDialogs.open(activity, thread, new AlertDialog.Builder(activity));
                AlertDialog foregroundReview = DeskDialogs.open(activity, foreground, new AlertDialog.Builder(activity));
                ArtifactDesk.dismissReviews(activity, thread);
                assertFalse(threadReview.isShowing());
                assertTrue(foregroundReview.isShowing());
                ArtifactDesk.dismiss(activity);
                assertFalse(foregroundReview.isShowing());
            });
        } finally { runTestOnUiThread(activity::finish); }
    }

    public void testNavigationAndLayoutChangeCloseEverySecureReaderDialogBeforeRouting() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(intent);
        Activity activity = getInstrumentation().startActivitySync(intent);
        WorkspaceLayout proposal = layout("system");
        try {
            runTestOnUiThread(() -> {
                AlertDialog[] layers = new AlertDialog[2];
                boolean[] called = new boolean[3];
                ArtifactDesk.Host host = new WorkspaceTestHost() {
                    @Override public void onAction(String id) {
                        assertEquals("threads", id);
                        assertFalse(layers[0].isShowing());
                        assertFalse(layers[1].isShowing());
                        called[0] = true;
                    }
                    @Override public void onLayoutChanged() {
                        assertFalse(layers[0].isShowing());
                        assertFalse(layers[1].isShowing());
                        assertTrue(called[1]);
                        called[2] = true;
                    }
                };
                View page = SurfaceRenderer.render(activity, proposal, new View(activity), host, () -> {});
                showLayers(activity, layers);
                Button navigate = findButton(page, activity.getString(R.string.ws_action_threads));
                assertNotNull(navigate);
                navigate.performClick();
                assertTrue(called[0]);
                showLayers(activity, layers);
                SurfaceRenderer.change(activity, host, () -> called[1] = true);
                assertTrue(called[2]);
            });
        } finally {
            runTestOnUiThread(() -> { ArtifactDesk.dismiss(activity); activity.finish(); });
        }
    }

    private void showLayers(Activity activity, AlertDialog[] layers) {
        for (int i = 0; i < layers.length; i++) {
            layers[i] = DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Artefacts"));
            assertNotNull(layers[i]);
            assertTrue(layers[i].isShowing());
            assertTrue((layers[i].getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        }
    }

    private Button findButton(View view, String name) {
        if (view instanceof Button && name.contentEquals(((Button)view).getText())) return (Button)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button child = findButton(group.getChildAt(i), name);
                if (child != null) return child;
            }
        }
        return null;
    }
}
