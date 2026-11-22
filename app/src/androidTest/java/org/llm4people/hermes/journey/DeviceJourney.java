package org.llm4people.hermes.journey;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Test-APK-only entry point, launched with two isolated fixture endpoints by verification/device/run.py. */
public final class DeviceJourney extends Instrumentation {
    private String phase;
    private JourneyUi ui;
    private JSONObject fixture;
    private String primary;
    private String secondary;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        phase = arguments.getString("phase", "");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            File dir = new File(getTargetContext().getCacheDir(), "device-journey");
            fixture = new JSONObject(new String(Files.readAllBytes(new File(dir, "fixture.json").toPath()),
                    StandardCharsets.UTF_8));
            primary = "Atelier " + fixture.getString("run");
            secondary = "Personnel " + fixture.getString("run");
            String pkg = getTargetContext().getPackageName();
            Activity activity = startActivitySync(new Intent().setClassName(pkg, pkg + ".MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0)
                throw new AssertionError("Private workspace allows external screen capture");
            ui = new JourneyUi(this, activity, new File(dir, fixture.getString("run")));
            switch (phase) {
                case "work": work(); break;
                case "restart": restart(); break;
                case "pages":
                    String name = "Écrans " + fixture.getString("run");
                    profile(name, "endpoint", "key", fixture.getString("phrase"));
                    new ToolSelectionJourney(ui).run();
                    new WorkspacePagesJourney(ui).run(name, () -> pagesIsolation(name));
                    lock();
                    forget(name, fixture.getString("phrase"));
                    break;
                default: throw new IllegalArgumentException("Unknown phase");
            }
            result.putString("journey", phase + " passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void profile(String label, String endpoint, String key, String phrase) throws Exception {
        if (ui.has("Ajouter un profil")) ui.tap("Ajouter un profil");
        ui.see("Connecter Hermes");
        ui.fill("Nom du profil", label);
        ui.fill("Adresse du serveur", fixture.getString(endpoint));
        ui.fill("Clé d’accès Hermes", fixture.getString(key));
        ui.fill("Phrase secrète du profil", phrase);
        ui.fill("Confirmer la phrase secrète", phrase);
        ui.tap("Enregistrer le profil");
        ui.see("Nouvelle conversation");
    }

    private void pagesIsolation(String owner) throws Exception {
        String other = "Lecture " + fixture.getString("run");
        String passphrase = fixture.getString("secondPhrase");
        lock();
        profile(other, "secondEndpoint", "secondKey", passphrase);
        ui.see("Aucune conversation pour ce profil.");
        menu("Mes écrans");
        ui.contains("Demandez à Hermes de créer un écran");
        ui.absent("Diagnostic mobile");
        lock();
        forget(other, passphrase);
        unlock(owner, fixture.getString("phrase"));
        ui.see("Écriture ciblée");
    }

    private void lock() {
        ui.tap("Autres actions");
        ui.tap("Verrouiller le profil");
        ui.see("Choisir un profil");
    }

    private void unlock(String label, String phrase) {
        ui.tap(label);
        ui.fill("Phrase secrète du profil", phrase);
        ui.tap("Déverrouiller");
    }

    private void menu(String name) {
        ui.tap("Autres actions");
        ui.tap(name);
    }

    private void send(String text, String expected) {
        ui.fill("Message à Hermes", text);
        ui.tap("Envoyer");
        ui.contains(expected);
        // Wait for transcript reconciliation, as it owns artifact and usage records.
        ui.see("Envoyer");
    }

    private void work() throws Exception {
        if (ui.has("Ajouter un profil")) ui.tap("Ajouter un profil");
        ui.see("Connecter Hermes");
        ui.capture("01-connexion");
        profile(primary, "endpoint", "key", fixture.getString("phrase"));
        ui.see("Conversation de vérification");
        ui.capture("02-discussions");
        ui.tap("Nouvelle conversation");
        ui.see("Message à Hermes");
        ui.see("Comprendre du code");
        ui.see("Planifier mon travail");
        ui.capture("02-conversation-vide");
        ui.tap("Comprendre du code");
        ui.contains("Peux-tu m’expliquer ce code ?");
        ui.absent("Planifier mon travail");
        send("Bonjour depuis le téléphone", "La conversation est prête.");
        ui.absent("Quelques idées pour commencer");
        send("Donne une fonction Python pour calculer un carré.", "return n * n");
        ui.capture("03-discussion-code");
        menu("Artefacts");
        ui.tap("Extrait python");
        ui.contains("return n * n");
        ui.capture("04-artefact");
        ui.tap("Fermer");
        ui.tap("Fermer");
        send("Disposition de vérification", "Raccourcis de vérification");
        menu("Artefacts");
        ui.tap("Disposition · Atelier");
        ui.tap("Appliquer la disposition…");
        ui.tap("Appliquer");
        ui.tap("Atelier");
        ui.see("Raccourcis de vérification");
        ui.capture("05-atelier-personnalise");
        menu("Rétablir l’atelier");
        ui.tap("Atelier");
        ui.tap("Annuler la modification");
        ui.see("Espace de travail");
        ui.absent("Raccourcis de vérification");
        ui.tap("Reprendre la discussion");

        menu("Modèle et outils");
        ui.see("Ensembles d’outils");
        ui.capture("06-reglages");
        ui.tap("Choisir le modèle");
        ui.tap(fixture.getString("provider"));
        ui.tap("fake-model");
        ui.contains("fake-model");
        ui.tap("Choisir le modèle");
        ui.tap(fixture.getString("alternateProvider"));
        ui.tap(fixture.getString("alternateModel"));
        ui.contains(fixture.getString("alternateModel"));
        ui.tap("Fermer");
        send("Quel fournisseur de vérification répond ?", "Réponse du fournisseur Atelier.");
        menu("Utilisation");
        ui.see("Cumul de la discussion");
        ui.see("Jetons de sortie");
        ui.capture("07-statistiques");
        ui.tap("Fermer");
        reminders();

        ui.tap("Message à Hermes");
        ui.imeVisible();
        ui.fill("Message à Hermes", fixture.getString("draft"));
        ui.usableComposer();
        ui.capture("09-compositeur-clavier");
        ui.back();
        lock();
        profile(secondary, "secondEndpoint", "secondKey", fixture.getString("secondPhrase"));
        ui.see("Aucune conversation pour ce profil.");
        ui.absent(fixture.getString("draft"));
        ui.absent("Exemple Python");
        lock();
        unlock(primary, fixture.getString("secondPhrase"));
        ui.contains("Impossible de déverrouiller");
        ui.absent(fixture.getString("draft"));
    }

    private void reminders() throws Exception {
        String name = "Rappel " + fixture.getString("run");
        menu("Rappels");
        ui.tap("Nouveau rappel");
        ui.fill("Titre", name);
        ui.fill("Consigne pour Hermes", "Relire la fonction carré.");
        ui.tap("Enregistrer");
        ui.tap(name);
        ui.see("Relire la fonction carré.");
        ui.capture("08-rappel");
        ui.tap("Mettre en pause");
        ui.tap(name);
        ui.tap("Reprendre");
        ui.tap(name);
        ui.tap("Supprimer");
        ui.tap("Supprimer");
        ui.see("Aucun rappel planifié pour ce profil.");
        ui.tap("Fermer");
    }

    private void restart() throws Exception {
        ui.see("Choisir un profil");
        ui.absent(fixture.getString("draft"));
        unlock(primary, fixture.getString("phrase"));
        ui.see(fixture.getString("draft"));
        ui.usableComposer();
        ui.capture("10-discussion-nuit-grands-caracteres");
        ui.tap("Atelier");
        ui.see("Espace de travail");
        ui.capture("11-atelier-nuit-grands-caracteres");
        lock();
        forget(primary, fixture.getString("phrase"));
        forget(secondary, fixture.getString("secondPhrase"));
    }

    private void forget(String label, String passphrase) {
        ui.tap(label);
        ui.fill("Phrase secrète du profil", passphrase);
        ui.tap("Retirer ce profil");
        ui.tap("Retirer");
        ui.see("Choisir un profil");
        ui.absent(label);
    }
}
