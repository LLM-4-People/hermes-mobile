package org.llm4people.hermes.nearby;

import org.junit.Test;

import static org.junit.Assert.*;

public final class CoarseAreaTest {
    private static final long NOW = 500_000_000_000L;

    @Test public void retainsOnlyACellWithConservativeUncertaintyAndAge() {
        CoarseArea first = CoarseArea.from(0.005, 0.006, 500, NOW - 1, NOW);
        CoarseArea second = CoarseArea.from(-0.008, -0.007, 501, NOW, NOW);
        assertEquals(0, first.latitude, 0);
        assertEquals(0, first.longitude, 0);
        assertEquals(first.latitude, second.latitude, 0);
        assertEquals(first.longitude, second.longitude, 0);
        assertEquals(3_000, first.accuracyMeters);
        assertEquals(4_000, second.accuracyMeters);
        assertEquals(1, first.ageSeconds);
        assertEquals(0, second.ageSeconds);
        CoarseArea boundary = CoarseArea.from(0, 0, 5_000, NOW - 300_000_000_000L, NOW);
        assertEquals(8_000, boundary.accuracyMeters);
        assertEquals(300, boundary.ageSeconds);
    }

    @Test public void rejectsInvalidMissingFutureStaleOrVeryUncertainSamples() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 90.001, -90.001})
            assertNull(CoarseArea.from(invalid, 0, 10, NOW, NOW));
        for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, 181, -181})
            assertNull(CoarseArea.from(0, invalid, 10, NOW, NOW));
        for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, 5_000.1f})
            assertNull(CoarseArea.from(0, 0, invalid, NOW, NOW));
        for (long invalid : new long[]{0, -1, NOW + 1, NOW - 300_000_000_001L})
            assertNull(CoarseArea.from(0, 0, 10, invalid, NOW));
    }

    @Test public void highLatitudeDoesNotExposeNarrowLongitudeCellsAndAntimeridianWraps() {
        CoarseArea polarA = CoarseArea.from(89.98, 1, 10, NOW, NOW);
        CoarseArea polarB = CoarseArea.from(89.98, 20, 10, NOW, NOW);
        assertEquals(polarA.longitude, polarB.longitude, 0);
        assertEquals(0, CoarseArea.from(-90, -179, 10, NOW, NOW).longitude, 0);
        for (double latitude : new double[]{0, 46.23, -59.9, 85.3, 90}) {
            CoarseArea east = CoarseArea.from(latitude, 180, 10, NOW, NOW);
            CoarseArea west = CoarseArea.from(latitude, -180, 10, NOW, NOW);
            assertEquals(east.longitude, west.longitude, 0);
            assertTrue(east.longitude >= -180 && east.longitude < 180);
            assertEquals(Math.rint(east.longitude * 100), east.longitude * 100, 1e-8);
        }
    }
}
