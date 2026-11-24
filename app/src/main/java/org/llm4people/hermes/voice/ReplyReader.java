package org.llm4people.hermes.voice;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** User-initiated speech for the visible workspace. Playback, including initialization, is disposable. */
public final class ReplyReader {
    private final Activity activity;
    private final Runnable cancelDictation;
    private final Supplier<VoiceLocale> localeSupplier;
    private final SpeechPlayback playback;
    private Button owner;

    ReplyReader(Activity activity, Runnable cancelDictation, Supplier<VoiceLocale> localeSupplier) {
        this.activity = activity;
        this.cancelDictation = cancelDictation;
        this.localeSupplier = localeSupplier == null ? () -> VoiceLocale.SYSTEM : localeSupplier;
        playback = new SpeechPlayback(() -> new AndroidReplySpeech(activity, this.localeSupplier), this::finished);
    }

    public boolean active() { return playback.active(); }
    public void stop() { playback.stop(); }

    public void readReply(String source) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            stop();
            cancelDictation.run();
            playback.play(SpokenText.prose(activity, source));
        }
    }

    /** Speak one sentence chunk for opt-in hands-free duplex streaming, reporting completion to the caller. */
    void speakSentence(String sentence, Consumer<Boolean> done) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            done.accept(false);
            return;
        }
        playback.play(sentence, failure -> {
            if (failure == SpeechPlayback.Failure.UNAVAILABLE) {
                finished(failure);
                done.accept(false);
            } else {
                done.accept(failure == null);
            }
        });
    }

    /** Pass the question and the displayed French option labels, excluding answer drafts and wire metadata. */
    public void bindQuestion(Button button, String question, List<String> choices) {
        if (owner == button) stop();
        label(button, false);
        button.setOnClickListener(v -> {
            if (!button.isAttachedToWindow() || !button.isShown()) return;
            if (owner == button && active()) { stop(); return; }
            stop();
            cancelDictation.run();
            owner = button;
            button.addOnAttachStateChangeListener(detach);
            label(button, true);
            StringBuilder text = new StringBuilder(SpokenText.prose(activity, question));
            for (String choice : choices) {
                String option = SpokenText.prose(activity, choice);
                if (!option.isEmpty()) text.append('\n').append(option);
                if (text.length() >= 16_000) break;
            }
            playback.play(text.toString().trim());
        });
    }

    private final View.OnAttachStateChangeListener detach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { if (owner == view) stop(); }
    };

    private void label(Button button, boolean playing) {
        button.setText(playing ? R.string.read_stop : R.string.read_listen);
        button.setContentDescription(activity.getString(playing ? R.string.read_stop_label : R.string.read_question_label));
    }

    private void finished(SpeechPlayback.Failure failure) {
        if (owner != null) {
            owner.removeOnAttachStateChangeListener(detach);
            label(owner, false);
            owner = null;
        }
        if (failure != null) {
            int message = switch (failure) {
                case EMPTY -> R.string.read_empty;
                case UNAVAILABLE -> {
                    VoiceLocale selected = localeSupplier.get();
                    boolean french = selected == VoiceLocale.FRENCH
                            || ((selected == null || selected == VoiceLocale.SYSTEM)
                                    && AppLanguage.TAG_FRENCH.equals(AppLanguage.resolvedTag(activity)));
                    yield french ? R.string.read_unavailable : R.string.read_unavailable_locale;
                }
                case INTERRUPTED -> R.string.read_interrupted;
            };
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }
}
