package org.llm4people.hermes.os;

import org.junit.Test;

import static org.junit.Assert.*;

public class LiveRunNotificationTest {
    @Test public void idleAndNullSnapshotsProduceNoNotificationPresentation() {
        assertNull(LiveRunNotification.present(null));
        assertNull(LiveRunNotification.present(LiveRunNotification.Snapshot.idle()));
        assertFalse(LiveRunNotification.Snapshot.idle().active());
    }

    @Test public void presentationsUseCalmFrenchCopyWithZeroPrivateDataOrJargon() {
        LiveRunNotification.Presentation starting = LiveRunNotification.present(
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.STARTING, 0, 0, false, false));
        assertNotNull(starting);
        assertEquals("Hermes travaille…", starting.title());
        assertEquals("Envoi", starting.shortCriticalText());
        assertTrue(starting.indeterminate());

        LiveRunNotification.Presentation stepped = LiveRunNotification.present(
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.TOOL_RUNNING, 2, 4, false, true));
        assertNotNull(stepped);
        assertEquals("Hermes travaille…", stepped.title());
        assertEquals("Étape 2/4", stepped.shortCriticalText());
        assertFalse(stepped.indeterminate());
        assertTrue(stepped.progress() > 0 && stepped.progress() < stepped.max());

        LiveRunNotification.Presentation decision = LiveRunNotification.present(
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.AWAITING_DECISION, 3, 4, true, true));
        assertNotNull(decision);
        assertEquals("Décision requise", decision.title());
        assertEquals("Action", decision.shortCriticalText());
        assertFalse(decision.indeterminate());
    }

    @Test public void outOfRangeStepCountsClampSafely() {
        LiveRunNotification.Presentation clamped = LiveRunNotification.present(
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.TOOL_RUNNING, 500, 3, false, true));
        assertNotNull(clamped);
        assertEquals("Étape 3/3", clamped.shortCriticalText());

        LiveRunNotification.Presentation negative = LiveRunNotification.present(
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.WORKING, -5, -2, false, true));
        assertNotNull(negative);
        assertEquals("En cours", negative.shortCriticalText());
        assertTrue(negative.indeterminate());
    }
}
