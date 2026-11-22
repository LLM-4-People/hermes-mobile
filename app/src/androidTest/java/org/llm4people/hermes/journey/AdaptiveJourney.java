package org.llm4people.hermes.journey;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.widget.EditText;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.R;

/** Verifies phone, tablet, rotation and large-text layout continuity without discarding live state. */
final class AdaptiveJourney {
    private final Instrumentation driver;
    private final JourneyUi ui;
    private final Activity activity;
    private final JSONObject fixture;
    private final JSONObject matrix = new JSONObject();

    AdaptiveJourney(Instrumentation driver, JourneyUi ui, Activity activity, JSONObject fixture) {
        this.driver = driver;
        this.ui = ui;
        this.activity = activity;
        this.fixture = fixture;
    }

    void resize() throws Exception {
        String draft = fixture.optString("draft", "Brouillon adaptatif " + fixture.optString("run", "test"));
        ui.tap("Nouvelle conversation");
        ui.see("Message à Hermes");
        ui.fill("Message à Hermes", draft);
        EditText initialComposer = ui.field("Message à Hermes");
        int selection = Math.min(8, draft.length());
        ui.main(() -> {
            initialComposer.requestFocus();
            initialComposer.setSelection(selection);
            return null;
        });
        ui.usableComposer();
        JSONObject initial = snapshot();
        matrix.put("initial", initial);
        ui.capture("30-adaptatif-initial");

        if (initial.optBoolean("dualPane")) {
            ui.tap("Masquer les discussions");
            ui.see("Afficher les discussions");
            assertPreserved(initialComposer, draft, selection);
            ui.tap("Afficher les discussions");
            ui.see("Masquer les discussions");
            assertPreserved(initialComposer, draft, selection);
            matrix.put("sidebarToggled", true);
        }

        int startingOrientation = ui.main(() -> activity.getResources().getConfiguration().orientation);
        int targetOrientation = startingOrientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        ui.main(() -> {
            activity.setRequestedOrientation(targetOrientation);
            return null;
        });
        ui.until("in-place configuration change", () -> {
            if (activity.isDestroyed() || activity.isFinishing()) {
                throw new AssertionError("Rotation recreated or finished MainActivity");
            }
            int current = activity.getResources().getConfiguration().orientation;
            return current != startingOrientation ? Boolean.TRUE : null;
        });
        driver.waitForIdleSync();
        ui.usableComposer();
        assertPreserved(initialComposer, draft, selection);
        matrix.put("rotated", snapshot());
        matrix.put("sameActivity", true);
        matrix.put("sameComposer", true);
        ui.capture("31-adaptatif-rotation");

        ui.tap("Autres actions");
        ui.tap("Rappels");
        ui.tap("Nouveau rappel");
        String reminderDraft = "Rappel adaptatif " + fixture.optString("run", "test");
        ui.fill("Titre", reminderDraft);
        if (!reminderDraft.equals(ui.fieldText("Titre"))) {
            throw new AssertionError("Reminder title draft did not stick");
        }
        ui.tap("Retour");
        ui.tap("Fermer");
        assertPreserved(initialComposer, draft, selection);

        ui.main(() -> {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            return null;
        });
        driver.waitForIdleSync();
    }

    void largeText() throws Exception {
        String draft = fixture.optString("draft", "Brouillon adaptatif " + fixture.optString("run", "test"));
        ui.see(draft);
        ui.usableComposer();
        JSONObject state = snapshot();
        matrix.put("largeText", state);
        ui.capture("32-adaptatif-grands-caracteres");
        ui.tap("Autres actions");
        ui.tap("Rappels");
        ui.tap("Nouveau rappel");
        EditText title = ui.field("Titre");
        int minTarget = ui.main(() -> Math.round(activity.getResources().getDisplayMetrics().density * 48));
        int measured = ui.main(title::getHeight);
        if (measured < minTarget) {
            throw new AssertionError("Reminder field height below 48dp at large text: " + measured);
        }
        ui.tap("Retour");
        ui.tap("Fermer");
    }

    JSONObject evidence() { return matrix; }

    private void assertPreserved(EditText expected, String draft, int selection) {
        EditText current = ui.field("Message à Hermes");
        ui.main(() -> {
            if (current != expected) throw new AssertionError("Composer view was recreated on resize");
            if (!draft.equals(current.getText().toString())) throw new AssertionError("Draft lost across resize");
            if (current.getSelectionStart() != selection) throw new AssertionError("Cursor selection moved across resize");
            return null;
        });
    }

    private JSONObject snapshot() throws JSONException {
        return ui.main(() -> {
            Configuration config = activity.getResources().getConfiguration();
            View root = activity.getWindow().getDecorView();
            View sidebar = root.findViewById(R.id.discussion_sidebar);
            View detail = root.findViewById(R.id.discussion_detail);
            boolean sidebarShown = sidebar != null && sidebar.isShown();
            boolean detailShown = detail != null && detail.isShown();
            boolean dualPane = false;
            if (sidebarShown && detailShown) {
                Rect left = new Rect();
                Rect right = new Rect();
                if (sidebar.getGlobalVisibleRect(left) && detail.getGlobalVisibleRect(right)) {
                    dualPane = left.right <= right.left || right.right <= left.left;
                }
            }
            try {
                return new JSONObject()
                        .put("widthPx", root.getWidth())
                        .put("heightPx", root.getHeight())
                        .put("screenWidthDp", config.screenWidthDp)
                        .put("screenHeightDp", config.screenHeightDp)
                        .put("fontScale", config.fontScale)
                        .put("sidebarShown", sidebarShown)
                        .put("detailShown", detailShown)
                        .put("dualPane", dualPane);
            } catch (JSONException error) {
                throw new AssertionError(error);
            }
        });
    }
}
