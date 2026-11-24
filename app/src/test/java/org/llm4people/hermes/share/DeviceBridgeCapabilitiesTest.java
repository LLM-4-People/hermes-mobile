package org.llm4people.hermes.share;

import org.junit.Test;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.presence.PresencePolicy;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeviceBridgeCapabilitiesTest {
    private static final Set<String> FORK_PR18_ALLOWLIST = Set.of(
            "camera", "location_coarse", "nearby_osm",
            "calendar_draft", "alarm_timer", "maps_handoff",
            "share_sheet", "clipboard_read",
            "dial_draft", "email_draft", "contacts_pick", "ui_surfaces_v2",
            "notifications_digest", "notification_reply", "media_control", "calendar_read");

    @Test
    public void advertisedCapabilitiesStrictlyMatchForkPr15Allowlist() {
        List<String> full = DeviceBridgeFlow.advertisedCapabilities(true, true, true, true);
        for (String cap : full) {
            assertTrue("Non-allowlisted capability would trigger HTTP 400: " + cap,
                    FORK_PR18_ALLOWLIST.contains(cap));
        }
        assertTrue(full.contains("camera"));
        assertTrue(full.contains("location_coarse"));
        assertTrue(full.contains("nearby_osm"));
        assertTrue(full.contains("alarm_timer"));
        assertTrue(full.contains("maps_handoff"));
        assertTrue(full.contains("calendar_draft"));
        assertTrue(full.contains("dial_draft"));
        assertTrue(full.contains("email_draft"));
        assertTrue(full.contains("contacts_pick"));
        assertTrue(full.contains("ui_surfaces_v2"));
        assertFalse(full.contains("map_navigation"));
        assertFalse(full.contains("clipboard_write"));
        assertFalse(full.contains("sms_draft"));
        assertFalse(full.contains("contact_draft"));

        List<String> minimal = DeviceBridgeFlow.advertisedCapabilities(false, false, false, false);
        assertFalse(minimal.contains("camera"));
        assertFalse(minimal.contains("location_coarse"));
        assertFalse(minimal.contains("nearby_osm"));
        assertFalse(minimal.contains("alarm_timer"));
        assertFalse(minimal.contains("maps_handoff"));

        List<String> executable = DeviceBridgeFlow.executableCapabilities(true, true, true);
        for (String cap : executable) {
            assertTrue("Executable capability must be in Fork PR #18 allowlist: " + cap,
                    FORK_PR18_ALLOWLIST.contains(cap));
        }
        assertTrue(executable.contains("calendar_draft"));
        assertTrue(executable.contains("alarm_timer"));
        assertTrue(executable.contains("maps_handoff"));
        assertTrue(executable.contains("share_sheet"));
        assertTrue(executable.contains("clipboard_read"));
        assertTrue(executable.contains("dial_draft"));
        assertTrue(executable.contains("email_draft"));
        // Unbacked or disabled ambient capabilities must NOT be advertised by default.
        assertFalse(executable.contains("camera"));
        assertFalse(executable.contains("location_coarse"));
        assertFalse(executable.contains("nearby_osm"));
        assertFalse(executable.contains("contacts_pick"));
        assertFalse(executable.contains("ui_surfaces_v2"));
        assertFalse(executable.contains("notifications_digest"));
        assertFalse(executable.contains("notification_reply"));
        assertFalse(executable.contains("media_control"));
        assertFalse(executable.contains("calendar_read"));

        List<String> restricted = DeviceBridgeFlow.executableCapabilities(false, false, false);
        assertFalse(restricted.contains("maps_handoff"));
        assertFalse(restricted.contains("alarm_timer"));
        assertFalse(restricted.contains("clipboard_read"));
    }

    @Test
    public void ambientCapabilitiesAdvertiseStrictlyWhenBothOptInAndOsPermissionAreActive() {
        NotificationAllowlist.Settings allOn = new NotificationAllowlist.Settings(true, true, true, true, true);
        // Fail closed when OS listener & calendar permission are missing.
        assertTrue(DeviceBridgeFlow.ambientExecutableCapabilities(allOn, false, false, true).isEmpty());
        // Fail closed when opt-in is OFF even if OS listener & calendar permission are granted.
        assertTrue(DeviceBridgeFlow.ambientExecutableCapabilities(
                NotificationAllowlist.Settings.ALL_OFF, true, true, true).isEmpty());
        // Fail closed for notifications when allowlist is empty.
        List<String> noApps = DeviceBridgeFlow.ambientExecutableCapabilities(allOn, true, true, false);
        assertFalse(noApps.contains("notifications_digest"));
        assertFalse(noApps.contains("notification_reply"));
        assertTrue(noApps.contains("media_control"));
        assertTrue(noApps.contains("calendar_read"));
        // Full opt-in + OS permissions + non-empty allowlist advertises all 4 ambient capabilities.
        List<String> ready = DeviceBridgeFlow.executableCapabilities(true, true, true, allOn, true, true, true);
        assertTrue(ready.contains("notifications_digest"));
        assertTrue(ready.contains("notification_reply"));
        assertTrue(ready.contains("media_control"));
        assertTrue(ready.contains("calendar_read"));
        for (String cap : ready) {
            assertTrue("Ambient capability must stay in Fork PR #18 allowlist: " + cap,
                    FORK_PR18_ALLOWLIST.contains(cap));
        }
    }

    @Test
    public void localizedTitlesAndDefaultReasonsExistForEverySupportedAction() {
        for (String action : DeviceBridgeFlow.SUPPORTED_ACTIONS) {
            assertTrue("Missing title resource for " + action, DeviceBridgeFlow.titleRes(action) != 0);
            assertTrue("Missing reason resource for " + action, DeviceBridgeFlow.defaultReasonRes(action) != 0);
        }
        assertEquals(org.llm4people.hermes.R.string.share_bridge_title_default, DeviceBridgeFlow.titleRes("unknown_action"));
        assertEquals(org.llm4people.hermes.R.string.share_bridge_reason_default, DeviceBridgeFlow.defaultReasonRes("unknown_action"));
    }

    @Test
    public void mediaStatusCommandAndCalendarDaysAheadParametersResolveCleanly() {
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand((String) null));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand(""));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand("   "));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand("status"));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand("STATUS"));
        assertFalse(DeviceBridgeFlow.isMediaStatusCommand("pause"));
        assertFalse(DeviceBridgeFlow.isMediaStatusCommand("next"));

        assertEquals(7, DeviceBridgeFlow.resolveCalendarDays(null, null));
        assertEquals(14, DeviceBridgeFlow.resolveCalendarDays(14, null));
        assertEquals(3, DeviceBridgeFlow.resolveCalendarDays(null, 3));
        assertEquals(1, DeviceBridgeFlow.resolveCalendarDays(1, 10));
    }

    @Test
    public void deepCapabilitiesGatePresenceAndNudgesFailClosedAndStayInSupportedActions() {
        List<String> defCaps = DeviceBridgeFlow.deepExecutableCapabilities(PresencePolicy.defaults());
        assertTrue(defCaps.contains("app_navigate"));
        assertTrue(defCaps.contains("app_customize"));
        assertTrue(defCaps.contains("app_create_template"));
        assertTrue(defCaps.contains("android_sync_shortcuts"));
        assertFalse("app_read_state requires shareAppPresence opt-in", defCaps.contains("app_read_state"));
        assertFalse("android_proactive_nudge requires proactiveNudgesEnabled opt-in",
                defCaps.contains("android_proactive_nudge"));

        PresencePolicy optedIn = PresencePolicy.defaults()
                .withAppPresence(true)
                .withProactiveNudges(true);
        List<String> fullCaps = DeviceBridgeFlow.deepExecutableCapabilities(optedIn);
        assertTrue(fullCaps.contains("app_read_state"));
        assertTrue(fullCaps.contains("android_proactive_nudge"));
        for (String cap : fullCaps) {
            assertTrue("Deep capability must be registered in SUPPORTED_ACTIONS: " + cap,
                    DeviceBridgeFlow.SUPPORTED_ACTIONS.contains(cap));
        }
    }

    @Test
    public void calendarDraftWithoutEndDefaultsToOneHourOnlyForValidOffsetStarts() {
        assertEquals("2026-11-23T11:00:00Z", DeviceBridgeFlow.defaultCalendarEnd("2026-11-23T10:00:00Z"));
        assertEquals("2026-11-24T00:30:00-05:00", DeviceBridgeFlow.defaultCalendarEnd(" 2026-11-23T23:30:00-05:00 "));
        // No offset, free text, or blank starts stay without an end so DeviceAction fails closed.
        for (String invalid : new String[]{null, "", "   ", "demain 10h", "2026-11-23T10:00:00", "2026-13-40T10:00:00Z"}) {
            assertEquals(null, DeviceBridgeFlow.defaultCalendarEnd(invalid));
        }
    }
}
