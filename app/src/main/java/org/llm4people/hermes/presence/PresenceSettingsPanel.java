package org.llm4people.hermes.presence;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.os.PermissionCenter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Plain-language bilingual native settings panel for live presence opt-ins, continuous location
 * privacy controls (1-tap pause &amp; turn-everything-off), and live app appearance customization
 * (palettes, spacing density, 1-tap Undo, and Reset to default).
 * Fail-closed: every sharing toggle starts OFF.
 */
public final class PresenceSettingsPanel {
    public static final String ROUTE_ID = "presence_control";

    public interface Listener {
        default void onPolicyChanged(PresencePolicy policy) {}
        default void onCustomizationChanged(AppCustomization customization) {}
        default void onRequestLocationPermission() {}
        default void onRequestPreciseLocationPermission() { onRequestLocationPermission(); }
        default void onRequestNotificationPermission() {}
    }

    public enum ToggleDecision {
        APPLY,
        REQUEST_PERMISSION,
        OPEN_SETTINGS,
        UNAVAILABLE
    }

    /** Pure-Java fail-closed gate: never enables a permission-backed toggle before GRANT. */
    public static ToggleDecision decidePermissionToggle(
            boolean requestedEnabled, PermissionCenter.State permissionState) {
        if (!requestedEnabled) return ToggleDecision.APPLY;
        if (permissionState == PermissionCenter.State.GRANTED) return ToggleDecision.APPLY;
        if (permissionState == PermissionCenter.State.UNAVAILABLE) return ToggleDecision.UNAVAILABLE;
        if (permissionState == PermissionCenter.State.PERMANENTLY_DENIED) return ToggleDecision.OPEN_SETTINGS;
        return ToggleDecision.REQUEST_PERMISSION;
    }

    /** Reconcile persisted presence toggles against live Android permissions (fail-closed on revocation). */
    public static PresencePolicy reconcilePermissions(
            PresencePolicy policy,
            boolean hasCoarseLocation,
            boolean hasFineLocation,
            boolean notificationsAllowed) {
        PresencePolicy current = policy == null ? PresencePolicy.defaults() : policy;
        PresencePolicy next = current;
        if (next.continuousLocationEnabled && !hasCoarseLocation) {
            next = next.withContinuousLocation(false);
        } else if (next.preciseLocation && !hasFineLocation) {
            next = next.withPreciseLocation(false);
        }
        if (next.proactiveNudgesEnabled && !notificationsAllowed) {
            next = next.withProactiveNudges(false);
        }
        return next;
    }

    private final Context context;
    private final ContinuousLocationTracker locationTracker;
    private final AppCustomizationStore customizationStore;
    private final Consumer<PresencePolicy> policySaver;
    private final Listener listener;

    private PresencePolicy policy;
    private LinearLayout statusBannerCard;
    private TextView statusBanner;
    private TextView feedback;
    private Button feedbackAction;
    private Button pauseLocationButton;
    private Button undoButton;
    private Switch screenSwitch;
    private Switch readSwitch;
    private Switch locationSwitch;
    private Switch preciseSwitch;
    private TextView preciseSummary;
    private Switch nudgeSwitch;
    private final Map<AppCustomization.PalettePreset, Button> paletteChips =
            new EnumMap<>(AppCustomization.PalettePreset.class);
    private final Map<AppCustomization.Density, Button> densityChips =
            new EnumMap<>(AppCustomization.Density.class);
    private boolean binding;
    private PermissionCenter.Key pendingDenialKey;
    private AlertDialog activeDialog;

    public PresenceSettingsPanel(
            Context context,
            PresencePolicy initialPolicy,
            ContinuousLocationTracker locationTracker,
            AppCustomizationStore customizationStore,
            Consumer<PresencePolicy> policySaver,
            Listener listener) {
        this.context = Objects.requireNonNull(context, "context");
        this.policy = initialPolicy == null ? PresencePolicy.defaults() : initialPolicy;
        this.locationTracker = Objects.requireNonNull(locationTracker, "locationTracker");
        this.customizationStore = Objects.requireNonNull(customizationStore, "customizationStore");
        this.policySaver = policySaver == null ? p -> {} : policySaver;
        this.listener = listener == null ? new Listener() {} : listener;
    }

