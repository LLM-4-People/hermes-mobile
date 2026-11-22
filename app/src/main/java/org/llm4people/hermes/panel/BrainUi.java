package org.llm4people.hermes.panel;

import android.content.Context;
import android.graphics.Typeface;
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

/** Shared native Material-free paper/ink/teal widgets for Brain Studio panels (DRY, DESIGN.md SSoT). */
final class BrainUi {
    private BrainUi() {}

    static EditText searchField(Context context, int id, int hintRes) {
        EditText field = new EditText(context);
        if (id != 0) field.setId(id);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine();
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
        Ui.pad(field, 12, 8);
        return field;
    }

    static EditText multilineField(Context context, int hintRes, int minLines) {
        EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMinLines(minLines);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setHint(hintRes);
        field.setTextColor(context.getColor(R.color.ink));
        field.setHintTextColor(context.getColor(R.color.muted));
        field.setBackgroundResource(R.drawable.profile_input);
        field.setBackgroundTintList(null);
        Ui.pad(field, 12, 10);
        return field;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = Ui.column(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(context.getColor(R.color.surface));
        bg.setCornerRadius(Ui.dp(context, 12));
        bg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.line));
        card.setBackground(bg);
        Ui.pad(card, 14, 12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(context, 6), 0, Ui.dp(context, 6));
        card.setLayoutParams(params);
        return card;
    }

    static LinearLayout tintedBanner(Context context) {
        LinearLayout banner = Ui.row(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(context.getColor(R.color.accent_surface));
        bg.setCornerRadius(Ui.dp(context, 10));
        banner.setBackground(bg);
        Ui.pad(banner, 12, 8);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(context, 6), 0, Ui.dp(context, 6));
        banner.setLayoutParams(params);
        return banner;
    }

    static Button chip(Context context, String label, boolean selected, Runnable onClick) {
        Button chip = new Button(context, null, android.R.attr.borderlessButtonStyle);
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(14);
        chip.setMinHeight(Ui.dp(context, 48));
        chip.setMinWidth(0);
        chip.setMinimumWidth(0);
        chip.setSelected(selected);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(context, 8));
        if (selected) {
            bg.setColor(context.getColor(R.color.accent));
            chip.setTextColor(context.getColor(R.color.on_accent));
            chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else {
            bg.setColor(context.getColor(R.color.surface));
            bg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.line));
            chip.setTextColor(context.getColor(R.color.ink));
        }
        chip.setBackground(bg);
        Ui.pad(chip, 12, 6);
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

    static TextView badge(Context context, String label, boolean accent) {
        TextView view = Ui.text(context, label, 13);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(context, 6));
        bg.setColor(context.getColor(accent ? R.color.accent_surface : R.color.paper));
        if (!accent) bg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.line));
        view.setBackground(bg);
        view.setTextColor(context.getColor(accent ? R.color.accent : R.color.muted));
        Ui.pad(view, 8, 3);
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
