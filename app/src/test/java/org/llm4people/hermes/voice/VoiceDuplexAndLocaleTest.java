package org.llm4people.hermes.voice;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public final class VoiceDuplexAndLocaleTest {

    @Test public void voiceLocaleResolvesFrenchEnglishAndSystemCleanly() {
        assertEquals("fr-CA", VoiceLocale.FRENCH.resolveTag(Locale.CANADA_FRENCH));
        assertEquals("fr-FR", VoiceLocale.FRENCH.resolveTag(Locale.US));
        assertEquals("fr", VoiceLocale.FRENCH.resolveLanguage(Locale.US));

        assertEquals("en-GB", VoiceLocale.ENGLISH.resolveTag(Locale.UK));
        assertEquals("en-US", VoiceLocale.ENGLISH.resolveTag(Locale.FRANCE));
        assertEquals("en", VoiceLocale.ENGLISH.resolveLanguage(Locale.FRANCE));

        assertEquals("de-DE", VoiceLocale.SYSTEM.resolveTag(Locale.GERMANY));
        assertEquals("de", VoiceLocale.SYSTEM.resolveLanguage(Locale.GERMANY));
        assertEquals("fr-FR", VoiceLocale.SYSTEM.resolveTag(null));

        assertEquals(VoiceLocale.FRENCH, VoiceLocale.fromStorageKey(null));
        assertEquals(VoiceLocale.FRENCH, VoiceLocale.fromStorageKey("unknown"));
        assertEquals(VoiceLocale.ENGLISH, VoiceLocale.fromStorageKey("english"));
        assertEquals(VoiceLocale.SYSTEM, VoiceLocale.fromStorageKey("system"));
    }

    @Test public void audioLevelMeterNormalizesRmsAndRejectsNonFiniteValues() {
        assertEquals(0f, AudioLevelMeter.normalizeRms(Float.NaN), 0.0001f);
        assertEquals(0f, AudioLevelMeter.normalizeRms(Float.POSITIVE_INFINITY), 0.0001f);
        assertEquals(0f, AudioLevelMeter.normalizeRms(Float.NEGATIVE_INFINITY), 0.0001f);
        assertEquals(0f, AudioLevelMeter.normalizeRms(-10f), 0.0001f);
        assertEquals(0f, AudioLevelMeter.normalizeRms(-2f), 0.0001f);
        assertEquals(0.5f, AudioLevelMeter.normalizeRms(4f), 0.0001f);
        assertEquals(1f, AudioLevelMeter.normalizeRms(10f), 0.0001f);
        assertEquals(1f, AudioLevelMeter.normalizeRms(40f), 0.0001f);
    }

    @Test public void sentenceStreamExtractsCompleteSentencesAndSkipsPrivateBlocks() {
        SpokenText.SentenceStream stream = new SpokenText.SentenceStream();
        assertTrue(stream.push("<think>réflexion privée</think>\nBonjour").isEmpty());
        List<String> first = stream.push("<think>réflexion privée</think>\nBonjour tout le monde. Comment");
        assertEquals(List.of("Bonjour tout le monde."), first);

        List<String> second = stream.push("<think>réflexion privée</think>\nBonjour tout le monde. Comment allez-vous ? Voici ");
        assertEquals(List.of("Comment allez-vous ?"), second);

        List<String> tail = stream.flush("<think>réflexion privée</think>\nBonjour tout le monde. Comment allez-vous ? Voici la fin");
        assertEquals(List.of("Voici la fin"), tail);
    }

    private static final class FakeHost implements DuplexVoiceSession.Callbacks {
        final List<Integer> listens = new ArrayList<>();
        final List<String> submitted = new ArrayList<>();
        final List<String> spoken = new ArrayList<>();
        final List<Integer> spokenGens = new ArrayList<>();
        final List<DuplexVoiceSession.State> states = new ArrayList<>();
        int stopListens;
        int stopSpeaks;

        @Override public void startListening(int gen) { listens.add(gen); }
        @Override public void stopListening() { stopListens++; }
        @Override public void submitUtterance(String utterance) { submitted.add(utterance); }
        @Override public void speakSentence(String sentence, int gen) { spoken.add(sentence); spokenGens.add(gen); }
        @Override public void stopSpeaking() { stopSpeaks++; }
        @Override public void stateChanged(DuplexVoiceSession.State state) { states.add(state); }
    }

    @Test public void duplexSessionLoopsListenThinkSpeakAndReturnsToListen() {
        FakeHost host = new FakeHost();
        DuplexVoiceSession session = new DuplexVoiceSession(host);
        assertEquals(DuplexVoiceSession.State.OFF, session.state());

        session.start();
        assertEquals(DuplexVoiceSession.State.LISTENING, session.state());
        assertEquals(1, host.listens.size());
        int gen1 = host.listens.get(0);

        // Stale generation is ignored.
        session.onTranscriptFinal(gen1 - 1, "ignoré");
        assertTrue(host.submitted.isEmpty());

        session.onTranscriptFinal(gen1, "Quelle heure est-il ?");
        assertEquals(DuplexVoiceSession.State.THINKING, session.state());
        assertEquals(List.of("Quelle heure est-il ?"), host.submitted);

        // Stream two sentences incrementally.
        session.onReplyDelta("Il est midi. ");
        assertEquals(DuplexVoiceSession.State.SPEAKING, session.state());
        assertEquals(List.of("Il est midi."), host.spoken);
        int speakGen = host.spokenGens.get(0);

        session.onReplyComplete("Il est midi. Bonne journée !");
        session.onSentenceDone(speakGen, true);
        assertEquals(List.of("Il est midi.", "Bonne journée !"), host.spoken);

        session.onSentenceDone(speakGen, true);
        // Automatically returns to listening for the next hands-free turn.
        assertEquals(DuplexVoiceSession.State.LISTENING, session.state());
        assertEquals(2, host.listens.size());

        session.stop();
        assertEquals(DuplexVoiceSession.State.OFF, session.state());
    }

    @Test public void duplexBargeInInterruptsSpeechFencesOldCallbacksAndReopensMic() {
        FakeHost host = new FakeHost();
        DuplexVoiceSession session = new DuplexVoiceSession(host);
        session.start();
        session.onTranscriptFinal(host.listens.get(0), "Explique Java.");
        session.onReplyDelta("Première phrase. Deuxième phrase. ");
        assertEquals(DuplexVoiceSession.State.SPEAKING, session.state());
        int oldSpeakGen = host.spokenGens.get(0);

        // Single loud RMS frame does not false-trigger; two consecutive loud frames trigger barge-in.
        assertFalse(session.onRmsDuringSpeech(Float.NaN));
        assertFalse(session.onRmsDuringSpeech(DuplexVoiceSession.BARGE_IN_RMS_DB));
        assertFalse(session.onRmsDuringSpeech(1.0f)); // resets counter
        assertFalse(session.onRmsDuringSpeech(DuplexVoiceSession.BARGE_IN_RMS_DB));
        assertTrue(session.onRmsDuringSpeech(DuplexVoiceSession.BARGE_IN_RMS_DB + 1f));

        assertEquals(DuplexVoiceSession.State.LISTENING, session.state());
        assertEquals(1, host.stopSpeaks);
        assertEquals(2, host.listens.size());

        // Late TTS completion from interrupted utterance cannot enqueue the second sentence or corrupt state.
        session.onSentenceDone(oldSpeakGen, false);
        assertEquals(DuplexVoiceSession.State.LISTENING, session.state());
        assertEquals(1, host.spoken.size());
    }

    @Test public void duplexRejectsEmptyUtterancesAndStopsGracefullyOnSilenceOrTtsFailure() {
        FakeHost host = new FakeHost();
        DuplexVoiceSession session = new DuplexVoiceSession(host);
        session.start();

        // Empty utterances retry up to MAX_CONSECUTIVE_SILENCE then stop cleanly.
        for (int i = 0; i <= DuplexVoiceSession.MAX_CONSECUTIVE_SILENCE; i++) {
            int gen = host.listens.get(host.listens.size() - 1);
            session.onTranscriptFinal(gen, "   ");
        }
        assertTrue(host.submitted.isEmpty());
        assertEquals(DuplexVoiceSession.State.OFF, session.state());

        // TTS failure stops cleanly without looping.
        session.start();
        session.onTranscriptFinal(host.listens.get(host.listens.size() - 1), "Bonjour");
        session.onReplyComplete("Salut.");
        session.onSentenceDone(host.spokenGens.get(0), false);
        assertEquals(DuplexVoiceSession.State.OFF, session.state());
    }
}
