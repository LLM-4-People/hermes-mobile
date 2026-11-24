package org.llm4people.hermes.panel;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Shared native Material 3 paper/ink/teal widgets for Brain & Profile panels (DRY, Ui token SSoT). */
final class BrainUi {
    private BrainUi() {}

    static EditText searchField(Context context, int id, int hintRes) {
        EditText field = new EditText(context);
        if (id != 0) field.setId(id);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine();
        field.setTextSize(15);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setHint(hintRes);
        field.setTextColor(context.getColor(R.color.ink));
        field.setHintTextColor(context.getColor(R.color.muted));
        field.setBackgroundResource(R.drawable.profile_input);
        field.setBackgroundTintList(null);
        field.setMinHeight(Ui.dp(context, 48));
        Ui.pad(field, 14, 10);
        return field;
    }

    static EditText multilineField(Context context, int hintRes, int minLines) {
        EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setMinLines(minLines);
        field.setTextSize(16);
        field.setLineSpacing(0f, 1.25f);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setHint(hintRes);
        field.setTextColor(context.getColor(R.color.ink));
        field.setHintTextColor(context.getColor(R.color.muted));
        field.setBackgroundResource(R.drawable.profile_input);
        field.setBackgroundTintList(null);
        Ui.pad(field, 14, 12);
        return field;
    }

    /** Material 3 surface card (16dp corners, 1dp hairline border) with 6dp vertical breathing room. */
    static LinearLayout card(Context context) {
        LinearLayout card = Ui.card(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(context, 6), 0, Ui.dp(context, 6));
        card.setLayoutParams(params);
        return card;
    }

    static LinearLayout tintedBanner(Context context) {
        LinearLayout banner = Ui.row(context);
        banner.setBackground(Ui.shape(context, Ui.BUTTON_CORNER_DP, R.color.accent_surface, 0, 0));
        Ui.pad(banner, 14, 10);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(context, 6), 0, Ui.dp(context, 6));
        banner.setLayoutParams(params);
        return banner;
    }

    /** Material 3 choice chip (14dp rounded ripple, >= 48dp touch target, wrap-safe at 1.3x font scale). */
    static Button chip(Context context, String label, boolean selected, Runnable onClick) {
        Button chip = new Button(context, null, android.R.attr.borderlessButtonStyle);
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(14);
        chip.setMaxLines(2);
        chip.setTypeface(Ui.mediumTypeface());
        chip.setStateListAnimator(null);
        int minTouch = Ui.dp(context, 48);
        chip.setMinHeight(minTouch);
        chip.setMinimumHeight(minTouch);
        chip.setMinWidth(minTouch);
        chip.setMinimumWidth(minTouch);
        chip.setSelected(selected);
        GradientDrawable bg = selected
                ? Ui.shape(context, Ui.BUTTON_CORNER_DP, R.color.accent, 0, 0)
                : Ui.shape(context, Ui.BUTTON_CORNER_DP, R.color.surface, R.color.line, 1);
        chip.setTextColor(context.getColor(selected ? R.color.on_accent : R.color.ink));
        chip.setBackground(Ui.roundedRipple(context, Ui.BUTTON_CORNER_DP, bg));
        Ui.pad(chip, 14, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(Ui.dp(context, 8));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    static HorizontalScrollView chipBar(Context context, LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(context, 6), 0, Ui.dp(context, 6));
        scroll.setLayoutParams(lp);
        return scroll;
    }

    /** Capsule status pill backed by Ui.pill (999dp radius, crisp WCAG-AA contrast). */
    static TextView badge(Context context, String label, boolean accent) {
        TextView view = Ui.pill(context, label,
                accent ? R.color.accent_surface : R.color.surface_container,
                accent ? R.color.accent : R.color.muted);
        if (!accent) {
            view.setBackground(Ui.shape(context, 999, R.color.surface_container, R.color.line, 1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(Ui.dp(context, 6));
        view.setLayoutParams(lp);
        return view;
    }

    static TextView liveNote(SessionPanel panel, LinearLayout box, String message) {
        TextView note = panel.note(box, message);
        note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return note;
    }

    static void showUnavailable(SessionPanel panel, LinearLayout box, int messageRes, Runnable retry) {
        box.removeAllViews();
        liveNote(panel, box, panel.t(messageRes));
        if (retry != null) panel.action(box, R.string.panel_refresh, retry);
    }
}
