package org.llm4people.hermes.thread;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import java.util.ArrayList;
import java.util.List;

final class MessageText {
    private MessageText() {}

    static CharSequence styled(Context context, TranscriptPage page) {
        SpannableStringBuilder content = new SpannableStringBuilder(page.text);
        for (TranscriptPage.Mark mark : page.marks) {
            switch (mark.style) {
                case HEADING:
                    span(content, mark, new RelativeSizeSpan(1.16f));
                    span(content, mark, new StyleSpan(Typeface.BOLD));
                    break;
                case BOLD: span(content, mark, new StyleSpan(Typeface.BOLD)); break;
                case ITALIC: span(content, mark, new StyleSpan(Typeface.ITALIC)); break;
                case INLINE_CODE: case CODE: case INSERTION: case DELETION:
                    span(content, mark, new TypefaceSpan("monospace"));
                    span(content, mark, new RelativeSizeSpan(.94f));
                    span(content, mark, new BackgroundColorSpan(context.getColor(R.color.surface)));
                    if (mark.style == TranscriptPage.Style.INSERTION || mark.style == TranscriptPage.Style.DELETION) {
                        int color = mark.style == TranscriptPage.Style.INSERTION ? R.color.accent : R.color.danger;
                        span(content, mark, new ForegroundColorSpan(context.getColor(color)));
                    }
                    break;
            }
        }
        return content;
    }

    static void updateLive(TextView target, LiveTranscript stream, String source) {
        int previousStart = stream.windowStart();
        if (!stream.update(source)) return;
        Context context = target.getContext();
        SpannableStringBuilder rendered = new SpannableStringBuilder();
        if (stream.earlier) rendered.append("…\n");
        if (stream.preceding != null) rendered.append(styled(context, stream.preceding));
        rendered.append(styled(context, stream.latest));
        if (stream.later) rendered.append("…");
        // Preserve a selection only while its original passage remains at the same offsets.
        int selectedThrough = Math.max(target.getSelectionStart(), target.getSelectionEnd());
        if (target.hasSelection() && stream.windowStart() == previousStart && selectedThrough <= rendered.length()
                && TextUtils.regionMatches(target.getText(), 0, rendered, 0, selectedThrough)) {
            target.setTextKeepState(rendered);
        } else {
            target.setText(rendered);
        }
    }

    private static void span(SpannableStringBuilder content, TranscriptPage.Mark mark, Object style) {
        content.setSpan(style, mark.start, mark.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** One selectable page at a time, even for multi-megabyte tool logs. */
    static View reader(Context context, String source, boolean markdown, boolean tool) {
        LinearLayout root = Ui.column(context);
        Ui.pad(root, 16, 6);
        LinearLayout nav = Ui.row(context);
        nav.setGravity(Gravity.CENTER);
        List<TranscriptPage.Cursor> history = new ArrayList<>();
        history.add(new TranscriptPage.Cursor());
        TextView text = Ui.text(context, "", 16);
        text.setTypeface(tool ? Typeface.MONOSPACE : Typeface.DEFAULT);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        TextView position = Ui.text(context, "", 14);
        Button previous = Ui.button(context, context.getString(R.string.transcript_previous), null);
        Button next = Ui.button(context, context.getString(R.string.transcript_next), null);
        nav.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(position);
        nav.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(nav);
        root.addView(scroll);
        class Pages {
            void show(int index) {
                TranscriptPage page = new TranscriptPage(source, history.get(index), markdown, TranscriptPage.PAGE_CHARS);
                if (page.hasMore() && history.size() == index + 1) history.add(page.next);
                text.setText(styled(context, page));
                position.setText(context.getString(R.string.transcript_page, index + 1));
                previous.setEnabled(index > 0);
                next.setEnabled(page.hasMore());
                previous.setOnClickListener(v -> show(index - 1));
                next.setOnClickListener(v -> show(index + 1));
                scroll.scrollTo(0, 0);
                position.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            }
        }
        new Pages().show(0);
        return root;
    }
}
