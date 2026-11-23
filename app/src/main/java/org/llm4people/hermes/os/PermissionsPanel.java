package org.llm4people.hermes.os;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Activity activity;
    private TextView summaryText;
    private TextView feedback;
    private LinearLayout cardsContainer;

    public PermissionsPanel(Activity activity) {
        this.activity = Objects.requireNonNull(activity, "activity");
    }

    /** Register this panel in {@link RouteRegistry} under {@link RouteRegistry.Section#ACTIVITE}. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Context context, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        return registry.register(new RouteRegistry.Destination(
                ROUTE_ID,
                RouteRegistry.Section.ACTIVITE,
                context.getString(R.string.perm_route_label),
                context.getString(R.string.perm_route_hint),
                0,
                false,
                null,
                open));
    }

    /** Format a concise live summary line for Activity hub or Connection Doctor cards. */
    public static String summaryLine(Context context) {
        Objects.requireNonNull(context, "context");
        PermissionCenter.Summary summary = PermissionCenter.summarize(context);
        if (summary.blocked() > 0) {
            return context.getString(R.string.perm_summary_with_blocked,
                    summary.allowed(), summary.blocked(), summary.off());
        }
        return context.getString(R.string.perm_summary_counts, summary.allowed(), summary.off());
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
        LinearLayout card = createCard(activity);
        card.setTag("permissions:notifications_banner");

        TextView badge = statusPill(activity, state);
        card.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView title = Ui.text(activity, activity.getString(R.string.perm_banner_notifications_title), 16);
        title.setTypeface(MEDIUM);
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
        actionsLp.topMargin = Ui.dp(activity, 8);

        Button allowBtn = minTargetButton(activity,
                activity.getString(blocked
                        ? R.string.perm_action_open_notification_settings
                        : R.string.perm_action_allow_notifications),
                v -> PermissionCenter.requestOrOpenSettings(activity, PermissionCenter.Key.NOTIFICATIONS));
        allowBtn.setTag("permissions:banner_allow_notifications");
        actions.addView(allowBtn, new LinearLayout.LayoutParams(-2, -2));

        if (onManagePermissions != null) {
            Button manageBtn = minTargetButton(activity,
                    activity.getString(R.string.perm_action_manage_all),
                    v -> onManagePermissions.run());
            manageBtn.setTag("permissions:banner_manage_all");
            LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(-2, -2);
            manageLp.topMargin = Ui.dp(activity, 4);
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
        title.setTypeface(MEDIUM);
        title.setAccessibilityHeading(true);
        body.addView(title);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.perm_panel_subtitle), 15);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        subtitle.setPaddingRelative(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 14));
        body.addView(subtitle);

        LinearLayout summaryCard = createCard(activity);
        summaryText = Ui.text(activity, "", 16);
        summaryText.setTypeface(MEDIUM);
        summaryText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        summaryCard.addView(summaryText);

        Button settingsButton = minTargetButton(activity,
                activity.getString(R.string.perm_action_open_settings),
                v -> {
                    if (!PermissionCenter.openAppSettings(activity)) {
                        showFeedback(activity.getString(R.string.perm_settings_unavailable), true);
                    }
                });
        settingsButton.setTag("permissions:open_app_settings");
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(-2, -2);
        settingsLp.topMargin = Ui.dp(activity, 8);
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
        PermissionCenter.Summary summary = PermissionCenter.summarizeStates(states);
        if (summaryText != null) {
            if (summary.blocked() > 0) {
                summaryText.setText(activity.getString(R.string.perm_summary_with_blocked,
                        summary.allowed(), summary.blocked(), summary.off()));
            } else {
                summaryText.setText(activity.getString(R.string.perm_summary_counts,
                        summary.allowed(), summary.off()));
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

    /** Handle a completed runtime permission prompt initiated by {@link PermissionCenter}. */
    public void onPermissionResult(int requestCode, String[] permissions, int[] results) {
        PermissionCenter.Key key = PermissionCenter.fromRequestCode(requestCode);
        if (key == null) return;
        boolean granted = results != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
        PermissionCenter.State updated = PermissionCenter.state(activity, key);
        String label = activity.getString(titleRes(key));
        if (granted || updated == PermissionCenter.State.GRANTED) {
            showFeedback(activity.getString(R.string.perm_feedback_granted, label), false);
        } else if (updated == PermissionCenter.State.PERMANENTLY_DENIED) {
            showFeedback(activity.getString(R.string.perm_feedback_blocked, label), true);
        } else {
            showFeedback(activity.getString(R.string.perm_feedback_denied, label), true);
        }
        refresh();
    }

    private LinearLayout buildPermissionCard(PermissionCenter.Key key, PermissionCenter.State state) {
        LinearLayout card = createCard(activity);
        card.setTag("permissions:card:" + key.storageKey);

        TextView badge = statusPill(activity, state);
        card.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView title = Ui.text(activity, activity.getString(titleRes(key)), 16);
        title.setTypeface(MEDIUM);
        title.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);
        card.addView(title);

        TextView desc = Ui.text(activity, activity.getString(descriptionRes(key)), 14);
        desc.setTextColor(activity.getColor(R.color.muted));
        desc.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        card.addView(desc);

        if (state != PermissionCenter.State.GRANTED && state != PermissionCenter.State.UNAVAILABLE) {
            Button action = minTargetButton(activity, actionLabel(key, state), v -> {
                if (!PermissionCenter.requestOrOpenSettings(activity, key)) {
                    showFeedback(activity.getString(R.string.perm_settings_unavailable), true);
                }
            });
            action.setTag("permissions:action:" + key.storageKey);
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, -2);
            actionLp.topMargin = Ui.dp(activity, 8);
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
        feedback.setText(message);
        feedback.setTextColor(activity.getColor(error ? R.color.danger : R.color.accent));
        feedback.setVisibility(View.VISIBLE);
    }

    private static TextView statusPill(Context context, PermissionCenter.State state) {
        int textRes;
        int inkColor;
        int surfaceColor;
        switch (state) {
            case GRANTED -> {
                textRes = R.string.perm_status_allowed;
                inkColor = context.getColor(R.color.accent);
                surfaceColor = context.getColor(R.color.accent_surface);
            }
            case PERMANENTLY_DENIED -> {
                textRes = R.string.perm_status_blocked;
                inkColor = context.getColor(R.color.danger);
                surfaceColor = context.getColor(R.color.danger_surface);
            }
            case UNAVAILABLE -> {
                textRes = R.string.perm_status_unavailable;
                inkColor = context.getColor(R.color.muted);
                surfaceColor = context.getColor(R.color.paper);
            }
            case NOT_REQUESTED, DENIED -> {
                textRes = R.string.perm_status_off;
                inkColor = context.getColor(R.color.warning);
                surfaceColor = context.getColor(R.color.warning_surface);
            }
            default -> {
                textRes = R.string.perm_status_off;
                inkColor = context.getColor(R.color.muted);
                surfaceColor = context.getColor(R.color.paper);
            }
        }
        TextView badge = Ui.text(context, context.getString(textRes), 12);
        badge.setTypeface(MEDIUM);
        badge.setTextColor(inkColor);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(surfaceColor);
        pill.setCornerRadius(Ui.dp(context, 8));
        badge.setBackground(pill);
        Ui.pad(badge, 10, 4);
        return badge;
    }

    private static LinearLayout createCard(Context context) {
        LinearLayout card = Ui.column(context);
        Ui.pad(card, 16, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(context.getColor(R.color.surface));
        bg.setCornerRadius(Ui.dp(context, 12));
        bg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.line));
        card.setBackground(bg);
        return card;
    }

    private static Button minTargetButton(Context context, String label, View.OnClickListener action) {
        Button button = Ui.button(context, label, action);
        int minTouch = Ui.dp(context, 48);
        button.setMinWidth(minTouch);
        button.setMinHeight(minTouch);
        button.setMinimumWidth(minTouch);
        button.setMinimumHeight(minTouch);
        return button;
    }

    private static int titleRes(PermissionCenter.Key key) {
        return switch (key) {
            case NOTIFICATIONS -> R.string.perm_notifications_title;
            case CAMERA -> R.string.perm_camera_title;
            case MICROPHONE -> R.string.perm_microphone_title;
            case LOCATION, PRECISE_LOCATION -> R.string.perm_location_title;
            case CALENDAR -> R.string.perm_calendar_title;
            case CONTACTS -> R.string.perm_contacts_title;
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
            case CONTACTS -> R.string.perm_contacts_desc;
            case NOTIFICATION_ACCESS -> R.string.perm_notification_access_desc;
            case INSTALL_UPDATES -> R.string.perm_install_updates_desc;
        };
    }
}