    /** Registers this panel cleanly under {@link RouteRegistry.Section#ACTIVITE}. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Context context, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        return registry.register(new RouteRegistry.Destination(
                ROUTE_ID,
                RouteRegistry.Section.ACTIVITE,
                context.getString(R.string.presence_route_label),
                context.getString(R.string.presence_route_hint),
                0,
                false,
                null,
                open));
    }

    public PresencePolicy currentPolicy() {
        return policy;
    }

    public View buildView() {
        LinearLayout body = Ui.column(context);
        ScrollView scroll = Ui.centeredScroll(context, body, AdaptiveLayout.READING_COLUMN_MAX_DP, 20, 16, 24);
        scroll.setBackgroundColor(context.getColor(R.color.paper));

        TextView intro = mutedText(context.getString(R.string.presence_panel_intro));
        body.addView(intro);

        statusBannerCard = Ui.tonalCard(context);
        statusBanner = Ui.text(context, "", 14);
        statusBanner.setTypeface(Ui.mediumTypeface());
        statusBanner.setTag("presence:status_banner");
        statusBannerCard.addView(statusBanner, new LinearLayout.LayoutParams(-1, -2));
        addTop(body, statusBannerCard, 12);

        int minTouch = Ui.dp(context, 48);
        int gap = Ui.dp(context, 8);
        LinearLayout quickControls = Ui.row(context);
        pauseLocationButton = Ui.outlinedButton(context, context.getString(R.string.presence_pause_location), v -> toggleLocationPause());
        pauseLocationButton.setTag("presence:pause_location");
        pauseLocationButton.setMinWidth(minTouch);
        pauseLocationButton.setMinimumWidth(minTouch);
        LinearLayout.LayoutParams pauseLp = new LinearLayout.LayoutParams(-2, -2);
        pauseLp.setMarginEnd(gap);

        Button allOffButton = Ui.outlinedButton(context, context.getString(R.string.presence_turn_all_off), v -> turnEverythingOff());
        allOffButton.setTag("presence:turn_all_off");
        allOffButton.setTextColor(Ui.dangerInk(context));
        StateListDrawable dangerStates = new StateListDrawable();
        dangerStates.addState(new int[]{-android.R.attr.state_enabled},
                Ui.shape(context, Ui.BUTTON_CORNER_DP, R.color.disabled_surface, R.color.line, 1));
        dangerStates.addState(new int[]{},
                Ui.shape(context, Ui.BUTTON_CORNER_DP, R.color.danger_surface, R.color.danger, 1));
        allOffButton.setBackground(Ui.roundedRipple(context, Ui.BUTTON_CORNER_DP, dangerStates));
        allOffButton.setMinWidth(minTouch);
        allOffButton.setMinimumWidth(minTouch);

        quickControls.addView(pauseLocationButton, pauseLp);
        quickControls.addView(allOffButton);
        addTop(body, horizontalRow(quickControls), 10);

        feedback = Ui.text(context, "", 14);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        addTop(body, feedback, 8);

        feedbackAction = Ui.tonalButton(context, "", null);
        feedbackAction.setTag("presence:feedback_action");
        feedbackAction.setMinWidth(minTouch);
        feedbackAction.setMinimumWidth(minTouch);
        feedbackAction.setVisibility(View.GONE);
        LinearLayout.LayoutParams feedbackActionLp = new LinearLayout.LayoutParams(-2, -2);
        feedbackActionLp.topMargin = Ui.dp(context, 6);
        body.addView(feedbackAction, feedbackActionLp);

        addSectionHeader(body, context.getString(R.string.presence_section_sharing));

        LinearLayout togglesCard = Ui.card(context);
        screenSwitch = addToggle(togglesCard, false, "presence:toggle_screen",
                context.getString(R.string.presence_toggle_screen_title),
                context.getString(R.string.presence_toggle_screen_summary),
                checked -> applyPolicy(policy.withAppPresence(checked), context.getString(R.string.presence_feedback_saved)),
                null);

        readSwitch = addToggle(togglesCard, true, "presence:toggle_read",
                context.getString(R.string.presence_toggle_read_title),
                context.getString(R.string.presence_toggle_read_summary),
                checked -> applyPolicy(policy.withReadWithoutReply(checked), context.getString(R.string.presence_feedback_saved)),
                null);

        locationSwitch = addToggle(togglesCard, true, "presence:toggle_location",
                context.getString(R.string.presence_toggle_location_title),
                context.getString(R.string.presence_toggle_location_summary),
                this::handleLocationToggle,
                null);

        preciseSwitch = addToggle(togglesCard, true, "presence:toggle_precise",
                context.getString(R.string.presence_toggle_precise_title),
                context.getString(R.string.presence_toggle_precise_summary),
                this::handlePreciseLocationToggle,
                summaryView -> preciseSummary = summaryView);

        nudgeSwitch = addToggle(togglesCard, true, "presence:toggle_nudges",
                context.getString(R.string.presence_toggle_nudges_title),
                context.getString(R.string.presence_toggle_nudges_summary),
                this::handleNudgesToggle,
                null);
        addTop(body, togglesCard, 10);

        buildCustomizationSection(body);
        refresh();
        return scroll;
    }

    public void refresh() {
        boolean hasCoarse = ContinuousLocationTracker.hasCoarsePermission(context);
        boolean hasFine = ContinuousLocationTracker.hasFinePermission(context);
        boolean hasNotifications = PermissionCenter.state(context, PermissionCenter.Key.NOTIFICATIONS)
                == PermissionCenter.State.GRANTED;
        PresencePolicy reconciled = reconcilePermissions(policy, hasCoarse, hasFine, hasNotifications);
        if (!reconciled.equals(policy)) {
            policy = reconciled;
            policySaver.accept(policy);
            if (!policy.allowsLiveLocation()) {
                locationTracker.stopAndWipe();
            }
            listener.onPolicyChanged(policy);
        }
        if (pendingDenialKey != null
                && PermissionCenter.state(context, pendingDenialKey) == PermissionCenter.State.GRANTED) {
            clearFeedback();
        }
        binding = true;
        try {
            if (screenSwitch != null) screenSwitch.setChecked(policy.shareAppPresence);
            if (readSwitch != null) readSwitch.setChecked(policy.shareReadWithoutReply);
            if (locationSwitch != null) locationSwitch.setChecked(policy.continuousLocationEnabled);
            if (preciseSwitch != null) {
                preciseSwitch.setChecked(policy.preciseLocation);
                preciseSwitch.setEnabled(policy.continuousLocationEnabled);
            }
            if (preciseSummary != null) {
                preciseSummary.setTextColor(context.getColor(
                        policy.continuousLocationEnabled ? R.color.muted : R.color.disabled_ink));
            }
            if (nudgeSwitch != null) nudgeSwitch.setChecked(policy.proactiveNudgesEnabled);
            if (pauseLocationButton != null) {
                pauseLocationButton.setVisibility(policy.continuousLocationEnabled ? View.VISIBLE : View.GONE);
                pauseLocationButton.setText(context.getString(policy.locationPaused
                        ? R.string.presence_resume_location : R.string.presence_pause_location));
            }
            if (undoButton != null) {
                undoButton.setEnabled(customizationStore.canUndo());
            }
            updateCustomizationChips();
            updateBanner();
        } finally {
            binding = false;
        }
    }

    public void dismissDialogs() {
        AlertDialog dialog = activeDialog;
        activeDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    public void close() {
        dismissDialogs();
    }

    private void buildCustomizationSection(LinearLayout body) {
        addSectionHeader(body, context.getString(R.string.custom_section_title));
        addTop(body, mutedText(context.getString(R.string.custom_section_intro)), 4);

        paletteChips.clear();
        densityChips.clear();

        addTop(body, Ui.text(context, context.getString(R.string.custom_palette_label), 14), 10);
        LinearLayout paletteRow1 = Ui.row(context);
        LinearLayout paletteRow2 = Ui.row(context);
        addPaletteChip(paletteRow1, AppCustomization.PalettePreset.FOREST, R.string.custom_palette_forest);
        addPaletteChip(paletteRow1, AppCustomization.PalettePreset.SLATE, R.string.custom_palette_slate);
        addPaletteChip(paletteRow1, AppCustomization.PalettePreset.TERRACOTTA, R.string.custom_palette_terracotta);
        addPaletteChip(paletteRow2, AppCustomization.PalettePreset.INDIGO, R.string.custom_palette_indigo);
        addPaletteChip(paletteRow2, AppCustomization.PalettePreset.PLUM, R.string.custom_palette_plum);
        addTop(body, horizontalRow(paletteRow1), 8);
        addTop(body, horizontalRow(paletteRow2), 8);

        addTop(body, Ui.text(context, context.getString(R.string.custom_density_label), 14), 14);
        LinearLayout densityRow = Ui.row(context);
        addDensityChip(densityRow, AppCustomization.Density.COMPACT, R.string.custom_density_compact);
        addDensityChip(densityRow, AppCustomization.Density.COMFORTABLE, R.string.custom_density_comfortable);
        addDensityChip(densityRow, AppCustomization.Density.SPACIOUS, R.string.custom_density_spacious);
        addTop(body, horizontalRow(densityRow), 8);

        int minTouch = Ui.dp(context, 48);
        int gap = Ui.dp(context, 8);
        LinearLayout undoRow = Ui.row(context);
        undoButton = Ui.outlinedButton(context, context.getString(R.string.custom_undo), v -> {
            AppCustomization restored = customizationStore.undo();
            listener.onCustomizationChanged(restored);
            showFeedback(context.getString(R.string.custom_feedback_undone));
            refresh();
        });
        undoButton.setTag("custom:undo");
        undoButton.setMinWidth(minTouch);
        undoButton.setMinimumWidth(minTouch);

        Button resetButton = Ui.outlinedButton(context, context.getString(R.string.custom_reset), v -> {
            AppCustomization reset = customizationStore.resetToDefault();
            listener.onCustomizationChanged(reset);
            showFeedback(context.getString(R.string.custom_feedback_reset));
            refresh();
        });
        resetButton.setTag("custom:reset");
        resetButton.setMinWidth(minTouch);
        resetButton.setMinimumWidth(minTouch);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-2, -2);
        resetLp.setMarginStart(gap);

        undoRow.addView(undoButton);
        undoRow.addView(resetButton, resetLp);
        addTop(body, horizontalRow(undoRow), 12);
    }

    private HorizontalScrollView horizontalRow(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void addPaletteChip(LinearLayout row, AppCustomization.PalettePreset preset, int labelRes) {
        Button chip = Ui.outlinedButton(context, context.getString(labelRes), v -> {
            AppCustomization next = customizationStore.current().withPalette(preset, null);
            customizationStore.apply(next);
            listener.onCustomizationChanged(next);
            showFeedback(context.getString(R.string.custom_feedback_applied));
            refresh();
        });
        chip.setTag("custom:palette:" + preset.wire);
        int minTouch = Ui.dp(context, 48);
        chip.setMinWidth(minTouch);
        chip.setMinimumWidth(minTouch);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(context, 8));
        row.addView(chip, lp);
        paletteChips.put(preset, chip);
    }

    private void addDensityChip(LinearLayout row, AppCustomization.Density density, int labelRes) {
        Button chip = Ui.outlinedButton(context, context.getString(labelRes), v -> {
            AppCustomization next = customizationStore.current().withDensity(density);
            customizationStore.apply(next);
            listener.onCustomizationChanged(next);
            showFeedback(context.getString(R.string.custom_feedback_applied));
            refresh();
        });
        chip.setTag("custom:density:" + density.wire);
        int minTouch = Ui.dp(context, 48);
        chip.setMinWidth(minTouch);
        chip.setMinimumWidth(minTouch);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(context, 8));
        row.addView(chip, lp);
        densityChips.put(density, chip);
    }

    private void updateCustomizationChips() {
        AppCustomization current = customizationStore.current();
        for (Map.Entry<AppCustomization.PalettePreset, Button> entry : paletteChips.entrySet()) {
            styleChoiceChip(entry.getValue(), current.palette == entry.getKey());
        }
        for (Map.Entry<AppCustomization.Density, Button> entry : densityChips.entrySet()) {
            styleChoiceChip(entry.getValue(), current.density == entry.getKey());
        }
    }

    private void styleChoiceChip(Button chip, boolean selected) {
        if (chip == null) return;
        chip.setSelected(selected);
        if (selected) {
            chip.setBackground(Ui.roundedRipple(context, Ui.BUTTON_CORNER_DP,
                    Ui.shape(context, Ui.BUTTON_CORNER_DP, R.color.accent_surface, R.color.accent, 1)));
            chip.setTextColor(Ui.enabledDisabledInk(context, R.color.accent));
        } else {
            chip.setBackground(Ui.roundedRipple(context, Ui.BUTTON_CORNER_DP,
                    Ui.shape(context, Ui.BUTTON_CORNER_DP, R.color.surface, R.color.line, 1)));
            chip.setTextColor(Ui.enabledDisabledInk(context, R.color.ink));
        }
    }

    /** Called by host Activity once Android grants coarse or fine location permission. */
    public void onLocationPermissionGranted(boolean fine) {
        if (fine) {
            if (!ContinuousLocationTracker.hasFinePermission(context)) {
                refresh();
                return;
            }
            applyPolicy(policy.withContinuousLocation(true).withPreciseLocation(true),
                    context.getString(R.string.presence_feedback_saved));
            return;
        }
        if (!ContinuousLocationTracker.hasCoarsePermission(context)) {
            refresh();
            return;
        }
        applyPolicy(policy.withContinuousLocation(true),
                context.getString(R.string.presence_feedback_saved));
    }

