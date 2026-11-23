package org.llm4people.hermes.journey;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in driver for a live gateway. The voice adapter supplies recognition results inside the test APK. */
public final class ConversationCardsJourney extends Instrumentation {
    private File directory;
    private JSONObject fixture;
    private JourneyUi ui;
    private Activity activity;
    private Object dictation;
    private String tag;
    private boolean ownProfile;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        tag = arguments.getString("tag", "");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Throwable error = null;
        try {
            if (!tag.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("Invalid fixture tag");
            directory = new File(getTargetContext().getCacheDir(), "cards-" + tag);
            fixture = new JSONObject(new String(Files.readAllBytes(
                    new File(directory, "fixture.json").toPath()), StandardCharsets.UTF_8));
            String pkg = getTargetContext().getPackageName();
            activity = startActivitySync(new Intent().setClassName(pkg, pkg + ".MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ui = new JourneyUi(this, activity, directory);
            installRecognizer();
            addProfile();
            ui.tap(ui.str(R.string.thread_new_conversation));
            JSONArray turns = fixture.getJSONArray("turns");
            for (int i = 0; i < turns.length(); i++) {
                JSONObject spec = turns.getJSONObject(i);
                turn(i, spec);
                if (i == 0) reopenHistory(spec);
            }
        } catch (Throwable failure) {
            error = failure;
        } finally {
            try {
                if (ownProfile) removeOwnProfile();
            } catch (Throwable cleanup) {
                if (error == null) error = cleanup;
                else error.addSuppressed(cleanup);
            }
        }
        if (error == null) result.putString("cards", "passed");
        else result.putString("failure", android.util.Log.getStackTraceString(error));
        finish(error == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void addProfile() throws Exception {
        String addProfile = ui.str(R.string.profile_add_action);
        if (ui.has(addProfile)) ui.tap(addProfile);
        ui.see(ui.str(R.string.profile_connect_title));
        ui.fill(ui.str(R.string.profile_name_label), fixture.getString("profile"));
        ui.fill(ui.str(R.string.profile_server_label), fixture.getString("endpoint"));
        ui.fill(ui.str(R.string.profile_key_label), fixture.getString("key"));
        ui.fill(ui.str(R.string.profile_passphrase_label), fixture.getString("phrase"));
        ui.fill(ui.str(R.string.profile_passphrase_confirm_label), fixture.getString("phrase"));
        ui.tap(ui.str(R.string.profile_save_action));
        ui.see(ui.str(R.string.thread_new_conversation));
        ownProfile = true;
    }

    private void removeOwnProfile() throws Exception {
        ui.lockProfile();
        String chooseProfile = ui.str(R.string.profile_choose_title);
        String name = fixture.getString("profile");
        ui.tap(name);
        ui.fill(ui.str(R.string.profile_passphrase_label), fixture.getString("phrase"));
        ui.tap(ui.str(R.string.profile_remove_action));
        ui.tap("Retirer");
        ui.see(chooseProfile);
        ui.absent(name);
    }

    private void reopenHistory(JSONObject turn) throws Exception {
        ui.see(ui.str(R.string.thread_send));
        ui.lockProfile();
        ui.tap(fixture.getString("profile"));
        ui.fill(ui.str(R.string.profile_passphrase_label), fixture.getString("phrase"));
        ui.tap(ui.str(R.string.profile_unlock_action));
        ui.see(ui.str(R.string.thread_message_hint));
        ui.contains(turn.getString("reply"));
        seeTranscriptRow("Hermes", turn.getString("question"));
        seeTranscriptRow(ui.str(R.string.thread_you), turn.getString("answer"));
        ui.capture("card-restored-history");
    }

    private void seeTranscriptRow(String role, String content) {
        AtomicReference<ListView> listRef = new AtomicReference<>();
        main(() -> listRef.set(transcript(activity.getWindow().getDecorView())));
        ListView list = listRef.get();
        if (list == null) throw new AssertionError("Missing reopened transcript");
        int[] count = {0};
        main(() -> count[0] = list.getCount());
        for (int position = 0; position < count[0]; position++) {
            int index = position;
            main(() -> list.setSelectionFromTop(index, 0));
            waitForIdleSync();
            boolean[] found = {false};
            main(() -> {
                for (int i = 0; i < list.getChildCount(); i++) {
                    View row = list.getChildAt(i);
                    if (exactText(row, role) && exactText(row, content)) found[0] = true;
                }
            });
            if (found[0]) return;
        }
        throw new AssertionError("Missing reopened " + role + " row: " + content);
    }

    private static ListView transcript(View view) {
        if (view instanceof ListView list && list.isShown()) return list;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView list = transcript(group.getChildAt(i));
                if (list != null) return list;
            }
        }
        return null;
    }

    private static boolean exactText(View view, String value) {
        if (view instanceof TextView text && text.isShown() && value.contentEquals(text.getText())) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (exactText(group.getChildAt(i), value)) return true;
            }
        }
        return false;
    }

