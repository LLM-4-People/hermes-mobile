package org.llm4people.hermes.presence;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.os.PermissionCenter;

import java.util.ArrayList;
import java.util.List;
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
    private static volatile Typeface mediumTypeface;

    private static Typeface mediumTypeface() {
        Typeface cached = mediumTypeface;
        if (cached == null) {
            cached = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            mediumTypeface = cached;
        }
        return cached;
    }

    public interface Listener {
        default void onPolicyChanged(PresencePolicy policy) {}
        default void onCustomizationChanged(AppCustomization customization) {}
        default void onRequestLocationPermission() {}
        default void onRequestPreciseLocationPermission() { onRequestLocationPermission(); }
    }

    private final Context context;
    private final ContinuousLocationTracker locationTracker;
    private final AppCustomizationStore customizationStore;
    private final Consumer<PresencePolicy> policySaver;
    private final Listener listener;

    private PresencePolicy policy;
    private TextView statusBanner;
    private TextView feedback;
    private Button feedbackAction;
    private Button pauseLocationButton;
    private Button undoButton;
    private Switch screenSwitch;
    private Switch readSwitch;
    private Switch locationSwitch;
    private Switch preciseSwitch;
    private Switch nudgeSwitch;
    private boolean binding;
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
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(context.getColor(R.color.paper));

        LinearLayout body = Ui.column(context);
        Ui.pad(body, 20, 18);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(context, context.getString(R.string.presence_panel_title), 24);
        title.setTypeface(mediumTypeface());
        title.setAccessibilityHeading(true);
        body.addView(title);

        TextView intro = mutedText(context.getString(R.string.presence_panel_intro));
        addTop(body, intro, 6);

        statusBanner = Ui.text(context, "", 14);
        statusBanner.setTypeface(mediumTypeface());
        statusBanner.setTag("presence:status_banner");
        Ui.pad(statusBanner, 14, 10);
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(context.getColor(R.color.accent_surface));
        bannerBg.setCornerRadius(Ui.dp(context, 10));
        statusBanner.setBackground(bannerBg);
        addTop(body, statusBanner, 12);

        int minTouch = Ui.dp(context, 48);
        LinearLayout quickControls = Ui.row(context);
        pauseLocationButton = Ui.button(context, context.getString(R.string.presence_pause_location), v -> toggleLocationPause());
        pauseLocationButton.setTag("presence:pause_location");
        pauseLocationButton.setMinWidth(minTouch);
        pauseLocationButton.setMinimumWidth(minTouch);

        Button allOffButton = Ui.button(context, context.getString(R.string.presence_turn_all_off), v -> turnEverythingOff());
        allOffButton.setTag("presence:turn_all_off");
        allOffButton.setTextColor(context.getColor(R.color.danger));
        allOffButton.setMinWidth(minTouch);
        allOffButton.setMinimumWidth(minTouch);

        quickControls.addView(pauseLocationButton);
        quickControls.addView(allOffButton);
        addTop(body, horizontalRow(quickControls), 6);

        feedback = Ui.text(context, "", 14);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        addTop(body, feedback, 6);

        feedbackAction = Ui.button(context, "", null);
        feedbackAction.setTag("presence:feedback_action");
        feedbackAction.setMinWidth(minTouch);
        feedbackAction.setMinimumWidth(minTouch);
        feedbackAction.setVisibility(View.GONE);
        addTop(body, feedbackAction, 4);

        addSectionHeader(body, context.getString(R.string.presence_section_sharing));

        screenSwitch = addToggle(body, "presence:toggle_screen",
                context.getString(R.string.presence_toggle_screen_title),
                context.getString(R.string.presence_toggle_screen_summary),
                checked -> applyPolicy(policy.withAppPresence(checked), context.getString(R.string.presence_feedback_saved)));

        readSwitch = addToggle(body, "presence:toggle_read",
                context.getString(R.string.presence_toggle_read_title),
                context.getString(R.string.presence_toggle_read_summary),
                checked -> applyPolicy(policy.withReadWithoutReply(checked), context.getString(R.string.presence_feedback_saved)));

        locationSwitch = addToggle(body, "presence:toggle_location",
                context.getString(R.string.presence_toggle_location_title),
                context.getString(R.string.presence_toggle_location_summary),
                this::handleLocationToggle);

        preciseSwitch = addToggle(body, "presence:toggle_precise",
                context.getString(R.string.presence_toggle_precise_title),
                context.getString(R.string.presence_toggle_precise_summary),
                this::handlePreciseLocationToggle);

        nudgeSwitch = addToggle(body, "presence:toggle_nudges",
                context.getString(R.string.presence_toggle_nudges_title),
                context.getString(R.string.presence_toggle_nudges_summary),
                checked -> applyPolicy(policy.withProactiveNudges(checked), context.getString(R.string.presence_feedback_saved)));

        buildCustomizationSection(body);
        refresh();
        return scroll;
    }

    public void refresh() {
        binding = true;
        try {
            if (screenSwitch != null) screenSwitch.setChecked(policy.shareAppPresence);
            if (readSwitch != null) readSwitch.setChecked(policy.shareReadWithoutReply);
            if (locationSwitch != null) locationSwitch.setChecked(policy.continuousLocationEnabled);
            if (preciseSwitch != null) {
                preciseSwitch.setChecked(policy.preciseLocation);
                preciseSwitch.setEnabled(policy.continuousLocationEnabled);
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

        addTop(body, Ui.text(context, context.getString(R.string.custom_palette_label), 14), 10);
        LinearLayout paletteRow1 = Ui.row(context);
        LinearLayout paletteRow2 = Ui.row(context);
        addPaletteChip(paletteRow1, AppCustomization.PalettePreset.FOREST, R.string.custom_palette_forest);
        addPaletteChip(paletteRow1, AppCustomization.PalettePreset.SLATE, R.string.custom_palette_slate);
        addPaletteChip(paletteRow1, AppCustomization.PalettePreset.TERRACOTTA, R.string.custom_palette_terracotta);
        addPaletteChip(paletteRow2, AppCustomization.PalettePreset.INDIGO, R.string.custom_palette_indigo);
        addPaletteChip(paletteRow2, AppCustomization.PalettePreset.PLUM, R.string.custom_palette_plum);
        addTop(body, horizontalRow(paletteRow1), 6);
        addTop(body, horizontalRow(paletteRow2), 4);

        addTop(body, Ui.text(context, context.getString(R.string.custom_density_label), 14), 12);
        LinearLayout densityRow = Ui.row(context);
        addDensityChip(densityRow, AppCustomization.Density.COMPACT, R.string.custom_density_compact);
        addDensityChip(densityRow, AppCustomization.Density.COMFORTABLE, R.string.custom_density_comfortable);
        addDensityChip(densityRow, AppCustomization.Density.SPACIOUS, R.string.custom_density_spacious);
        addTop(body, horizontalRow(densityRow), 6);

        int minTouch = Ui.dp(context, 48);
        LinearLayout undoRow = Ui.row(context);
        undoButton = Ui.button(context, context.getString(R.string.custom_undo), v -> {
            AppCustomization restored = customizationStore.undo();
            listener.onCustomizationChanged(restored);
            showFeedback(context.getString(R.string.custom_feedback_undone));
            refresh();
        });
        undoButton.setTag("custom:undo");
        undoButton.setMinWidth(minTouch);
        undoButton.setMinimumWidth(minTouch);

        Button resetButton = Ui.button(context, context.getString(R.string.custom_reset), v -> {
            AppCustomization reset = customizationStore.resetToDefault();
            listener.onCustomizationChanged(reset);
            showFeedback(context.getString(R.string.custom_feedback_reset));
            refresh();
        });
        resetButton.setTag("custom:reset");
        resetButton.setMinWidth(minTouch);
        resetButton.setMinimumWidth(minTouch);

        undoRow.addView(undoButton);
        undoRow.addView(resetButton);
        addTop(body, horizontalRow(undoRow), 10);
    }

    private HorizontalScrollView horizontalRow(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void addPaletteChip(LinearLayout row, AppCustomization.PalettePreset preset, int labelRes) {
        Button chip = Ui.button(context, context.getString(labelRes), v -> {
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
        row.addView(chip);
    }

    private void addDensityChip(LinearLayout row, AppCustomization.Density density, int labelRes) {
        Button chip = Ui.button(context, context.getString(labelRes), v -> {
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
        row.addView(chip);
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
            showFeedbackWithAction(context.getString(R.string.perm_location_blocked),
                    context.getString(R.string.perm_action_open_settings),
                    () -> {
                        if (!PermissionCenter.openAppSettings(context)) {
                            showFeedback(context.getString(R.string.perm_settings_unavailable));
                        }
                    });
        } else {
            showFeedbackWithAction(context.getString(R.string.presence_feedback_location_permission),
                    context.getString(R.string.perm_action_allow_location),
                    () -> {
                        PermissionCenter.recordRequested(context, key);
                        if (fine) listener.onRequestPreciseLocationPermission();
                        else listener.onRequestLocationPermission();
                    });
        }
        refresh();
    }

    private void handleLocationToggle(boolean enabled) {
        if (enabled && !ContinuousLocationTracker.hasCoarsePermission(context)) {
            PermissionCenter.State state = PermissionCenter.state(context, PermissionCenter.Key.LOCATION);
            if (state == PermissionCenter.State.PERMANENTLY_DENIED) {
                applyPolicy(policy.withContinuousLocation(false),
                        context.getString(R.string.perm_location_blocked));
                showFeedbackWithAction(context.getString(R.string.perm_location_blocked),
                        context.getString(R.string.perm_action_open_settings),
                        () -> {
                            if (!PermissionCenter.openAppSettings(context)) {
                                showFeedback(context.getString(R.string.perm_settings_unavailable));
                            }
                        });
                return;
            }
            PermissionCenter.recordRequested(context, PermissionCenter.Key.LOCATION);
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
            if (state == PermissionCenter.State.UNAVAILABLE) {
                applyPolicy(policy.withPreciseLocation(false),
                        context.getString(R.string.perm_precise_location_unavailable));
                return;
            }
            if (state == PermissionCenter.State.PERMANENTLY_DENIED) {
                applyPolicy(policy.withPreciseLocation(false),
                        context.getString(R.string.perm_location_blocked));
                showFeedbackWithAction(context.getString(R.string.perm_location_blocked),
                        context.getString(R.string.perm_action_open_settings),
                        () -> {
                            if (!PermissionCenter.openAppSettings(context)) {
                                showFeedback(context.getString(R.string.perm_settings_unavailable));
                            }
                        });
                return;
            }
            PermissionCenter.recordRequested(context, PermissionCenter.Key.PRECISE_LOCATION);
            applyPolicy(policy.withPreciseLocation(false),
                    context.getString(R.string.presence_feedback_location_permission));
            listener.onRequestPreciseLocationPermission();
            return;
        }
        applyPolicy(policy.withPreciseLocation(enabled), context.getString(R.string.presence_feedback_saved));
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
        } else {
            statusBanner.setText(context.getString(R.string.presence_status_active, String.join(" · ", tags)));
        }
    }

    private void showFeedback(String text) {
        if (feedback == null) return;
        feedback.setText(text);
        feedback.setVisibility(View.VISIBLE);
        if (feedbackAction != null) {
            feedbackAction.setVisibility(View.GONE);
            feedbackAction.setOnClickListener(null);
        }
    }

    private void showFeedbackWithAction(String text, String actionLabel, Runnable onClick) {
        showFeedback(text);
        if (feedbackAction != null && onClick != null) {
            feedbackAction.setText(actionLabel);
            feedbackAction.setOnClickListener(v -> onClick.run());
            feedbackAction.setVisibility(View.VISIBLE);
        }
    }

    private void addSectionHeader(LinearLayout parent, String label) {
        addTop(parent, Ui.divider(context), 14);
        TextView header = Ui.text(context, label, 18);
        header.setTypeface(mediumTypeface());
        header.setAccessibilityHeading(true);
        addTop(parent, header, 12);
    }

    private Switch addToggle(
            LinearLayout parent, String tag, String title, String summary, Consumer<Boolean> onToggle) {
        LinearLayout block = Ui.column(context);
        Switch sw = new Switch(context);
        sw.setText(title);
        sw.setTextSize(15);
        sw.setTextColor(context.getColor(R.color.ink));
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
        block.addView(mutedText(summary));
        addTop(parent, block, 10);
        return sw;
    }

    private TextView mutedText(String text) {
        TextView view = Ui.text(context, text, 13);
        view.setTextColor(context.getColor(R.color.muted));
        return view;
    }

    private void addTop(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(context, topDp);
        parent.addView(child, lp);
    }
}
