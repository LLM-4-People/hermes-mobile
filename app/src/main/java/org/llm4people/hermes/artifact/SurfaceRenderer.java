package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
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
import java.util.Collections;
import java.util.List;

final class SurfaceRenderer {
    static View decorate(Activity activity, Account account, String surface, View nativeContent, ArtifactDesk.Host host) {
        LayoutHistory history = new LayoutHistory(account, surface);
        try {
            String current = history.current();
            if (current.isEmpty()) {
                if ("thread".equals(surface)) host.threadSlots(Collections.emptyList());
                if ("home".equals(surface) && nativeContent != null) {
                    return decorateHomeWithCustomTabs(activity, account, nativeContent, host);
                }
                return nativeContent;
            }
            WorkspaceLayout layout = WorkspaceLayout.parse(current);
            if (!surface.equals(layout.surface)) throw new IOException("Mismatched surface");
            if ("thread".equals(surface)) host.threadSlots(layout.slots());
            InstrumentStateStore store = account == null ? null : InstrumentStateStore.forAccount(account);
            ThemePalette palette = resolvePalette(activity, account, layout);
            return render(activity, layout, nativeContent, host, palette, store, "atelier",
                    () -> controls(activity, history, host));
        } catch (IOException | IllegalStateException e) {
            Diagnostics.warning("workspace", "read_failed");
            if ("thread".equals(surface)) host.threadSlots(Collections.emptyList());
            return nativeContent;
        }
    }

    private static View decorateHomeWithCustomTabs(Activity activity, Account account, View nativeContent, ArtifactDesk.Host host) {
        if (account == null || host == null) return nativeContent;
        ThemePalette palette = ThemePalette.active(activity, account);
        LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(account, palette.night);
        if (manager.tabs().isEmpty()) return nativeContent;
        LinearLayout wrap = Ui.column(activity);
        wrap.setBackgroundColor(palette.paper);
        LinearLayout tabHeader = Ui.column(activity);
        Ui.pad(tabHeader, 16, 8);
        WorkspacePages.mountCustomTabs(tabHeader, activity, account, host, palette);
        wrap.addView(tabHeader);
        wrap.addView(nativeContent, new LinearLayout.LayoutParams(-1, 0, 1f));
        return wrap;
    }

    static ThemePalette resolvePalette(Context context, Account account, WorkspaceLayout layout) {
        boolean sysNight = ThemePalette.isNight(context);
        ThemePalette.OverrideSpec base = account == null
                ? ThemePalette.OverrideSpec.defaults() : ThemePalette.load(account, sysNight);
        String theme = !"system".equals(layout.theme) ? layout.theme : base.theme;
        String accent = layout.accent != null ? layout.accent : base.accentHex;
        String density = !"comfortable".equals(layout.density) ? layout.density : base.density;
        String style = !"paper".equals(layout.surfaceStyle) ? layout.surfaceStyle : base.surfaceStyle;
        try {
            ThemePalette.OverrideSpec merged = ThemePalette.OverrideSpec.validated(
                    theme, accent, density, base.fontScaleStep, style, sysNight);
            return ThemePalette.resolve(merged, sysNight);
        } catch (IOException fallback) {
            return ThemePalette.resolve(base, sysNight);
        }
    }

    static View preview(Context context, WorkspaceLayout layout) {
        Context c = palette(context, layout);
        ThemePalette colors = resolvePalette(c, null, layout);
        TextView sample = Ui.text(c, WorkspaceLayout.screenName(layout.surface) + " · Contenu de l’écran", 16);
        Ui.pad(sample, 20, 20);
        sample.setBackgroundColor(colors.accentSurface);
        LinearLayout column = Ui.column(c);
        column.setBackgroundColor(colors.paper);
        title(column, layout, colors);
        for (WorkspaceLayout.Block block : layout.blocks) {
            if (block.nativeContent) {
                if (!block.slots.isEmpty()) sample.setText(slotSummary(block));
                column.addView(sample);
            } else WorkspaceBlocks.add(column, layout, block, colors, null, "", null, null);
        }
        return column;
    }

    static View render(Activity activity, WorkspaceLayout layout, View nativeContent,
                       ArtifactDesk.Host host, Runnable manage) {
        return render(activity, layout, nativeContent, host, resolvePalette(activity, null, layout), null, "atelier", manage);
    }