    private void turn(int index, JSONObject turn) throws Exception {
        ui.fill(ui.str(R.string.thread_message_hint), turn.getString("prompt"));
        ui.tap(ui.str(R.string.thread_send));
        boolean approval = "approval".equals(turn.getString("kind"));
        String answer = turn.getString("answer");
        ui.showPendingControl(approval ? "Autoriser une fois" : turn.getString("question"));
        if (turn.optBoolean("read_history")) {
            await("permit-" + index);
            seeTranscriptRow(ui.str(R.string.thread_you), fixture.getJSONArray("turns").getJSONObject(0).getString("prompt"));
            ui.tap(ui.str(R.string.ws_reply_hermes));
        }
        if (approval) {
            ui.see("Autoriser une fois");
            ui.see("Refuser");
            ui.tapInListRow("Refuser", "Voir les détails");
            ui.containsInListRow("Refuser", "chmod 666 partage-demo.txt");
            ui.tapInListRow("Refuser", "Masquer les détails");
        } else {
            ui.contains(turn.getString("question"));
        }
        ui.capture("card-" + (index + 1));
        await("permit-" + index);
        if (approval) {
            ui.tap("once".equals(answer) ? "Autoriser une fois" : "Refuser");
        } else if (turn.has("choices")) {
            ui.tap(answer);
        } else {
            if (turn.optBoolean("voice")) {
                main(() -> {
                    EditText editor = answerEditor(activity.getWindow().getDecorView());
                    if (editor == null) throw new AssertionError("Missing answer editor");
                    View microphone = null;
                    String dictate = activity.getString(R.string.voice_dictate);
                    for (ViewParent parent = editor.getParent(); parent instanceof View view; parent = parent.getParent()) {
                        microphone = microphone(view, dictate);
                        if (microphone != null) break;
                    }
                    if (microphone == null || !microphone.performClick()) throw new AssertionError("Missing answer microphone");
                    dictation.getClass().getMethod("result", String.class).invoke(dictation, answer);
                    if (!answer.contentEquals(editor.getText())) throw new AssertionError("Speech result missed the answer editor");
                });
                ui.capture("card-voice-draft");
                if (!new File(directory, "voice-filled").createNewFile()) throw new AssertionError("Duplicate dictation");
                await("permit-voice-submit");
            } else {
                ui.fill("Votre réponse", answer);
            }
            ui.tap("Envoyer la réponse");
        }
        ui.contains(turn.getString("reply"));
    }

    private static EditText answerEditor(View view) {
        if (!view.isShown()) return null;
        if (view instanceof EditText edit && "Votre réponse".contentEquals(edit.getHint() == null ? "" : edit.getHint())) return edit;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText editor = answerEditor(group.getChildAt(i));
                if (editor != null) return editor;
            }
        }
        return null;
    }

    private static View microphone(View view, String label) {
        if (!view.isShown()) return null;
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View button = microphone(group.getChildAt(i), label);
                if (button != null) return button;
            }
        }
        return null;
    }

    private void installRecognizer() throws Exception {
        main(() -> {
            Class<?> type = Class.forName("org.llm4people.hermes.voice.FakeDictation", true, getClass().getClassLoader());
            dictation = type.getConstructor().newInstance();
            Field voice = activity.getClass().getDeclaredField("voice");
            voice.setAccessible(true);
            voice.set(activity, type.getMethod("controller", Activity.class).invoke(dictation, activity));
        });
    }

    private interface UiWork { void run() throws Exception; }

    private void main(UiWork work) {
        AtomicReference<Throwable> fault = new AtomicReference<>();
        runOnMainSync(() -> {
            try { work.run(); }
            catch (Throwable error) { fault.set(error); }
        });
        if (fault.get() != null) throw new AssertionError("UI interaction failed", fault.get());
    }

    private void await(String signal) {
        long deadline = SystemClock.elapsedRealtime() + 120000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (new File(directory, "abort").exists()) throw new AssertionError("Gateway verification stopped");
            if (new File(directory, signal).exists()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Gateway did not confirm " + signal);
    }
}
