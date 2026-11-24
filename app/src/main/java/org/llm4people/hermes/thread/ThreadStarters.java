package org.llm4people.hermes.thread;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.templates.WorkflowTemplate;

/** Optional Material 3 starter cards and quick workflow templates in an empty transcript. The composer owns every resulting draft. */
final class ThreadStarters {
    private static final int MAX_QUICK_TEMPLATES = 3;
    private static Typeface mediumTypeface;
    private final LinearLayout content;
    private final ListView list;

    private static Typeface mediumTypeface() {
        if (mediumTypeface == null) {
            mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
        return mediumTypeface;
    }

    ThreadStarters(ListView transcript, Consumer<String> edit) {
        list = transcript;
        Context context = transcript.getContext();
        content = Ui.column(context);
        Ui.pad(content, 18, 16);
        content.setVisibility(View.GONE);
        TextView heading = Ui.text(context, context.getString(R.string.starter_heading), 24);
        heading.setTypeface(mediumTypeface());
        heading.setLetterSpacing(-0.02f);
        heading.setPaddingRelative(0, 0, 0, Ui.dp(context, 12));
        heading.setAccessibilityHeading(true);
        content.addView(heading);
        add(R.string.starter_question, 0, edit);
        add(R.string.starter_code, R.string.starter_code_draft, edit);
        add(R.string.starter_plan, R.string.starter_plan_draft, edit);
        addQuickTemplates(context, edit);

        // The list measures its header even when a child is gone. Keep the hidden header zero-height.
        FrameLayout header = new FrameLayout(context);
        header.addView(content);
        transcript.addHeaderView(header, null, false);
        transcript.setItemsCanFocus(true);
        transcript.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) alignEmptyList();
        });
    }

    private void add(int label, int draft, Consumer<String> edit) {
        Context context = content.getContext();
        Button action = Ui.outlinedButton(context, context.getString(label), view -> {
            if (content.isShown()) edit.accept(draft == 0 ? "" : context.getString(draft) + "\n");
        });
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setSingleLine(false);
        action.setTextSize(15);
        action.setTypeface(Typeface.DEFAULT);
        action.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_navigate_forward, 0);
        action.setCompoundDrawableTintList(context.getColorStateList(R.color.accent));
        action.setCompoundDrawablePadding(Ui.dp(context, 10));
        Ui.pad(action, 16, 12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(context, 8);
        content.addView(action, params);
    }

    private void addQuickTemplates(Context context, Consumer<String> edit) {
        List<WorkflowTemplate> builtIns = WorkflowTemplate.builtIn(context);
        if (builtIns == null || builtIns.isEmpty()) return;

        TextView label = Ui.text(context, context.getString(R.string.thread_starters_templates_heading), 14);
        label.setTypeface(mediumTypeface());
        label.setTextColor(context.getColor(R.color.muted));
        label.setAccessibilityHeading(true);
        label.setPaddingRelative(0, Ui.dp(context, 12), 0, Ui.dp(context, 8));
        content.addView(label);

        HorizontalScrollView strip = new HorizontalScrollView(context);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(context);
        int gap = Ui.dp(context, 8);
        int count = Math.min(MAX_QUICK_TEMPLATES, builtIns.size());
        for (int i = 0; i < count; i++) {
            WorkflowTemplate tpl = builtIns.get(i);
            Button chip = Ui.tonalButton(context, tpl.title, v -> {
                if (content.isShown()) edit.accept(tpl.draft);
            });
            chip.setContentDescription(context.getString(R.string.templates_chip_cd, tpl.title));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i < count - 1) lp.setMarginEnd(gap);
            row.addView(chip, lp);
        }
        strip.addView(row);
        content.addView(strip);
    }

    static boolean available(boolean confirmedEmpty, String draft, boolean pending, List<String> slots) {
        return confirmedEmpty && draft.isEmpty() && !pending
                && (slots.isEmpty() || slots.contains("composer") && slots.contains("transcript"));
    }

    void show(boolean available) {
        int visibility = available ? View.VISIBLE : View.GONE;
        if (content.getVisibility() == visibility) return;
        content.setVisibility(visibility);
        alignEmptyList();
    }

    private void alignEmptyList() {
        if (content.getVisibility() != View.VISIBLE || list.getWidth() == 0) {
            list.setStackFromBottom(false);
            return;
        }
        Context context = content.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        float listWidthDp = density > 0f ? list.getWidth() / density : 0f;
        int sideGutterDp = org.llm4people.hermes.AdaptiveLayout.centeredGutterDp(
                listWidthDp, org.llm4people.hermes.AdaptiveLayout.READING_COLUMN_MAX_DP, 18);
        Ui.pad(content, sideGutterDp, 16);
        // Bring the examples to the editor when they fit. With a short keyboard viewport
        // or large type, begin at the heading and let the native list scroll normally.
        content.measure(View.MeasureSpec.makeMeasureSpec(list.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.setStackFromBottom(content.getMeasuredHeight() <= list.getHeight());
    }
}
