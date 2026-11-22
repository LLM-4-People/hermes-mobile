package org.llm4people.hermes.journey;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;

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
    private volatile Activity resumed;

    private boolean instrumentViewport() {
        return "instrument".equals(phase) || "instrument-wide".equals(phase);
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        if (instrumentViewport()) {
            activity.setRequestedOrientation("instrument".equals(phase)
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        super.callActivityOnCreate(activity, state);
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        resumed = activity;
    }

    private Activity instrumentActivity() {
        boolean wide = "instrument-wide".equals(phase);
        int width = wide ? 1200 : 320, height = wide ? 720 : 640;
        Activity[] ready = new Activity[1];
        String[] observed = {"no resumed activity"};
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync(() -> {
                if (resumed == null || resumed.isDestroyed() || resumed.isFinishing()) return;
                View root = resumed.getWindow().getDecorView();
                observed[0] = root.getWidth() + "×" + root.getHeight();
                if (root.hasWindowFocus() && root.getWidth() == width && root.getHeight() == height) {
                    ready[0] = resumed;
                }
            });
            if (ready[0] != null) return ready[0];
            SystemClock.sleep(100);
        }
        throw new AssertionError("Expected app viewport " + width + "×" + height + ", saw " + observed[0]);
    }

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
            AppLanguage.apply(getTargetContext(), AppLanguage.Mode.FRENCH);
            Activity activity = startActivitySync(new Intent().setClassName(pkg, pkg + ".MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            if (instrumentViewport()) activity = instrumentActivity();
            if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0)
                throw new AssertionError("Private workspace allows external screen capture");
            ui = new JourneyUi(this, activity, new File(dir, fixture.getString("run")));
            switch (phase) {
                case "adaptive":
                case "adaptive-large-text": {
                    String owner = "Lecture " + fixture.getString("run");
                    String phrase = fixture.getString("phrase");
                    AdaptiveJourney journey = new AdaptiveJourney(this, ui, activity, fixture);
                    if (phase.equals("adaptive")) {
                        profile(owner, "endpoint", "key", phrase);
                        journey.resize();
                    } else {
                        unlock(owner, phrase);
                        journey.largeText();
                    }
                    result.putString("matrix", journey.evidence().toString());
                    lock();
                    if (phase.equals("adaptive-large-text")) forget(owner, phrase);
                    break;
                }
                case "nearby-capture": {
                    File folder = new File(dir, fixture.getString("run"));
                    NearbyGuideCapture.observe(folder, () -> new JourneyUi(this, resumed, folder));
                    break;
                }
                case "skills-draft": {
                    String owner = "Compétences " + fixture.getString("run");
                    profile(owner, "endpoint", "key", fixture.getString("phrase"));
                    new DiscussionSkillsJourney(ui, fixture).stage();
                    lock();
                    break;
                }
                case "skills-send": {
                    String owner = "Compétences " + fixture.getString("run");
                    String phrase = fixture.getString("phrase");
                    ui.see("Choisir un profil");
                    unlock(owner, phrase);
                    new DiscussionSkillsJourney(ui, fixture).send();
                    lock();
                    forget(owner, phrase);
                    break;
                }
                case "photo": {
                    String owner = "Image " + fixture.getString("run");
                    String phrase = fixture.getString("phrase");
                    profile(owner, "endpoint", "key", phrase);
                    new DiscussionPhotoJourney(this, ui, fixture.getString("run"), () -> unlock(owner, phrase)).run();
                    lock();
                    forget(owner, phrase);
                    break;
                }
                case "work": work(); break;
                case "restart": restart(); break;
                case "instrument": {
                    String owner = "Relecture " + fixture.getString("run");
                    profile(owner, "endpoint", "key", fixture.getString("phrase"));
                    new ThreadInstrumentJourney(ui, fixture).portrait();
                    lock();
                    break;
                }
                case "instrument-wide": {
                    String owner = "Relecture " + fixture.getString("run");
                    ui.see("Choisir un profil");
                    unlock(owner, fixture.getString("phrase"));
                    new ThreadInstrumentJourney(ui, fixture).wide();
                    lock();
                    forget(owner, fixture.getString("phrase"));
                    break;
                }
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
        ui.hideKeyboard();
        ui.heading("Bonjour depuis le téléphone");
        ui.capture("03-discussion-code");
        ui.tap("Ouvrir le code · python");
        ui.contains("return n * n");
        ui.see("Copier le contenu");
        ui.tap("Fermer");
        ui.tap("Toutes les discussions");
        ui.see("Bonjour depuis le téléphone");
        ui.capture("15-discussion-retrouvee");
        ui.tap("Bonjour depuis le téléphone");
        ui.heading("Bonjour depuis le téléphone");
        ui.contains("return n * n");
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
        ui.see(fixture.getString("provider"));
        ui.capture("06-fournisseurs");
        ui.tap(fixture.getString("alternateProvider"));
        ui.see(fixture.getString("alternateModel"));
        ui.capture("06-modeles");
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
        ui.tapButton(name);
        ui.see("État : Planifié");
        ui.see("Relire la fonction carré.");
        ui.capture("08-rappel");
        ui.tap("Mettre en pause");
        ui.tapButton(name);
        ui.see("État : En pause");
        ui.tap("Reprendre");
        ui.tapButton(name);
        ui.see("État : Planifié");
        ui.tapButton("Supprimer");
        ui.see("Supprimer « " + name + " » ?");
        ui.tapButton("Supprimer");
        ui.see("Aucun rappel planifié pour ce profil.");
        ui.tap("Fermer");
    }

    private void restart() throws Exception {
        ui.see("Choisir un profil");
        ui.absent(fixture.getString("draft"));
        unlock(primary, fixture.getString("phrase"));
        ui.see(fixture.getString("draft"));
        ui.contains("Réponse du fournisseur Atelier.");
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
