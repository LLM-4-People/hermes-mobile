package org.llm4people.hermes.journey;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.Surface;
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
        String composerHint = ui.str(R.string.thread_message_hint);
        ui.tap(ui.str(R.string.thread_new_conversation));
        ui.see(composerHint);
        ui.fill(composerHint, draft);
        EditText initialComposer = ui.field(composerHint);
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
            ui.tap(ui.str(R.string.ws_hide_discussions));
            ui.see(ui.str(R.string.ws_show_discussions));
            assertPreserved(initialComposer, draft, selection);
            ui.tap(ui.str(R.string.ws_show_discussions));
            ui.see(ui.str(R.string.ws_hide_discussions));
            assertPreserved(initialComposer, draft, selection);
            matrix.put("sidebarToggled", true);
        }

        int startingOrientation = ui.main(() -> activity.getResources().getConfiguration().orientation);
        int startingRotation = ui.main(() -> activity.getDisplay().getRotation());
        int targetRotation = startingOrientation == Configuration.ORIENTATION_LANDSCAPE
                ? UiAutomation.ROTATION_FREEZE_0
                : UiAutomation.ROTATION_FREEZE_90;
        UiAutomation automation = driver.getUiAutomation();
        // Drive rotation at the WindowManager level: Android 16 (API 36) ignores
        // Activity.setRequestedOrientation() on sw >= 600dp displays for targetSdk 36.
        if (!automation.setRotation(targetRotation)) {
            throw new AssertionError("WindowManager rejected display rotation request " + targetRotation);
        }
        try {
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

            ui.hideKeyboard();
            ui.tap(ui.str(R.string.nav_notes_tasks));
            ui.tap(ui.str(R.string.hub_seg_reminders));
            ui.tap(ui.str(R.string.hub_manage_reminders));
            ui.tap(ui.str(R.string.panel_new_reminder));
            String reminderDraft = "Rappel adaptatif " + fixture.optString("run", "test");
            String titleLabel = ui.str(R.string.panel_name);
            ui.fill(titleLabel, reminderDraft);
            if (!reminderDraft.equals(ui.fieldText(titleLabel))) {
                throw new AssertionError("Reminder title draft did not stick");
            }
            ui.tap(ui.str(R.string.panel_back));
            ui.tap(ui.str(R.string.panel_done));
            ui.tap(ui.str(R.string.nav_discussions));
            assertPreserved(initialComposer, draft, selection);
        } finally {
            automation.setRotation(freezeFor(startingRotation));
            ui.until("restored starting orientation", () -> {
                if (activity.isDestroyed() || activity.isFinishing()) return Boolean.TRUE;
                return activity.getResources().getConfiguration().orientation == startingOrientation
                        && activity.getDisplay().getRotation() == startingRotation ? Boolean.TRUE : null;
            });
            driver.waitForIdleSync();
        }
    }

    private static int freezeFor(int surfaceRotation) {
        return switch (surfaceRotation) {
            case Surface.ROTATION_90 -> UiAutomation.ROTATION_FREEZE_90;
            case Surface.ROTATION_180 -> UiAutomation.ROTATION_FREEZE_180;
            case Surface.ROTATION_270 -> UiAutomation.ROTATION_FREEZE_270;
            default -> UiAutomation.ROTATION_FREEZE_0;
        };
    }

    void largeText() throws Exception {
        String draft = fixture.optString("draft", "Brouillon adaptatif " + fixture.optString("run", "test"));
        ui.see(draft);
        ui.usableComposer();
        JSONObject state = snapshot();
        matrix.put("largeText", state);
        ui.capture("32-adaptatif-grands-caracteres");
        ui.hideKeyboard();
        ui.tap(ui.str(R.string.nav_notes_tasks));
        ui.tap(ui.str(R.string.hub_seg_reminders));
        ui.tap(ui.str(R.string.hub_manage_reminders));
        ui.tap(ui.str(R.string.panel_new_reminder));
        EditText title = ui.field(ui.str(R.string.panel_name));
        int minTarget = ui.main(() -> Math.round(activity.getResources().getDisplayMetrics().density * 48));
        int measured = ui.main(title::getHeight);
        if (measured < minTarget) {
            throw new AssertionError("Reminder field height below 48dp at large text: " + measured);
        }
        ui.tap(ui.str(R.string.panel_back));
        ui.tap(ui.str(R.string.panel_done));
        ui.tap(ui.str(R.string.nav_discussions));
    }

    JSONObject evidence() { return matrix; }

    private void assertPreserved(EditText expected, String draft, int selection) {
        EditText current = ui.field(ui.str(R.string.thread_message_hint));
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
