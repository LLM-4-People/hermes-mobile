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
        apply("Disposition · Écran personnel · diagnostic");
        String existing = "Garder la trace.";
        ui.fill(composerHint, existing);
        apply("Disposition · Conversation");
        ui.see("Écriture ciblée");
        ui.see(existing);
        ui.absent("Vous");
        pages();
        ui.tap("Diagnostic mobile");
        ui.see(name);
        ui.fill("Fichier", "sample.py");
        ui.tap("Préparer un brouillon…");
        ui.see("Relire le brouillon");
        ui.tap("Vers le brouillon");
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
        ui.tap("Supprimer");
        pages();
        ui.contains("Demandez à Hermes de créer un écran");
        ui.absent("Diagnostic mobile");
    }

    private void pages() {
        ui.tap(ui.str(R.string.nav_atelier));
        ui.tap(ui.str(R.string.ws_my_screens));
    }

    private void apply(String artifact) {
        menu(ui.str(R.string.ws_artifacts));
        ui.tap(artifact);
        ui.tap("Appliquer la disposition…");
        ui.tap("Appliquer");
    }

    private void menu(String action) {
        ui.tap(ui.str(R.string.ws_more_actions));
        ui.tap(action);
    }
}
