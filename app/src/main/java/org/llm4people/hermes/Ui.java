package org.llm4people.hermes;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared native Material 3 primitives. Dimensions follow screen density and text follows font scale. */
public final class Ui {
    public static final int BUTTON_CORNER_DP = 14;
    public static final int CARD_CORNER_DP = 16;

    private static Typeface mediumTypeface;

    /** Lazy holder so host-JVM tests calling null-safe helpers never trigger Android View stub init. */
    private static final class AccessibilityDelegates {
        static final View.AccessibilityDelegate BUTTON_ROLE = new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        };
        static final View.AccessibilityDelegate SELECTABLE_BUTTON_ROLE = new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setSelected(host.isSelected());
            }
        };
        static final View.AccessibilityDelegate CHECKABLE_CHECKED = new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(true);
            }
        };
        static final View.AccessibilityDelegate CHECKABLE_UNCHECKED = new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(false);
            }
        };
    }

    private Ui() {}

    /** Announce a custom clickable container (row/card) with Android Button role using one shared delegate. */
    public static void setButtonRole(View view) {
        if (view != null) {
            view.setAccessibilityDelegate(AccessibilityDelegates.BUTTON_ROLE);
        }
    }

    /** Announce a selectable custom button (nav tab, segment pill, chip) with Button role + host selection state. */
    public static void setSelectableButtonRole(View view) {
        if (view != null) {
            view.setAccessibilityDelegate(AccessibilityDelegates.SELECTABLE_BUTTON_ROLE);
        }
    }

    /** Announce a checkable chip/toggle button's checked state using two shared static delegates. */
    public static void setCheckableState(View view, boolean checked) {
        if (view != null) {
            view.setAccessibilityDelegate(
                    checked ? AccessibilityDelegates.CHECKABLE_CHECKED : AccessibilityDelegates.CHECKABLE_UNCHECKED);
        }
    }

    /** Cached medium-weight sans-serif typeface shared across native UI surfaces (lazy for host-JVM safety). */
    public static Typeface mediumTypeface() {
        if (mediumTypeface == null) {
            mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
        return mediumTypeface;
    }

    /** Enabled/disabled ColorStateList pairing {@code enabledColorRes} with semantic {@code R.color.disabled_ink}. */
    public static ColorStateList enabledDisabledInk(Context context, int enabledColorRes) {
        return enabledDisabledInk(context.getColor(enabledColorRes), context.getColor(R.color.disabled_ink));
    }

    /** Semantic destructive button ink preserving distinct disabled state. */
    public static ColorStateList dangerInk(Context context) {
        return enabledDisabledInk(context, R.color.danger);
    }

    /** ScrollView centering {@code content} within {@code maxColumnDp} using {@link AdaptiveLayout#centeredGutterDp}. */
    public static ScrollView centeredScroll(
            Context context, View content, int maxColumnDp, int minGutterDp, int topDp, int bottomDp) {
        ScrollView scroll = new ScrollView(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                float density = getResources().getDisplayMetrics().density;
                int gutter = dp(context, AdaptiveLayout.centeredGutterDp(
                        MeasureSpec.getSize(widthSpec) / density, maxColumnDp, minGutterDp));
                int top = dp(context, topDp);
                int bottom = dp(context, bottomDp);
                if (content.getPaddingStart() != gutter
                        || content.getPaddingTop() != top
                        || content.getPaddingBottom() != bottom) {
                    content.setPaddingRelative(gutter, top, gutter, bottom);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static void pad(View view, int horizontalDp, int verticalDp) {
        int h = dp(view.getContext(), horizontalDp);
        int v = dp(view.getContext(), verticalDp);
        view.setPaddingRelative(h, v, h, v);
    }

    public static TextView text(Context context, String label, float sp) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(sp);
        view.setTextColor(context.getColor(R.color.ink));
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return view;
    }

    /** Material 3 text button with rounded ripple, distinct disabled ink and >= 48dp touch target. */
    public static Button button(Context context, String label, View.OnClickListener action) {
        Button button = baseButton(context, label, action);
        button.setTextSize(14);
        button.setTextColor(enabledDisabledInk(
                context.getColor(R.color.accent), context.getColor(R.color.disabled_ink)));
        button.setBackground(roundedRipple(context, BUTTON_CORNER_DP, null));
        pad(button, 12, 8);
        return button;
    }

    /** Material 3 high-emphasis filled primary CTA button (14dp corners, muted disabled fill). */
    public static Button filledButton(Context context, String label, View.OnClickListener action) {
        Button button = baseButton(context, label, action);
        button.setTextSize(15);
        button.setTextColor(enabledDisabledInk(
                context.getColor(R.color.on_accent), context.getColor(R.color.disabled_ink)));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                shape(context, BUTTON_CORNER_DP, R.color.disabled_surface, R.color.line, 1));
        states.addState(new int[]{},
                shape(context, BUTTON_CORNER_DP, R.color.accent, 0, 0));
        button.setBackground(roundedRipple(context, BUTTON_CORNER_DP, states));
        pad(button, 20, 12);
        return button;
    }

    /** Material 3 medium-emphasis tonal button (accent_surface fill, muted disabled fill). */
    public static Button tonalButton(Context context, String label, View.OnClickListener action) {
        Button button = baseButton(context, label, action);
        button.setTextSize(14);
        button.setTextColor(enabledDisabledInk(
                context.getColor(R.color.accent), context.getColor(R.color.disabled_ink)));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                shape(context, BUTTON_CORNER_DP, R.color.disabled_surface, R.color.line, 1));
        states.addState(new int[]{},
                shape(context, BUTTON_CORNER_DP, R.color.accent_surface, 0, 0));
        button.setBackground(roundedRipple(context, BUTTON_CORNER_DP, states));
        pad(button, 16, 10);
        return button;
    }

    /** Material 3 outlined secondary button (surface fill + 1dp hairline outline). */
    public static Button outlinedButton(Context context, String label, View.OnClickListener action) {
        Button button = baseButton(context, label, action);
        button.setTextSize(14);
        button.setTextColor(enabledDisabledInk(
                context.getColor(R.color.ink), context.getColor(R.color.disabled_ink)));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                shape(context, BUTTON_CORNER_DP, R.color.disabled_surface, R.color.line, 1));
        states.addState(new int[]{},
                shape(context, BUTTON_CORNER_DP, R.color.surface, R.color.line, 1));
        button.setBackground(roundedRipple(context, BUTTON_CORNER_DP, states));
        pad(button, 16, 10);
        return button;
    }

    /** Material 3 checkable choice chip / segment button (&gt;= 48dp touch target, borderless M3 style). */
    public static Button choiceChip(Context context, String label, View.OnClickListener action) {
        Button button = baseButton(context, label, action);
        button.setTextSize(14);
        pad(button, 14, 10);
        return button;
    }

    /** Apply Material 3 selected/unselected chip surface, border, ink, and checkable a11y state. */
    public static void styleChoiceChip(Button button, boolean selected) {
        if (button == null) return;
        Context context = button.getContext();
        button.setBackground(roundedRipple(context, BUTTON_CORNER_DP, shape(
                context,
                BUTTON_CORNER_DP,
                selected ? R.color.accent_surface : R.color.surface,
                selected ? R.color.accent : R.color.line,
                selected ? 2 : 1)));
        button.setTextColor(context.getColor(selected ? R.color.accent : R.color.ink));
        button.setSelected(selected);
        setCheckableState(button, selected);
    }

    /** Apply Material 3 outlined dialog EditText styling (profile_input, ink/muted, 48dp minimum height). */
    public static void styleDialogInput(Context context, EditText input) {
        if (context == null || input == null) return;
        input.setBackgroundResource(R.drawable.profile_input);
        input.setBackgroundTintList(null);
        input.setTextColor(context.getColor(R.color.ink));
        input.setHintTextColor(context.getColor(R.color.muted));
        pad(input, 12, 10);
        int minHeight = dp(context, 48);
        input.setMinHeight(minHeight);
        input.setMinimumHeight(minHeight);
    }

    /** Clean Material 3 surface card (16dp corners, 1dp hairline border, single elevation layer). */
    public static LinearLayout card(Context context) {
        LinearLayout layout = column(context);
        layout.setBackground(shape(context, CARD_CORNER_DP, R.color.surface, R.color.line, 1));
        pad(layout, 16, 14);
        return layout;
    }

    /** Tonal surface card without stroke for calm grouping over paper. */
    public static LinearLayout tonalCard(Context context) {
        LinearLayout layout = column(context);
        layout.setBackground(shape(context, CARD_CORNER_DP, R.color.surface_container, 0, 0));
        pad(layout, 16, 14);
        return layout;
    }

    /** Compact rounded status badge (pure typography, zero emojis). */
    public static TextView pill(Context context, String label, int bgColorRes, int textColorRes) {
        TextView view = text(context, label, 12);
        view.setTypeface(mediumTypeface());
        view.setSingleLine(true);
        view.setTextColor(context.getColor(textColorRes));
        view.setPaddingRelative(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
        view.setBackground(shape(context, 999, bgColorRes, 0, 0));
        return view;
    }

    /** Circular 36dp initials monogram badge for profile cards and hubs. */
    public static TextView avatarBadge(Context context, String name) {
        return avatarBadge(context, initials(name), 36, false);
    }

    /** Circular initials monogram badge with configurable size and accent polarity. */
    public static TextView avatarBadge(Context context, String initials, int sizeDp, boolean accent) {
        TextView badge = new TextView(context);
        badge.setText(initials);
        badge.setTextSize(14);
        badge.setTypeface(mediumTypeface());
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(context.getColor(accent ? R.color.on_accent : R.color.accent));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(context.getColor(accent ? R.color.accent : R.color.accent_surface));
        badge.setBackground(circle);
        int size = dp(context, sizeDp);
        badge.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return badge;
    }

    /** Derive 1-2 uppercase initials from a display name for crisp typographic avatars. */
    public static String initials(String name) {
        return initials(name, null);
    }

    /** Derive 1-2 uppercase initials from {@code name} (falling back to {@code fallback} when blank). */
    public static String initials(String name, String fallback) {
        String source = (name != null && !name.trim().isEmpty())
                ? name.trim()
                : (fallback == null ? "" : fallback.trim());
        if (source.isEmpty()) return "H";
        String[] parts = source.split("[\\s_-]+");
        StringBuilder out = new StringBuilder(2);
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int cp = part.codePointAt(0);
            if (Character.isLetterOrDigit(cp)) {
                out.appendCodePoint(Character.toUpperCase(cp));
                if (out.length() == 2) break;
            }
        }
        return out.length() == 0 ? "H" : out.toString();
    }

    /** Build a rounded Material 3 RippleDrawable over optional background content. */
    public static RippleDrawable roundedRipple(Context context, int cornerDp, Drawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(context, cornerDp));
        return new RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.ripple)), content, mask);
    }

    /** Build a rounded GradientDrawable using color resource IDs. */
    public static GradientDrawable shape(
            Context context, int cornerDp, int fillColorRes, int strokeColorRes, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(context, cornerDp));
        if (fillColorRes != 0) drawable.setColor(context.getColor(fillColorRes));
        if (strokeColorRes != 0 && strokeDp > 0) {
            drawable.setStroke(Math.max(1, dp(context, strokeDp)), context.getColor(strokeColorRes));
        }
        return drawable;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    /** Dialogs have their own windows, independent of the activity's screen capture policy. */
    public static AlertDialog secureDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        return dialog;
    }

    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(context.getColor(R.color.line));
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(context, 1))));
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }

    public static View insetDivider(Context context, int startDp) {
        View line = divider(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(context, 1)));
        params.setMarginStart(dp(context, startDp));
        line.setLayoutParams(params);
        return line;
    }

    /** Decorative circular icon badge shared across Apps, Notes &amp; Tasks, and Settings rows. */
    public static LinearLayout iconCircle(Context context, int iconRes, int circleDp, int iconDp, boolean filled) {
        LinearLayout circle = new LinearLayout(context);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(context.getColor(filled ? R.color.accent : R.color.accent_surface));
        circle.setBackground(bg);
        circle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(context.getColor(filled ? R.color.on_accent : R.color.accent)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconPx = dp(context, iconDp);
        circle.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
        int circlePx = dp(context, circleDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(circlePx, circlePx);
        lp.setMarginEnd(dp(context, 12));
        circle.setLayoutParams(lp);
        return circle;
    }

    private static Button baseButton(Context context, String label, View.OnClickListener action) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTypeface(mediumTypeface());
        button.setStateListAnimator(null);
        int minTouch = dp(context, 48);
        button.setMinHeight(minTouch);
        button.setMinimumHeight(minTouch);
        button.setOnClickListener(action);
        return button;
    }

    /** Enabled/disabled ColorStateList pairing raw {@code enabledColor} and {@code disabledColor} ARGB values. */
    public static ColorStateList enabledDisabledInk(int enabledColor, int disabledColor) {
        return new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{disabledColor, enabledColor});
    }
}
