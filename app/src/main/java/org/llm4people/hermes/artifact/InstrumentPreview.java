package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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

    /** Builds a transient, editable page with the same native controls and draft review as an installed page. */
    public static View render(Activity activity, String pageJson, ArtifactDesk.Host host) {
        Objects.requireNonNull(host);
        try {
            WorkspaceLayout page = page(pageJson);
            return SurfaceRenderer.pageContent(SurfaceRenderer.palette(activity, page), activity, page, host, false);
        } catch (IOException invalid) {
            return status(activity, "Aperçu indisponible. Rouvrez la proposition depuis la discussion.");
        }
    }

    /** Reads only the two declarative definitions. No profile, field answers or storage access is needed. */
    public static View compare(Activity activity, String previousPageJson, String nextPageJson) {
        List<InstrumentChanges.Change> changes;
        try {
            changes = InstrumentChanges.between(page(previousPageJson), page(nextPageJson));
        } catch (IOException invalid) {
            return status(activity, "Comparaison indisponible. Rouvrez les versions depuis la discussion.");
        }
        if (changes.isEmpty()) return status(activity, "Ces deux versions sont identiques à l’usage.");
        LinearLayout body = Ui.column(activity);
        body.setBackgroundColor(activity.getColor(R.color.paper));
        body.setSaveFromParentEnabled(false);
        Ui.pad(body, 16, 4);
        String prior = "";
        for (InstrumentChanges.Change change : changes) {
            if (!change.section().equals(prior) && !change.section().isEmpty()) {
                TextView heading = Ui.text(activity, change.section(), 18);
                heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                heading.setAccessibilityHeading(true);
                Ui.pad(heading, 0, 10);
                body.addView(heading);
            }
            prior = change.section();
            body.addView(changeRow(activity, change));
            body.addView(Ui.divider(activity));
        }
        return body;
    }

    private static WorkspaceLayout page(String raw) throws IOException {
        WorkspaceLayout layout = WorkspaceLayout.parse(raw);
        if (!WorkspaceLayout.custom(layout.surface)) throw WorkspaceLayout.invalid();
        return layout;
    }

    private static TextView changeRow(Context context, InstrumentChanges.Change change) {
        SpannableStringBuilder copy = new SpannableStringBuilder();
        copy.append(change.label(), new StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        boolean pair = change.before() != null && change.after() != null;
        if (change.before() != null) append(context, copy, pair ? "Avant" : "", change.before(), R.color.muted);
        if (change.after() != null) append(context, copy, pair ? "Après" : "", change.after(), R.color.ink);
        TextView row = Ui.text(context, "", 16);
        row.setText(copy);
        row.setLineSpacing(Ui.dp(context, 2), 1);
        Ui.pad(row, 0, 10);
        return row;
    }

    private static void append(Context context, SpannableStringBuilder copy, String label, String value, int color) {
        copy.append('\n');
        int start = copy.length();
        if (!label.isEmpty()) copy.append(label + "  ", new StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        copy.append(value);
        copy.setSpan(new ForegroundColorSpan(context.getColor(color)), start, copy.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static View status(Context c, String text) {
        TextView view = Ui.text(c, text, 16);
        view.setTextColor(c.getColor(R.color.muted));
        Ui.pad(view, 16, 12);
        return view;
    }
}
