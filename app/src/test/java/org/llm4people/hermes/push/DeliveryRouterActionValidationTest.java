package org.llm4people.hermes.push;

import org.junit.Test;
import org.llm4people.hermes.artifact.DeviceAction;

import java.time.OffsetDateTime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for DeviceAction fail-closed validators used by DeliveryRouter.parseSafeDeviceAction. */
public final class DeliveryRouterActionValidationTest {

    @Test
    public void acceptsValidCalendarMapAndShareTextFields() {
        assertTrue(DeviceAction.isValidSingleLineField("Sprint review", DeviceAction.MAX_CALENDAR_TITLE_CHARS));
        assertTrue(DeviceAction.isValidSingleLineField("Room 4B", DeviceAction.MAX_CALENDAR_LOCATION_CHARS));
        assertTrue(DeviceAction.isValidSingleLineField("Café Montréal", DeviceAction.MAX_MAP_QUERY_CHARS));
        assertTrue(DeviceAction.isValidShareText("Line 1\n\tLine 2 \uD83D\uDC68\u200D\uD83D\uDCBB"));
        assertTrue(DeviceAction.isValidCalendarYear(OffsetDateTime.parse("2026-11-27T10:00:00Z")));
        assertTrue(DeviceAction.isValidCalendarYear(OffsetDateTime.parse("0001-01-01T00:00:00Z")));
        assertTrue(DeviceAction.isValidCalendarYear(OffsetDateTime.parse("9999-12-31T23:59:59Z")));
    }

    @Test
    public void rejectsBidiControlsInvisiblesOverlongAndOutOfRangeYears() {
        String[] hostile = {
                "",
                "   ",
                "Safe \u202E spoofing",
                "Safe \u2066 isolate",
                "Null\u0000byte",
                "Carriage\rreturn",
                "\u200B\u200B",
                "\u3164\u3164",
                "Line\u2028sep",
                "Para\u2029sep",
                "Unpaired\uD800surrogate"
        };
        for (String bad : hostile) {
            assertFalse("Single-line field must reject: " + bad,
                    DeviceAction.isValidSingleLineField(bad, DeviceAction.MAX_CALENDAR_TITLE_CHARS));
            assertFalse("Map query must reject: " + bad,
                    DeviceAction.isValidSingleLineField(bad, DeviceAction.MAX_MAP_QUERY_CHARS));
            assertFalse("Share text must reject: " + bad,
                    DeviceAction.isValidShareText(bad));
        }

        // Single-line fields reject newlines/tabs whereas share_text allows them.
        assertFalse(DeviceAction.isValidSingleLineField("Line 1\nLine 2", DeviceAction.MAX_CALENDAR_TITLE_CHARS));
        assertFalse(DeviceAction.isValidSingleLineField("Tab\there", DeviceAction.MAX_MAP_QUERY_CHARS));

        // Overlong bounds
        assertFalse(DeviceAction.isValidSingleLineField("A".repeat(121), DeviceAction.MAX_CALENDAR_TITLE_CHARS));
        assertFalse(DeviceAction.isValidSingleLineField("L".repeat(201), DeviceAction.MAX_CALENDAR_LOCATION_CHARS));
        assertFalse(DeviceAction.isValidSingleLineField("Q".repeat(201), DeviceAction.MAX_MAP_QUERY_CHARS));
        assertFalse(DeviceAction.isValidShareText("S".repeat(2001)));

        // Out-of-range calendar years (outside 1..9999)
        assertFalse(DeviceAction.isValidCalendarYear(null));
        assertFalse(DeviceAction.isValidCalendarYear(OffsetDateTime.parse("0000-01-01T00:00:00Z")));
        assertFalse(DeviceAction.isValidCalendarYear(OffsetDateTime.parse("+10000-01-01T00:00:00Z")));
    }
}
