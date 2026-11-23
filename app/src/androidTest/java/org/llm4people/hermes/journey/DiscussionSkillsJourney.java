package org.llm4people.hermes.journey;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.R;

/** Two phases let the host check the canonical admission ledger before the real Send tap. */
final class DiscussionSkillsJourney {
    private final JourneyUi ui;
    private final JSONObject fixture;

    DiscussionSkillsJourney(JourneyUi ui, JSONObject fixture) {
        this.ui = ui;
        this.fixture = fixture;
    }

    void stage() throws JSONException {
        ui.tap(ui.str(R.string.thread_new_conversation));
        ui.fill(ui.str(R.string.thread_message_hint), fixture.getString("draft"));
        ui.hideKeyboard();
        ui.tap(ui.str(R.string.ws_more_actions));
        ui.tap(ui.str(R.string.ws_model_and_tools));
        ui.tap(ui.str(R.string.skills_title));
        ui.fill(ui.str(R.string.brain_skills_search), "relecture");
        ui.hideKeyboard();
        ui.see(fixture.getString("skill"));
        ui.absent("support/synthese");
        ui.capture("01-competence-recherche");
        ui.tap(fixture.getString("skill"));
        ui.see("qualite");
        ui.contains(fixture.getString("summary"));
        ui.capture("02-competence-resume");
        ui.tap(ui.str(R.string.brain_skills_use_in_thread));
        ui.see(fixture.getString("staged"));
        ui.capture("03-competence-brouillon");
    }

    void send() throws JSONException {
        String sendLabel = ui.str(R.string.thread_send);
        ui.see(fixture.getString("staged"));
        ui.tap(sendLabel);
        ui.see(sendLabel);
        ui.see("Compétence consultée");
        ui.see("Voir le résultat");
        ui.revealText(fixture.getString("answer"));
        ui.capture("04-competence-reponse");
        ui.tap("Voir le résultat");
        ui.see("Résultat · skill_view");
        ui.see(ui.str(R.string.code_copy_content));
        ui.selectableText(fixture.getString("skill") + "/SKILL.md", fixture.getString("marker"));
        ui.tap(ui.str(R.string.ws_close));
    }
}
