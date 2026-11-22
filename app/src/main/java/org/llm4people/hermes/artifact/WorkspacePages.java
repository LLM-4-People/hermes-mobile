package org.llm4people.hermes.artifact;

import android.app.Activity;
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
        } catch (IllegalStateException error) {
            Diagnostics.warning("workspace","page_index_unavailable");
            list.addView(Ui.text(a,"Bibliothèque indisponible. Rouvrez votre profil.",16));
        }
        return scroll;
    }
}
