package org.llm4people.hermes.journey;

import org.json.JSONException;
import org.json.JSONObject;

/** Two phases let the host check the canonical admission ledger before the real Send tap. */
final class DiscussionSkillsJourney {
    private final JourneyUi ui;
    private final JSONObject fixture;

    DiscussionSkillsJourney(JourneyUi ui, JSONObject fixture) {
        this.ui = ui;
        this.fixture = fixture;
    }

    void stage() throws JSONException {
        ui.tap("Nouvelle conversation");
        ui.fill("Message à Hermes", fixture.getString("draft"));
        ui.hideKeyboard();
        ui.tap("Autres actions");
        ui.tap("Modèle et outils");
        ui.tap("Compétences de Hermes");
        ui.fill("Rechercher une compétence", "relecture");
        ui.hideKeyboard();
        ui.see(fixture.getString("skill"));
        ui.absent("support/synthese");
        ui.capture("01-competence-recherche");
        ui.tap(fixture.getString("skill"));
        ui.contains("Catégorie · qualite", fixture.getString("summary"));
        ui.capture("02-competence-resume");
        ui.tap("Préparer le brouillon");
        ui.see(fixture.getString("staged"));
        ui.capture("03-competence-brouillon");
    }

    void send() throws JSONException {
        ui.see(fixture.getString("staged"));
        ui.tap("Envoyer");
        ui.contains(fixture.getString("answer"));
        ui.see("Envoyer");
        ui.capture("04-competence-reponse");
    }
}
