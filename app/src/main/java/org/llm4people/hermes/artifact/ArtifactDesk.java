package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONArray;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.Account;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Native, profile-scoped artifact reader. Construct from the current ordered transcript. */
public final class ArtifactDesk {
    public interface Host {
        BooleanSupplier deviceActionOrigin();
        /** Only a verified proposal in its originating discussion can mount a nearby guide. */
        default View nearbyGuide() { return null; }
        void onAction(String actionId);
        void onLayoutChanged();
        void openWorkspace(String pageId);
        void stageDraft(String text);
        String workspaceValue(String source);
        void threadSlots(List<String> order);
    }

    private final Activity activity;
    private final Account account;
    private final Host host;
    private final ArtifactCatalog catalog;
    private final boolean threadSelected;

    public ArtifactDesk(Activity activity, Account account, String threadId, JSONArray messages, Host host) {
        this.activity = activity;
        this.account = account;
        this.host = host;
        threadSelected = threadId != null && !threadId.isEmpty();
        catalog = new ArtifactCatalog(messages == null ? new JSONArray() : messages);
    }

    public static String proposalInstructions(Account account, String threadId) {
        String instructions = WorkspaceLayout.instructions();
        try {
            ThreadInstruments.Pin pin = new ThreadInstruments(account, threadId).state().current;
            if (pin != null) instructions += WorkspaceLayout.pinnedReference(pin.pageId, pin.messageId, pin.fenceOrdinal);
        } catch (IllegalStateException unreadable) {
            Diagnostics.warning("workspace", "instrument_reference_unavailable");
        }
        return instructions;
    }

    public static View decorate(Activity activity, Account account, String surface, View nativeContent, Host host) {
        return SurfaceRenderer.decorate(activity, account, surface, nativeContent, host);
    }

    public static View pages(Activity activity, Account account, Host host) {
        return WorkspacePages.gallery(activity, account, host);
    }

    public static View page(Activity activity, Account account, String id, Host host) {
        if (!WorkspaceLayout.custom(id)) return null;
        return SurfaceRenderer.decorate(activity, account, id, null, host);
    }

    public static void refreshBindings(View root, Host host) {
        DeskDialogs.dismissStale();
        WorkspaceBlocks.refresh(root,host);
    }

    public static void dismiss(Activity activity) { DeskDialogs.dismiss(activity); }
    public static void dismissReviews(Activity activity, Host owner) {
        if (owner != null) DeskDialogs.dismiss(activity, owner);
    }

    /** Recovery is always reachable from native navigation, even after a layout has been restored. */
    public static void manage(Activity activity, Account account, Host host) {
        Map<String,String> all = new LinkedHashMap<>(WorkspaceLayout.SURFACES);
        AlertDialog.Builder controls = new AlertDialog.Builder(activity).setTitle("Dispositions des écrans");
        try {
            PageLayouts.titles(account,true).forEach((id,title) -> all.put(id, title + " (" + id.substring(5) + ")"));
        } catch (PageLayouts.Damaged damaged) {
            controls.setNeutralButton(WorkspacePages.REPAIR, (dialog, which) ->
                    WorkspacePages.confirmRecovery(activity, damaged, host));
        } catch (IllegalStateException error) {
            Diagnostics.warning("workspace","page_index_unavailable");
        }
        String[] surfaces = all.keySet().toArray(new String[0]);
        String[] labels = all.values().toArray(new String[0]);
        DeskDialogs.open(activity, controls.setItems(labels, (d, which) -> SurfaceRenderer.controls(activity,
                        new LayoutHistory(account, surfaces[which]), host))
                .setNegativeButton("Fermer", null));
    }

    public void show() {
        LinearLayout list = Ui.column(activity);
        Ui.pad(list, 18, 8);
        if (threadSelected) list.addView(Ui.button(activity, "Fichiers publiés", v -> {
            DeskDialogs.navigate(activity, () -> host.onAction("published_files"));
        }));
        if (catalog.records.isEmpty()) list.addView(Ui.text(activity,
                "Les extraits de code, les correctifs et les propositions de disposition de ce fil apparaîtront ici.", 16));
        for (ArtifactCatalog.Record record : catalog.records) {
            int versions = record.versions.size();
            String label = record.title + (versions > 1 ? " · " + versions + " versions" : "");
            Button b = Ui.button(activity, label, v -> detail(record, versions - 1));
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setAllCaps(false);
            list.addView(b);
            list.addView(Ui.divider(activity));
        }
        if (catalog.limited) list.addView(Ui.text(activity,
                "Les messages volumineux ou plus anciens restent consultables dans le fil.", 14));
        ScrollView nativeList = scroll(list);
        DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Artefacts du fil")
                .setView(decorate(activity, account, "artifacts", nativeList, host))
                .setNegativeButton("Fermer", null));
    }

    private void detail(ArtifactCatalog.Record record, int version) {
        LinearLayout box = Ui.column(activity);
        Ui.pad(box, 16, 8);
        ScrollView scroll = scroll(box);
        presentVersion(box, scroll, record, version);
        DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle(record.title)
                .setView(scroll).setNegativeButton("Fermer", null));
    }

    private void presentVersion(LinearLayout box, ScrollView scroll, ArtifactCatalog.Record record, int version) {
        box.removeAllViews();
        String content = record.versions.get(version);
        if (record.versions.size() > 1) {
            String label = "Version " + (version + 1) + " sur " + record.versions.size();
            box.addView(Ui.button(activity, label, v -> {
                String[] options = new String[record.versions.size()];
                for (int n = 0; n < options.length; n++) options[n] = "Version " + (n + 1);
                DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Version à lire")
                        .setSingleChoiceItems(options, version, (d, n) -> {
                            d.dismiss();
                            presentVersion(box, scroll, record, n);
                            scroll.scrollTo(0, 0);
                        }).setNegativeButton("Fermer", null));
            }));
        }
        if (WorkspaceLayout.FENCE.equals(record.language)) {
            try {
                WorkspaceLayout layout = WorkspaceLayout.parse(content);
                String surface = WorkspaceLayout.screenName(layout.surface);
                box.addView(Ui.text(activity, "Écran concerné · " + surface, 16));
                if ("replace".equals(layout.mode)) box.addView(Ui.text(activity, WorkspaceLayout.custom(layout.surface)
                        ? "Écran personnel enregistré dans ce profil." : "Cette proposition remplace le contenu de cet écran.", 15));
                box.addView(SurfaceRenderer.preview(activity, layout));
                box.addView(Ui.button(activity, "Appliquer la disposition…",
                        v -> confirm(layout, content)));
                return;
            } catch (IOException e) {
                Diagnostics.warning("workspace", "proposal_rejected");
                box.addView(Ui.text(activity,
                        "Disposition invalide. Demandez à Hermes de corriger la proposition.", 16));
            }
        }
        CodeReader.contents(box, record.title, record.language, content);
    }

    private void confirm(WorkspaceLayout layout, String raw) {
        String screen = WorkspaceLayout.screenName(layout.surface);
        DeskDialogs.open(activity, new AlertDialog.Builder(activity).setTitle("Modifier l’écran « " + screen + " » ?")
                .setMessage("Cette disposition sera utilisée dans ce profil. Vous pourrez l’annuler depuis l’atelier.")
                .setNegativeButton("Annuler", null).setPositiveButton("Appliquer", (d, w) ->
                        SurfaceRenderer.change(activity, host, () -> new LayoutHistory(account, layout.surface).replace(raw))));
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }
}
