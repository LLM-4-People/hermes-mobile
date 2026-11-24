package org.llm4people.hermes.voice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests verifying SpeechPlayback logs privacy-safe Diagnostics warnings on failure. */
public final class SpeechPlaybackDiagnosticsTest {
    @Before
    public void installNoOpDiagnosticsSink() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void restoreDiagnosticsState() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void emitsPrivacySafeDiagnosticsOnFactoryAndSpeakFailures() {
        List<SpeechPlayback.Failure> failures = new ArrayList<>();

        // Factory throws RuntimeException -> UNAVAILABLE + playback_start_failed warning without leaking text.
        SpeechPlayback brokenFactory = new SpeechPlayback(() -> {
            throw new IllegalStateException("Secret user prompt should never appear");
        }, failures::add);
        brokenFactory.play("Bonjour secret");
        assertEquals(List.of(SpeechPlayback.Failure.UNAVAILABLE), failures);
        String logAfterStart = Diagnostics.exportRedacted();
        assertTrue("Expected playback_start_failed warning", logAfterStart.contains("voice.playback_start_failed"));
        assertFalse("Must never leak exception message", logAfterStart.contains("Secret"));

        // Speak throws RuntimeException -> INTERRUPTED + playback_speak_failed warning.
        Diagnostics.clear();
        SpeechPlayback brokenSpeak = new SpeechPlayback(() -> new SpeechPlayback.Driver() {
            @Override public void prepare(Consumer<Boolean> ready) { ready.accept(true); }
            @Override public void speak(String chunk, Consumer<Boolean> finished) {
                throw new RuntimeException("Engine crashed");
            }
            @Override public void close() {}
        }, failures::add);
        brokenSpeak.play("Texte confidentiel");
        assertEquals(List.of(SpeechPlayback.Failure.UNAVAILABLE, SpeechPlayback.Failure.INTERRUPTED), failures);
        String logAfterSpeak = Diagnostics.exportRedacted();
        assertTrue("Expected playback_speak_failed warning", logAfterSpeak.contains("voice.playback_speak_failed"));
        assertFalse("Must never leak spoken text", logAfterSpeak.contains("confidentiel"));
    }
}
