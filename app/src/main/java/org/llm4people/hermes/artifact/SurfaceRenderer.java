package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.Account;
import java.io.IOException;
import java.util.Collections;

final class SurfaceRenderer {
    static View decorate(Activity activity, Account account, String surface, View nativeContent, ArtifactDesk.Host host) {
        LayoutHistory history = new LayoutHistory(account, surface);
        try {
            String current = history.current();
            if (current.isEmpty()) {
                if ("thread".equals(surface)) host.threadSlots(Collections.emptyList());
                return nativeContent;
            }
            WorkspaceLayout layout = WorkspaceLayout.parse(current);
            if (!surface.equals(layout.surface)) throw new IOException("Mismatched surface");
            if ("thread".equals(surface)) host.threadSlots(layout.slots());
            return render(activity, layout, nativeContent, host, () -> controls(activity, history, host));
        } catch (IOException | IllegalStateException e) {
            Diagnostics.warning("workspace", "read_failed");
            if ("thread".equals(surface)) host.threadSlots(Collections.emptyList());
            return nativeContent;
        }
    }

    static View preview(Context context, WorkspaceLayout layout) {
        Context c = palette(context, layout);
        TextView sample = Ui.text(c, WorkspaceLayout.screenName(layout.surface) + " · Contenu de l’écran", 16);
        Ui.pad(sample, 20, 20);
        sample.setBackgroundColor(c.getColor(R.color.accent_surface));
        LinearLayout column = Ui.column(c);
        column.setBackgroundColor(c.getColor(R.color.paper));
        title(column, layout);
        for (WorkspaceLayout.Block block : layout.blocks) {
            if (block.nativeContent) {
                if (!block.slots.isEmpty()) sample.setText(slotSummary(block));
                column.addView(sample);
            } else WorkspaceBlocks.add(column, layout, block, null, null);
        }
        return column;
    }

    static View render(Activity activity, WorkspaceLayout layout, View nativeContent,
                       ArtifactDesk.Host host, Runnable manage) {
        Context c = palette(activity, layout);
        // Preserve chat/editor space and let each collection of controls scroll independently.
        SurfaceFrame frame = new SurfaceFrame(c);
        LinearLayout before = Ui.column(c), after = Ui.column(c);
        title(before, layout);
        if (!"replace".equals(layout.mode)) before.addView(Ui.button(c, "Modifier la disposition", v -> manage.run()));
        LinearLayout zone = before;
        for (WorkspaceLayout.Block block : layout.blocks) {
            if (block.nativeContent) zone = after;
            else WorkspaceBlocks.add(zone, layout, block, activity, host);
        }
        if ("replace".equals(layout.mode)) {
            ScrollView page = new ScrollView(c);
            page.setBackgroundColor(c.getColor(R.color.paper));
            page.setFillViewport(true);
            page.addView(before);
            return page;
        }
        frame.addRegion(before);
        frame.addView(nativeContent, new LinearLayout.LayoutParams(-1, 0, 1));
        if (after.getChildCount() != 0) frame.addRegion(after);
        return frame;
    }

    private static Context palette(Context context, WorkspaceLayout layout) {
        if ("system".equals(layout.theme)) return context;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        int mode = "dark".equals(layout.theme) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
        return new android.view.ContextThemeWrapper(context.createConfigurationContext(config),
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

    private static String slotSummary(WorkspaceLayout.Block block) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String slot : block.slots) {
            if ("notice".equals(slot)) labels.add("État");
            else if ("transcript".equals(slot)) labels.add("Messages");
            else labels.add("Rédaction");
        }
        return String.join(" · ", labels);
    }

    static AlertDialog controls(Activity activity, LayoutHistory history, ArtifactDesk.Host host) {
        try {
            boolean page = WorkspaceLayout.custom(history.surface);
            boolean removed = page && history.current().isEmpty();
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(WorkspaceLayout.screenName(history.surface)).setNegativeButton("Fermer", null);
            if (removed) {
                builder.setMessage("Cet écran a été supprimé. Vous pouvez annuler sa suppression ou effacer sa révision.")
                        .setPositiveButton("Effacer la révision…", (d,w) -> DeskDialogs.open(activity,
                                new AlertDialog.Builder(activity).setTitle("Effacer définitivement la révision ?")
                                .setNegativeButton("Annuler",null).setPositiveButton("Effacer",(dialog,n) ->
                                        change(activity,host,history::forgetRemoved))));
            } else {
                builder.setMessage(page ? "Supprimer cet écran de votre atelier ? Vous pourrez annuler cette opération."
                        : "Vous pouvez rétablir la disposition d’origine.")
                        .setPositiveButton(page ? "Supprimer" : "Rétablir", (d,w) ->
                                change(activity,host,() -> history.replace("")));
            }
            if (history.canUndo()) builder.setNeutralButton("Annuler la modification",
                    (d, w) -> change(activity, host, history::undo));
            return DeskDialogs.open(activity, builder);
        } catch (PageLayouts.Damaged damaged) {
            return WorkspacePages.confirmRecovery(activity, damaged, host);
        } catch (LayoutHistory.Unreadable e) {
            Diagnostics.warning("workspace", "read_failed");
            String screen = WorkspaceLayout.SURFACES.get(history.surface);
            if (screen == null) return failure(activity);
            return DeskDialogs.open(activity, new AlertDialog.Builder(activity)
                    .setTitle("Rétablir l’écran « " + screen + " » ?")
                    .setMessage("Sa disposition enregistrée est illisible. Rétablir cet écran effacera "
                            + "définitivement sa disposition et son historique dans ce profil.")
                    .setNegativeButton("Annuler", null).setPositiveButton("Rétablir", (d, w) ->
                            change(activity, host, history::resetUnreadable)));
        } catch (IllegalStateException e) { return failure(activity); }
    }

    static void change(Activity activity, ArtifactDesk.Host host, Runnable write) {
        try { write.run(); }
        catch (PageLayouts.Full full) {
            DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Bibliothèque pleine")
                    .setMessage("Libérez une place depuis les dispositions des écrans en effaçant une révision supprimée.")
                    .setPositiveButton("Fermer",null));
            return;
        }
        catch (PageLayouts.Damaged damaged) {
            WorkspacePages.confirmRecovery(activity, damaged, host);
            return;
        }
        catch (LayoutHistory.Unreadable e) {
            Diagnostics.warning("workspace", "read_failed");
            DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Disposition illisible")
                    .setMessage("Dans le menu, choisissez « Rétablir l’atelier », puis l’écran concerné "
                            + "pour retrouver sa disposition d’origine.")
                    .setPositiveButton("Fermer", null));
            return;
        } catch (IllegalStateException e) { failure(activity); return; }
        Diagnostics.event("workspace", "changed");
        DeskDialogs.navigate(activity, host::onLayoutChanged);
    }

    private static AlertDialog failure(Activity activity) {
        Diagnostics.warning("workspace", "storage_failed");
        return DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Disposition indisponible")
                .setMessage("Impossible d’accéder à cette disposition. Vérifiez que le profil est déverrouillé, puis réessayez.").setPositiveButton("Fermer", null));
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
