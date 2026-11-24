package org.llm4people.hermes.journey;

import org.llm4people.hermes.R;

/** An authenticated agent proposes two changes; only explicit user actions change the app or submit a turn. */
final class WorkspacePagesJourney {
    private final JourneyUi ui;
    WorkspacePagesJourney(JourneyUi ui) { this.ui = ui; }

    interface AccountSwitch { void verify() throws Exception; }

    void run(String name, AccountSwitch isolation) throws Exception {
        String composerHint = ui.str(R.string.thread_message_hint);
        String sendLabel = ui.str(R.string.thread_send);
        ui.tap(ui.str(R.string.thread_new_conversation));
        ui.fill(composerHint, "Vérifie mes écrans mobiles.");
        ui.tap(sendLabel);
        ui.contains("Voici les deux dispositions demandées.");
        ui.see(sendLabel);
        apply(ui.str(R.string.ws_art_layout_titled, ui.str(R.string.ws_surface_personal, "diagnostic")));
        String existing = "Garder la trace.";
        ui.fill(composerHint, existing);
        apply(ui.str(R.string.ws_art_layout_titled, ui.str(R.string.ws_surface_thread)));
        ui.see("Écriture ciblée");
        ui.see(existing);
        ui.absent(ui.str(R.string.thread_you));
        pages();
        ui.tap("Diagnostic mobile");
        ui.see(name);
        ui.fill("Fichier", "sample.py");
        ui.tap(ui.str(R.string.ws_form_prepare_draft));
        ui.see(ui.str(R.string.ws_form_review_title));
        ui.tap(ui.str(R.string.ws_form_send_to_draft));
        String staged = existing + "\nRelire ce fichier.\n\nFichier\nsample.py";
        ui.see(staged);
        ui.see(sendLabel);
        ui.capture("12-formulaire-brouillon");
        ui.tap(ui.str(R.string.ws_back_previous));
        ui.see("sample.py");
        ui.see(name);
        ui.capture("13-ecran-personnel");
        ui.tap(ui.str(R.string.nav_discussions));
        menu(ui.str(R.string.ws_original_discussion_view));
        ui.see(staged);
        ui.tap(sendLabel);
        ui.contains("La conversation est prête.");
        ui.see(sendLabel);
        isolation.verify();
        pages();
        ui.tap("Diagnostic mobile");
        ui.see(name);
        menu(ui.str(R.string.ws_restore_desk));
        ui.tap("Diagnostic mobile (diagnostic)");
        ui.tap(ui.str(R.string.ws_delete));
        pages();
        ui.contains(ui.str(R.string.ws_pages_empty));
        ui.absent("Diagnostic mobile");
    }

    private void pages() {
        ui.tap(ui.str(R.string.nav_apps));
        ui.tap(ui.str(R.string.ws_my_screens));
    }

    private void apply(String artifact) {
        menu(ui.str(R.string.ws_artifacts));
        ui.tap(artifact);
        ui.tap(ui.str(R.string.ws_art_apply_layout_action));
        ui.tap(ui.str(R.string.ws_apply));
    }

    private void menu(String action) {
        ui.tap(ui.str(R.string.ws_more_actions));
        ui.tap(action);
    }
}
