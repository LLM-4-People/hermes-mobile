package org.llm4people.hermes.journey;

/** The fixture model reports the tools offered by the actual authenticated run. */
final class ToolSelectionJourney {
    private final JourneyUi ui;
    ToolSelectionJourney(JourneyUi ui) { this.ui = ui; }

    void run() {
        ui.tap("Nouvelle conversation");
        ui.see("Message à Hermes");
        toggleAndAsk("Écriture désactivée.", "14-outils-sans-ecriture");
        toggleAndAsk("Écriture disponible.", "14-outils-avec-ecriture");
        ui.capture("14-outil-discussion");
        ui.tap("Toutes les discussions");
        ui.see("Nouvelle conversation");
    }

    private void toggleAndAsk(String expected, String captureName) {
        ui.tap("Autres actions");
        ui.tap("Modèle et outils");
        ui.tap("Choisir les outils de file");
        ui.tap("write_file");
        ui.see("write_file");
        ui.capture(captureName);
        ui.tap("Retour");
        ui.tap("Fermer");
        ui.fill("Message à Hermes", "Quels outils de vérification sont disponibles ?");
        ui.tap("Envoyer");
        ui.contains(expected);
        ui.see("Envoyer");
    }
}