    /** Called by host Activity with the outcome of a location permission prompt. */
    public void onLocationPermissionResult(boolean fine, boolean granted) {
        if (granted) {
            onLocationPermissionGranted(fine);
            return;
        }
        PermissionCenter.Key key = fine ? PermissionCenter.Key.PRECISE_LOCATION : PermissionCenter.Key.LOCATION;
        PermissionCenter.State state = PermissionCenter.state(context, key);
        if (state == PermissionCenter.State.UNAVAILABLE) {
            showFeedback(context.getString(R.string.perm_precise_location_unavailable));
        } else if (state == PermissionCenter.State.PERMANENTLY_DENIED) {
            showFeedbackWithAction(key, context.getString(R.string.perm_location_blocked),
                    context.getString(R.string.perm_action_open_settings),
                    () -> {
                        if (!PermissionCenter.openAppSettings(context)) {
                            showFeedback(context.getString(R.string.perm_settings_unavailable));
                        }
                    });
        } else {
            showFeedbackWithAction(key, context.getString(R.string.presence_feedback_location_permission),
                    context.getString(R.string.perm_action_allow_location),
                    () -> {
                        if (fine) listener.onRequestPreciseLocationPermission();
                        else listener.onRequestLocationPermission();
                    });
        }
        refresh();
    }

