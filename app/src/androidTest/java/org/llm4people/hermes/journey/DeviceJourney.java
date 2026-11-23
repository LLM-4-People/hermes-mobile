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
import org.llm4people.hermes.R;

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
                    ui.see(ui.str(R.string.profile_choose_title));
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
                    ui.see(ui.str(R.string.profile_choose_title));
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
        String addProfile = ui.str(R.string.profile_add_action);
        if (ui.has(addProfile)) ui.tap(addProfile);
        ui.see(ui.str(R.string.profile_connect_title));
        ui.fill(ui.str(R.string.profile_name_label), label);
        ui.fill(ui.str(R.string.profile_server_label), fixture.getString(endpoint));
        ui.fill(ui.str(R.string.profile_key_label), fixture.getString(key));
        ui.fill(ui.str(R.string.profile_passphrase_label), phrase);
        ui.fill(ui.str(R.string.profile_passphrase_confirm_label), phrase);
        ui.tap(ui.str(R.string.profile_save_action));
        ui.see(ui.str(R.string.thread_new_conversation));
    }

    private void pagesIsolation(String owner) throws Exception {
        String other = "Lecture " + fixture.getString("run");
        String passphrase = fixture.getString("secondPhrase");
        lock();
        profile(other, "secondEndpoint", "secondKey", passphrase);
        ui.see("Aucune conversation pour ce profil.");
        ui.tap(ui.str(R.string.nav_atelier));
        ui.tap(ui.str(R.string.ws_my_screens));
        ui.contains(ui.str(R.string.ws_pages_empty));
        ui.absent("Diagnostic mobile");
        lock();
        forget(other, passphrase);
        unlock(owner, fixture.getString("phrase"));
        ui.see("Écriture ciblée");
    }

    private void lock() {
        ui.lockProfile();
    }

    private void unlock(String label, String phrase) {
        ui.tap(label);
        ui.fill(ui.str(R.string.profile_passphrase_label), phrase);
        ui.tap(ui.str(R.string.profile_unlock_action));
    }

    private void menu(String name) {
        ui.tap(ui.str(R.string.ws_more_actions));
        ui.tap(name);
    }

    private void send(String text, String expected) {
        ui.fill(ui.str(R.string.thread_message_hint), text);
        ui.tap(ui.str(R.string.thread_send));
        ui.contains(expected);
        // Wait for transcript reconciliation, as it owns artifact and usage records.
        ui.see(ui.str(R.string.thread_send));
    }

    private void work() throws Exception {
        String addProfile = ui.str(R.string.profile_add_action);
        if (ui.has(addProfile)) ui.tap(addProfile);
        ui.see(ui.str(R.string.profile_connect_title));
        ui.capture("01-connexion");
        profile(primary, "endpoint", "key", fixture.getString("phrase"));
        ui.see("Conversation de vérification");
        ui.capture("02-discussions");
        ui.tap(ui.str(R.string.thread_new_conversation));
        ui.see(ui.str(R.string.thread_message_hint));
        ui.see(ui.str(R.string.starter_code));
        ui.see(ui.str(R.string.starter_plan));
        ui.capture("02-conversation-vide");
        ui.tap(ui.str(R.string.starter_code));
        ui.contains(ui.str(R.string.starter_code_draft));
        ui.absent(ui.str(R.string.starter_plan));
        send("Bonjour depuis le téléphone", "La conversation est prête.");
        ui.absent(ui.str(R.string.starter_heading));
        send("Donne une fonction Python pour calculer un carré.", "return n * n");
        ui.hideKeyboard();
        ui.heading("Bonjour depuis le téléphone");
        ui.capture("03-discussion-code");
        ui.tap(ui.str(R.string.code_open_named, "python"));
        ui.contains("return n * n");
        ui.see(ui.str(R.string.code_copy_content));
        ui.tap(ui.str(R.string.ws_close));
        ui.tap(ui.str(R.string.ws_all_discussions));
        ui.see("Bonjour depuis le téléphone");
        ui.capture("15-discussion-retrouvee");
        ui.tap("Bonjour depuis le téléphone");
        ui.heading("Bonjour depuis le téléphone");
        ui.contains("return n * n");
        menu(ui.str(R.string.ws_artifacts));
        ui.tap(ui.str(R.string.ws_art_snippet_lang, "python"));
        ui.contains("return n * n");
        ui.capture("04-artefact");
        ui.tap(ui.str(R.string.ws_close));
        ui.tap(ui.str(R.string.ws_close));
        send("Disposition de vérification", "Raccourcis de vérification");
        menu(ui.str(R.string.ws_artifacts));
        ui.tap(ui.str(R.string.ws_art_layout_titled, ui.str(R.string.ws_surface_home)));
        ui.tap(ui.str(R.string.ws_art_apply_layout_action));
        ui.tap(ui.str(R.string.ws_apply));
        ui.tap(ui.str(R.string.nav_atelier));
        ui.see("Raccourcis de vérification");
        ui.capture("05-atelier-personnalise");
        menu(ui.str(R.string.ws_restore_desk));
        ui.tap(ui.str(R.string.ws_surface_home));
        ui.tap(ui.str(R.string.ws_surface_undo_change));
        ui.see(ui.str(R.string.ws_workspace));
        ui.absent("Raccourcis de vérification");
        ui.tap(ui.str(R.string.ws_resume_discussion));

        menu(ui.str(R.string.ws_model_and_tools));
        ui.see(ui.str(R.string.panel_tools));
        ui.capture("06-reglages");
        ui.tap(ui.str(R.string.panel_route));
        ui.tap(fixture.getString("provider"));
        ui.tap("fake-model");
        ui.contains("fake-model");
        ui.tap(ui.str(R.string.panel_route));
        ui.see(fixture.getString("provider"));
        ui.capture("06-fournisseurs");
        ui.tap(fixture.getString("alternateProvider"));
        ui.see(fixture.getString("alternateModel"));
        ui.capture("06-modeles");
        ui.tap(fixture.getString("alternateModel"));
        ui.contains(fixture.getString("alternateModel"));
        ui.tap(ui.str(R.string.panel_done));
        send("Quel fournisseur de vérification répond ?", "Réponse du fournisseur Atelier.");
        menu(ui.str(R.string.ws_usage));
        ui.see(ui.str(R.string.panel_server_totals));
        ui.see(ui.str(R.string.panel_out));
        ui.capture("07-statistiques");
        ui.tap(ui.str(R.string.panel_done));
        reminders();

        ui.tap(ui.str(R.string.thread_message_hint));
        ui.imeVisible();
        ui.fill(ui.str(R.string.thread_message_hint), fixture.getString("draft"));
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
        ui.contains(ui.str(R.string.profile_setup_unlock_failed));
        ui.absent(fixture.getString("draft"));
    }

    private void reminders() throws Exception {
        String name = "Rappel " + fixture.getString("run");
        String scheduled = ui.str(R.string.panel_job_state, ui.str(R.string.panel_scheduled));
        String paused = ui.str(R.string.panel_job_state, ui.str(R.string.panel_paused));
        ui.tap(ui.str(R.string.nav_activity));
        ui.tap(ui.str(R.string.activity_reminders_title));
        ui.tap(ui.str(R.string.panel_new_reminder));
        ui.fill(ui.str(R.string.panel_name), name);
        ui.fill(ui.str(R.string.panel_prompt), "Relire la fonction carré.");
        ui.tap(ui.str(R.string.panel_save));
        ui.tapButton(name);
        ui.see(scheduled);
        ui.see("Relire la fonction carré.");
        ui.capture("08-rappel");
        ui.tap(ui.str(R.string.panel_pause));
        ui.tapButton(name);
        ui.see(paused);
        ui.tap(ui.str(R.string.panel_resume));
        ui.tapButton(name);
        ui.see(scheduled);
        ui.tapButton(ui.str(R.string.panel_delete));
        ui.see(ui.str(R.string.panel_confirm_delete, name));
        ui.tapButton(ui.str(R.string.panel_delete));
        ui.see(ui.str(R.string.panel_no_reminder));
        ui.tap(ui.str(R.string.panel_done));
        ui.tap(ui.str(R.string.nav_discussions));
    }

    private void restart() throws Exception {
        ui.see(ui.str(R.string.profile_choose_title));
        ui.absent(fixture.getString("draft"));
        unlock(primary, fixture.getString("phrase"));
        ui.see(fixture.getString("draft"));
        ui.contains("Réponse du fournisseur Atelier.");
        ui.usableComposer();
        ui.capture("10-discussion-nuit-grands-caracteres");
        ui.tap(ui.str(R.string.nav_atelier));
        ui.see(ui.str(R.string.ws_workspace));
        ui.capture("11-atelier-nuit-grands-caracteres");
        lock();
        forget(primary, fixture.getString("phrase"));
        forget(secondary, fixture.getString("secondPhrase"));
    }

    private void forget(String label, String passphrase) {
        ui.tap(label);
        ui.fill(ui.str(R.string.profile_passphrase_label), passphrase);
        ui.tap(ui.str(R.string.profile_remove_action));
        ui.tap(ui.str(R.string.profile_setup_remove_confirm));
        ui.see(ui.str(R.string.profile_choose_title));
        ui.absent(label);
    }
}
