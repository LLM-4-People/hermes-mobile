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
        tracker.updateActiveRunCount(epoch, 1);

        ContinuousLocationTracker locTracker = new ContinuousLocationTracker();
        ContinuousLocationTracker.Snapshot coarseFix = locTracker.recordFix(
                45.50171, -73.56728, 12.0f, 0.0f, "Centre-ville", t0, false);

        PresencePolicy policy = PresencePolicy.defaults()
                .withAppPresence(true)
                .withContinuousLocation(true);

        String wire = tracker.toWireContextJson(policy, coarseFix, t0 + 25_000L);
        assertNotNull(wire);
        assertTrue(wire.contains("\"current_screen\":\"thread\""));
        assertTrue(wire.contains("\"active_session_id\":\"sess-7\""));
        assertTrue(wire.contains("\"active_run_count\":1"));
        assertTrue(wire.contains("\"mode\":\"coarse\""));
        assertTrue(wire.contains("\"lat\":45.50"));
        assertTrue(wire.contains("\"lon\":-73.57"));

        // Verify removed/legacy keys are absent.
        assertFalse(wire.contains("\"read_without_reply\""));
        assertFalse(wire.contains("\"read_age_s\""));
        assertFalse(wire.contains("\"dismissed_notifications\""));
        assertFalse(wire.contains("\"precision\""));
    }

    @Test
    public void staleQueuedLocationListenerNeverRepopulatesAfterStopAndWipeOrResubscribe() {
        ContinuousLocationTracker tracker = new ContinuousLocationTracker();
        PresencePolicy precisePolicy = PresencePolicy.defaults()
                .withContinuousLocation(true)
                .withPreciseLocation(true);
        PresencePolicy coarsePolicy = precisePolicy.withPreciseLocation(false);

        android.location.LocationListener firstListener = loc -> {};
        android.location.LocationListener secondListener = loc -> {};

        long t0 = 4_000_000L;
        tracker.activateListenerForTest(firstListener, true);
        ContinuousLocationTracker.Snapshot active = tracker.recordFromActiveListener(
                firstListener, 45.50171, -73.56728, 8.0f, 0.0f, t0);
        assertNotNull(active);
        assertTrue(active.precise());
        assertNotNull(tracker.currentSnapshot(precisePolicy, t0 + 1_000L));

        // Lock/pause/disable wipes RAM and detaches listener.
        tracker.stopAndWipe();
        assertNull(tracker.currentSnapshot(precisePolicy, t0 + 2_000L));

        // Stale MainLooper callback queued before stopAndWipe() MUST be ignored.
        assertNull(tracker.recordFromActiveListener(
                firstListener, 45.50171, -73.56728, 8.0f, 0.0f, t0 + 3_000L));
        assertNull(tracker.currentSnapshot(precisePolicy, t0 + 3_000L));

        // Even after restarting with a new coarse listener, stale callbacks from firstListener MUST be ignored.
        tracker.activateListenerForTest(secondListener, false);
        assertNull(tracker.recordFromActiveListener(
                firstListener, 45.50171, -73.56728, 8.0f, 0.0f, t0 + 4_000L));
        assertNull(tracker.currentSnapshot(coarsePolicy, t0 + 4_000L));

        // Callbacks from the active secondListener record normally in coarse mode.
        ContinuousLocationTracker.Snapshot coarse = tracker.recordFromActiveListener(
                secondListener, 45.50171, -73.56728, 8.0f, 0.0f, t0 + 5_000L);
        assertNotNull(coarse);
        assertFalse(coarse.precise());
        assertNotNull(tracker.currentSnapshot(coarsePolicy, t0 + 5_000L));
    }
}