    /** Called by host Activity with the outcome of a notification permission prompt for proactive nudges. */
    public void onNotificationPermissionResult(boolean granted) {
        PermissionCenter.State state = PermissionCenter.state(context, PermissionCenter.Key.NOTIFICATIONS);
        if (granted || state == PermissionCenter.State.GRANTED) {
            applyPolicy(policy.withProactiveNudges(true), context.getString(R.string.presence_feedback_saved));
            return;
        }
        applyPolicy(policy.withProactiveNudges(false), context.getString(
                state == PermissionCenter.State.PERMANENTLY_DENIED
                        ? R.string.perm_banner_notifications_blocked_body
                        : R.string.perm_banner_notifications_body));
        showNotificationRecovery(state);
    }

    private void handleLocationToggle(boolean enabled) {
        if (enabled && !ContinuousLocationTracker.hasCoarsePermission(context)) {
            PermissionCenter.State state = PermissionCenter.state(context, PermissionCenter.Key.LOCATION);
            if (decidePermissionToggle(true, state) == ToggleDecision.OPEN_SETTINGS) {
                applyPolicy(policy.withContinuousLocation(false),
                        context.getString(R.string.perm_location_blocked));
                showFeedbackWithAction(PermissionCenter.Key.LOCATION,
                        context.getString(R.string.perm_location_blocked),
                        context.getString(R.string.perm_action_open_settings),
                        () -> {
                            if (!PermissionCenter.openAppSettings(context)) {
                                showFeedback(context.getString(R.string.perm_settings_unavailable));
                            }
                        });
                return;
            }
            applyPolicy(policy.withContinuousLocation(false),
                    context.getString(R.string.presence_feedback_location_permission));
            listener.onRequestLocationPermission();
            return;
        }
        applyPolicy(policy.withContinuousLocation(enabled), context.getString(R.string.presence_feedback_saved));
    }

