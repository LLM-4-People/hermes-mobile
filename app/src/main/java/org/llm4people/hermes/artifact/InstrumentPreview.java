package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TabStopSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Live contents for the thread's tool pane. The thread host supplies navigation, title, and scrolling. */
public final class InstrumentPreview {
    private InstrumentPreview() {}

    /** Builds a transient, editable page with profile-scoped theme and encrypted local tool state. */
    public static View render(Activity activity, String pageJson, ArtifactDesk.Host host,
                              org.llm4people.hermes.profile.Account account, String stateScope) {
        Objects.requireNonNull(host);
        try {
            WorkspaceLayout page = page(pageJson);
            ThemePalette palette = SurfaceRenderer.resolvePalette(activity, account, page);
            Context ctx = SurfaceRenderer.palette(activity, palette);
            InstrumentStateStore store = account == null ? null : InstrumentStateStore.forAccount(account);
            return SurfaceRenderer.pageContent(ctx, activity, page, host, palette, store, stateScope, false);
        } catch (IOException invalid) {
            return status(activity, activity.getString(R.string.ws_diff_preview_unavailable));
        }
    }

    /** Reads only the two declarative definitions. No profile, field answers or storage access is needed. */
    public static View compare(Activity activity, String previousPageJson, String nextPageJson) {
        List<InstrumentChanges.Detail> changes;
        try {
            changes = InstrumentChanges.details(activity, page(previousPageJson), page(nextPageJson));
        } catch (IOException invalid) {
            return status(activity, activity.getString(R.string.ws_diff_compare_unavailable));
        }
        if (changes.isEmpty()) return status(activity, activity.getString(R.string.ws_diff_identical));
        LinearLayout body = Ui.column(activity);
        body.setBackgroundColor(activity.getColor(R.color.paper));
        body.setSaveFromParentEnabled(false);
        Ui.pad(body, 16, 4);
        String prior = "";
        for (InstrumentChanges.Detail detail : changes) {
            InstrumentChanges.Change change = detail.change();
            if (!change.section().equals(prior) && !change.section().isEmpty()) {
                TextView heading = Ui.text(activity, change.section(), 16);
                heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                heading.setAccessibilityHeading(true);
                heading.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 4));
                body.addView(heading);
            }
            prior = change.section();
            body.addView(changeRow(activity, detail));
            body.addView(Ui.divider(activity));
        }
        return body;
    }

    private static WorkspaceLayout page(String raw) throws IOException {
        WorkspaceLayout layout = WorkspaceLayout.parse(raw);
        if (!WorkspaceLayout.custom(layout.surface)) throw WorkspaceLayout.invalid();
        return layout;
    }

    private static TextView changeRow(Context context, InstrumentChanges.Detail detail) {
        InstrumentChanges.Change change = detail.change();
        InstrumentChanges.FieldDescription field = detail.afterField() == null ? detail.beforeField() : detail.afterField();
        TextView row = Ui.text(context, "", 16);
        SpannableStringBuilder copy = new SpannableStringBuilder();
        boolean pair = change.before() != null && change.after() != null;
        if (field != null && !pair) {
            copy.append(field.name(), new StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(copy, new RelativeSizeSpan(19f / 16), 0);
            copy.append('\n');
            int metadata = copy.length();
            copy.append(change.label()).append(" · ").append(field.requirement());
            span(copy, new RelativeSizeSpan(14f / 16), metadata);
            span(copy, new ForegroundColorSpan(context.getColor(R.color.muted)), metadata);
            appendOptions(context, copy, field, 0, context.getColor(R.color.muted));
        } else {
            copy.append(change.label(), new StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (change.before() != null) appendValue(row, copy, pair ? context.getString(R.string.ws_diff_before) : "",
                    change.before(), detail.beforeField(), R.color.muted);
            if (change.after() != null) appendValue(row, copy, pair ? context.getString(R.string.ws_diff_after) : "",
                    change.after(), detail.afterField(), R.color.ink);
        }
        row.setLineSpacing(Ui.dp(context, 3), 1);
        row.setText(copy);
        Ui.pad(row, 0, 10);
        return row;
    }

    private static void appendValue(TextView row, SpannableStringBuilder copy, String label, String value,
                                    InstrumentChanges.FieldDescription field, int color) {
        Context c = row.getContext();
        copy.append('\n');
        int start = copy.length();
        if (label.isEmpty()) {
            copy.append(value);
        } else {
            float maxLabel = Math.max(
                    row.getPaint().measureText(c.getString(R.string.ws_diff_before)),
                    row.getPaint().measureText(c.getString(R.string.ws_diff_after)));
            int gutter = (int) Math.ceil(maxLabel) + Ui.dp(c, 12);
            copy.append(label, new StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(copy, new RelativeSizeSpan(14f / 16), start);
            copy.append('\t');
            int valueStart = copy.length();
            copy.append(field == null ? value : field.name());
            int newline = value.indexOf('\n');
            int firstEnd = field != null || newline < 0 ? copy.length() : valueStart + newline + 1;
            copy.setSpan(new TabStopSpan.Standard(gutter), start, firstEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            copy.setSpan(new LeadingMarginSpan.Standard(0, gutter), start, firstEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (firstEnd < copy.length()) span(copy, new LeadingMarginSpan.Standard(gutter), firstEnd);
            if (field != null) {
                span(copy, new StyleSpan(Typeface.BOLD), valueStart);
                copy.append('\n');
                int requirement = copy.length();
                copy.append(field.requirement());
                span(copy, new LeadingMarginSpan.Standard(gutter), requirement);
                span(copy, new RelativeSizeSpan(14f / 16), requirement);
                appendOptions(c, copy, field, gutter, c.getColor(color));
            }
        }
        span(copy, new ForegroundColorSpan(c.getColor(color)), start);
    }

    private static void appendOptions(Context c, SpannableStringBuilder copy,
                                      InstrumentChanges.FieldDescription field, int gutter, int bulletColor) {
        for (String option : field.options()) {
            copy.append('\n');
            int start = copy.length();
            copy.append(option);
            if (gutter > 0) span(copy, new LeadingMarginSpan.Standard(gutter), start);
            span(copy, new BulletSpan(Ui.dp(c, 8), bulletColor, Ui.dp(c, 2)), start);
        }
    }

    private static void span(SpannableStringBuilder copy, Object style, int start) {
        copy.setSpan(style, start, copy.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static View status(Context c, String text) {
        TextView view = Ui.text(c, text, 16);
        view.setTextColor(c.getColor(R.color.muted));
        Ui.pad(view, 16, 12);
        return view;
    }
}
