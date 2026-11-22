package org.llm4people.hermes.voice;

/** One recognition session with live audio level, partial results, and selectable locale. */
interface VoiceInput {
    enum Source { DEVICE, SYSTEM, NONE }

    interface Listener {
        void ready();
        void ended();
        void result(String transcript);
        void error(int reason);
        default void partial(String transcript) {}
        default void rms(float rmsdB) {}
    }

    interface Recording {
        void start();
        void stop();
        void close();
    }

    Source preferred();
    boolean systemAvailable();
    VoiceModel localModel();
    boolean permitted();
    void requestPermission(int code);
    Recording open(Source source, Listener listener);
    default void setLocale(VoiceLocale locale) {}
    default VoiceLocale locale() { return VoiceLocale.FRENCH; }
}