    private void handlePreciseLocationToggle(boolean enabled) {
        if (enabled && !ContinuousLocationTracker.hasFinePermission(context)) {
            PermissionCenter.State state = PermissionCenter.state(context, PermissionCenter.Key.PRECISE_LOCATION);
            ToggleDecision decision = decidePermissionToggle(true, state);
            if (decision == ToggleDecision.UNAVAILABLE) {
                applyPolicy(policy.withPreciseLocation(false),
                        context.getString(R.string.perm_precise_location_unavailable));
                return;
            }
            if (decision == ToggleDecision.OPEN_SETTINGS) {
                applyPolicy(policy.withPreciseLocation(false),
                        context.getString(R.string.perm_location_blocked));
                showFeedbackWithAction(PermissionCenter.Key.PRECISE_LOCATION,
                        context.getString(R.string.perm_location_blocked),
                        context.getString(R.string.perm_action_open_settings),
                        () -> {
                            if (!PermissionCenter.openAppSettings(context)) {
                                showFeedback(context.getString(R.string.perm_settings_unavailable));
                            }
                        });
                return;
            }
            applyPolicy(policy.withPreciseLocation(false),
                    context.getString(R.string.presence_feedback_location_permission));
            listener.onRequestPreciseLocationPermission();
            return;
        }
        applyPolicy(policy.withPreciseLocation(enabled), context.getString(R.string.presence_feedback_saved));
    }

