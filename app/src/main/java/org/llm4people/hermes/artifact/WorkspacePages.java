package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.Account;
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
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(a.getColor(R.color.paper));
        LinearLayout list = Ui.column(a);
        Ui.pad(list,20,12);
        scroll.addView(list);
        try {
            Map<String,String> pages = PageLayouts.titles(account,false);
            if (pages.isEmpty()) list.addView(Ui.text(a,
                    "Demandez à Hermes de créer un écran, puis appliquez sa proposition depuis les artefacts du fil.",16));
            for (Map.Entry<String,String> entry : pages.entrySet()) {
                Button link = Ui.button(a,entry.getValue(),v -> host.openWorkspace(entry.getKey()));
                link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                list.addView(link);
                list.addView(Ui.divider(a));
            }
        } catch (PageLayouts.Damaged damaged) {
            Diagnostics.warning("workspace", "page_index_damaged");
            list.addView(Ui.text(a, "Les données de vos écrans personnels sont endommagées.", 16));
            list.addView(Ui.button(a, REPAIR, view -> confirmRecovery(a, damaged, host)));
        } catch (IllegalStateException error) {
            Diagnostics.warning("workspace","page_index_unavailable");
            list.addView(Ui.text(a,"Bibliothèque indisponible. Rouvrez votre profil.",16));
        }
        return scroll;
    }
}
