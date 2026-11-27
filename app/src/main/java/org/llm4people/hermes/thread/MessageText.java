package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Layout;
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
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.CodeReader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class MessageText {
    private MessageText() {}

    static CharSequence styled(Context context, TranscriptPage page) {
        return styled(context, page, true);
    }

    private static CharSequence styled(Context context, TranscriptPage page, boolean indentBlocks) {
        if (page.rejectedLinks > 0) {
            Diagnostics.warning("markdown", "unsafe_link_rejected");
        }
        SpannableStringBuilder content = new SpannableStringBuilder(page.text);
        int accent = context.getColor(R.color.accent);
        int muted = context.getColor(R.color.muted);
        int danger = context.getColor(R.color.danger);
        int paper = context.getColor(R.color.surface);
        int barWidth = Math.max(2, Ui.dp(context, 3));
        int quoteGap = Ui.dp(context, 10);
        int listIndent = Ui.dp(context, 16);
        for (TranscriptPage.Mark mark : page.marks) {
            switch (mark.style) {
                case HEADING:
                    span(content, mark, new RelativeSizeSpan(1.16f));
                    span(content, mark, new StyleSpan(Typeface.BOLD));
                    break;
                case BOLD: span(content, mark, new StyleSpan(Typeface.BOLD)); break;
                case ITALIC: span(content, mark, new StyleSpan(Typeface.ITALIC)); break;
                case QUOTE:
                    span(content, mark, new QuoteBarSpan(accent, barWidth, quoteGap, Math.max(1, mark.level)));
                    span(content, mark, new ForegroundColorSpan(muted));
                    span(content, mark, new StyleSpan(Typeface.ITALIC));
                    break;
                case NESTED_LIST:
                    if (indentBlocks) {
                        span(content, mark, new LeadingMarginSpan.Standard(listIndent * Math.max(1, mark.level)));
                    }
                    break;
                case TABLE_LINE:
                    span(content, mark, new TypefaceSpan("monospace"));
                    span(content, mark, new RelativeSizeSpan(0.90f));
                    break;
                case INLINE_CODE: case CODE: case INSERTION: case DELETION:
                    span(content, mark, new TypefaceSpan("monospace"));
                    span(content, mark, new RelativeSizeSpan(.94f));
                    if (mark.style == TranscriptPage.Style.INLINE_CODE)
                        span(content, mark, new BackgroundColorSpan(paper));
                    if (mark.style == TranscriptPage.Style.INSERTION || mark.style == TranscriptPage.Style.DELETION) {
                        int color = mark.style == TranscriptPage.Style.INSERTION ? accent : danger;
                        span(content, mark, new ForegroundColorSpan(color));
                    }
                    break;
            }
        }
        if (!page.blocks.isEmpty()) {
            int inset = Ui.dp(context, 10);
            // One continuous surface across wrapped lines, indentation and blank lines.
            LineBackgroundSpan surface = (canvas, paint, left, right, top, baseline, bottom, text, start, end, line) -> {
                int original = paint.getColor();
                paint.setColor(paper);
                canvas.drawRect(left, top, right, bottom, paint);
                paint.setColor(original);
            };
            for (TranscriptPage.Block block : page.blocks) {
                content.setSpan(surface, block.displayStart, block.displayEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (indentBlocks) content.setSpan(new LeadingMarginSpan.Standard(inset), block.displayStart,
                        block.displayEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return content;
    }

    /** History keeps the surrounding conversation visible. The reader retains the original source. */
    static CharSequence excerpt(Context context, TranscriptPage page) {
        SpannableStringBuilder text = new SpannableStringBuilder(styled(context, page));
        // Apply block excerpts and collapse native-rendered tables from back to front so offsets stay valid.
        List<int[]> regions = new ArrayList<>();
        for (TranscriptPage.Block b : page.blocks) regions.add(new int[]{b.displayStart, b.displayEnd, 0});
        for (TranscriptPage.Table t : page.tables) regions.add(new int[]{t.displayStart, t.displayEnd, 1});
        regions.sort((a, b) -> Integer.compare(b[0], a[0]));
        for (int[] region : regions) {
            int start = region[0], end = region[1], kind = region[2];
            if (kind == 1) {
                int deleteEnd = end;
                if (deleteEnd < text.length() && text.charAt(deleteEnd) == '\n') deleteEnd++;
                if (start <= deleteEnd && deleteEnd <= text.length()) {
                    text.delete(start, deleteEnd);
                }
            } else {
                if (end > start && text.charAt(end - 1) == '\n') end--;
                int at = start, lines = 0;
                int limit = Math.min(end, at + 200);
                while (at < limit && lines < 4) {
                    if (text.charAt(at++) == '\n') lines++;
                }
                if (at < end) {
                    if (Character.isLowSurrogate(text.charAt(at))) at--;
                    text.replace(at, end, "…");
                }
            }
        }
        if (!page.blocks.isEmpty() && page.blocks.get(page.blocks.size() - 1).displayEnd == page.text.length()
                && text.length() > 0 && text.charAt(text.length() - 1) == '\n')
            text.delete(text.length() - 1, text.length());
        return text;
    }

    static void updateLive(TextView target, LiveTranscript stream, String source, RunPreview.Origin origin) {
        long previousStart = stream.windowStart();
        if (!stream.update(source, origin)) return;
        Context context = target.getContext();
        SpannableStringBuilder rendered = new SpannableStringBuilder();
        if (stream.earlier) rendered.append("…\n");
        // Adjacent live pages can share a paragraph. Avoid stacking paragraph insets there.
        int lastWorkspace = Integer.MIN_VALUE;
        if (stream.preceding != null) lastWorkspace = appendLive(context, rendered, stream.preceding, lastWorkspace);
        lastWorkspace = appendLive(context, rendered, stream.latest, lastWorkspace);
        stream.foldedWorkspace = lastWorkspace != Integer.MIN_VALUE;
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

    // Collapse only parsed blocks in these two bounded display pages. The paged source reader
    // keeps the original retained bytes, including unclosed or invalid workspace definitions.
    private static int appendLive(Context context, SpannableStringBuilder rendered, TranscriptPage page, int lastBody) {
        CharSequence formatted = styled(context, page, false);
        int start = 0;
        for (TranscriptPage.Block block : page.blocks) {
            if (!block.marker.workspace()) continue;
            rendered.append(formatted, start, block.displayStart);
            if (block.bodyStart != lastBody) {
                rendered.append(context.getString(R.string.instrument_stream_source), new StyleSpan(Typeface.ITALIC),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (block.displayEnd > block.displayStart && formatted.charAt(block.displayEnd - 1) == '\n')
                rendered.append('\n');
            start = block.displayEnd;
            lastBody = block.bodyStart;
        }
        rendered.append(formatted, start, formatted.length());
        return lastBody;
    }

    private static void span(SpannableStringBuilder content, TranscriptPage.Mark mark, Object style) {
        content.setSpan(style, mark.start, mark.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Render parsed Markdown tables as clean, horizontal-scrollable native Android grids. */
    static void addTables(LinearLayout parent, TranscriptPage page) {
        if (page.tables.isEmpty()) return;
        Context context = parent.getContext();
        for (TranscriptPage.Table table : page.tables) {
            View grid = tableView(context, table);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = Ui.dp(context, 6);
            params.bottomMargin = Ui.dp(context, 4);
            parent.addView(grid, params);
        }
    }

    static View tableView(Context context, TranscriptPage.Table table) {
        TableLayout grid = new TableLayout(context);
        grid.setStretchAllColumns(false);
        grid.setShrinkAllColumns(false);
        grid.setContentDescription(context.getString(R.string.markdown_table_description,
                table.headers.size(), table.rows.size()));

        TableRow headerRow = new TableRow(context);
        headerRow.setBackgroundColor(context.getColor(R.color.accent_surface));
        for (int c = 0; c < table.headers.size(); c++) {
            headerRow.addView(tableCell(context, table.headers.get(c), alignGravity(table.alignments, c), true));
        }
        grid.addView(headerRow);
        grid.addView(Ui.divider(context));

        for (int r = 0; r < table.rows.size(); r++) {
            List<String> cells = table.rows.get(r);
            TableRow row = new TableRow(context);
            row.setBackgroundColor(context.getColor(r % 2 == 0 ? R.color.surface : R.color.paper));
            for (int c = 0; c < table.headers.size(); c++) {
                String value = c < cells.size() ? cells.get(c) : "";
                row.addView(tableCell(context, value, alignGravity(table.alignments, c), false));
            }
            grid.addView(row);
            if (r + 1 < table.rows.size()) grid.addView(Ui.divider(context));
        }

        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setFillViewport(true);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(Ui.dp(context, 8));
        border.setColor(context.getColor(R.color.surface));
        border.setStroke(Ui.dp(context, 1), context.getColor(R.color.line));
        scroller.setBackground(border);
        scroller.addView(grid, new HorizontalScrollView.LayoutParams(-2, -2));
        return scroller;
    }

    private static TextView tableCell(Context context, String value, int gravity, boolean header) {
        TextView cell = Ui.text(context, value, header ? 14 : 14);
        cell.setTypeface(Typeface.DEFAULT, header ? Typeface.BOLD : Typeface.NORMAL);
        cell.setGravity(gravity | Gravity.CENTER_VERTICAL);
        cell.setTextIsSelectable(true);
        cell.setMaxWidth(Ui.dp(context, 240));
        Ui.pad(cell, 10, 7);
        return cell;
    }

    private static int alignGravity(List<TranscriptPage.Align> alignments, int column) {
        if (column < 0 || column >= alignments.size()) return Gravity.START;
        return switch (alignments.get(column)) {
            case CENTER -> Gravity.CENTER_HORIZONTAL;
            case RIGHT -> Gravity.END;
            default -> Gravity.START;
        };
    }

    /** Reviewed external HTTPS link actions. Never opens a URL without explicit user confirmation. */
    static void addLinkActions(LinearLayout parent, TranscriptPage page, Consumer<AlertDialog.Builder> open) {
        if (page.links.isEmpty() || open == null) return;
        Context context = parent.getContext();
        int count = page.links.size();
        if (count <= 3) {
            for (TranscriptPage.Link link : page.links) {
                String caption = link.label.equalsIgnoreCase(link.host)
                        ? link.host : link.label + " · " + link.host;
                Button action = Ui.button(context, context.getString(R.string.markdown_link_open, caption),
                        v -> confirmLink(context, link, open));
                action.setSingleLine(false);
                action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                parent.addView(action);
            }
        } else {
            Button chooser = Ui.button(context, context.getString(R.string.markdown_link_open_many, count), v -> {
                String[] items = new String[count];
                for (int i = 0; i < count; i++) {
                    TranscriptPage.Link link = page.links.get(i);
                    items[i] = link.label + " (" + link.host + ")";
                }
                open.accept(new AlertDialog.Builder(context)
                        .setTitle(R.string.markdown_link_choose)
                        .setItems(items, (d, which) -> confirmLink(context, page.links.get(which), open))
                        .setNegativeButton(R.string.ws_close, null));
            });
            chooser.setSingleLine(false);
            chooser.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            parent.addView(chooser);
        }
    }

    static void confirmLink(Context context, TranscriptPage.Link link, Consumer<AlertDialog.Builder> open) {
        SafeLinks.Validated safe = SafeLinks.validate(link.url);
        if (safe == null) {
            Diagnostics.warning("markdown", "unsafe_link_blocked");
            return;
        }
        LinearLayout body = Ui.column(context);
        Ui.pad(body, 20, 10);
        TextView host = Ui.text(context, safe.host(), 16);
        host.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(host);
        TextView urlView = Ui.text(context, safe.url(), 13);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setTextColor(context.getColor(R.color.muted));
        urlView.setTextIsSelectable(true);
        Ui.pad(urlView, 0, 6);
        body.addView(urlView);
        TextView hint = Ui.text(context, context.getString(R.string.markdown_link_confirm_hint), 14);
        hint.setTextColor(context.getColor(R.color.muted));
        body.addView(hint);

        open.accept(new AlertDialog.Builder(context)
                .setTitle(R.string.markdown_link_confirm_title)
                .setView(body)
                .setPositiveButton(R.string.markdown_link_confirm_open, (d, w) -> launchHttps(context, safe.url()))
                .setNeutralButton(R.string.markdown_link_copy, (d, w) -> copyLink(context, safe.url()))
                .setNegativeButton(R.string.ws_cancel, null));
    }

    static boolean launchHttps(Context context, String rawUrl) {
        SafeLinks.Validated safe = SafeLinks.validate(rawUrl);
        if (safe == null) {
            Diagnostics.warning("markdown", "unsafe_link_blocked");
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(safe.url()));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            context.startActivity(intent);
            Diagnostics.event("markdown", "https_link_opened");
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Diagnostics.warning("markdown", "https_link_unavailable");
            Toast.makeText(context, R.string.markdown_link_unavailable, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static void copyLink(Context context, String url) {
        try {
            if (Ui.copySensitiveText(context, context.getString(R.string.thread_link_fallback), url)
                    && android.os.Build.VERSION.SDK_INT < 33) {
                Toast.makeText(context, R.string.markdown_link_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException failed) {
            Diagnostics.warning("markdown", "link_copy_failed");
        }
    }

    /** Collapsible disclosure block for Hermes's reasoning ({@code <think>} or {@code reasoning.delta}). */
    static View reasoningCard(Context context, String reasoning, boolean streaming, Consumer<AlertDialog.Builder> open) {
        LinearLayout card = Ui.column(context);
        Ui.pad(card, 12, 6);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(Ui.dp(context, 10));
        border.setColor(context.getColor(R.color.surface));
        border.setStroke(Ui.dp(context, 1), context.getColor(R.color.line));
        card.setBackground(border);

        LinearLayout contentBox = Ui.column(context);
        contentBox.setVisibility(View.GONE);
        String collapsedLabel = context.getString(streaming ? R.string.reasoning_streaming : R.string.reasoning_title);
        String collapsedDescription = context.getString(
                streaming ? R.string.reasoning_streaming_show_label : R.string.reasoning_show_label);
        Button toggle = Ui.button(context, collapsedLabel, null);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setTextSize(14);
        toggle.setContentDescription(collapsedDescription);
        PromptButtons.icon(toggle, R.drawable.ic_prompt_disclosure);
        toggle.setOnClickListener(v -> {
            boolean opening = contentBox.getVisibility() == View.GONE;
            if (opening && contentBox.getChildCount() == 0) {
                boolean over = reasoning.length() > TranscriptPage.PAGE_CHARS;
                TextView detail = Ui.text(context, over
                        ? reasoning.substring(0, TranscriptPage.PAGE_CHARS) + "…" : reasoning, 14);
                detail.setTextColor(context.getColor(R.color.muted));
                detail.setTextIsSelectable(true);
                Ui.pad(detail, 4, 4);
                contentBox.addView(detail);
                if (over && open != null) {
                    Button full = Ui.button(context, context.getString(R.string.reasoning_read_full),
                            b -> open.accept(new AlertDialog.Builder(context)
                                    .setTitle(R.string.reasoning_title)
                                    .setView(reader(context, reasoning, false, false))
                                    .setPositiveButton(R.string.ws_close, null)));
                    contentBox.addView(full);
                }
            }
            contentBox.setVisibility(opening ? View.VISIBLE : View.GONE);
            toggle.setActivated(opening);
            toggle.setText(opening ? context.getString(R.string.reasoning_hide) : collapsedLabel);
            toggle.setContentDescription(opening ? context.getString(R.string.reasoning_hide) : collapsedDescription);
        });
        card.addView(toggle);
        card.addView(contentBox);
        return card;
    }

    /** One entry point per text page keeps the native selection continuous across prose and code. */
    static void addCodeAction(LinearLayout parent, TranscriptPage page, String source,
                              Consumer<AlertDialog.Builder> open) {
        if (page.blocks.isEmpty()) return;
        Context context = parent.getContext();
        int count = page.blocks.size();
        TranscriptPage.Block first = page.blocks.get(0);
        String title = first.title(source);
        String label = count > 1 ? context.getString(R.string.code_open_many)
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
                        .setNegativeButton(R.string.ws_close, null));
            }
        });
        if (count == 1 && first.displayEnd == page.text.length()) {
            action.setBackground(Ui.roundedRipple(context, Ui.BUTTON_CORNER_DP,
                    Ui.shape(context, Ui.BUTTON_CORNER_DP, R.color.surface, 0, 0)));
            Ui.pad(action, 10, 4);
        }
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
                while (column.getChildCount() > 1) column.removeViewAt(column.getChildCount() - 1);
                if (markdown) {
                    addTables(column, page);
                    if (open != null) addLinkActions(column, page, open);
                }
                if (open != null) addCodeAction(column, page, source, open);
                position.setText(context.getString(R.string.transcript_page, index + 1));
                previous.setEnabled(index > 0);
                next.setEnabled(page.hasMore());
                previous.setOnClickListener(v -> show(index - 1));
                next.setOnClickListener(v -> show(index + 1));
                scroll.scrollTo(0, 0);
            }
        }
        new Pages().show(0);
        position.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return root;
    }

    /** Crisp left teal vertical rule for Markdown blockquotes. */
    private static final class QuoteBarSpan implements LeadingMarginSpan {
        private final int color;
        private final int barWidth;
        private final int gap;
        private final int depth;

        QuoteBarSpan(int color, int barWidth, int gap, int depth) {
            this.color = color;
            this.barWidth = barWidth;
            this.gap = gap;
            this.depth = Math.min(3, depth);
        }

        @Override public int getLeadingMargin(boolean first) {
            return (barWidth + gap) * depth;
        }

        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                                CharSequence text, int start, int end, boolean first, Layout layout) {
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            for (int d = 0; d < depth; d++) {
                int left = x + dir * d * (barWidth + gap);
                c.drawRect(left, top, left + dir * barWidth, bottom, p);
            }
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }
    }
}
