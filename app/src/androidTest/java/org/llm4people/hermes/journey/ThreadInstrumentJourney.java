package org.llm4people.hermes.journey;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.R;

/** Drives the model's two real review forms without publishing either as an account-wide page. */
final class ThreadInstrumentJourney {
    private final JourneyUi ui;
    private final String first, goal, revision, other, title, form;
    private static final String STAGED = "Relire ce changement.\n\nFichier\nfacture.py";

    ThreadInstrumentJourney(JourneyUi ui, JSONObject spec) throws JSONException {
        this.ui = ui;
        first = spec.getString("first");
        goal = spec.getString("goal");
        revision = spec.getString("revision");
        other = spec.getString("other");
        title = spec.getString("instrumentTitle");
        form = spec.getString("form");
    }

    void portrait() {
        String ret = ui.str(R.string.instrument_return);
        String open = ui.str(R.string.instrument_open_pin);
        String tryTool = ui.str(R.string.instrument_try);
        String pin = ui.str(R.string.instrument_pin);
        String compare = ui.str(R.string.instrument_compare);
        String createTool = ui.str(R.string.instrument_create);
        String expectedRequest = ui.str(R.string.instrument_create_request);
        String composerHint = ui.str(R.string.thread_message_hint);
        String sendLabel = ui.str(R.string.thread_send);
        String tabProposed = ui.str(R.string.instrument_tab_next_role);
        String tabPinned = ui.str(R.string.instrument_tab_pin_role);
        String tabDiff = ui.str(R.string.instrument_tab_diff_role);

        ui.tap(ui.str(R.string.thread_new_conversation));
        send(first, "Vérifiez le calcul du total avant l’arrondi.");
        ui.tap(ui.str(R.string.ws_more_actions));
        ui.revealText(createTool);
        ui.capture("18-creer-outil");
        ui.tap(createTool);
        String request = ui.fieldText(composerHint);
        if (!request.equals(expectedRequest)) {
            throw new AssertionError("The create-tool request did not enter the editable composer");
        }
        ui.fill(composerHint, request + "\n\n" + goal);
        ui.hideKeyboard();
        ui.heading(first);
        ui.capture("19-demande-outil");
        ui.tap(sendLabel);
        ui.contains("Voici votre outil de relecture.");
        ui.see(sendLabel);
        ui.tap(tryTool);
        ui.contains(title);
        ui.see(form);
        ui.fill("Fichier", "facture.py");
        ui.hideKeyboard();
        ui.revealField("Fichier");
        ui.capture("20-essai-discussion");
        ui.tap(ret);
        ui.absent(open);
        ui.tap(ui.str(R.string.nav_apps));
        ui.tap(ui.str(R.string.ws_my_screens));
        ui.contains(ui.str(R.string.ws_pages_empty));
        ui.absent(title);
        ui.tap(ui.str(R.string.nav_discussions));
        ui.tap(tryTool);
        ui.tap(pin);
        ui.see(form);
        ui.fill("Fichier", "facture.py");
        ui.tap(ui.str(R.string.ws_form_prepare_draft));
        ui.see(ui.str(R.string.ws_form_review_title));
        ui.tap(ui.str(R.string.ws_form_send_to_draft));
        ui.see(STAGED);
        ui.heading(first);
        ui.hideKeyboard();
        ui.capture("21-brouillon-outil");
        if (ui.has(ret)) ui.tap(ret);
        ui.tap(open);
        ui.see(form);
        send(revision, "La priorité est prête à comparer.");
        ui.tap(compare);
        ui.contains(ui.str(R.string.ws_diff_draft_prompt), ui.str(R.string.ws_diff_before), "Relire ce changement.",
                ui.str(R.string.ws_diff_after), "Relire ce changement par priorité.");
        ui.revealText(ui.str(R.string.ws_diff_field_added), "Priorité");
        ui.capture("22-comparaison-outil");
        ui.tap(tabProposed);
        String candidateFile = "facture/revue-proposee.py";
        ui.fill("Fichier", candidateFile);
        ui.fill("Priorité", "Élevée");
        ui.hideKeyboard();
        ui.revealField("Priorité");
        ui.capture("22-version-proposee");
        ui.tap(tabPinned);
        ui.see(form);
        ui.absent("Priorité");
        if (candidateFile.equals(ui.fieldText("Fichier")))
            throw new AssertionError("An unadopted revision changed the pinned form");
        ui.fill("Fichier", "facture/revue-epinglee.py");
        ui.revealField("Fichier");
        ui.capture("22-version-epinglee");
        ui.tap(tabProposed);
        if (!candidateFile.equals(ui.fieldText("Fichier")) || !"Élevée".equals(ui.fieldText("Priorité")))
            throw new AssertionError("Switching between revisions discarded the private candidate fields");
        ui.tap(tabDiff);
        ui.contains(ui.str(R.string.ws_diff_field_added), "Priorité");
        ui.tap(ui.str(R.string.instrument_keep));
        ui.see(form);
        ui.absent("Priorité");
        ui.tap(ret);
        ui.tap(compare);
        ui.tap(ui.str(R.string.instrument_adopt));
        ui.see("Priorité");
        ui.fill("Priorité", "Élevée");
        ui.hideKeyboard();
        ui.revealField("Priorité");
        ui.capture("23-outil-adopte");
        ui.tap(ui.str(R.string.instrument_undo));
        ui.see(form);
        ui.absent("Priorité");
        ui.revealField("Fichier");
        ui.capture("24-outil-annule");
        ui.tap(ret);
        ui.tap(ui.str(R.string.ws_all_discussions));
        ui.tap(ui.str(R.string.thread_new_conversation));
        send(other, "La réunion est prête.");
        ui.absent(open);
        ui.absent(tryTool);
        ui.absent(compare);
        ui.tap(ui.str(R.string.ws_all_discussions));
        ui.tap(first);
        ui.heading(first);
        ui.tap(open);
        ui.see(form);
        ui.absent("Priorité");
        ui.tap(ret);
    }

    void wide() {
        ui.heading(first);
        ui.tap(ui.str(R.string.instrument_open_pin));
        ui.see(form);
        ui.fill("Fichier", "facture.py");
        ui.hideKeyboard();
        ui.sideBySide("Fichier");
        ui.capture("25-outil-grand-ecran");
    }

    private void send(String prompt, String reply) {
        ui.fill(ui.str(R.string.thread_message_hint), prompt);
        ui.tap(ui.str(R.string.thread_send));
        ui.contains(reply);
        ui.see(ui.str(R.string.thread_send));
        ui.hideKeyboard();
    }
}
