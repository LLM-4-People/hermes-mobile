package org.llm4people.hermes.voice;

/** One recognition session. The Android implementation only consumes the final transcript. */
interface VoiceInput {
    enum Source { DEVICE, SYSTEM, NONE }

    interface Listener {
        void ready();
        void ended();
        void result(String transcript);
        void error(int reason);
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
}
