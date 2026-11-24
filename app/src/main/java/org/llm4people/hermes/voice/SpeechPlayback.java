package org.llm4people.hermes.voice;

import org.llm4people.hermes.Diagnostics;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** All callbacks and commands run on the main thread. Each playback owns precisely one engine. */
final class SpeechPlayback {
    interface Driver {
        void prepare(Consumer<Boolean> ready);
        void speak(String chunk, Consumer<Boolean> finished);
        void close();
    }
    enum Failure { EMPTY, UNAVAILABLE, INTERRUPTED }

    private final Supplier<Driver> factory;
    private final Consumer<Failure> finished;
    private Driver active;
    private Consumer<Failure> activeCallback;

    SpeechPlayback(Supplier<Driver> factory, Consumer<Failure> finished) {
        this.factory = factory;
        this.finished = finished;
    }

    boolean active() { return active != null; }

    void play(String text) {
        play(text, null);
    }

    void play(String text, Consumer<Failure> perCallDone) {
        stop();
        List<String> chunks = SpokenText.chunks(text);
        if (chunks.isEmpty()) {
            notifyDone(perCallDone, Failure.EMPTY);
            return;
        }
        activeCallback = perCallDone;
        try {
            Driver owner = factory.get();
            active = owner;
            owner.prepare(ready -> {
                if (owner != active) return;
                if (ready) next(owner, chunks, 0);
                else finish(Failure.UNAVAILABLE);
            });
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("voice", "playback_start_failed", unavailable);
            finish(Failure.UNAVAILABLE);
        }
    }

    private void next(Driver owner, List<String> chunks, int index) {
        try {
            owner.speak(chunks.get(index), ok -> {
                if (owner != active) return;
                if (!ok) finish(Failure.INTERRUPTED);
                else if (index + 1 == chunks.size()) finish(null);
                else next(owner, chunks, index + 1);
            });
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("voice", "playback_speak_failed", unavailable);
            finish(Failure.INTERRUPTED);
        }
    }

    void stop() {
        if (active != null) finish(null);
    }

    private void finish(Failure failure) {
        Driver owner = active;
        Consumer<Failure> callback = activeCallback;
        active = null;
        activeCallback = null;
        try {
            if (owner != null) owner.close();
        } finally {
            notifyDone(callback, failure);
        }
    }

    private void notifyDone(Consumer<Failure> callback, Failure failure) {
        if (callback != null) callback.accept(failure);
        else finished.accept(failure);
    }
}
