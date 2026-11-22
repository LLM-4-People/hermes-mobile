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

    ThreadStarters(ListView transcript, Consumer<String> edit) {
        Context context = transcript.getContext();
        content = Ui.column(context);
        Ui.pad(content, 18, 12);
        content.setVisibility(View.GONE);
        TextView heading = Ui.text(context, context.getString(R.string.starter_heading), 20);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        content.addView(heading);
        add(R.string.starter_question, 0, edit);
        add(R.string.starter_code, R.string.starter_code_draft, edit);
        add(R.string.starter_plan, R.string.starter_plan_draft, edit);

        // The list measures its header even when a child is gone. Keep the hidden header zero-height.
        FrameLayout header = new FrameLayout(context);
        header.addView(content);
        transcript.addHeaderView(header, null, false);
        transcript.setItemsCanFocus(true);
    }

    private void add(int label, int draft, Consumer<String> edit) {
        Context context = content.getContext();
        Button action = Ui.button(context, context.getString(label), view -> {
            if (content.isShown()) edit.accept(draft == 0 ? "" : context.getString(draft) + "\n");
        });
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setSingleLine(false);
        content.addView(action);
    }

    static boolean available(boolean confirmedEmpty, String draft, boolean pending, List<String> slots) {
        return confirmedEmpty && draft.isEmpty() && !pending
                && (slots.isEmpty() || slots.contains("composer") && slots.contains("transcript"));
    }

    void show(boolean available) { content.setVisibility(available ? View.VISIBLE : View.GONE); }
}
