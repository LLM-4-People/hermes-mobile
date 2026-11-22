package org.llm4people.hermes.share;

import org.junit.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeviceBridgeCapabilitiesTest {
    private static final Pattern JARGON = Pattern.compile("\\b(json|sse|prompt|endpoint|token|toolset|cron|yaml)\\b");
    @Test
    public void advertisedCapabilitiesReflectDeviceSupport() {
        List<String> full = DeviceBridgeFlow.advertisedCapabilities(true, true, true, true);
        assertTrue(full.contains("camera"));
        assertTrue(full.contains("location_coarse"));
        assertTrue(full.contains("nearby_osm"));
        assertTrue(full.contains("alarm_timer"));
        assertTrue(full.contains("map_navigation"));
        assertTrue(full.contains("calendar_draft"));
        assertTrue(full.contains("dial_draft"));
        assertTrue(full.contains("sms_draft"));
        assertTrue(full.contains("email_draft"));
        assertTrue(full.contains("contact_draft"));

        List<String> minimal = DeviceBridgeFlow.advertisedCapabilities(false, false, false, false);
        assertFalse(minimal.contains("camera"));
        assertFalse(minimal.contains("location_coarse"));
        assertFalse(minimal.contains("nearby_osm"));
        assertFalse(minimal.contains("alarm_timer"));
        assertFalse(minimal.contains("map_navigation"));
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
