package org.llm4people.hermes.share;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeviceBridgeCapabilitiesTest {
    private static final Pattern JARGON = Pattern.compile("\\b(json|sse|prompt|endpoint|token|toolset|cron|yaml)\\b");
    private static final Set<String> FORK_PR15_ALLOWLIST = Set.of(
            "camera", "location_coarse", "nearby_osm",
            "calendar_draft", "alarm_timer", "maps_handoff",
            "share_sheet", "clipboard_read",
            "dial_draft", "email_draft", "contacts_pick", "ui_surfaces_v2");

    @Test
    public void advertisedCapabilitiesStrictlyMatchForkPr15Allowlist() {
        List<String> full = DeviceBridgeFlow.advertisedCapabilities(true, true, true, true);
        for (String cap : full) {
            assertTrue("Non-allowlisted capability would trigger HTTP 400: " + cap,
                    FORK_PR15_ALLOWLIST.contains(cap));
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
            assertTrue("Executable capability must be in Fork PR #15 allowlist: " + cap,
                    FORK_PR15_ALLOWLIST.contains(cap));
        }
        assertTrue(executable.contains("calendar_draft"));
        assertTrue(executable.contains("alarm_timer"));
        assertTrue(executable.contains("maps_handoff"));
        assertTrue(executable.contains("share_sheet"));
        assertTrue(executable.contains("clipboard_read"));
        assertTrue(executable.contains("dial_draft"));
        assertTrue(executable.contains("email_draft"));
        // Unbacked capabilities must NOT be advertised until backed by a real executor.
        assertFalse(executable.contains("camera"));
        assertFalse(executable.contains("location_coarse"));
        assertFalse(executable.contains("nearby_osm"));
        assertFalse(executable.contains("contacts_pick"));
        assertFalse(executable.contains("ui_surfaces_v2"));

        List<String> restricted = DeviceBridgeFlow.executableCapabilities(false, false, false);
        assertFalse(restricted.contains("maps_handoff"));
        assertFalse(restricted.contains("alarm_timer"));
        assertFalse(restricted.contains("clipboard_read"));
    }

    @Test
    public void frenchTitlesUsePlainEverydayLanguageWithoutAiJargon() {
        for (String action : DeviceBridgeFlow.SUPPORTED_ACTIONS) {
            String title = DeviceBridgeFlow.frenchTitle(action);
            assertFalse("Blank title for " + action, title.trim().isEmpty());
            assertFalse("AI jargon found in: " + title, JARGON.matcher(title.toLowerCase()).find());
        }
    }
}