    static View render(Activity activity, WorkspaceLayout layout, View nativeContent,
                       ArtifactDesk.Host host, ThemePalette colors, InstrumentStateStore store,
                       String stateScope, Runnable manage) {
        Context c = palette(activity, layout);
        if ("replace".equals(layout.mode)) {
            ScrollView page = new ScrollView(c);
            page.setBackgroundColor(colors.paper);
            page.setFillViewport(true);
            LinearLayout content = pageContent(c, activity, layout, host, colors, store, stateScope, true);
            if (WorkspaceLayout.custom(layout.surface) && activity != null) {
                Button pinHome = Ui.button(c, "Épingler sur l’écran d’accueil",
                        v -> HomePinBridge.offerPinSurface(activity, layout.surface, layout.title));
                pinHome.setTextColor(colors.accent);
                Ui.pad(pinHome, 16, 8);
                content.addView(pinHome);
            }
            page.addView(content);
            return page;
        }
        // Preserve chat/editor space and let each collection of controls scroll independently.
        SurfaceFrame frame = new SurfaceFrame(c);
        LinearLayout before = Ui.column(c), after = Ui.column(c);
        title(before, layout, colors);
        Button editBtn = Ui.button(c, "Modifier la disposition", v -> manage.run());
        editBtn.setTextColor(colors.accent);
        before.addView(editBtn);
        LinearLayout zone = before;
        for (WorkspaceLayout.Block block : layout.blocks) {
            if (block.nativeContent) zone = after;
            else WorkspaceBlocks.add(zone, layout, block, colors, store, stateScope, activity, host);
        }
        frame.addRegion(before);
        frame.addView(nativeContent, new LinearLayout.LayoutParams(-1, 0, 1));
        if (after.getChildCount() != 0) frame.addRegion(after);
        return frame;
    }

    static LinearLayout pageContent(Context c, Activity activity, WorkspaceLayout layout,
                                    ArtifactDesk.Host host, boolean showTitle) {
        return pageContent(c, activity, layout, host, resolvePalette(c, null, layout), null, "thread", showTitle);
    }

    static LinearLayout pageContent(Context c, Activity activity, WorkspaceLayout layout,
                                    ArtifactDesk.Host host, ThemePalette colors,
                                    InstrumentStateStore store, String stateScope, boolean showTitle) {
        LinearLayout body = Ui.column(c);
        body.setBackgroundColor(colors.paper);
        body.setSaveFromParentEnabled(false);
        if (showTitle) title(body, layout, colors);
        for (WorkspaceLayout.Block block : layout.blocks) {
            WorkspaceBlocks.add(body, layout, block, colors, store, stateScope, activity, host);
        }
        return body;
    }

    static Context palette(Context context, WorkspaceLayout layout) {
        if ("system".equals(layout.theme)) return context;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        int mode = "dark".equals(layout.theme) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
        return new android.view.ContextThemeWrapper(context.createConfigurationContext(config),
                mode == Configuration.UI_MODE_NIGHT_YES ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
    }

    private static void title(LinearLayout zone, WorkspaceLayout layout, ThemePalette colors) {
        Context c = zone.getContext();
        TextView title = Ui.text(c, layout.title, colors.scaledSp(22));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(colors.ink);
        title.setAccessibilityHeading(true);
        Ui.pad(title, 16, gap(layout));
        zone.addView(title);
    }

    private static int gap(WorkspaceLayout layout) {
        return "compact".equals(layout.density) ? 4 : "spacious".equals(layout.density) ? 18 : 12;
    }

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
            List<LayoutHistory.Revision> revs = history.revisions();
            String revNote = revs.isEmpty() ? "" : "\n\n" + revs.size() + " révision" + (revs.size() > 1 ? "s" : "") + " enregistrée" + (revs.size() > 1 ? "s" : "") + ".";
            if (removed) {
                builder.setMessage("Cet écran a été supprimé. Vous pouvez annuler sa suppression ou effacer sa révision." + revNote)
                        .setPositiveButton("Effacer la révision…", (d, w) -> DeskDialogs.open(activity,
                                new AlertDialog.Builder(activity).setTitle("Effacer définitivement la révision ?")
                                        .setNegativeButton("Annuler", null).setPositiveButton("Effacer", (dialog, n) ->
                                                change(activity, host, history::forgetRemoved))));
            } else {
                builder.setMessage((page ? "Supprimer cet écran de votre atelier ? Vous pourrez annuler cette opération."
                                : "Vous pouvez rétablir la disposition d’origine.") + revNote)
                        .setPositiveButton(page ? "Supprimer" : "Rétablir", (d, w) ->
                                change(activity, host, () -> history.replace("")));
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
                    .setPositiveButton("Fermer", null));
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
                if (!(v instanceof ScrollView) || ((LayoutParams) v.getLayoutParams()).weight != 0) continue;
                v.measure(getChildMeasureSpec(w, 0, -1), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                v.getLayoutParams().height = Math.min(available / 3, v.getMeasuredHeight());
            }
            super.onMeasure(w, h);
        }
    }
}
