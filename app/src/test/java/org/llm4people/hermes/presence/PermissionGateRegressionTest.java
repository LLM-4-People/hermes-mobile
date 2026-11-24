package org.llm4people.hermes.presence;

import org.junit.Test;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.os.PermissionCenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM regression tests for fail-closed permission gating and revocation reconciliation
 * across PresenceSettingsPanel (A3.4 / A3.7) and AmbientAccessPanel (A3.5 / A3.7).
 */
public final class PermissionGateRegressionTest {

    @Test
    public void presenceToggleGateIsFailClosedUntilPermissionGranted() {
        // Disabling a toggle always applies regardless of permission state.
        for (PermissionCenter.State state : PermissionCenter.State.values()) {
            assertEquals(PresenceSettingsPanel.ToggleDecision.APPLY,
                    PresenceSettingsPanel.decidePermissionToggle(false, state));
        }
        assertEquals(PresenceSettingsPanel.ToggleDecision.APPLY,
                PresenceSettingsPanel.decidePermissionToggle(false, null));

        // Enabling a permission-backed toggle only applies when GRANTED.
        assertEquals(PresenceSettingsPanel.ToggleDecision.APPLY,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.GRANTED));
        assertEquals(PresenceSettingsPanel.ToggleDecision.REQUEST_PERMISSION,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.NOT_REQUESTED));
        assertEquals(PresenceSettingsPanel.ToggleDecision.REQUEST_PERMISSION,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.DENIED));
        assertEquals(PresenceSettingsPanel.ToggleDecision.OPEN_SETTINGS,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.PERMANENTLY_DENIED));
        assertEquals(PresenceSettingsPanel.ToggleDecision.UNAVAILABLE,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.UNAVAILABLE));
        assertEquals(PresenceSettingsPanel.ToggleDecision.REQUEST_PERMISSION,
                PresenceSettingsPanel.decidePermissionToggle(true, null));
    }

    @Test
    public void presenceReconcileDisablesRevokedLocationAndProactiveNudges() {
        PresencePolicy allOn = PresencePolicy.defaults()
                .withAppPresence(true)
                .withReadWithoutReply(true)
                .withContinuousLocation(true)
                .withPreciseLocation(true)
                .withProactiveNudges(true);

        // All permissions granted preserves every toggle.
        assertEquals(allOn, PresenceSettingsPanel.reconcilePermissions(allOn, true, true, true));

        // Revoked notifications disables proactive nudges while leaving unrelated toggles intact.
        PresencePolicy noNotifs = PresenceSettingsPanel.reconcilePermissions(allOn, true, true, false);
        assertFalse(noNotifs.proactiveNudgesEnabled);
        assertTrue(noNotifs.continuousLocationEnabled);
        assertTrue(noNotifs.preciseLocation);
        assertTrue(noNotifs.shareAppPresence);

        // Revoked fine location downgrades preciseLocation to false while keeping coarse location on.
        PresencePolicy coarseOnly = PresenceSettingsPanel.reconcilePermissions(allOn, true, false, true);
        assertTrue(coarseOnly.continuousLocationEnabled);
        assertFalse(coarseOnly.preciseLocation);
        assertTrue(coarseOnly.proactiveNudgesEnabled);

        // Revoked coarse location disables both continuous and precise location.
        PresencePolicy noLoc = PresenceSettingsPanel.reconcilePermissions(allOn, false, false, true);
        assertFalse(noLoc.continuousLocationEnabled);
        assertFalse(noLoc.preciseLocation);
        assertTrue(noLoc.proactiveNudgesEnabled);
        assertTrue(noLoc.shareAppPresence);

        // Null policy fails closed to defaults.
        assertEquals(PresencePolicy.defaults(),
                PresenceSettingsPanel.reconcilePermissions(null, false, false, false));
    }

    @Test
    public void ambientReconcileFailsClosedForRevokedCalendarAndRetiredFlags() {
        NotificationAllowlist.Settings active =
                new NotificationAllowlist.Settings(true, true, true, false, false);

        // Calendar permission granted preserves active settings unchanged.
        assertEquals(active, AmbientAccessPanel.reconcilePermissions(active, true));

        // Revoked Calendar forces calendarEnabled=false without disturbing notifications or media.
        NotificationAllowlist.Settings noCal = AmbientAccessPanel.reconcilePermissions(active, false);
        assertFalse(noCal.calendarEnabled());
        assertTrue(noCal.notificationsEnabled());
        assertTrue(noCal.mediaEnabled());
        assertFalse(noCal.contactsEnabled());
        assertFalse(noCal.deviceContextEnabled());

        // Legacy stored contacts/device_context flags are cleared fail-closed even when calendar is granted.
        NotificationAllowlist.Settings legacyAllOn =
                new NotificationAllowlist.Settings(true, true, true, true, true);
        NotificationAllowlist.Settings cleaned = AmbientAccessPanel.reconcilePermissions(legacyAllOn, true);
        assertTrue(cleaned.notificationsEnabled());
        assertTrue(cleaned.mediaEnabled());
        assertTrue(cleaned.calendarEnabled());
        assertFalse(cleaned.contactsEnabled());
        assertFalse(cleaned.deviceContextEnabled());

        // Null settings fail closed to all-false.
        NotificationAllowlist.Settings fromNull = AmbientAccessPanel.reconcilePermissions(null, true);
        assertEquals(NotificationAllowlist.Settings.ALL_OFF, fromNull);
    }
}
