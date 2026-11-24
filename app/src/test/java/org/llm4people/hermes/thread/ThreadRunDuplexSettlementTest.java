package org.llm4people.hermes.thread;

import org.junit.Test;
import org.llm4people.hermes.voice.DuplexVoiceSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for R19.6.1 duplex settlement ordering after run tracking finishes. */
public final class ThreadRunDuplexSettlementTest {
    @Test
    public void settlesDuplexOnlyAfterTrackingClearsPendingAndStopsOtherwise() {
        List<String> completed = new ArrayList<>();
        AtomicInteger stops = new AtomicInteger();

        // Settled "completed" run delivers final reply to duplex.
        ThreadRun.settleDuplexAfterTracking(false, "completed", "Bonjour !", completed::add, stops::incrementAndGet);
        assertEquals(List.of("Bonjour !"), completed);
        assertEquals(0, stops.get());

        // Still-pending tracking (e.g. preserved local receipt after finishTracking) stops duplex instead.
        ThreadRun.settleDuplexAfterTracking(true, "completed", "Bonjour !", completed::add, stops::incrementAndGet);
        assertEquals(1, completed.size());
        assertEquals(1, stops.get());

        // Non-completed outcomes always stop duplex.
        for (String outcome : new String[]{"cancelled", "failed", "interrupted", "", null}) {
            int beforeStops = stops.get();
            ThreadRun.settleDuplexAfterTracking(false, outcome, "ignored", completed::add, stops::incrementAndGet);
            assertEquals("Non-completed outcome must stop duplex: " + outcome, beforeStops + 1, stops.get());
            assertEquals(1, completed.size());
        }
    }

    @Test
    public void duplexReentersListeningOnlyAfterPendingReceiptClears() {
        AtomicBoolean pendingReceipt = new AtomicBoolean(false);
        List<String> events = new ArrayList<>();
        List<Integer> listenGens = new ArrayList<>();

        DuplexVoiceSession session = new DuplexVoiceSession(new DuplexVoiceSession.Callbacks() {
            @Override public void startListening(int gen) {
                assertFalse("Duplex must NEVER re-enter LISTENING while journal.pending() != null", pendingReceipt.get());
                listenGens.add(gen);
                events.add("listen:" + gen);
            }
            @Override public void stopListening() {}
            @Override public void submitUtterance(String utterance) {
                pendingReceipt.set(true);
                events.add("submit:" + utterance);
            }
            @Override public void speakSentence(String sentence, int gen) { events.add("speak:" + sentence); }
            @Override public void stopSpeaking() {}
            @Override public void stateChanged(DuplexVoiceSession.State state) {}
        });

        session.start();
        session.onTranscriptFinal(listenGens.get(0), "Quelle heure ?");
        session.setBargeInAllowed(false);
        assertTrue(pendingReceipt.get());

        // Simulate run completion: pending clears and busy(false) unlocks barge-in BEFORE duplexReplyComplete.
        pendingReceipt.set(false);
        session.setBargeInAllowed(true);
        ThreadRun.settleDuplexAfterTracking(false, "completed", "", session::onReplyComplete, session::stop);

        assertEquals(DuplexVoiceSession.State.LISTENING, session.state());
        assertEquals(2, listenGens.size());
    }
}
