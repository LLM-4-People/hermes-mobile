package org.llm4people.hermes.voice;

import android.app.Activity;

/** Instrumentation-only recognition adapter. Inject results on the activity thread. */
public final class FakeDictation implements VoiceInput {
    Source source = Source.DEVICE;
    boolean allowed = true;
    boolean hasSystem = true;
    VoiceModel model;
    int requested;
    int recordings;
    int stops;
    int closes;
    Source opened;
    Listener listener;

    public VoiceDrafts controller(Activity activity) { return new VoiceDrafts(activity, this); }
    public void result(String text) { listener.result(text); }

    static final class FakeModel implements VoiceModel {
        State availability = State.OFFERED;
        int downloads;
        int closes;
        java.util.function.Consumer<State> events;

        @Override public void check(java.util.function.Consumer<State> callback) { callback.accept(availability); }
        @Override public void download(java.util.function.Consumer<State> callback) { downloads++; events = callback; }
        @Override public void close() { closes++; }
    }

    public void missingFrenchModel() {
        model = new FakeModel();
        listener.error(android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
    }

    @Override public Source preferred() { return source; }
    @Override public VoiceModel localModel() { return model; }
    @Override public boolean systemAvailable() { return hasSystem; }
    @Override public boolean permitted() { return allowed; }
    @Override public void requestPermission(int code) { requested = code; }
    @Override public Recording open(Source source, Listener target) {
        opened = source;
        listener = target;
        return new Recording() {
            @Override public void start() { recordings++; target.ready(); }
            @Override public void stop() { stops++; target.ended(); }
            @Override public void close() { closes++; }
        };
    }
}
