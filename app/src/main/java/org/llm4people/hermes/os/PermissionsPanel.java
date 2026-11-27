package org.llm4people.hermes.os;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;

import java.util.Map;
import java.util.Objects;

/**
 * Native bilingual Permissions Center mounted under Activity ({@link #ROUTE_ID}) and linked from
 * Connection Doctor. Displays every Android runtime and special-access permission with a live
 * status pill, plain-language explanation, and 1-tap Allow or Open Android settings recovery.
 */
public final class PermissionsPanel {
    public static final String ROUTE_ID = "permissions_center";

    private final Activity activity;
    private TextView summaryText;
    private TextView feedback;
    private LinearLayout cardsContainer;
    private PermissionCenter.Key pendingFeedbackKey;
    private boolean pendingFeedbackWasGranted;

    public PermissionsPanel(Activity activity) {
        this.activity = Objects.requireNonNull(activity, "activity");
    }

    /** Register this panel in {@link RouteRegistry} under Settings. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        return registry.registerAction(ROUTE_ID, open);
    }

    /** Format a concise live summary line for Settings or Connection Doctor cards. */
    public static String summaryLine(Context context) {
        Objects.requireNonNull(context, "context");
        return formatSummary(context, PermissionCenter.summarize(context));
    }

    static String formatSummary(Context context, PermissionCenter.Summary summary) {
        String allowed = context.getResources().getQuantityString(
                R.plurals.perm_count_allowed, summary.allowed(), summary.allowed());
        String off = context.getResources().getQuantityString(
                R.plurals.perm_count_off, summary.off(), summary.off());
        if (summary.blocked() > 0) {
            String blocked = context.getResources().getQuantityString(
                    R.plurals.perm_count_blocked, summary.blocked(), summary.blocked());
            return allowed + " · " + blocked + " · " + off;
        }
        return allowed + " · " + off;
    }

    /**
     * Build a 1-tap notification permission prompt card when notifications are off or blocked.
     * Returns null when notifications are already allowed or unavailable so the screen stays quiet.
     */
    public static View buildNotificationPromptBanner(Activity activity, Runnable onManagePermissions) {
        Objects.requireNonNull(activity, "activity");
        PermissionCenter.State state = PermissionCenter.state(activity, PermissionCenter.Key.NOTIFICATIONS);
        if (state == PermissionCenter.State.GRANTED || state == PermissionCenter.State.UNAVAILABLE) {
            return null;
        }
        LinearLayout card = Ui.card(activity);
        card.setTag("permissions:notifications_banner");

        TextView badge = statusPill(activity, state);
        card.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView title = Ui.text(activity, activity.getString(R.string.perm_banner_notifications_title), 16);
        title.setTypeface(Ui.mediumTypeface());
        title.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);
        card.addView(title);

        boolean blocked = state == PermissionCenter.State.PERMANENTLY_DENIED;
        TextView detail = Ui.text(activity, activity.getString(blocked
                ? R.string.perm_banner_notifications_blocked_body
                : R.string.perm_banner_notifications_body), 14);
        detail.setTextColor(activity.getColor(R.color.muted));
        detail.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        card.addView(detail);

        LinearLayout actions = Ui.column(activity);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = Ui.dp(activity, 10);

        Button allowBtn = Ui.filledButton(activity,
                activity.getString(blocked
                        ? R.string.perm_action_open_notification_settings
                        : R.string.perm_action_allow_notifications),
                v -> PermissionCenter.requestOrOpenSettings(activity, PermissionCenter.Key.NOTIFICATIONS));
        allowBtn.setTag("permissions:banner_allow_notifications");
        actions.addView(allowBtn, new LinearLayout.LayoutParams(-2, -2));