    private void handleNudgesToggle(boolean enabled) {
        if (!enabled) {
            applyPolicy(policy.withProactiveNudges(false), context.getString(R.string.presence_feedback_saved));
            return;
        }
        PermissionCenter.State state = PermissionCenter.state(context, PermissionCenter.Key.NOTIFICATIONS);
        switch (decidePermissionToggle(true, state)) {
            case APPLY -> applyPolicy(policy.withProactiveNudges(true),
                    context.getString(R.string.presence_feedback_saved));
            case OPEN_SETTINGS -> {
                applyPolicy(policy.withProactiveNudges(false),
                        context.getString(R.string.perm_banner_notifications_blocked_body));
                showNotificationRecovery(state);
            }
            case REQUEST_PERMISSION -> {
                applyPolicy(policy.withProactiveNudges(false),
                        context.getString(R.string.perm_banner_notifications_body));
                showNotificationRecovery(state);
                listener.onRequestNotificationPermission();
            }
            case UNAVAILABLE -> applyPolicy(policy.withProactiveNudges(false),
                    context.getString(R.string.perm_settings_unavailable));
        }
    }

    private void showNotificationRecovery(PermissionCenter.State state) {
        if (state == PermissionCenter.State.PERMANENTLY_DENIED || android.os.Build.VERSION.SDK_INT < 33) {
            showFeedbackWithAction(PermissionCenter.Key.NOTIFICATIONS,
                    context.getString(R.string.perm_banner_notifications_blocked_body),
                    context.getString(R.string.perm_action_open_notification_settings),
                    () -> {
                        if (!PermissionCenter.openNotificationSettings(context)) {
                            showFeedback(context.getString(R.string.perm_settings_unavailable));
                        }
                    });
        } else {
            showFeedbackWithAction(PermissionCenter.Key.NOTIFICATIONS,
                    context.getString(R.string.perm_banner_notifications_body),
                    context.getString(R.string.perm_action_allow_notifications),
                    listener::onRequestNotificationPermission);
        }
    }

    private void toggleLocationPause() {
        if (!policy.continuousLocationEnabled) return;
        boolean nextPaused = !policy.locationPaused;
        applyPolicy(policy.withLocationPaused(nextPaused), context.getString(R.string.presence_feedback_saved));
    }

    private void turnEverythingOff() {
        applyPolicy(PresencePolicy.defaults(), context.getString(R.string.presence_feedback_all_off));
        Diagnostics.event("presence", "turn_all_off");
    }

    private void applyPolicy(PresencePolicy next, String message) {
        policy = next;
        policySaver.accept(policy);
        if (policy.allowsLiveLocation()) {
            locationTracker.syncWithPolicy(context, policy);
        } else {
            locationTracker.stopAndWipe();
        }
        listener.onPolicyChanged(policy);
        showFeedback(message);
        refresh();
    }

