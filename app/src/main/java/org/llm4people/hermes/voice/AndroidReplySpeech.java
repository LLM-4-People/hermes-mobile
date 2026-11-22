package org.llm4people.hermes.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Uses the installed engine's local French voice. Never submits text to a network voice. */
final class AndroidReplySpeech implements SpeechPlayback.Driver {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech engine;
    private boolean closed;
    private int sequence;
    private String utterance;
    private Consumer<Boolean> completion;
    private Consumer<Boolean> ready;
    private final Runnable initTimeout = () -> initialized(false);

    AndroidReplySpeech(Context context) { this.context = context.getApplicationContext(); }

    @Override public void prepare(Consumer<Boolean> callback) {
        ready = callback;
        main.postDelayed(initTimeout, 12_000);
        engine = new TextToSpeech(context, status -> main.post(() -> {
            if (closed || ready == null) return;
            try { initialized(status == TextToSpeech.SUCCESS && configure()); }
            catch (RuntimeException unavailable) { initialized(false); }
        }));
    }

    private boolean configure() {
        Set<Voice> voices = engine.getVoices();
        if (voices == null) return false;
        Locale preferred = context.getResources().getConfiguration().getLocales().get(0);
        Voice voice = voices.stream().filter(AndroidReplySpeech::installedFrench)
                .max(Comparator.comparing((Voice option) -> option.getLocale().equals(preferred))
                        .thenComparingInt(Voice::getQuality).thenComparing(Voice::getName)).orElse(null);
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS || !installedFrench(engine.getVoice())) return false;
        engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        return engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { main.post(() -> complete(id, true)); }
            @Override public void onError(String id) { main.post(() -> complete(id, false)); }
            @Override public void onStop(String id, boolean interrupted) { main.post(() -> complete(id, false)); }
        }) == TextToSpeech.SUCCESS;
    }

    static boolean installedFrench(Voice voice) {
        return voice != null && voice.getLocale() != null && "fr".equals(voice.getLocale().getLanguage())
                && !voice.isNetworkConnectionRequired() && voice.getFeatures() != null
                && !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    private void initialized(boolean ok) {
        main.removeCallbacks(initTimeout);
        Consumer<Boolean> callback = ready;
        ready = null;
        if (callback != null) callback.accept(ok);
    }

    @Override public void speak(String chunk, Consumer<Boolean> callback) {
        if (closed || !installedFrench(engine.getVoice())) { callback.accept(false); return; }
        utterance = Integer.toString(++sequence);
        completion = callback;
        if (engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, utterance) != TextToSpeech.SUCCESS) complete(utterance, false);
    }

    private void complete(String id, boolean ok) {
        if (closed || completion == null || !utterance.equals(id)) return;
        Consumer<Boolean> callback = completion;
        completion = null;
        callback.accept(ok);
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        ready = completion = null;
        TextToSpeech service = engine;
        engine = null;
        if (service != null) {
            try { service.stop(); } catch (RuntimeException unavailable) { /* Already disconnected. */ }
            try { service.shutdown(); } catch (RuntimeException unavailable) { /* Already disconnected. */ }
        }
    }
}
