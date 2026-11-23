package org.llm4people.hermes.presence;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for Round 1 security &amp; wire-contract fixes:
 * - Fail-closed precise-to-coarse location downgrade in ContinuousLocationTracker and AppPresenceTracker
 * - Canonical Track 9A app_presence keys (mode, in_app_seconds_since_read, notifications[].source)
 */
public final class PresenceSecurityPrivacyFixTest {

    @Test
    public void preciseFixNeverLeaksAfterDowngradeToCoarseMode() {
        ContinuousLocationTracker tracker = new ContinuousLocationTracker();
        PresencePolicy precisePolicy = PresencePolicy.defaults()
                .withContinuousLocation(true)
                .withPreciseLocation(true);
        PresencePolicy coarsePolicy = precisePolicy.withPreciseLocation(false);

        long t0 = 2_000_000L;
        ContinuousLocationTracker.Snapshot preciseFix = tracker.recordFix(
                45.50171, -73.56728, 8.0f, 0.0f, "Montréal", t0, true);
        assertTrue(preciseFix.precise());
        assertNotNull(tracker.currentSnapshot(precisePolicy, t0 + 1_000L));

        // Fail-closed: coarse policy MUST NOT receive a cached precise GPS fix.
        assertNull(tracker.currentSnapshot(coarsePolicy, t0 + 1_000L));

        // Defense in depth: even if a caller passes a precise Snapshot directly to AppPresenceTracker
        // under a coarse-only policy, toWireContextJson must refuse to emit it!
        AppPresenceTracker presence = new AppPresenceTracker();
        presence.bindProfile("profile-loc", 1L);
        assertNull(presence.toWireContextJson(coarsePolicy, preciseFix, t0 + 1_000L));

        // Under coarse + screen sharing, screen context still emits but continuous_location is omitted.
        String screenOnlyWire = presence.toWireContextJson(coarsePolicy.withAppPresence(true), preciseFix, t0 + 1_000L);
        assertNotNull(screenOnlyWire);
        assertFalse(screenOnlyWire.contains("continuous_location"));
        assertFalse(screenOnlyWire.contains("45.50171"));

        // Under precise policy, 5-decimal coordinates and canonical mode="precise" are emitted.
        String preciseWire = presence.toWireContextJson(precisePolicy, preciseFix, t0 + 1_000L);
        assertNotNull(preciseWire);
        assertTrue(preciseWire.contains("\"mode\":\"precise\""));
        assertTrue(preciseWire.contains("\"lat\":45.50171"));
        assertFalse(preciseWire.contains("\"precision\""));
    }

    @Test
    public void appPresenceEmitsCanonicalTrack9AWireKeys() {
        AppPresenceTracker tracker = new AppPresenceTracker();
        long epoch = tracker.bindProfile("profile-wire", 5L);
        long t0 = 3_000_000L;
        tracker.updateScreen(epoch, "thread", "sess-7");
        tracker.recordAssistantMessageDelivered(epoch, "sess-7", "msg-9");
        tracker.markAssistantMessageRead(epoch, "sess-7", "msg-9", t0);
        tracker.recordNotificationReadWithoutReply(epoch, "n-1", "Signal", t0);

        ContinuousLocationTracker locTracker = new ContinuousLocationTracker();
        ContinuousLocationTracker.Snapshot coarseFix = locTracker.recordFix(
                45.50171, -73.56728, 12.0f, 0.0f, "Centre-ville", t0, false);

        PresencePolicy policy = PresencePolicy.defaults()
                .withAppPresence(true)
                .withReadWithoutReply(true)
                .withReadGraceSec(10)
                .withContinuousLocation(true);

        String wire = tracker.toWireContextJson(policy, coarseFix, t0 + 25_000L);
        assertNotNull(wire);
        assertTrue(wire.contains("\"in_app_unread_reply\":true"));
        assertTrue(wire.contains("\"in_app_seconds_since_read\":25"));
        assertTrue(wire.contains("\"notifications\":[{\"source\":\"Signal\",\"age_s\":25}]"));
        assertTrue(wire.contains("\"mode\":\"coarse\""));
        assertTrue(wire.contains("\"lat\":45.50"));
        assertTrue(wire.contains("\"lon\":-73.57"));

        // Verify legacy mismatched keys are gone.
        assertFalse(wire.contains("\"read_age_s\""));
        assertFalse(wire.contains("\"dismissed_notifications\""));
        assertFalse(wire.contains("\"precision\""));
    }
}
