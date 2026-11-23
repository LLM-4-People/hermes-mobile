package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.Account;
import java.util.List;
import java.util.Map;

final class WorkspacePages {
    static final String REPAIR = "Réparer la bibliothèque…";

    static AlertDialog confirmRecovery(Activity activity, PageLayouts.Damaged damaged, ArtifactDesk.Host host) {
        String consequence = damaged.hasReadablePages()
                ? "Les écrans et révisions lisibles seront conservés. Les données endommagées seront effacées."
                : "La bibliothèque est illisible. Tous ses écrans personnels et leurs révisions seront effacés.";
        return DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Réparer vos écrans personnels ?")
                .setMessage(consequence + " Cette opération est définitive.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Réparer", (dialog, which) -> SurfaceRenderer.change(activity, host, damaged::recover)));
    }

    static View gallery(Activity a, Account account, ArtifactDesk.Host host) {
        ThemePalette palette = ThemePalette.active(a, account);
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(palette.paper);
        LinearLayout list = Ui.column(a);
        Ui.pad(list, 20, 12);
        scroll.addView(list);
        mountCustomTabs(list, a, account, host, palette);
        try {
            Map<String, String> pages = PageLayouts.titles(account, false);
            if (pages.isEmpty()) list.addView(Ui.text(a,
                    "Demandez à Hermes de créer un écran, puis appliquez sa proposition depuis la discussion.", 16));
            for (Map.Entry<String, String> entry : pages.entrySet()) {
                Button link = Ui.button(a, entry.getValue(), v -> host.openWorkspace(entry.getKey()));
                link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                link.setTextColor(palette.accent);
                list.addView(link);
                list.addView(Ui.divider(a));
            }
        } catch (PageLayouts.Damaged damaged) {
            Diagnostics.warning("workspace", "page_index_damaged");
            list.addView(Ui.text(a, "Les données de vos écrans personnels sont endommagées.", 16));
            list.addView(Ui.button(a, REPAIR, view -> confirmRecovery(a, damaged, host)));
        } catch (IllegalStateException error) {
            Diagnostics.warning("workspace", "page_index_unavailable");
            list.addView(Ui.text(a, "Bibliothèque indisponible. Rouvrez votre profil.", 16));
        }
        return scroll;
    }

    static void mountCustomTabs(LinearLayout parent, Activity activity, Account account,
                                ArtifactDesk.Host host, ThemePalette palette) {
        if (account == null || host == null) return;
        LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(account, palette.night);
        List<LiveUiPatch.CustomTab> tabs = manager.tabs();
        if (tabs.isEmpty()) return;
        TextView heading = Ui.text(activity, "Onglets de l’atelier", palette.scaledSp(15));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextColor(palette.muted);
        heading.setPadding(0, 0, 0, Ui.dp(activity, 6));
        parent.addView(heading);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(activity);
        for (LiveUiPatch.CustomTab tab : tabs) {
            Button chip = Ui.button(activity, tab.label, v -> host.openWorkspace(tab.page));
            chip.setTag("atelier-tab-" + tab.id);
            chip.setTextColor(palette.accent);
            chip.setTextSize(palette.scaledSp(14));
            chip.setMinHeight(Ui.dp(activity, 44));
            Ui.pad(chip, 14, 6);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(activity, 18));
            bg.setColor(palette.accentSurface);
            bg.setStroke(Math.max(1, Ui.dp(activity, 1)), palette.line);
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(Ui.dp(activity, 8));
            row.addView(chip, lp);
        }
        scroll.addView(row);
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(-1, -2);
        wrap.bottomMargin = Ui.dp(activity, 12);
        parent.addView(scroll, wrap);
    }
}
