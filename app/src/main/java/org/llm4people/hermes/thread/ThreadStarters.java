package org.llm4people.hermes.thread;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Optional examples in an empty transcript. The composer owns every resulting draft. */
final class ThreadStarters {
    private final LinearLayout content;
    private final ListView list;

    ThreadStarters(ListView transcript, Consumer<String> edit) {
        list = transcript;
        Context context = transcript.getContext();
        content = Ui.column(context);
        Ui.pad(content, 18, 12);
        content.setVisibility(View.GONE);
        TextView heading = Ui.text(context, context.getString(R.string.starter_heading), 26);
        heading.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        heading.setLetterSpacing(-0.025f);
        heading.setPaddingRelative(0, 0, 0, Ui.dp(context, 6));
        heading.setAccessibilityHeading(true);
        content.addView(heading);
        add(R.string.starter_question, 0, edit);
        content.addView(Ui.divider(context));
        add(R.string.starter_code, R.string.starter_code_draft, edit);
        content.addView(Ui.divider(context));
        add(R.string.starter_plan, R.string.starter_plan_draft, edit);

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
        Button action = Ui.button(context, context.getString(label), view -> {
            if (content.isShown()) edit.accept(draft == 0 ? "" : context.getString(draft) + "\n");
        });
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setSingleLine(false);
        action.setTextSize(16);
        action.setTextColor(context.getColor(R.color.ink));
        action.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_go, 0);
        action.setCompoundDrawableTintList(context.getColorStateList(R.color.accent));
        action.setCompoundDrawablePadding(Ui.dp(context, 8));
        Ui.pad(action, 0, 4);
        content.addView(action);
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
        // Bring the examples to the editor when they fit. With a short keyboard viewport
        // or large type, begin at the heading and let the native list scroll normally.
        content.measure(View.MeasureSpec.makeMeasureSpec(list.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.setStackFromBottom(content.getMeasuredHeight() <= list.getHeight());
    }
}
