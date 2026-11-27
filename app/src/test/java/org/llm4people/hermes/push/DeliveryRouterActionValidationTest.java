package org.llm4people.hermes.push;

import org.junit.Test;

import java.time.OffsetDateTime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for DeliveryRouter.parseSafeDeviceAction fail-closed validators (R24.2.2). */
public final class DeliveryRouterActionValidationTest {

    @Test
    public void acceptsValidCalendarMapAndShareTextFields() {
        assertTrue(DeliveryRouter.isValidSingleLineField("Sprint review", DeliveryRouter.MAX_CALENDAR_TITLE_CHARS));
        assertTrue(DeliveryRouter.isValidSingleLineField("Room 4B", DeliveryRouter.MAX_CALENDAR_LOCATION_CHARS));
        assertTrue(DeliveryRouter.isValidSingleLineField("Café Montréal", DeliveryRouter.MAX_MAP_QUERY_CHARS));
        assertTrue(DeliveryRouter.isValidShareText("Line 1\n\tLine 2 \uD83D\uDC68\u200D\uD83D\uDCBB"));
        assertTrue(DeliveryRouter.isValidCalendarYear(OffsetDateTime.parse("2026-11-27T10:00:00Z")));
        assertTrue(DeliveryRouter.isValidCalendarYear(OffsetDateTime.parse("0001-01-01T00:00:00Z")));
        assertTrue(DeliveryRouter.isValidCalendarYear(OffsetDateTime.parse("9999-12-31T23:59:59Z")));
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
                    DeliveryRouter.isValidSingleLineField(bad, DeliveryRouter.MAX_CALENDAR_TITLE_CHARS));
            assertFalse("Map query must reject: " + bad,
                    DeliveryRouter.isValidSingleLineField(bad, DeliveryRouter.MAX_MAP_QUERY_CHARS));
            assertFalse("Share text must reject: " + bad,
                    DeliveryRouter.isValidShareText(bad));
        }

        // Single-line fields reject newlines/tabs whereas share_text allows them.
        assertFalse(DeliveryRouter.isValidSingleLineField("Line 1\nLine 2", DeliveryRouter.MAX_CALENDAR_TITLE_CHARS));
        assertFalse(DeliveryRouter.isValidSingleLineField("Tab\there", DeliveryRouter.MAX_MAP_QUERY_CHARS));

        // Overlong bounds
        assertFalse(DeliveryRouter.isValidSingleLineField("A".repeat(121), DeliveryRouter.MAX_CALENDAR_TITLE_CHARS));
        assertFalse(DeliveryRouter.isValidSingleLineField("L".repeat(201), DeliveryRouter.MAX_CALENDAR_LOCATION_CHARS));
        assertFalse(DeliveryRouter.isValidSingleLineField("Q".repeat(201), DeliveryRouter.MAX_MAP_QUERY_CHARS));
        assertFalse(DeliveryRouter.isValidShareText("S".repeat(2001)));

        // Out-of-range calendar years (outside 1..9999)
        assertFalse(DeliveryRouter.isValidCalendarYear(null));
        assertFalse(DeliveryRouter.isValidCalendarYear(OffsetDateTime.parse("0000-01-01T00:00:00Z")));
        assertFalse(DeliveryRouter.isValidCalendarYear(OffsetDateTime.parse("+10000-01-01T00:00:00Z")));
    }
}
