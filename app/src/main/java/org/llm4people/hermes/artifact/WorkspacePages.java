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
    static AlertDialog confirmRecovery(Activity activity, PageLayouts.Damaged damaged, ArtifactDesk.Host host) {
        String consequence = activity.getString(damaged.hasReadablePages()
                ? R.string.ws_pages_repair_partial : R.string.ws_pages_repair_all);
        boolean[] handled = {false};
        return DeskDialogs.open(activity, new AlertDialog.Builder(activity)
                .setTitle(R.string.ws_pages_repair_title)
                .setMessage(activity.getString(R.string.ws_pages_repair_irreversible, consequence))
                .setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.ws_repair, (dialog, which) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    SurfaceRenderer.change(activity, host, damaged::recover);
                }));
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
            Map<String, String> pages = PageLayouts.titles(a, account, false);
            if (pages.isEmpty()) {
                TextView empty = Ui.text(a, a.getString(R.string.ws_pages_empty), palette.scaledSp(16));
                empty.setTextColor(palette.ink);
                list.addView(empty);
            }
            for (Map.Entry<String, String> entry : pages.entrySet()) {
                Button link = Ui.button(a, entry.getValue(), v -> host.openWorkspace(entry.getKey()));
                link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                link.setTextColor(palette.accent);
                list.addView(link);
                View divider = Ui.divider(a);
                divider.setBackgroundColor(palette.line);
                list.addView(divider);
            }
        } catch (PageLayouts.Damaged damaged) {
            Diagnostics.warning("workspace", "page_index_damaged");
            TextView msg = Ui.text(a, a.getString(R.string.ws_pages_damaged), palette.scaledSp(16));
            msg.setTextColor(palette.ink);
            list.addView(msg);
            Button repair = Ui.button(a, a.getString(R.string.ws_pages_repair_action), view -> confirmRecovery(a, damaged, host));
            repair.setTextColor(palette.accent);
            list.addView(repair);
        } catch (IllegalStateException error) {
            Diagnostics.warning("workspace", "page_index_unavailable");
            TextView msg = Ui.text(a, a.getString(R.string.ws_pages_unavailable), palette.scaledSp(16));
            msg.setTextColor(palette.ink);
            list.addView(msg);
        }
        return scroll;
    }

    static void mountCustomTabs(LinearLayout parent, Activity activity, Account account,
                                ArtifactDesk.Host host, ThemePalette palette) {
        if (account == null || host == null) return;
        LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(account, palette.night);
        List<LiveUiPatch.CustomTab> tabs = manager.tabs();
        if (tabs.isEmpty()) return;
        TextView heading = Ui.text(activity, activity.getString(R.string.ws_pages_custom_tabs), palette.scaledSp(15));
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
            int touchMin = Ui.dp(activity, 48);
            chip.setMinHeight(touchMin);
            chip.setMinimumHeight(touchMin);
            Ui.pad(chip, 14, 6);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(activity, Ui.BUTTON_CORNER_DP));
            bg.setColor(palette.accentSurface);
            bg.setStroke(Math.max(1, Ui.dp(activity, 1)), WorkspaceForms.editorUnfocusedStrokeColor(palette));
            chip.setBackground(Ui.roundedRipple(activity, Ui.BUTTON_CORNER_DP, bg));
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