    private void updateBanner() {
        if (statusBanner == null) return;
        List<String> tags = new ArrayList<>();
        if (policy.shareAppPresence) tags.add(context.getString(R.string.presence_status_tag_screen));
        if (policy.shareReadWithoutReply) tags.add(context.getString(R.string.presence_status_tag_read));
        ContinuousLocationTracker.Indicator locInd = ContinuousLocationTracker.indicatorFor(policy);
        switch (locInd) {
            case ACTIVE_COARSE -> tags.add(context.getString(R.string.presence_status_tag_location_coarse));
            case ACTIVE_PRECISE -> tags.add(context.getString(R.string.presence_status_tag_location_precise));
            case PAUSED -> tags.add(context.getString(R.string.presence_status_tag_location_paused));
            case OFF -> {}
        }
        if (policy.proactiveNudgesEnabled) tags.add(context.getString(R.string.presence_status_tag_nudges));
        if (tags.isEmpty()) {
            statusBanner.setText(context.getString(R.string.presence_status_all_off));
            statusBanner.setTextColor(context.getColor(R.color.muted));
            if (statusBannerCard != null) {
                statusBannerCard.setBackground(Ui.shape(context, Ui.CARD_CORNER_DP, R.color.surface_container, 0, 0));
            }
        } else {
            statusBanner.setText(context.getString(R.string.presence_status_active, String.join(" · ", tags)));
            statusBanner.setTextColor(context.getColor(R.color.accent));
            if (statusBannerCard != null) {
                statusBannerCard.setBackground(Ui.shape(context, Ui.CARD_CORNER_DP, R.color.accent_surface, 0, 0));
            }
        }
    }

    private void showFeedback(String text) {
        pendingDenialKey = null;
        if (feedback == null) return;
        feedback.setText(text);
        feedback.setVisibility(View.VISIBLE);
        if (feedbackAction != null) {
            feedbackAction.setVisibility(View.GONE);
            feedbackAction.setOnClickListener(null);
        }
    }

    private void showFeedbackWithAction(
            PermissionCenter.Key denialKey, String text, String actionLabel, Runnable onClick) {
        showFeedback(text);
        pendingDenialKey = denialKey;
        if (feedbackAction != null && onClick != null) {
            feedbackAction.setText(actionLabel);
            feedbackAction.setOnClickListener(v -> onClick.run());
            feedbackAction.setVisibility(View.VISIBLE);
        }
    }

    private void clearFeedback() {
        pendingDenialKey = null;
        if (feedback != null) feedback.setVisibility(View.GONE);
        if (feedbackAction != null) {
            feedbackAction.setVisibility(View.GONE);
            feedbackAction.setOnClickListener(null);
        }
    }

    private void addSectionHeader(LinearLayout parent, String label) {
        addTop(parent, Ui.divider(context), 18);
        TextView header = Ui.text(context, label, 18);
        header.setTypeface(Ui.mediumTypeface());
        header.setAccessibilityHeading(true);
        addTop(parent, header, 14);
    }

    private Switch addToggle(
            LinearLayout card,
            boolean withDivider,
            String tag,
            String title,
            String summary,
            Consumer<Boolean> onToggle,
            Consumer<TextView> summarySink) {
        if (withDivider) {
            View divider = Ui.insetDivider(context, 16);
            LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
            divLp.topMargin = Ui.dp(context, 10);
            divLp.bottomMargin = Ui.dp(context, 10);
            card.addView(divider, divLp);
        }
        LinearLayout block = Ui.column(context);
        Switch sw = new Switch(context);
        sw.setText(title);
        sw.setTextSize(15);
        sw.setTextColor(Ui.enabledDisabledInk(context, R.color.ink));
        int minTouch = Ui.dp(context, 48);
        sw.setMinHeight(minTouch);
        sw.setMinimumHeight(minTouch);
        sw.setMinWidth(minTouch);
        sw.setMinimumWidth(minTouch);
        sw.setTag(tag);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (!binding) onToggle.accept(checked);
        });
        block.addView(sw, new LinearLayout.LayoutParams(-1, -2));
        TextView summaryView = mutedText(summary);
        block.addView(summaryView);
        if (summarySink != null) summarySink.accept(summaryView);
        card.addView(block, new LinearLayout.LayoutParams(-1, -2));
        return sw;
    }

    private TextView mutedText(String text) {
        TextView view = Ui.text(context, text, 13);
        view.setTextColor(context.getColor(R.color.muted));
        return view;
    }

    private void addTop(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = child.getLayoutParams() instanceof LinearLayout.LayoutParams existing
                ? existing
                : new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(context, topDp);
        parent.addView(child, lp);
    }
}
