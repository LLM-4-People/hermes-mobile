package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
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
import org.llm4people.hermes.artifact.CodeReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
                    if (mark.style == TranscriptPage.Style.INLINE_CODE)
                        span(content, mark, new BackgroundColorSpan(context.getColor(R.color.surface)));
                    if (mark.style == TranscriptPage.Style.INSERTION || mark.style == TranscriptPage.Style.DELETION) {
                        int color = mark.style == TranscriptPage.Style.INSERTION ? R.color.accent : R.color.danger;
                        span(content, mark, new ForegroundColorSpan(context.getColor(color)));
                    }
                    break;
            }
        }
        int inset = Ui.dp(context, 10);
        int paper = context.getColor(R.color.surface);
        for (TranscriptPage.Block block : page.blocks) {
            // One continuous surface across wrapped lines, indentation and blank lines.
            LineBackgroundSpan surface = (canvas, paint, left, right, top, baseline, bottom, text, start, end, line) -> {
                int original = paint.getColor();
                paint.setColor(paper);
                canvas.drawRect(left, top, right, bottom, paint);
                paint.setColor(original);
            };
            content.setSpan(surface, block.displayStart, block.displayEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            content.setSpan(new LeadingMarginSpan.Standard(inset), block.displayStart, block.displayEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return content;
    }

    /** History keeps the surrounding conversation visible. The reader retains the original source. */
    static CharSequence excerpt(Context context, TranscriptPage page) {
        SpannableStringBuilder text = new SpannableStringBuilder(styled(context, page));
        for (int index = page.blocks.size() - 1; index >= 0; index--) {
            TranscriptPage.Block block = page.blocks.get(index);
            int end = block.displayEnd;
            if (end > block.displayStart && text.charAt(end - 1) == '\n') end--;
            int at = block.displayStart, lines = 0;
            int limit = Math.min(end, at + 200);
            while (at < limit && lines < 4) {
                if (text.charAt(at++) == '\n') lines++;
            }
            if (at < end) {
                if (Character.isLowSurrogate(text.charAt(at))) at--;
                text.replace(at, end, "…");
            }
        }
        return text;
    }

    static void updateLive(TextView target, LiveTranscript stream, String source) {
        updateLive(target, stream, source, RunPreview.Origin.FIRST);
    }

    static void updateLive(TextView target, LiveTranscript stream, String source, RunPreview.Origin origin) {
        long previousStart = stream.windowStart();
        if (!stream.update(source, origin)) return;
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

    /** One entry point per text page keeps the native selection continuous across prose and code. */
    static void addCodeAction(LinearLayout parent, TranscriptPage page, String source,
                              Consumer<AlertDialog.Builder> open) {
        if (page.blocks.isEmpty()) return;
        Context context = parent.getContext();
        int count = page.blocks.size();
        TranscriptPage.Block first = page.blocks.get(0);
        String title = first.title(source);
        String label = count > 1 ? context.getString(R.string.code_open_many, count)
                : title.isEmpty() ? context.getString(R.string.code_open)
                : context.getString(R.string.code_open_named, title);
        Button action = Ui.button(context, label, v -> {
            if (count == 1) openBlock(context, first, source, open);
            else {
                String[] choices = new String[count];
                for (int i = 0; i < count; i++) {
                    String name = page.blocks.get(i).title(source);
                    String number = context.getString(R.string.code_excerpt_number, i + 1);
                    choices[i] = name.isEmpty() ? number : number + " · " + name;
                }
                open.accept(new AlertDialog.Builder(context).setTitle(R.string.code_choose).setItems(choices,
                        (d, which) -> openBlock(context, page.blocks.get(which), source, open))
                        .setNegativeButton("Fermer", null));
            }
        });
        action.setSingleLine(false);
        action.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        parent.addView(action);
    }

    private static void openBlock(Context context, TranscriptPage.Block block, String source,
                                  Consumer<AlertDialog.Builder> open) {
        String title = block.title(source);
        open.accept(CodeReader.dialog(context, title.isEmpty() ? context.getString(R.string.code_excerpt) : title,
                block.marker.language, block.content(source)));
    }

    /** One selectable page at a time, even for multi-megabyte tool logs. */
    static View reader(Context context, String source, boolean markdown, boolean tool) {
        return reader(context, source, markdown, tool, new TranscriptPage.Cursor(), null);
    }

    static View reader(Context context, String source, boolean markdown, boolean tool,
                       TranscriptPage.Cursor first, Consumer<AlertDialog.Builder> open) {
        LinearLayout root = Ui.column(context);
        Ui.pad(root, 16, 6);
        LinearLayout nav = Ui.row(context);
        nav.setGravity(Gravity.CENTER);
        List<TranscriptPage.Cursor> history = new ArrayList<>();
        history.add(first);
        TextView text = Ui.text(context, "", 16);
        text.setTypeface(tool ? Typeface.MONOSPACE : Typeface.DEFAULT);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(context);
        LinearLayout column = Ui.column(context);
        column.addView(text);
        scroll.addView(column);
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
                if (column.getChildCount() > 1) column.removeViewAt(1);
                if (open != null) addCodeAction(column, page, source, open);
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
