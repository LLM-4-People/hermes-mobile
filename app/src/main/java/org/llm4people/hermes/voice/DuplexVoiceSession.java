package org.llm4people.hermes.voice;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Opt-in hands-free duplex voice session state machine.
 * Coordinates continuous listen -> send -> sentence-streaming offline TTS -> instant barge-in.
 */
public final class DuplexVoiceSession {
    public static final float BARGE_IN_RMS_DB = 6.5f;
    static final int BARGE_IN_CONSECUTIVE_FRAMES = 2;
    static final int MAX_CONSECUTIVE_SILENCE = 2;

    public enum State { OFF, LISTENING, THINKING, SPEAKING }

    public interface Callbacks {
        void startListening(int generation);
        void stopListening();
        void submitUtterance(String utterance);
        void speakSentence(String sentence, int generation);
        void stopSpeaking();
        void stateChanged(State state);
    }

    private final Callbacks callbacks;
    private final Queue<String> speechQueue = new ArrayDeque<>();
    private final SpokenText.SentenceStream sentenceStream = new SpokenText.SentenceStream();
    private State state = State.OFF;
    private int generation;
    private boolean speakingSentence;
    private boolean replyFinished;
    private boolean bargeInBlocked;
    private int loudFrames;
    private int consecutiveSilence;

    public DuplexVoiceSession(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public State state() { return state; }
    public boolean active() { return state != State.OFF; }

    /**
     * Restricts barge-in while the host still has an in-flight server run whose receipt has not
     * yet settled, so a follow-up utterance can never collide with an unsettled pending run.
     */
    public void setBargeInAllowed(boolean allowed) {
        bargeInBlocked = !allowed;
    }

    /** True when barge-in is permitted and the session is currently thinking or speaking. */
    public boolean canBargeIn() {
        return !bargeInBlocked && (state == State.SPEAKING || state == State.THINKING);
    }

    /** Explicit opt-in start of hands-free duplex mode. */
    public void start() {
        if (state != State.OFF) return;
        consecutiveSilence = 0;
        enterListening();
    }

    /** Immediately stop all microphone and TTS activity and leave duplex mode. */
    public void stop() {
        if (state == State.OFF) return;
        ++generation;
        speechQueue.clear();
        sentenceStream.reset();
        speakingSentence = false;
        replyFinished = false;
        loudFrames = 0;
        consecutiveSilence = 0;
        state = State.OFF;
        callbacks.stopListening();
        callbacks.stopSpeaking();
        callbacks.stateChanged(State.OFF);
    }

    /** User finished speaking an utterance in duplex mode. Empty input never submits to Hermes. */
    public void onTranscriptFinal(int gen, String transcript) {
        if (state != State.LISTENING || gen != generation) return;
        String cleaned = transcript == null ? "" : transcript.trim();
        if (cleaned.isEmpty()) {
            handleSilence();
            return;
        }
        consecutiveSilence = 0;
        sentenceStream.reset();
        speechQueue.clear();
        speakingSentence = false;
        replyFinished = false;
        loudFrames = 0;
        ++generation;
        state = State.THINKING;
        callbacks.stateChanged(State.THINKING);
        callbacks.submitUtterance(cleaned);
    }

    /** Handle a recognition error during duplex listening. */
    public void onListenError(int gen, boolean recoverableSilence) {
        if (state != State.LISTENING || gen != generation) return;
        if (recoverableSilence) {
            handleSilence();
        } else {
            stop();
        }
    }

    private void handleSilence() {
        consecutiveSilence++;
        if (consecutiveSilence > MAX_CONSECUTIVE_SILENCE) {
            stop();
        } else {
            enterListening();
        }
    }

    /** Stream assistant reply deltas incrementally into sentence-chunked offline TTS. */
    public void onReplyDelta(String cumulativeVisibleText) {
        if (state != State.THINKING && state != State.SPEAKING) return;
        List<String> ready = sentenceStream.push(cumulativeVisibleText);
        enqueueSentences(ready);
    }

    /** Flush any remaining prose when the assistant run completes. */
    public void onReplyComplete(String finalVisibleText) {
        if (state != State.THINKING && state != State.SPEAKING) return;
        replyFinished = true;
        List<String> tail = sentenceStream.flush(finalVisibleText);
        enqueueSentences(tail);
        if ((state == State.THINKING || state == State.SPEAKING) && !speakingSentence && speechQueue.isEmpty()) {
            enterListening();
        }
    }

    /** Handle completion (or interruption/unavailability) of one spoken sentence. */
    public void onSentenceDone(int gen, boolean ok) {
        if (state != State.SPEAKING || gen != generation) return;
        speakingSentence = false;
        if (!ok) {
            stop();
            return;
        }
        if (!speechQueue.isEmpty()) {
            pumpSpeech();
        } else if (replyFinished) {
            enterListening();
        }
    }

    /** Instant barge-in: immediately interrupt Hermes mid-sentence and start listening when permitted. */
    public void bargeIn() {
        if (!canBargeIn()) return;
        ++generation;
        speechQueue.clear();
        sentenceStream.reset();
        speakingSentence = false;
        replyFinished = false;
        loudFrames = 0;
        consecutiveSilence = 0;
        callbacks.stopSpeaking();
        enterListening();
    }

    /** Optional energy-based barge-in trigger while Hermes is speaking. */
    public boolean onRmsDuringSpeech(float rmsdB) {
        if (state != State.SPEAKING || bargeInBlocked) {
            loudFrames = 0;
            return false;
        }
        if (!Float.isNaN(rmsdB) && !Float.isInfinite(rmsdB) && rmsdB >= BARGE_IN_RMS_DB) {
            loudFrames++;
            if (loudFrames >= BARGE_IN_CONSECUTIVE_FRAMES) {
                bargeIn();
                return true;
            }
        } else {
            loudFrames = 0;
        }
        return false;
    }

    private void enqueueSentences(List<String> sentences) {
        if (sentences.isEmpty()) return;
        for (String sentence : sentences) {
            if (sentence != null && !sentence.trim().isEmpty()) {
                speechQueue.add(sentence.trim());
            }
        }
        if (!speechQueue.isEmpty()) {
            if (state != State.SPEAKING) {
                state = State.SPEAKING;
                loudFrames = 0;
                callbacks.stateChanged(State.SPEAKING);
            }
            if (!speakingSentence) pumpSpeech();
        }
    }

    private void pumpSpeech() {
        String next = speechQueue.poll();
        if (next == null) return;
        speakingSentence = true;
        callbacks.speakSentence(next, generation);
    }

    private void enterListening() {
        int nextGen = ++generation;
        state = State.LISTENING;
        loudFrames = 0;
        callbacks.stateChanged(State.LISTENING);
        callbacks.startListening(nextGen);
    }
}
