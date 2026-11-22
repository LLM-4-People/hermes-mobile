package org.llm4people.hermes.voice;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.speech.SpeechRecognizer;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

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
        try { getInstrumentation().runOnMainSync(activity::finish); }
        finally { super.tearDown(); }
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
}
