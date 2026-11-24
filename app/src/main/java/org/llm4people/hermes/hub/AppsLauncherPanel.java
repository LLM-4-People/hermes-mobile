package org.llm4people.hermes.hub;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.settings.SettingsWrapLayout;

import java.util.List;
import java.util.Objects;

/**
 * Living Material 3 Apps Launcher screen focused strictly on interactive tools you run and do:
 * Hands-Free Voice hero card, live Custom Screens &amp; Tools cards, and Reusable Workflows.
 * Intentionally excludes Memory, Skills, Workspace Files, and Settings.
 */
public final class AppsLauncherPanel {
    public static final String ROUTE_ID = "apps";
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public interface StateProvider {
        String voiceLocaleLabel();
        List<AppsLauncherModel.ScreenEntry> screens();
        int templateCount();
    }

    public interface Callbacks {
        void startVoiceChat();
        void openVoiceOptions();
        void openScreen(String pageId);
        void createTool();
        void openScreensGallery();
        void pinHomeWidget();
        void restoreDefaults();
        void openTemplates();
    }

    private final Activity activity;
    private final StateProvider state;
    private final Callbacks callbacks;

    private LinearLayout voiceSlot;
    private LinearLayout screensSlot;
    private LinearLayout workflowsSlot;

    public AppsLauncherPanel(Activity activity, StateProvider state, Callbacks callbacks) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.state = Objects.requireNonNull(state, "state");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    public View buildView() {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                float density = getResources().getDisplayMetrics().density;
                int gutter = Ui.dp(activity, AdaptiveLayout.centeredGutterDp(
                        MeasureSpec.getSize(widthSpec) / density, AdaptiveLayout.READING_COLUMN_MAX_DP, 20));
                int top = Ui.dp(activity, 14);
                int bottom = Ui.dp(activity, 28);
                if (list.getPaddingStart() != gutter || list.getPaddingTop() != top) {
                    list.setPaddingRelative(gutter, top, gutter, bottom);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setTag("hub:apps");

        voiceSlot = Ui.column(activity);
        list.addView(voiceSlot, new LinearLayout.LayoutParams(-1, -2));

        screensSlot = Ui.column(activity);
        list.addView(screensSlot, new LinearLayout.LayoutParams(-1, -2));

        workflowsSlot = Ui.column(activity);
        list.addView(workflowsSlot, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        refresh();
        Diagnostics.debug("apps_launcher", "opened");
        return scroll;
    }

    public void refresh() {
        if (voiceSlot == null || screensSlot == null || workflowsSlot == null) return;
        renderVoiceHero();
        renderScreensSection();
        renderWorkflowsSection();
    }

    private void renderVoiceHero() {
        voiceSlot.removeAllViews();
        LinearLayout card = Ui.card(activity);
        card.setTag("apps:voice_hero");

        LinearLayout header = Ui.row(activity);
        header.addView(iconCircle(R.drawable.ic_voice_settings, 44, 22, true));

        LinearLayout copy = Ui.column(activity);
        TextView title = Ui.text(activity, activity.getString(R.string.hub_apps_voice_title), 18);
        title.setTypeface(MEDIUM);
        copy.addView(title);

        TextView body = Ui.text(activity, activity.getString(R.string.hub_apps_voice_body), 14);
        body.setTextColor(activity.getColor(R.color.muted));
        body.setLineSpacing(0f, 1.2f);
        body.setPaddingRelative(0, Ui.dp(activity, 3), 0, 0);
        copy.addView(body);
        header.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(header);

        String localeLabel = state.voiceLocaleLabel();
        if (localeLabel != null && !localeLabel.trim().isEmpty()) {
            TextView pill = Ui.pill(activity, localeLabel.trim(), R.color.accent_surface, R.color.accent);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2, -2);
            pLp.topMargin = Ui.dp(activity, 10);
            card.addView(pill, pLp);
        }

        SettingsWrapLayout actions = new SettingsWrapLayout(activity, 10, 8);
        actions.addView(Ui.filledButton(activity, activity.getString(R.string.hub_apps_voice_start),
                v -> callbacks.startVoiceChat()));
        actions.addView(Ui.outlinedButton(activity, activity.getString(R.string.hub_apps_voice_options),
                v -> callbacks.openVoiceOptions()));
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-1, -2);
        actLp.topMargin = Ui.dp(activity, 14);
        card.addView(actions, actLp);

        voiceSlot.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderScreensSection() {
        screensSlot.removeAllViews();
        addSectionHeading(screensSlot, activity.getString(R.string.hub_apps_screens_section));

        LinearLayout card = Ui.column(activity);
        card.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.line, 1));
        card.setClipToOutline(true);