        if (onManagePermissions != null) {
            Button manageBtn = Ui.outlinedButton(activity,
                    activity.getString(R.string.perm_action_manage_all),
                    v -> onManagePermissions.run());
            manageBtn.setTag("permissions:banner_manage_all");
            LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(-2, -2);
            manageLp.topMargin = Ui.dp(activity, 8);
            actions.addView(manageBtn, manageLp);
        }
        card.addView(actions, actionsLp);
        return card;
    }

    /** Build the full scrollable Permissions Center view. */
    public View buildView() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));

        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 20, 18);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(activity, activity.getString(R.string.perm_panel_title), 24);
        title.setTypeface(Ui.mediumTypeface());
        title.setAccessibilityHeading(true);
        body.addView(title);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.perm_panel_subtitle), 15);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        subtitle.setPaddingRelative(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 14));
        body.addView(subtitle);

        LinearLayout summaryCard = Ui.card(activity);
        summaryText = Ui.text(activity, "", 16);
        summaryText.setTypeface(Ui.mediumTypeface());
        summaryText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        summaryCard.addView(summaryText);

        Button settingsButton = Ui.outlinedButton(activity,
                activity.getString(R.string.perm_action_open_settings),
                v -> {
                    if (!PermissionCenter.openAppSettings(activity)) {
                        pendingFeedbackKey = null;
                        pendingFeedbackWasGranted = false;
                        showFeedback(activity.getString(R.string.perm_settings_unavailable), true);
                    }
                });
        settingsButton.setTag("permissions:open_app_settings");
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(-2, -2);
        settingsLp.topMargin = Ui.dp(activity, 10);
        summaryCard.addView(settingsButton, settingsLp);
        body.addView(summaryCard, new LinearLayout.LayoutParams(-1, -2));

        feedback = Ui.text(activity, "", 14);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setPaddingRelative(0, Ui.dp(activity, 10), 0, 0);
        feedback.setVisibility(View.GONE);
        body.addView(feedback);

        cardsContainer = Ui.column(activity);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(-1, -2);
        cardsLp.topMargin = Ui.dp(activity, 12);
        body.addView(cardsContainer, cardsLp);

        refresh();
        return scroll;
    }

    /** Re-read live permission states (e.g. after returning from Android settings or a prompt). */
    public void refresh() {
        if (cardsContainer == null) return;
        Map<PermissionCenter.Key, PermissionCenter.State> states = PermissionCenter.snapshot(activity);
        if (shouldClearStaleFeedback(pendingFeedbackKey, pendingFeedbackWasGranted, states)) {
            clearFeedback();
        }
        PermissionCenter.Summary summary = PermissionCenter.summarizeStates(states);
        if (summaryText != null) {
            String next = formatSummary(activity, summary);
            if (!next.contentEquals(summaryText.getText())) {
                summaryText.setText(next);
            }
        }
        cardsContainer.removeAllViews();
        for (Map.Entry<PermissionCenter.Key, PermissionCenter.State> entry : states.entrySet()) {
            LinearLayout card = buildPermissionCard(entry.getKey(), entry.getValue());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(activity, 10);
            cardsContainer.addView(card, lp);
        }
    }

    /**
     * Pure decision helper for clearing stale permission feedback when the target permission's
     * granted status diverges from the feedback banner (denied/blocked -> granted, or granted -> revoked).
     */
    static boolean shouldClearStaleFeedback(
            PermissionCenter.Key pendingKey,
            boolean pendingWasGranted,
            Map<PermissionCenter.Key, PermissionCenter.State> states) {
        if (pendingKey == null || states == null) return false;
        PermissionCenter.State current = states.get(pendingKey);
        if (current == null) return false;
        return pendingWasGranted != (current == PermissionCenter.State.GRANTED);
    }

    /** Pure-JVM SSoT mapping live post-prompt permission state to feedback copy. */
    static int feedbackResForPermissionOutcome(PermissionCenter.State updated) {
        if (updated == PermissionCenter.State.GRANTED) return R.string.perm_feedback_granted;
        if (updated == PermissionCenter.State.PERMANENTLY_DENIED) return R.string.perm_feedback_blocked;
        return R.string.perm_feedback_denied;
    }

    /** Handle a completed runtime permission prompt initiated by {@link PermissionCenter}. */
    public void onPermissionResult(int requestCode, int[] results) {
        PermissionCenter.Key key = PermissionCenter.fromRequestCode(requestCode);
        if (key == null) return;
        if (!PermissionCenter.recordCompletedPrompt(activity, key, results)) {
            refresh();
            return;
        }
        PermissionCenter.State updated = PermissionCenter.state(activity, key);
        String label = activity.getString(titleRes(key));
        boolean nowGranted = updated == PermissionCenter.State.GRANTED;
        pendingFeedbackKey = key;
        pendingFeedbackWasGranted = nowGranted;
        showFeedback(activity.getString(feedbackResForPermissionOutcome(updated), label), !nowGranted);
        refresh();
    }

    private LinearLayout buildPermissionCard(PermissionCenter.Key key, PermissionCenter.State state) {
        LinearLayout card = Ui.card(activity);
        card.setTag("permissions:card:" + key.storageKey);

        TextView badge = statusPill(activity, state);
        card.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView title = Ui.text(activity, activity.getString(titleRes(key)), 16);
        title.setTypeface(Ui.mediumTypeface());
        title.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);
        card.addView(title);

        TextView desc = Ui.text(activity, activity.getString(descriptionRes(key)), 14);
        desc.setTextColor(activity.getColor(R.color.muted));
        desc.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        card.addView(desc);

        if (state != PermissionCenter.State.GRANTED && state != PermissionCenter.State.UNAVAILABLE) {
            Button action = Ui.filledButton(activity, actionLabel(key, state), v -> {
                if (!PermissionCenter.requestOrOpenSettings(activity, key)) {
                    pendingFeedbackKey = null;
                    pendingFeedbackWasGranted = false;
                    showFeedback(activity.getString(R.string.perm_settings_unavailable), true);
                }
            });
            action.setTag("permissions:action:" + key.storageKey);
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, -2);
            actionLp.topMargin = Ui.dp(activity, 10);
            card.addView(action, actionLp);
        }
        return card;
    }

    private String actionLabel(PermissionCenter.Key key, PermissionCenter.State state) {
        if (key == PermissionCenter.Key.NOTIFICATION_ACCESS) {
            return activity.getString(R.string.perm_action_open_listener_settings);
        }
        if (key == PermissionCenter.Key.INSTALL_UPDATES) {
            return activity.getString(R.string.perm_action_open_install_settings);
        }
        if (key == PermissionCenter.Key.NOTIFICATIONS) {
            if (state == PermissionCenter.State.PERMANENTLY_DENIED || android.os.Build.VERSION.SDK_INT < 33) {
                return activity.getString(R.string.perm_action_open_notification_settings);
            }
            return activity.getString(R.string.perm_action_allow_notifications);
        }
        if (state == PermissionCenter.State.PERMANENTLY_DENIED) {
            return activity.getString(R.string.perm_action_open_settings);
        }
        return activity.getString(R.string.perm_action_allow);
    }

    private void showFeedback(String message, boolean error) {
        if (feedback == null) return;
        feedback.setTextColor(activity.getColor(error ? R.color.danger : R.color.accent));
        Ui.announceLiveStatus(feedback, message);
    }

    private void clearFeedback() {
        pendingFeedbackKey = null;
        pendingFeedbackWasGranted = false;
        Ui.clearLiveStatus(feedback);
    }

    private static TextView statusPill(Context context, PermissionCenter.State state) {
        int textRes;
        int inkColorRes;
        int surfaceColorRes;
        switch (state) {
            case GRANTED -> {
                textRes = R.string.perm_status_allowed;
                inkColorRes = R.color.accent;
                surfaceColorRes = R.color.accent_surface;
            }
            case PERMANENTLY_DENIED -> {
                textRes = R.string.perm_status_blocked;
                inkColorRes = R.color.danger;
                surfaceColorRes = R.color.danger_surface;
            }
            case UNAVAILABLE -> {
                textRes = R.string.perm_status_unavailable;
                inkColorRes = R.color.muted;
                surfaceColorRes = R.color.paper;
            }
            case NOT_REQUESTED, DENIED -> {
                textRes = R.string.perm_status_off;
                inkColorRes = R.color.warning;
                surfaceColorRes = R.color.warning_surface;
            }
            default -> {
                textRes = R.string.perm_status_off;
                inkColorRes = R.color.muted;
                surfaceColorRes = R.color.paper;
            }
        }
        return Ui.pill(context, context.getString(textRes), surfaceColorRes, inkColorRes);
    }

    private static int titleRes(PermissionCenter.Key key) {
        return switch (key) {
            case NOTIFICATIONS -> R.string.perm_notifications_title;
            case CAMERA -> R.string.perm_camera_title;
            case MICROPHONE -> R.string.perm_microphone_title;
            case LOCATION, PRECISE_LOCATION -> R.string.perm_location_title;
            case CALENDAR -> R.string.perm_calendar_title;
            case NOTIFICATION_ACCESS -> R.string.perm_notification_access_title;
            case INSTALL_UPDATES -> R.string.perm_install_updates_title;
        };
    }

    private static int descriptionRes(PermissionCenter.Key key) {
        return switch (key) {
            case NOTIFICATIONS -> R.string.perm_notifications_desc;
            case CAMERA -> R.string.perm_camera_desc;
            case MICROPHONE -> R.string.perm_microphone_desc;
            case LOCATION, PRECISE_LOCATION -> R.string.perm_location_desc;
            case CALENDAR -> R.string.perm_calendar_desc;
            case NOTIFICATION_ACCESS -> R.string.perm_notification_access_desc;
            case INSTALL_UPDATES -> R.string.perm_install_updates_desc;
        };
    }
}
