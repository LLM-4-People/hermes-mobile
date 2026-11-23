package org.llm4people.hermes.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Uses the installed engine's local offline voice matching the active or selected language. Never uses network TTS. */
final class AndroidReplySpeech implements SpeechPlayback.Driver {
    private final Context context;
    private final Supplier<VoiceLocale> localeSupplier;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech engine;
    private String activeLanguage = AppLanguage.TAG_FRENCH;
    private boolean closed;
    private int sequence;
    private String utterance;
    private Consumer<Boolean> completion;
    private Consumer<Boolean> ready;
    private final Runnable initTimeout = () -> initialized(false);

    AndroidReplySpeech(Context context) {
        this(context, () -> VoiceLocale.SYSTEM);
    }

    AndroidReplySpeech(Context context, Supplier<VoiceLocale> localeSupplier) {
        this.context = context.getApplicationContext();
        this.localeSupplier = localeSupplier == null ? () -> VoiceLocale.SYSTEM : localeSupplier;
    }

    @Override public void prepare(Consumer<Boolean> callback) {
        ready = callback;
        main.postDelayed(initTimeout, 12_000);
        engine = new TextToSpeech(context, status -> main.post(() -> {
            if (closed || ready == null) return;
            try { initialized(status == TextToSpeech.SUCCESS && configure()); }
            catch (RuntimeException unavailable) {
                Diagnostics.warning("voice", "tts_configure_failed");
                initialized(false);
            }
        }));
    }

    private boolean configure() {
        Set<Voice> voices = engine.getVoices();
        if (voices == null) return false;
        VoiceLocale selected = localeSupplier.get();
        if (selected == null) selected = VoiceLocale.SYSTEM;
        Locale preferred;
        if (selected == VoiceLocale.SYSTEM) {
            preferred = AppLanguage.resolvedLocale(context);
            activeLanguage = AppLanguage.resolvedTag(context);
        } else {
            Locale systemLocale = context.getResources().getConfiguration().getLocales().get(0);
            activeLanguage = selected.resolveLanguage(systemLocale);
            preferred = Locale.forLanguageTag(selected.resolveTag(systemLocale));
        }
        Voice voice = voices.stream()
                .filter(option -> installedForLanguage(option, activeLanguage))
                .max(Comparator.comparing((Voice option) -> option.getLocale().equals(preferred))
                        .thenComparingInt(Voice::getQuality).thenComparing(Voice::getName))
                .orElse(null);
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS
                || !installedForLanguage(engine.getVoice(), activeLanguage)) {
            Diagnostics.warning("voice", "tts_offline_voice_missing");
            return false;
        }
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
        return installedForLanguage(voice, AppLanguage.TAG_FRENCH);
    }

    static boolean installedVoice(Voice voice, String languageCode) {
        return installedForLanguage(voice, languageCode);
    }

    static boolean installedForLanguage(Voice voice, String languageTag) {
        return voice != null && languageTag != null && !languageTag.isEmpty()
                && voice.getLocale() != null && languageTag.equalsIgnoreCase(voice.getLocale().getLanguage())
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
        if (closed || engine == null || !installedForLanguage(engine.getVoice(), activeLanguage)) {
            callback.accept(false);
            return;
        }
        utterance = Integer.toString(++sequence);
        completion = callback;
        if (engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, utterance) != TextToSpeech.SUCCESS) {
            complete(utterance, false);
        }
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
            try { service.stop(); } catch (RuntimeException unavailable) { Diagnostics.warning("voice", "tts_stop_failed"); }
            try { service.shutdown(); } catch (RuntimeException unavailable) { Diagnostics.warning("voice", "tts_shutdown_failed"); }
        }
    }
}