        List<AppsLauncherModel.ScreenEntry> screens = state.screens();
        if (screens == null || screens.isEmpty()) {
            LinearLayout empty = Ui.row(activity);
            Ui.pad(empty, 16, 16);
            empty.addView(iconCircle(R.drawable.ic_widgets, 40, 20, false));
            LinearLayout copy = Ui.column(activity);
            TextView title = Ui.text(activity, activity.getString(R.string.hub_apps_screens_empty_title), 16);
            title.setTypeface(MEDIUM);
            copy.addView(title);
            TextView body = Ui.text(activity, activity.getString(R.string.hub_apps_screens_empty_body), 14);
            body.setTextColor(activity.getColor(R.color.muted));
            body.setLineSpacing(0f, 1.2f);
            body.setPaddingRelative(0, Ui.dp(activity, 3), 0, 0);
            copy.addView(body);
            empty.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(empty);
        } else {
            for (int i = 0; i < screens.size(); i++) {
                AppsLauncherModel.ScreenEntry entry = screens.get(i);
                if (i > 0) card.addView(Ui.insetDivider(activity, 64));
                card.addView(buildScreenRow(entry));
            }
        }

        card.addView(Ui.insetDivider(activity, 16));

        SettingsWrapLayout controls = new SettingsWrapLayout(activity, 10, 8);
        Ui.pad(controls, 16, 14);
        controls.addView(Ui.filledButton(activity, activity.getString(R.string.hub_apps_create_tool),
                v -> callbacks.createTool()));
        controls.addView(Ui.tonalButton(activity, activity.getString(R.string.ws_my_screens),
                v -> callbacks.openScreensGallery()));
        controls.addView(Ui.outlinedButton(activity, activity.getString(R.string.widget_pin_action),
                v -> callbacks.pinHomeWidget()));
        controls.addView(Ui.outlinedButton(activity, activity.getString(R.string.ws_restore_desk),
                v -> callbacks.restoreDefaults()));
        card.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        screensSlot.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderWorkflowsSection() {
        workflowsSlot.removeAllViews();
        addSectionHeading(workflowsSlot, activity.getString(R.string.hub_apps_workflows_section));

        LinearLayout card = Ui.column(activity);
        card.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.line, 1));
        card.setClipToOutline(true);

        int count = Math.max(0, state.templateCount());
        String countText = activity.getString(R.string.hub_apps_templates_count, count);
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(Ui.dp(activity, 64));
        row.setPaddingRelative(Ui.dp(activity, 16), Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Ui.roundedRipple(activity, 0, null));
        row.setOnClickListener(v -> callbacks.openTemplates());
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        String label = activity.getString(R.string.templates_route_label);
        row.setContentDescription(label + ". " + countText);

        row.addView(iconCircle(R.drawable.ic_apps, 36, 20, false));

        LinearLayout copy = Ui.column(activity);
        TextView titleView = Ui.text(activity, label, 16);
        titleView.setTypeface(MEDIUM);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        copy.addView(titleView);
        TextView pill = Ui.pill(activity, countText, R.color.accent_surface, R.color.accent);
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2, -2);
        pLp.topMargin = Ui.dp(activity, 4);
        copy.addView(pill, pLp);

        TextView detail = Ui.text(activity, activity.getString(R.string.hub_apps_templates_body), 14);
        detail.setTextColor(activity.getColor(R.color.muted));
        detail.setLineSpacing(0f, 1.2f);
        detail.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        copy.addView(detail);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_navigate_forward);
        arrow.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.muted)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(Ui.dp(activity, 18), Ui.dp(activity, 18));
        arrowLp.setMarginStart(Ui.dp(activity, 8));
        row.addView(arrow, arrowLp);

        card.addView(row);
        workflowsSlot.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private View buildScreenRow(AppsLauncherModel.ScreenEntry entry) {
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(Ui.dp(activity, 56));
        row.setPaddingRelative(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 14), Ui.dp(activity, 12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Ui.roundedRipple(activity, 0, null));
        row.setOnClickListener(v -> callbacks.openScreen(entry.pageId()));
        row.setContentDescription(entry.title());
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });

        row.addView(iconCircle(R.drawable.ic_widgets, 36, 20, false));

        TextView title = Ui.text(activity, entry.title(), 16);
        title.setTypeface(MEDIUM);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_navigate_forward);
        arrow.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.muted)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(Ui.dp(activity, 18), Ui.dp(activity, 18));
        arrowLp.setMarginStart(Ui.dp(activity, 8));
        row.addView(arrow, arrowLp);
        return row;
    }

    private void addSectionHeading(LinearLayout parent, String title) {
        TextView heading = Ui.text(activity, title, 18);
        heading.setTypeface(MEDIUM);
        heading.setLetterSpacing(-0.01f);
        heading.setPaddingRelative(Ui.dp(activity, 4), Ui.dp(activity, 20), 0, Ui.dp(activity, 8));
        heading.setAccessibilityHeading(true);
        parent.addView(heading);
    }

    private LinearLayout iconCircle(int iconRes, int circleDp, int iconDp, boolean filled) {
        LinearLayout circle = new LinearLayout(activity);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(activity.getColor(filled ? R.color.accent : R.color.accent_surface));
        circle.setBackground(bg);
        circle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(activity.getColor(filled ? R.color.on_accent : R.color.accent)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconPx = Ui.dp(activity, iconDp);
        circle.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
        int circlePx = Ui.dp(activity, circleDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(circlePx, circlePx);
        lp.setMarginEnd(Ui.dp(activity, 12));
        circle.setLayoutParams(lp);
        return circle;
    }
}
