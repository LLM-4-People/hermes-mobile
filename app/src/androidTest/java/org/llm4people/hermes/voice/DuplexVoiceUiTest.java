package org.llm4people.hermes.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("deprecation")
public final class DuplexVoiceUiTest extends InstrumentationTestCase {
    private static final String VOICE_PREFS = "hermes_voice_prefs";
    private static final String KEY_VOICE_LOCALE = "voice_locale";

    private Context appContext;
    private String savedVoiceLocale;
    private Activity activity;
    private FakeDictation input;
    private VoiceDrafts voice;
    private EditText editor;
    private VoiceDrafts.Controls controls;

    @Override protected void setUp() throws Exception {
        super.setUp();
        appContext = getInstrumentation().getTargetContext();
        savedVoiceLocale = appContext.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VOICE_LOCALE, null);
        Intent launch = appContext.getPackageManager().getLaunchIntentForPackage(appContext.getPackageName());
        activity = getInstrumentation().startActivitySync(launch);
        getInstrumentation().runOnMainSync(() -> {
            input = new FakeDictation();
            voice = input.controller(activity);
            voice.setLocale(VoiceLocale.FRENCH);
            LinearLayout layout = Ui.column(activity);
            editor = new EditText(activity);
            editor.setSaveEnabled(false);
            controls = voice.bind(editor);
            layout.addView(editor);
            layout.addView(controls.button);
            layout.addView(controls.feedback);
            activity.setContentView(layout);
        });
    }

    @Override protected void tearDown() throws Exception {
        try {
            try {
                if (voice != null) {
                    getInstrumentation().runOnMainSync(() -> {
                        voice.reader().stop();
                        voice.cancel();
                    });
                }
            } finally {
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        } finally {
            try {
                if (appContext != null) {
                    SharedPreferences.Editor prefsEditor = appContext
                            .getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE).edit();
                    if (savedVoiceLocale != null) {
                        prefsEditor.putString(KEY_VOICE_LOCALE, savedVoiceLocale);
                    } else {
                        prefsEditor.remove(KEY_VOICE_LOCALE);
                    }
                    prefsEditor.commit();
                }
            } finally {
                Diagnostics.clear();
                super.tearDown();
            }
        }
    }

    public void testPartialResultsStreamIntoEditableDraftAndRollbackOnCancel() throws Throwable {
        runTestOnUiThread(() -> {
            editor.setText("Début");
            editor.setSelection(editor.length());
            controls.button.performClick();
            assertEquals(View.VISIBLE, controls.meter.getVisibility());

            input.rms(7.5f);
            assertTrue(controls.meter.currentLevel() > 0.3f);

            input.partial("bonjour");
            assertEquals("Début bonjour", editor.getText().toString());
            input.partial("bonjour tout le monde");
            assertEquals("Début bonjour tout le monde", editor.getText().toString());

            // Final result cleanly replaces provisional partial without duplication.
            input.result("bonjour tout le monde.");
            assertEquals("Début bonjour tout le monde.", editor.getText().toString());
            assertEquals(View.GONE, controls.meter.getVisibility());

            // Cancel rolls back an uncommitted partial while preserving original text.
            controls.button.performClick();
            input.partial("temporaire");
            assertEquals("Début bonjour tout le monde. temporaire", editor.getText().toString());
            voice.cancel();
            assertEquals("Début bonjour tout le monde.", editor.getText().toString());
        });
    }

    public void testLocaleSelectionAndOfflineMultiLocaleTtsFilter() throws Throwable {
        runTestOnUiThread(() -> {
            voice.setLocale(VoiceLocale.ENGLISH);
            assertEquals(VoiceLocale.ENGLISH, voice.locale());
            assertEquals(VoiceLocale.ENGLISH, input.locale());
            voice.setLocale(VoiceLocale.SYSTEM);
            assertEquals(VoiceLocale.SYSTEM, voice.locale());
            voice.setLocale(VoiceLocale.FRENCH);
            assertEquals(VoiceLocale.FRENCH, voice.locale());
        });

        Voice enOffline = new Voice("en", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet());
        Voice enNetwork = new Voice("en-net", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, true, Collections.emptySet());
        Voice enMissing = new Voice("en-miss", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false,
                Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
        assertTrue(AndroidReplySpeech.installedVoice(enOffline, "en"));
        assertFalse(AndroidReplySpeech.installedVoice(enNetwork, "en"));
        assertFalse(AndroidReplySpeech.installedVoice(enMissing, "en"));
        assertFalse(AndroidReplySpeech.installedVoice(enOffline, "fr"));
    }

    public void testOptInDuplexHandleSubmitsOnSpeechAndSupportsInstantBargeIn() throws Throwable {
        runTestOnUiThread(() -> {
            List<String> sent = new ArrayList<>();
            VoiceDrafts.DuplexHandle duplex = voice.bindDuplex(controls, sent::add);
            LinearLayout root = (LinearLayout) editor.getParent();
            root.addView(duplex.bar());

            assertFalse(duplex.active());
            assertEquals(View.GONE, duplex.bar().getVisibility());

            duplex.start();
            assertTrue(duplex.active());
            assertEquals(View.VISIBLE, duplex.bar().getVisibility());
            assertEquals(DuplexVoiceSession.State.LISTENING, duplex.state());

            input.result("Résume cette page");
            assertEquals(List.of("Résume cette page"), sent);
            assertEquals(DuplexVoiceSession.State.THINKING, duplex.state());

            duplex.bargeIn();
            assertEquals(DuplexVoiceSession.State.LISTENING, duplex.state());

            duplex.stop();
            assertFalse(duplex.active());
            assertEquals(View.GONE, duplex.bar().getVisibility());
        });
    }

    public void testDuplexSurvivesActivityPauseDuringAudioPermissionGrant() throws Throwable {
        runTestOnUiThread(() -> {
            input.allowed = false;
            VoiceDrafts.DuplexHandle duplex = voice.bindDuplex(controls, utterance -> {});
            LinearLayout root = (LinearLayout) editor.getParent();
            root.addView(duplex.bar());

            duplex.start();
            assertTrue(duplex.active());
            assertTrue(input.requested > 0);

            // Runtime permission sheet pauses and unfocuses the Activity before delivering the grant.
            voice.pause();
            assertTrue("Duplex must remain active while awaiting RECORD_AUDIO permission", duplex.active());

            input.allowed = true;
            assertTrue(voice.permissionResult(input.requested,
                    new String[]{android.Manifest.permission.RECORD_AUDIO},
                    new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED}));
            voice.resume();
            voice.windowFocusChanged(true);

            assertTrue(duplex.active());
            assertEquals(DuplexVoiceSession.State.LISTENING, duplex.state());
            assertEquals(1, input.recordings);

            duplex.stop();
            assertFalse(duplex.active());
        });
    }
}
