package org.llm4people.hermes.voice;

import java.util.function.Consumer;

/** Device-wide language resource. Checking or requesting a model never opens the microphone. */
interface VoiceModel {
    enum State { READY, OFFERED, PENDING, REQUESTED, UNKNOWN, FAILED }
    void check(Consumer<State> listener);
    void download(Consumer<State> listener);
    void close();
}
