package org.llm4people.hermes.journey;

import org.json.JSONException;
import org.json.JSONObject;

/** Drives the model's two real review forms without publishing either as an account-wide page. */
final class ThreadInstrumentJourney {
    private final JourneyUi ui;
    private final String first, template, goal, revision, other, title, form;
    private static final String RETURN = "Revenir à la discussion";
    private static final String OPEN = "Ouvrir l’outil épinglé";
    private static final String TRY = "Essayer dans la discussion";
    private static final String PIN = "Épingler à cette discussion";
    private static final String COMPARE = "Comparer les versions";
    private static final String STAGED = "Relire ce changement.\n\nFichier\nfacture.py";

    ThreadInstrumentJourney(JourneyUi ui, JSONObject spec) throws JSONException {
        this.ui = ui;
        first = spec.getString("first");
        template = spec.getString("template");
        goal = spec.getString("goal");
        revision = spec.getString("revision");
        other = spec.getString("other");
        title = spec.getString("instrumentTitle");
        form = spec.getString("form");
    }

    void portrait() {
        ui.tap("Nouvelle conversation");
        send(first, "Vérifiez le calcul du total avant l’arrondi.");
        ui.tap("Autres actions");
        ui.tap("Créer un outil pour ce fil");
        String request = ui.fieldText("Message à Hermes");
        if (!request.equals(template)) throw new AssertionError("The French request did not enter the editable composer");
        ui.fill("Message à Hermes", request + "\n\n" + goal);
        ui.hideKeyboard();
        ui.capture("19-demande-outil");
        ui.tap("Envoyer");
        ui.contains("Voici votre outil de relecture.");
        ui.see("Envoyer");
        ui.tap(TRY);
        ui.contains(title);
        ui.see(form);
        ui.fill("Fichier", "facture.py");
        ui.hideKeyboard();
        ui.capture("20-essai-discussion");
        ui.tap(RETURN);
        ui.absent(OPEN);
        ui.tap("Autres actions");
        ui.tap("Mes écrans");
        ui.contains("Demandez à Hermes de créer un écran");
        ui.absent(title);
        ui.tap("Revenir à l’écran précédent");
        ui.tap(TRY);
        ui.tap(PIN);
        ui.see(form);
        ui.fill("Fichier", "facture.py");
        ui.tap("Préparer un brouillon…");
        ui.see("Relire le brouillon");
        ui.tap("Vers le brouillon");
        ui.see(STAGED);
        ui.hideKeyboard();
        ui.capture("21-brouillon-outil");
        if (ui.has(RETURN)) ui.tap(RETURN);
        send(revision, "La priorité est prête à comparer.");
        ui.tap(COMPARE);
        ui.contains("Consigne du brouillon", "Avant", "Relire ce changement.",
                "Après", "Relire ce changement par priorité.");
        ui.contains("Champ ajouté", "Priorité");
        ui.capture("22-comparaison-outil");
        ui.tap("Garder la version épinglée");
        ui.see(form);
        ui.absent("Priorité");
        ui.tap(RETURN);
        ui.tap(COMPARE);
        ui.tap("Adopter cette version");
        ui.see("Priorité");
        ui.fill("Priorité", "Élevée");
        ui.hideKeyboard();
        ui.capture("23-outil-adopte");
        ui.tap("Annuler la modification");
        ui.see(form);
        ui.absent("Priorité");
        ui.capture("24-outil-annule");
        ui.tap(RETURN);
        ui.tap("Toutes les discussions");
        ui.tap("Nouvelle conversation");
        send(other, "La réunion est prête.");
        ui.absent(OPEN);
        ui.absent(TRY);
        ui.absent(COMPARE);
        ui.tap("Toutes les discussions");
        ui.tap(first);
        ui.heading(first);
        ui.tap(OPEN);
        ui.see(form);
        ui.absent("Priorité");
        ui.tap(RETURN);
    }

    void wide() {
        ui.heading(first);
        ui.tap(OPEN);
        ui.see(form);
        ui.fill("Fichier", "facture.py");
        ui.hideKeyboard();
        ui.sideBySide("Fichier");
        ui.capture("25-outil-grand-ecran");
    }

    private void send(String prompt, String reply) {
        ui.fill("Message à Hermes", prompt);
        ui.tap("Envoyer");
        ui.contains(reply);
        ui.see("Envoyer");
        ui.hideKeyboard();
    }
}
