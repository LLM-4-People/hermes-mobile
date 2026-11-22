package org.llm4people.hermes.voice;

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

    SpeechPlayback(Supplier<Driver> factory, Consumer<Failure> finished) {
        this.factory = factory;
        this.finished = finished;
    }

    boolean active() { return active != null; }

    void play(String text) {
        stop();
        List<String> chunks = SpokenText.chunks(text);
        if (chunks.isEmpty()) { finished.accept(Failure.EMPTY); return; }
        try {
            Driver owner = factory.get();
            active = owner;
            owner.prepare(ready -> {
                if (owner != active) return;
                if (ready) next(owner, chunks, 0);
                else finish(Failure.UNAVAILABLE);
            });
        } catch (RuntimeException unavailable) {
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
            finish(Failure.INTERRUPTED);
        }
    }

    void stop() {
        if (active != null) finish(null);
    }

    private void finish(Failure failure) {
        Driver owner = active;
        active = null;
        try {
            if (owner != null) owner.close();
        } finally {
            finished.accept(failure);
        }
    }
}
