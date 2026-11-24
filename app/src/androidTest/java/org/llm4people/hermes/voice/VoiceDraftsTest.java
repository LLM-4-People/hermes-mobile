package org.llm4people.hermes.voice;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.speech.SpeechRecognizer;
import android.os.Build;
import android.view.inspector.WindowInspector;
import android.widget.ListView;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class VoiceDraftsTest extends InstrumentationTestCase {
    private Activity activity;
    private final FakeDictation input = new FakeDictation();
    private VoiceDrafts voice;
    private LinearLayout layout;
    private EditText editor;
    private VoiceDrafts.Controls controls;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context app = getInstrumentation().getTargetContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        activity = getInstrumentation().startActivitySync(launch);
        getInstrumentation().runOnMainSync(() -> {
            layout = Ui.column(activity);
            voice = input.controller(activity);
            attachEditor();
            activity.setContentView(layout);
        });
    }

    @Override protected void tearDown() throws Exception {
        try { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
        finally {
            Diagnostics.clear();
            super.tearDown();
        }
    }

    private void attachEditor() {
        editor = new EditText(activity);
        editor.setSaveEnabled(false);
        controls = voice.bind(editor);
        layout.addView(editor);
        layout.addView(controls.button);
        layout.addView(controls.feedback);
    }

    private Button action() { return (Button) controls.feedback.getChildAt(1); }
    private void status(int id) {
        assertEquals(View.VISIBLE, controls.feedback.getVisibility());
        assertEquals(activity.getString(id), ((TextView) controls.feedback.getChildAt(0)).getText().toString());
    }

    public void testStopYieldsOneEditableResultAtCurrentSelection() throws Throwable {
        runTestOnUiThread(() -> {
            editor.setText("Je préfère Paris demain");
            editor.requestFocus();
            editor.setSelection(11, 16);
            controls.button.performClick();
            assertEquals(VoiceInput.Source.DEVICE, input.opened);
            status(R.string.voice_listening);
            controls.button.performClick();
            status(R.string.voice_processing);
            assertEquals(1, input.stops);
            input.result(" Montréal ");
            assertEquals("Je préfère Montréal demain", editor.getText().toString());
            assertEquals(19, editor.getSelectionStart());
            assertTrue(editor.hasFocus());
            input.result("duplicate");
            assertEquals("Je préfère Montréal demain", editor.getText().toString());
            assertEquals(1, input.closes);
            status(R.string.voice_added);
        });
    }

    public void testRecognitionUsesEditsMadeWhileListening() throws Throwable {
        runTestOnUiThread(() -> {
            editor.setText("L’");
            editor.setSelection(editor.length());
            controls.button.performClick();
            editor.append("équipe");
            input.result("voudrait en discuter.");
            assertEquals("L’équipe voudrait en discuter.", editor.getText().toString());
        });
    }

    public void testRecreatingActivityDiscardsRecordingAndReturnsToProfileGate() throws Throwable {
        java.lang.reflect.Field owner = MainActivity.class.getDeclaredField("voice");
        owner.setAccessible(true);
        Activity previous = activity;
        owner.set(previous, voice);
        EditText oldEditor = editor;
        Instrumentation.ActivityMonitor gate = getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
        try {
            runTestOnUiThread(() -> {
                controls.button.performClick();
                previous.recreate();
            });
            Activity reopened = gate.waitForActivityWithTimeout(30000);
            assertNotNull(reopened);
            activity = reopened;
            assertNotSame(voice, owner.get(reopened));
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                assertTrue(previous.isDestroyed());
                assertEquals(1, input.closes);
                input.result("ancien brouillon");
                assertEquals("", oldEditor.getText().toString());
                assertFalse(oldEditor.isAttachedToWindow());
            });
        } finally {
            getInstrumentation().removeMonitor(gate);
        }
    }

    public void testPermissionGrantWaitsForReturnAndStaleGrantsCannotOpenMic() throws Throwable {
        runTestOnUiThread(() -> {
            input.allowed = false;
            controls.button.performClick();
            int stale = input.requested;
            voice.pause();
            voice.cancel();
            voice.resume();
            controls.button.performClick();
            int current = input.requested;
            assertTrue(current != stale);
            input.allowed = true;
            String[] microphone = {Manifest.permission.RECORD_AUDIO};
            int[] grant = {PackageManager.PERMISSION_GRANTED};
            assertFalse(voice.permissionResult(stale, microphone, grant));
            assertEquals(0, input.recordings);
            voice.pause();
            assertTrue(voice.permissionResult(current, microphone, grant));
            assertEquals(0, input.recordings);
            voice.windowFocusChanged(true);
            assertEquals(0, input.recordings);
            voice.resume();
            voice.windowFocusChanged(true);
            assertEquals(1, input.recordings);
            voice.pause();
            assertEquals(1, input.closes);
            input.result("ancien profil");
            assertEquals("", editor.getText().toString());
            voice.resume();
            assertEquals(1, input.recordings);
        });
    }

    public void testPermissionDenialAndMissingServiceKeepKeyboardDraft() throws Throwable {
        runTestOnUiThread(() -> {
            editor.setText("mon texte");
            input.source = VoiceInput.Source.NONE;
            controls.button.performClick();
            status(R.string.voice_unavailable);
            assertEquals(0, input.requested);
            input.source = VoiceInput.Source.DEVICE;
            input.allowed = false;
            controls.button.performClick();
            assertTrue(voice.permissionResult(input.requested, new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_DENIED}));
            status(R.string.voice_permission_denied);
            assertEquals(0, input.recordings);
            assertEquals("mon texte", editor.getText().toString());
        });
    }

    public void testSystemServiceNeedsConsentEveryTimeAndOfflineKeepsDraft() throws Throwable {
        runTestOnUiThread(() -> {
            input.source = VoiceInput.Source.SYSTEM;
            editor.setText("Déjà écrit");
            controls.button.performClick();
            status(R.string.voice_system_notice);
            assertEquals(0, input.recordings);
            controls.button.performClick();
            assertEquals(View.GONE, controls.feedback.getVisibility());
            controls.button.performClick();
            action().performClick();
            assertEquals(VoiceInput.Source.SYSTEM, input.opened);
            input.listener.error(SpeechRecognizer.ERROR_NETWORK);
            status(R.string.voice_network);
            assertEquals("Déjà écrit", editor.getText().toString());
            controls.button.performClick();
            status(R.string.voice_system_notice);
            assertEquals(1, input.recordings);
        });
    }

    public void testMissingFrenchModelOffersExplicitSystemChoice() throws Throwable {
        runTestOnUiThread(() -> {
            controls.button.performClick();
            input.listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
            status(R.string.voice_local_language);
            action().performClick();
            status(R.string.voice_system_notice);
            assertEquals(1, input.recordings);
            action().performClick();
            assertEquals(VoiceInput.Source.SYSTEM, input.opened);
            input.result("");
            status(R.string.voice_empty);
            assertEquals("", editor.getText().toString());
        });
    }

    private void modelAction(int label) {
        assertEquals(View.VISIBLE, controls.feedback.getVisibility());
        assertEquals(View.GONE, controls.feedback.getChildAt(0).getVisibility());
        assertEquals(activity.getString(label), action().getText().toString());
    }

    @android.annotation.TargetApi(33)
    private void choose(int label) {
        action().performClick();
        int listId = activity.getResources().getIdentifier("select_dialog_listview", "id", "android");
        for (View window : WindowInspector.getGlobalWindowViews()) {
            ListView list = window.findViewById(listId);
            if (list == null) continue;
            for (int i = 0; i < list.getCount(); i++) {
                if (activity.getString(label).equals(list.getItemAtPosition(i))) {
                    list.performItemClick(null, i, i);
                    return;
                }
            }
        }
        fail("Missing model recovery option");
    }

    public void testLocalModelNeedsAnExplicitDownloadAndReadyNeverStartsRecording() throws Throwable {
        if (Build.VERSION.SDK_INT < 33) return;
        runTestOnUiThread(() -> {
            FakeDictation.FakeModel model = new FakeDictation.FakeModel();
            input.model = model;
            editor.setText("Brouillon privé");
            controls.button.performClick();
            input.listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
            modelAction(R.string.voice_model_options);
            assertEquals(0, model.downloads);
            choose(R.string.voice_model_download);
            assertEquals(1, model.downloads);
            modelAction(R.string.voice_model_follow);
            voice.pause();
            model.events.accept(VoiceModel.State.PENDING);
            voice.resume();
            modelAction(R.string.voice_model_progress);
            model.events.accept(VoiceModel.State.READY);
            status(R.string.voice_model_ready);
            assertEquals(1, model.closes);
            assertEquals(1, input.recordings);
            assertEquals("Brouillon privé", editor.getText().toString());
        });
    }

    public void testResumeClearsStalePermissionDeniedBannerOnceMicrophoneGranted() throws Throwable {
        runTestOnUiThread(() -> {
            input.allowed = false;
            controls.button.performClick();
            assertTrue(input.requested > 0);
            assertTrue(voice.permissionResult(
                    input.requested,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_DENIED}));
            status(R.string.voice_permission_denied);

            // While still denied, resume keeps the banner intact.
            voice.pause();
            voice.resume();
            status(R.string.voice_permission_denied);

            // Once permission is granted externally in Android Settings, resume clears the stale banner.
            voice.pause();
            input.allowed = true;
            voice.resume();
            assertEquals(View.GONE, controls.feedback.getVisibility());
        });
    }

    public void testLocalDownloadFailureKeepsSystemConsentAndIgnoresLateCompletion() throws Throwable {
        if (Build.VERSION.SDK_INT < 33) return;
        runTestOnUiThread(() -> {
            FakeDictation.FakeModel model = new FakeDictation.FakeModel();
            input.model = model;
            controls.button.performClick();
            input.listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
            choose(R.string.voice_model_download);
            model.events.accept(VoiceModel.State.FAILED);
            modelAction(R.string.voice_model_retry);
            choose(R.string.voice_other_service);
            status(R.string.voice_system_notice);
            assertEquals(1, input.recordings);
            model.events.accept(VoiceModel.State.READY);
            status(R.string.voice_system_notice);
            action().performClick();
            assertEquals(VoiceInput.Source.SYSTEM, input.opened);
        });
    }

    public void testModelVerificationSurvivesAnUnmonitoredRequestAndIgnoresDetachedEditor() throws Throwable {
        if (Build.VERSION.SDK_INT < 33) return;
        runTestOnUiThread(() -> {
            FakeDictation.FakeModel model = new FakeDictation.FakeModel();
            input.model = model;
            input.hasSystem = false;
            controls.button.performClick();
            input.listener.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED);
            modelAction(R.string.voice_model_options);
            choose(R.string.voice_model_download);
            model.events.accept(VoiceModel.State.REQUESTED);
            model.availability = VoiceModel.State.READY;
            choose(R.string.voice_model_check);
            status(R.string.voice_model_ready);
            controls.button.performClick();
            model.availability = VoiceModel.State.OFFERED;
            input.listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
            choose(R.string.voice_model_download);
            layout.removeAllViews();
            attachEditor();
            model.events.accept(VoiceModel.State.READY);
            assertEquals(View.GONE, controls.feedback.getVisibility());
            assertEquals("", editor.getText().toString());
        });
    }

    public void testCancelledAndDetachedTargetsRejectLateResultsAfterSwitch() throws Throwable {
        runTestOnUiThread(() -> {
            controls.button.performClick();
            VoiceInput.Listener first = input.listener;
            action().performClick();
            first.result("annulé");
            assertEquals("", editor.getText().toString());
            controls.button.performClick();
            VoiceInput.Listener second = input.listener;
            EditText old = editor;
            layout.removeAllViews();
            assertEquals(2, input.closes);
            attachEditor();
            controls.button.performClick();
            second.result("ancienne question");
            assertEquals("", old.getText().toString());
            assertEquals("", editor.getText().toString());
            input.result("Ma réponse");
            assertEquals("Ma réponse", editor.getText().toString());
        });
    }

    public void testRealAndroidRecognizerAbsenceKeepsFieldEditable() throws Throwable {
        runTestOnUiThread(() -> {
            VoiceInput real = new AndroidVoiceInput(activity);
            if (real.preferred() != VoiceInput.Source.NONE) return;
            layout.removeAllViews();
            voice = new VoiceDrafts(activity);
            attachEditor();
            editor.setText("sur cet appareil");
            controls.button.performClick();
            status(R.string.voice_unavailable);
            assertTrue(editor.isEnabled());
            assertEquals("sur cet appareil", editor.getText().toString());
        });
    }

    public void testStandaloneDictationOnSecondaryControlsStopsActiveDuplexInListeningAndThinking() throws Throwable {
        runTestOnUiThread(() -> {
            List<String> submitted = new ArrayList<>();
            VoiceDrafts.DuplexHandle duplex = voice.bindDuplex(controls, submitted::add);
            layout.addView(duplex.bar());

            EditText secondaryEditor = new EditText(activity);
            secondaryEditor.setSaveEnabled(false);
            VoiceDrafts.Controls secondary = voice.bind(secondaryEditor);
            layout.addView(secondaryEditor);
            layout.addView(secondary.button);
            layout.addView(secondary.feedback);

            // Case 1: Duplex in LISTENING -> tapping secondary dictation stops duplex and hides its bar.
            duplex.start();
            assertEquals(DuplexVoiceSession.State.LISTENING, duplex.state());
            assertTrue(duplex.active());
            assertEquals(View.VISIBLE, duplex.bar().getVisibility());
            assertEquals(1, input.recordings);

            secondary.button.performClick();
            assertEquals(DuplexVoiceSession.State.OFF, duplex.state());
            assertFalse(duplex.active());
            assertEquals(View.GONE, duplex.bar().getVisibility());
            assertEquals(1, input.closes);
            assertEquals(2, input.recordings);
            input.result("Réponse secondaire");
            assertEquals("Réponse secondaire", secondaryEditor.getText().toString());
            assertTrue(submitted.isEmpty());

            // Case 2: Duplex in THINKING -> tapping secondary dictation stops duplex cleanly.
            duplex.start();
            assertEquals(DuplexVoiceSession.State.LISTENING, duplex.state());
            input.result("Question duplex");
            assertEquals(DuplexVoiceSession.State.THINKING, duplex.state());
            assertEquals(View.VISIBLE, duplex.bar().getVisibility());
            assertEquals(List.of("Question duplex"), submitted);

            secondary.button.performClick();
            assertEquals(DuplexVoiceSession.State.OFF, duplex.state());
            assertFalse(duplex.active());
            assertEquals(View.GONE, duplex.bar().getVisibility());
            duplex.onReplyComplete("Réponse ignorée.");
            assertEquals(DuplexVoiceSession.State.OFF, duplex.state());
            input.result("Suite");
            assertEquals("Réponse secondaire Suite", secondaryEditor.getText().toString());
        });
    }
}
