package org.llm4people.hermes.voice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public final class SpeechPlaybackTest {
    @Before
    public void setUpDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void tearDownDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    static final class Fake implements SpeechPlayback.Driver {
        Consumer<Boolean> ready;
        Consumer<Boolean> done;
        final List<String> spoken = new ArrayList<>();
        boolean closed;
        public void prepare(Consumer<Boolean> callback) { ready = callback; }
        public void speak(String chunk, Consumer<Boolean> callback) { spoken.add(chunk); done = callback; }
        public void close() { closed = true; }
    }

    @Test public void stopDuringInitializationFencesOldProfileAndNeverSubmitsItsText() {
        List<Fake> drivers = new ArrayList<>();
        SpeechPlayback playback = new SpeechPlayback(() -> {
            Fake next = new Fake();
            drivers.add(next);
            return next;
        }, error -> assertNull(error));
        playback.play("Premier profil");
        playback.stop();
        playback.play("Deuxième profil");
        drivers.get(0).ready.accept(true);
        assertTrue(drivers.get(0).closed);
        assertTrue(drivers.get(0).spoken.isEmpty());
        drivers.get(1).ready.accept(true);
        assertEquals(List.of("Deuxième profil"), drivers.get(1).spoken);
        drivers.get(1).done.accept(true);
        assertTrue(drivers.get(1).closed);
        assertFalse(playback.active());
    }

    @Test public void stopMidReplyNeverEnqueuesAnotherChunkAndUnavailableEngineGetsNoText() {
        Fake engine = new Fake();
        List<SpeechPlayback.Failure> failures = new ArrayList<>();
        SpeechPlayback playback = new SpeechPlayback(() -> engine, failures::add);
        playback.play("Phrase longue. ".repeat(200));
        engine.ready.accept(true);
        assertEquals(1, engine.spoken.size());
        assertTrue(engine.spoken.get(0).length() <= 1800);
        playback.stop();
        engine.done.accept(true);
        assertEquals(1, engine.spoken.size());
        playback.play("Texte privé.");
        engine.ready.accept(false);
        assertEquals(SpeechPlayback.Failure.UNAVAILABLE, failures.get(failures.size() - 1));
        assertEquals(1, engine.spoken.size());
    }

    @Test public void allChunksAreReadInOrderAndEmptyTextNeverOpensAnEngine() {
        Fake engine = new Fake();
        List<SpeechPlayback.Failure> failures = new ArrayList<>();
        SpeechPlayback playback = new SpeechPlayback(() -> engine, failures::add);
        playback.play("");
        assertEquals(List.of(SpeechPlayback.Failure.EMPTY), failures);
        assertNull(engine.ready);
        String original = "Graines de potiron. ".repeat(240);
        playback.play(original);
        engine.ready.accept(true);
        while (!engine.closed) engine.done.accept(true);
        assertEquals(original, String.join("", engine.spoken));
    }

    @Test public void longSingleParagraphKeepsItsOpeningAndSkipsRawBase64() {
        String longInput = "Premier point. " + "Prenez le train. ".repeat(10_000);
        String prose = SpokenText.prose(longInput);
        assertTrue(prose.startsWith("Premier point. Prenez le train."));
        assertFalse("1-arg prose must not append hardcoded French", prose.contains("La suite"));
        assertFalse("1-arg prose must not append hardcoded English", prose.contains("available on screen"));
        assertTrue(prose.length() < 16_100);

        String enSuffix = "The rest is available on screen.";
        String enProse = SpokenText.prose(longInput, enSuffix);
        assertTrue(enProse.endsWith("\n" + enSuffix));

        String frSuffix = "La suite est disponible à l’écran.";
        String frProse = SpokenText.prose(longInput, frSuffix);
        assertTrue(frProse.endsWith("\n" + frSuffix));

        // Short replies must never append the continuation suffix.
        assertEquals("Short reply.", SpokenText.prose("Short reply.", enSuffix));
        assertEquals(prose, SpokenText.prose(longInput, "   "));

        assertEquals("", SpokenText.prose("YQ=="));
        assertEquals("", SpokenText.prose("data:application/pdf;base64,YQ=="));
    }

    @Test public void narrationOmitsInternalTagsCodeAttachmentsAndAddresses() {
        String input = "# Bonjour\n<think>notes privées\nsuite</think>\n````hermes-layout\n"
                + "{\"screen\":\"thread\"}\n```\n````\n    commande\n"
                + "![Aperçu](data:image/png;base64," + "ABC".repeat(130) + ")\n"
                + "Puis **choisissez** [le modèle](https://exemple.fr/secret).";
        String text = SpokenText.prose(input);
        assertTrue(text.startsWith("Bonjour"));
        assertTrue(text.endsWith("Puis choisissez le modèle."));
        for (String privateOrTechnical : List.of("notes", "suite", "screen", "commande", "ABC", "exemple", "```"))
            assertFalse(privateOrTechnical, text.contains(privateOrTechnical));
        assertEquals("", SpokenText.prose("<analysis>analyse inachevée"));
        assertEquals("", SpokenText.prose("~~~java\ncode sans fin"));
    }

    @Test public void factoryExceptionNotifiesPerCallDoneAndDoesNotInvokeDefaultCallback() {
        List<SpeechPlayback.Failure> defaultFailures = new ArrayList<>();
        List<SpeechPlayback.Failure> perCallFailures = new ArrayList<>();
        SpeechPlayback playback = new SpeechPlayback(() -> {
            throw new IllegalStateException("TTS binder unavailable");
        }, defaultFailures::add);

        playback.play("Bonjour", perCallFailures::add);

        assertEquals(List.of(SpeechPlayback.Failure.UNAVAILABLE), perCallFailures);
        assertTrue("Default callback must not run when perCallDone is supplied", defaultFailures.isEmpty());
        assertFalse(playback.active());
    }

    @Test public void explicitStopReportsInterruptedToPerCallCallbackAndNullToDefaultCallback() {
        List<Fake> drivers = new ArrayList<>();
        List<SpeechPlayback.Failure> defaultFailures = new ArrayList<>();
        List<SpeechPlayback.Failure> perCallFailures = new ArrayList<>();
        SpeechPlayback playback = new SpeechPlayback(() -> {
            Fake next = new Fake();
            drivers.add(next);
            return next;
        }, defaultFailures::add);

        // 2-arg play stopped during prepare -> perCallDone gets INTERRUPTED, default callback untouched.
        playback.play("Première phrase.", perCallFailures::add);
        playback.stop();
        assertEquals(List.of(SpeechPlayback.Failure.INTERRUPTED), perCallFailures);
        assertTrue(defaultFailures.isEmpty());
        assertTrue(drivers.get(0).closed);
        assertFalse(playback.active());

        // 2-arg play stopped mid-speech -> perCallDone gets INTERRUPTED, default callback untouched.
        playback.play("Deuxième phrase.", perCallFailures::add);
        drivers.get(1).ready.accept(true);
        assertEquals(List.of("Deuxième phrase."), drivers.get(1).spoken);
        playback.stop();
        assertEquals(List.of(SpeechPlayback.Failure.INTERRUPTED, SpeechPlayback.Failure.INTERRUPTED), perCallFailures);
        assertTrue(defaultFailures.isEmpty());
        assertTrue(drivers.get(1).closed);
        assertFalse(playback.active());

        // 1-arg play stopped explicitly -> default callback still receives null (no error toast).
        playback.play("Lecture directe.");
        drivers.get(2).ready.accept(true);
        playback.stop();
        assertEquals(1, defaultFailures.size());
        assertNull(defaultFailures.get(0));
        assertTrue(drivers.get(2).closed);
        assertFalse(playback.active());
    }
}
