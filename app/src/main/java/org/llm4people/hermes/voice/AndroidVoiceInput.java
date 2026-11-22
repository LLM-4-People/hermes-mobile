package org.llm4people.hermes.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.llm4people.hermes.Diagnostics;

import java.util.ArrayList;
import java.util.Locale;

final class AndroidVoiceInput implements VoiceInput {
    private final Activity activity;

    AndroidVoiceInput(Activity activity) { this.activity = activity; }

    @Override public Source preferred() {
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity))
            return Source.DEVICE;
        return systemAvailable() ? Source.SYSTEM : Source.NONE;
    }

    @Override public VoiceModel localModel() {
        return Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
                ? new AndroidVoiceModel(activity, request()) : null;
    }

    @Override public boolean systemAvailable() { return SpeechRecognizer.isRecognitionAvailable(activity); }
    @Override public boolean permitted() {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    @Override public void requestPermission(int code) {
        activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, code);
    }

    @Override public Recording open(Source source, Listener listener) {
        SpeechRecognizer recognizer;
        if (source == Source.DEVICE && Build.VERSION.SDK_INT >= 31)
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
        else if (source == Source.SYSTEM)
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        else throw new IllegalStateException("No recognizer for selected source");
        return new Recording() {
            @Override public void start() {
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) { listener.ready(); }
                    @Override public void onBeginningOfSpeech() { listener.ready(); }
                    @Override public void onEndOfSpeech() { listener.ended(); }
                    @Override public void onError(int error) { listener.error(error); }
                    @Override public void onResults(Bundle results) {
                        ArrayList<String> phrases = results == null ? null
                                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        listener.result(phrases == null || phrases.isEmpty() ? "" : phrases.get(0));
                    }
                    @Override public void onPartialResults(Bundle results) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onEvent(int event, Bundle params) {}
                });
                recognizer.startListening(request());
            }
            @Override public void stop() { recognizer.stopListening(); }
            @Override public void close() {
                try {
                    try { recognizer.cancel(); }
                    finally { recognizer.destroy(); }
                } catch (RuntimeException disconnected) {
                    Diagnostics.warning("voice", "service_disconnected");
                }
            }
        };
    }

    private Intent request() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    private String language() {
        LocaleList locales = activity.getResources().getConfiguration().getLocales();
        for (int i = 0; i < locales.size(); i++) {
            Locale locale = locales.get(i);
            if ("fr".equals(locale.getLanguage())) return locale.toLanguageTag();
        }
        return "fr-FR";
    }
}
