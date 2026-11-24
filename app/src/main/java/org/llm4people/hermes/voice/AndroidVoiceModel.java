package org.llm4people.hermes.voice;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.llm4people.hermes.HermesConfig;

import java.util.List;
import java.util.function.Consumer;

@TargetApi(33)
final class AndroidVoiceModel implements VoiceModel {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpeechRecognizer service;
    private final Intent request;
    private final int releaseTimeoutMs;
    private final Runnable release = this::destroy;
    private Consumer<State> observer;
    private boolean downloading;
    private boolean destroyed;

    AndroidVoiceModel(Context context, Intent request) {
        Context app = context.getApplicationContext();
        service = SpeechRecognizer.createOnDeviceSpeechRecognizer(app);
        this.request = request;
        this.releaseTimeoutMs = HermesConfig.load(app).voiceModelReleaseTimeoutMs;
    }

    @Override public void check(Consumer<State> listener) {
        observer = listener;
        String language = request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        service.checkRecognitionSupport(request, main::post, new RecognitionSupportCallback() {
            @Override public void onSupportResult(RecognitionSupport support) {
                State state = contains(support.getInstalledOnDeviceLanguages(), language) ? State.READY
                        : contains(support.getPendingOnDeviceLanguages(), language) ? State.REQUESTED
                        : contains(support.getSupportedOnDeviceLanguages(), language) ? State.OFFERED : State.UNKNOWN;
                notifyState(state);
            }
            @Override public void onError(int error) { notifyState(State.UNKNOWN); }
        });
    }

    private static boolean contains(List<String> languages, String requested) {
        for (String tag : languages) if (requested.equalsIgnoreCase(tag)) return true;
        return false;
    }

    @Override public void download(Consumer<State> listener) {
        observer = listener;
        downloading = true;
        if (Build.VERSION.SDK_INT >= 34) monitoredDownload();
        else unmonitoredDownload();
    }

    @TargetApi(34)
    private void monitoredDownload() {
        service.triggerModelDownload(request, main::post, new ModelDownloadListener() {
            @Override public void onProgress(int percent) { notifyState(State.PENDING); }
            @Override public void onScheduled() { terminal(State.REQUESTED); }
            @Override public void onSuccess() { terminal(State.READY); }
            @Override public void onError(int error) {
                if (error == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS && observer != null) {
                    try { unmonitoredDownload(); }
                    catch (RuntimeException unavailable) { terminal(State.FAILED); }
                } else terminal(State.FAILED);
            }
        });
    }

    private void unmonitoredDownload() {
        service.triggerModelDownload(request);
        notifyState(State.REQUESTED);
    }

    private void notifyState(State state) { if (observer != null) observer.accept(state); }

    private void terminal(State state) {
        downloading = false;
        notifyState(state);
        if (observer == null) destroy();
    }

    @Override public void close() {
        observer = null;
        // Android can show its own consent activity. Leave an accepted request with the service
        // while releasing every reference to the profile and editor immediately.
        if (downloading && !destroyed) main.postDelayed(release, releaseTimeoutMs);
        else destroy();
    }

    private void destroy() {
        if (destroyed) return;
        destroyed = true;
        main.removeCallbacks(release);
        service.destroy();
    }
}
