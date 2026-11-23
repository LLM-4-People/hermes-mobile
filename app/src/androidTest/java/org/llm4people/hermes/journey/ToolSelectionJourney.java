package org.llm4people.hermes.journey;

import org.llm4people.hermes.R;

/** The fixture model reports the tools offered by the actual authenticated run. */
final class ToolSelectionJourney {
    private final JourneyUi ui;
    ToolSelectionJourney(JourneyUi ui) { this.ui = ui; }

    void run() {
        String newConversation = ui.str(R.string.thread_new_conversation);
        ui.tap(newConversation);
        ui.see(ui.str(R.string.thread_message_hint));
        toggleAndAsk("Écriture désactivée.", "14-outils-sans-ecriture");
        toggleAndAsk("Écriture disponible.", "14-outils-avec-ecriture");
        ui.capture("14-outil-discussion");
        ui.tap(ui.str(R.string.ws_all_discussions));
        ui.see(newConversation);
    }

    private void toggleAndAsk(String expected, String captureName) {
        String sendLabel = ui.str(R.string.thread_send);
        ui.tap(ui.str(R.string.ws_more_actions));
        ui.tap(ui.str(R.string.ws_model_and_tools));
        ui.tap(ui.str(R.string.panel_choose_tools, "file"));
        ui.tap("write_file");
        ui.see("write_file");
        ui.capture(captureName);
        ui.tap(ui.str(R.string.panel_back));
        ui.tap(ui.str(R.string.panel_done));
        ui.fill(ui.str(R.string.thread_message_hint), "Quels outils de vérification sont disponibles ?");
        ui.tap(sendLabel);
        ui.contains(expected);
        ui.see(sendLabel);
    }
}
