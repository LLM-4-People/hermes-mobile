package org.llm4people.hermes.ambient;

import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DeviceContextSnapshotTest {
    @Test
    public void buildsDeterministicPhoneAndTabletSnapshotsAndSanitizesBounds() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-22T20:15:30Z"), ZoneId.of("America/Toronto"));

        DeviceContextSnapshot.Snapshot phone = DeviceContextSnapshot.build(
                84, true, DeviceContextSnapshot.NetworkKind.WIFI,
                fixed, ZoneId.of("America/Toronto"), 392, Locale.CANADA_FRENCH);
        assertEquals(84, phone.batteryPercent());
        assertTrue(phone.charging());
        assertEquals(DeviceContextSnapshot.NetworkKind.WIFI, phone.network());
        assertEquals("2026-11-22T15:15:30-05:00", phone.localIsoDateTime());
        assertEquals("America/Toronto", phone.timezoneId());
        assertEquals(DeviceContextSnapshot.FormFactor.PHONE, phone.formFactor());
        assertEquals("fr", phone.languageTag());
        String json = phone.toSanitizedJson();
        assertTrue(json.contains("\"battery_percent\":84"));
        assertTrue(json.contains("\"network\":\"wifi\""));
        assertTrue(json.contains("\"form_factor\":\"phone\""));
        assertTrue(json.contains("\"language\":\"fr\""));

        DeviceContextSnapshot.Snapshot tablet = DeviceContextSnapshot.build(
                140, false, DeviceContextSnapshot.NetworkKind.CELLULAR,
                fixed, ZoneId.of("UTC"), 720, Locale.US);
        assertEquals(100, tablet.batteryPercent());
        assertEquals(DeviceContextSnapshot.FormFactor.TABLET, tablet.formFactor());
        assertEquals("en", tablet.languageTag());

        DeviceContextSnapshot.Snapshot unknown = DeviceContextSnapshot.build(
                -5, false, null, fixed, ZoneId.of("UTC"), 360, null);
        assertEquals(-1, unknown.batteryPercent());
        assertEquals(DeviceContextSnapshot.NetworkKind.UNKNOWN, unknown.network());
        assertEquals("fr", unknown.languageTag());
    }

    @Test
    public void returnsNullWhenOptInIsDisabled() {
        assertNull(DeviceContextSnapshot.capture(null, false));
        assertNull(DeviceContextSnapshot.capture(null, true));
    }
}
