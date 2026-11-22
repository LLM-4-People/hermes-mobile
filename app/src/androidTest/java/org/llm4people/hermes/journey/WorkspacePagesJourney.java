package org.llm4people.hermes.journey;

/** An authenticated agent proposes two changes; only explicit user actions change the app or submit a turn. */
final class WorkspacePagesJourney {
    private final JourneyUi ui;
    WorkspacePagesJourney(JourneyUi ui) { this.ui = ui; }

    void run(String name) {
        ui.tap("Nouvelle conversation");
        ui.fill("Message à Hermes", "Vérifie mes écrans mobiles.");
        ui.tap("Envoyer");
        ui.contains("Voici les deux dispositions demandées.");
        ui.see("Envoyer");
        apply("Disposition · Écran personnel · diagnostic");
        String existing = "Garder la trace.";
        ui.fill("Message à Hermes", existing);
        apply("Disposition · Conversation");
        ui.see("Écriture ciblée");
        ui.see(existing);
        ui.absent("Vous");
        menu("Mes écrans");
        ui.tap("Diagnostic mobile");
        ui.see(name);
        ui.fill("Fichier", "sample.py");
        ui.tap("Préparer un brouillon…");
        ui.see("Relire le brouillon");
        ui.tap("Vers le brouillon");
        String staged = existing + "\nRelire ce fichier.\n\nFichier\nsample.py";
        ui.see(staged);
        ui.see("Envoyer");
        ui.capture("12-formulaire-brouillon");
        ui.tap("Revenir à l’écran précédent");
        ui.see("sample.py");
        ui.see(name);
        ui.capture("13-ecran-personnel");
        menu("Vue de discussion d’origine");
        ui.see(staged);
        ui.tap("Envoyer");
        ui.contains("La conversation est prête.");
        ui.see("Envoyer");
        menu("Rétablir l’atelier");
        ui.tap("Diagnostic mobile (diagnostic)");
        ui.tap("Rétablir");
        menu("Mes écrans");
        ui.contains("Demandez à Hermes de créer un écran");
        ui.absent("Diagnostic mobile");
    }

    private void apply(String artifact) {
        menu("Artefacts");
        ui.tap(artifact);
        ui.tap("Appliquer la disposition…");
        ui.tap("Appliquer");
    }

    private void menu(String action) {
        ui.tap("Autres actions");
        ui.tap(action);
    }
}
