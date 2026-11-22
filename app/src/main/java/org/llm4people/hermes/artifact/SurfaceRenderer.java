package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.Account;
import java.io.IOException;

final class SurfaceRenderer {
    static View decorate(Activity activity, Account account, String surface, View nativeContent, ArtifactDesk.Host host) {
        LayoutHistory history = new LayoutHistory(account, surface);
        try {
            String current = history.current();
            if (current.isEmpty()) return nativeContent;
            WorkspaceLayout layout = WorkspaceLayout.parse(current);
            if (!surface.equals(layout.surface)) throw new IOException("Mismatched surface");
            return render(activity, layout, nativeContent, host, () -> controls(activity, history, host));
        } catch (IOException | IllegalStateException e) {
            Diagnostics.warning("workspace", "read_failed");
            return nativeContent;
        }
    }

    static View preview(Activity activity, WorkspaceLayout layout) {
        Context c = palette(activity, layout);
        TextView sample = Ui.text(c, WorkspaceLayout.SURFACES.get(layout.surface) + " · Contenu de l’écran", 16);
        Ui.pad(sample, 20, 20);
        sample.setBackgroundColor(c.getColor(R.color.accent_surface));
        LinearLayout column = Ui.column(c);
        title(column, layout);
        for (WorkspaceLayout.Block block : layout.blocks) {
            if (block.nativeContent) column.addView(sample);
            else section(column, layout, block, null);
        }
        return column;
    }

    private static View render(Activity activity, WorkspaceLayout layout, View nativeContent,
                               ArtifactDesk.Host host, Runnable manage) {
        Context c = palette(activity, layout);
        // Preserve chat/editor space and let each collection of controls scroll independently.
        SurfaceFrame frame = new SurfaceFrame(c);
        LinearLayout before = Ui.column(c), after = Ui.column(c);
        title(before, layout);
        Button options = Ui.button(c, "Modifier la disposition", v -> manage.run());
        before.addView(options);
        LinearLayout zone = before;
        for (WorkspaceLayout.Block block : layout.blocks) {
            if (block.nativeContent) zone = after;
            else section(zone, layout, block, host);
        }
        frame.addRegion(before);
        frame.addView(nativeContent, new LinearLayout.LayoutParams(-1, 0, 1));
        if (after.getChildCount() != 0) frame.addRegion(after);
        return frame;
    }

    private static Context palette(Activity activity, WorkspaceLayout layout) {
        if ("system".equals(layout.theme)) return activity;
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        int mode = "dark".equals(layout.theme) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
        return new android.view.ContextThemeWrapper(activity.createConfigurationContext(config),
                mode == Configuration.UI_MODE_NIGHT_YES ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
    }

    private static void title(LinearLayout zone, WorkspaceLayout layout) {
        Context c = zone.getContext();
        TextView title = Ui.text(c, layout.title, 22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        Ui.pad(title, 16, gap(layout));
        zone.addView(title);
    }

    private static int gap(WorkspaceLayout layout) { return "compact".equals(layout.density) ? 4 : 12; }

    private static void section(LinearLayout parent, WorkspaceLayout layout, WorkspaceLayout.Block block,
                                ArtifactDesk.Host host) {
        Context c = parent.getContext();
        LinearLayout group = Ui.column(c);
        Ui.pad(group, 16, gap(layout));
        TextView heading = Ui.text(c, block.title, 18);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        group.addView(heading);
        if (!block.note.isEmpty()) group.addView(Ui.text(c, block.note, 16));
        for (String action : block.actions) {
            String label = WorkspaceLayout.ACTIONS.get(action);
            if (host == null) {
                TextView sample = Ui.text(c, label, 16);
                Ui.pad(sample, 8, 6);
                group.addView(sample);
            } else {
                Button b = Ui.button(c, label, v -> host.onAction(action));
                b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                group.addView(b);
            }
        }
        parent.addView(group);
        parent.addView(Ui.divider(c));
    }

    static void controls(Activity activity, LayoutHistory history, ArtifactDesk.Host host) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle("Disposition de l’écran").setMessage("Vous pouvez rétablir la disposition d’origine.")
                    .setNegativeButton("Fermer", null).setPositiveButton("Rétablir", (d, w) ->
                            change(activity, host, () -> history.replace("")));
            if (history.canUndo()) builder.setNeutralButton("Annuler la modification",
                    (d, w) -> change(activity, host, history::undo));
            DeskDialogs.open(activity, builder);
        } catch (IllegalStateException e) { failure(activity); }
    }

    static void change(Activity activity, ArtifactDesk.Host host, Runnable write) {
        try { write.run(); }
        catch (IllegalStateException e) { failure(activity); return; }
        Diagnostics.event("workspace", "changed");
        DeskDialogs.dismiss(activity);
        host.onLayoutChanged();
    }

    private static void failure(Activity activity) {
        Diagnostics.warning("workspace", "storage_failed");
        DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Disposition indisponible")
                .setMessage("Rouvrez le profil puis réessayez.").setPositiveButton("Fermer", null));
    }

    private static final class SurfaceFrame extends LinearLayout {
        SurfaceFrame(Context context) { super(context); setOrientation(VERTICAL); }
        void addRegion(LinearLayout content) {
            ScrollView scroll = new ScrollView(getContext());
            scroll.setBackgroundColor(getContext().getColor(R.color.paper));
            scroll.addView(content);
            addView(scroll, new LayoutParams(-1, -2));
        }
        @Override protected void onMeasure(int w, int h) {
            int available = MeasureSpec.getSize(h);
            if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED)
                available = getResources().getDisplayMetrics().heightPixels;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (!(v instanceof ScrollView) || ((LayoutParams)v.getLayoutParams()).weight != 0) continue;
                v.measure(getChildMeasureSpec(w, 0, -1), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                v.getLayoutParams().height = Math.min(available / 3, v.getMeasuredHeight());
            }
            super.onMeasure(w, h);
        }
    }
}
