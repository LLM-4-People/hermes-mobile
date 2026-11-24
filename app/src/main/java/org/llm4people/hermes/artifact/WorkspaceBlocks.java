package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Renders only local components and invokes the host's typed operations after a user gesture. */
final class WorkspaceBlocks {
    static void add(LinearLayout parent, WorkspaceLayout layout, WorkspaceLayout.Block block,
                    Activity activity, ArtifactDesk.Host host) {
        add(parent, layout, block, null, null, "", activity, host);
    }

    static void add(LinearLayout parent, WorkspaceLayout layout, WorkspaceLayout.Block block,
                    ThemePalette palette, InstrumentStateStore stateStore, String stateScope,
                    Activity activity, ArtifactDesk.Host host) {
        Context c = parent.getContext();
        ThemePalette colors = palette == null
                ? ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(c))
                : palette;
        if ("nearby_places".equals(block.type)) {
            View guide = host == null ? null : host.nearbyGuide();
            if (guide != null) parent.addView(guide);
            else {
                TextView unavailable = Ui.text(c, c.getString(R.string.ws_block_nearby_fallback), colors.scaledSp(16));
                unavailable.setTextColor(colors.ink);
                Ui.pad(unavailable, 16, colors.blockVerticalPaddingDp);
                parent.addView(unavailable);
            }
            return;
        }
        LinearLayout group = Ui.column(c);
        int vPad = "compact".equals(layout.density) ? 5 : "spacious".equals(layout.density) ? 18 : colors.blockVerticalPaddingDp;
        Ui.pad(group, 16, vPad);
        if ("link".equals(block.type)) {
            action(group, block.title, colors, host == null ? null : () ->
                    DeskDialogs.navigate(activity, () -> host.openWorkspace(block.page)));
        } else {
            TextView heading = Ui.text(c, block.displayTitle(c), colors.scaledSp(18));
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setTextColor(colors.ink);
            heading.setAccessibilityHeading(true);
            group.addView(heading);
            switch (block.type) {
                case "section":
                    if (!block.note.isEmpty()) {
                        TextView note = Ui.text(c, block.note, colors.scaledSp(16));
                        note.setTextColor(colors.ink);
                        group.addView(note);
                    }
                    for (String id : block.actions) action(group, WorkspaceLayout.actionLabel(c, id), colors,
                            host == null ? null : () -> DeskDialogs.navigate(activity, () -> host.onAction(id)));
                    break;
                case "value":
                    Value value = new Value(c, block.source, colors);
                    if (host != null) value.refresh(host);
                    group.addView(value);
                    break;
                case "device":
                    DeviceAction.CalendarEvent event = host != null && block.request instanceof DeviceAction.CalendarEvent calendar
                            ? calendar : null;
                    TextView details = Ui.text(c, event == null ? block.request.preview(c) : event.title(), colors.scaledSp(16));
                    details.setTextColor(colors.ink);
                    if (host != null) {
                        details.setMaxLines(2);
                        details.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    }
                    group.addView(details);
                    if (event != null) {
                        TextView start = Ui.text(c, event.startSummary(c), colors.scaledSp(16));
                        start.setTextColor(colors.muted);
                        group.addView(start);
                    }
                    if (host != null) {
                        Button review = Ui.button(c, block.request.kind().review(c),
                                v -> DeviceReview.open(activity, host, v, block.request));
                        review.setTextColor(colors.accent);
                        group.addView(review);
                    }
                    break;
                case "form":
                    WorkspaceForms.mount(group, block, colors, activity, host);
                    break;
                case "metric_grid":
                    if (!block.note.isEmpty()) {
                        TextView note = Ui.text(c, block.note, colors.scaledSp(15));
                        note.setTextColor(colors.muted);
                        note.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 6));
                        group.addView(note);
                    }
                    View grid = MetricGridBlock.build(c, block.metrics, colors, host);
                    LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
                    gridLp.topMargin = Ui.dp(c, 6);
                    group.addView(grid, gridLp);
                    break;
                case "sparkline":
                    if (!block.note.isEmpty()) {
                        TextView note = Ui.text(c, block.note, colors.scaledSp(15));
                        note.setTextColor(colors.muted);
                        group.addView(note);
                    }
                    SparklineView chart = new SparklineView(c, block.sparkline, colors);
                    LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 96));
                    chartLp.topMargin = Ui.dp(c, 6);
                    group.addView(chart, chartLp);
                    if (!block.sparkline.caption.isEmpty()) {
                        TextView cap = Ui.text(c, block.sparkline.caption, colors.scaledSp(13));
                        cap.setTextColor(colors.muted);
                        cap.setPadding(0, Ui.dp(c, 4), 0, 0);
                        group.addView(cap);
                    }
                    break;
                case "table":
                    if (!block.note.isEmpty()) {
                        TextView note = Ui.text(c, block.note, colors.scaledSp(15));
                        note.setTextColor(colors.muted);
                        note.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 6));
                        group.addView(note);
                    }
                    View table = DataTableBlock.build(c, block.table, colors, host);
                    LinearLayout.LayoutParams tableLp = new LinearLayout.LayoutParams(-1, -2);
                    tableLp.topMargin = Ui.dp(c, 6);
                    group.addView(table, tableLp);
                    break;
                case "controls":
                    InteractiveControlsBlock.mount(group, layout, block, colors, stateStore, stateScope, activity, host);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown layout block");
            }
        }
        parent.addView(group);
        parent.addView(Ui.divider(c));
    }

    private static void action(LinearLayout group, String label, ThemePalette colors, Runnable action) {
        Context c = group.getContext();
        if (action == null) {
            TextView sample = Ui.text(c, label, colors.scaledSp(16));
            sample.setTextColor(colors.accent);
            Ui.pad(sample, 8, 6);
            group.addView(sample);
        } else {
            Button button = Ui.button(c, label, v -> action.run());
            button.setTextColor(colors.accent);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            group.addView(button);
        }
    }

    static String sourceLabel(Context context, String source) {
        int res = switch (source) {
            case "profile.label" -> R.string.ws_source_profile_label;
            case "thread.title" -> R.string.ws_source_thread_title;
            case "thread.messageCount" -> R.string.ws_source_thread_message_count;
            default -> throw new IllegalArgumentException("Unknown value source");
        };
        return context.getString(res);
    }

    /** Mark and update only our bindings; writing a form never triggers a rebuild. */
    private static final class Value extends TextView {
        final String source;
        Value(Context c, String source, ThemePalette colors) {
            super(c);
            this.source = source;
            setTextSize(colors.scaledSp(16));
            setTextColor(colors.muted);
            setText(sourceLabel(c, source));
        }
        void refresh(ArtifactDesk.Host host) {
            String text = host.workspaceValue(source);
            if (text == null || text.isEmpty()) text = getContext().getString(R.string.ws_unavailable);
            if (text.length() > 240) text = text.substring(0, 240) + "…";
            if (!text.contentEquals(getText())) setText(text);
        }
    }

    static void refresh(View root, ArtifactDesk.Host host) {
        if (root instanceof Value v) v.refresh(host);
        else if (root instanceof MetricGridBlock.MetricValueView mv) mv.refresh(host);
        else if (root instanceof ViewGroup g && !(root instanceof android.widget.AdapterView)) {
            for (int i = 0; i < g.getChildCount(); i++) refresh(g.getChildAt(i), host);
        }
    }
}
