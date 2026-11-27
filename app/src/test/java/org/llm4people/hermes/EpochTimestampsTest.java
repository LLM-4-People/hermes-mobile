package org.llm4people.hermes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class EpochTimestampsTest {
    @Test
    public void parsesEpochSecondsMillisAndIso8601Formats() {
        assertEquals(1_760_000_125L, EpochTimestamps.parseEpochSeconds(1_760_000_125L));
        assertEquals(1_760_000_125L, EpochTimestamps.parseEpochSeconds(1_760_000_125_000L));
        assertEquals(1_760_000_125L, EpochTimestamps.parseEpochSeconds(1_760_000_125.9));
        assertEquals(1_760_000_125L, EpochTimestamps.parseEpochSeconds("1760000125"));
        assertEquals(1_760_000_125L, EpochTimestamps.parseEpochSeconds(" 1760000125000 "));
        long expectedIso = java.time.Instant.parse("2026-11-23T10:00:00Z").getEpochSecond();
        assertEquals(expectedIso, EpochTimestamps.parseEpochSeconds("2026-11-23T10:00:00Z"));
        assertEquals(expectedIso, EpochTimestamps.parseEpochSeconds("2026-11-23T12:00:00+02:00"));
    }

    @Test
    public void rejectsNullBlankNegativeNonFiniteAndGarbageInputsFailClosed() {
        assertEquals(0L, EpochTimestamps.parseEpochSeconds(null));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds(""));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds("   "));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds(0L));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds(-42L));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds("-42"));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds(Double.NaN));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds(Double.POSITIVE_INFINITY));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds("not-a-timestamp"));
        assertEquals(0L, EpochTimestamps.parseEpochSeconds(Boolean.TRUE));
    }
}
