package org.llm4people.hermes.nearby;

import android.location.Location;
import android.os.Bundle;

import junit.framework.TestCase;

import org.json.JSONObject;

public final class NearbyWireTest extends TestCase {
    public void testNativeLocationYieldsOnlyFourCoarseNumericFields() throws Exception {
        long now = 450_000_000_000L;
        Location sample = new Location("test");
        sample.setElapsedRealtimeNanos(now - 2);
        sample.setLatitude(0.0061);
        sample.setLongitude(-0.0044);
        sample.setAltitude(123);
        sample.setBearing(45);
        sample.setTime(1_000_000);
        Bundle extras = new Bundle();
        extras.putString("description", "private");
        sample.setExtras(extras);
        assertNull(CoarseArea.from(sample, now));
        sample.setAccuracy(12.4f);
        JSONObject result = CoarseArea.from(sample, now).json();
        assertEquals(4, result.length());
        for (String key : new String[]{"latitude", "longitude", "accuracy_m", "age_s"})
            assertTrue(result.get(key) instanceof Number);
        assertEquals(0.0, result.getDouble("latitude"), 0.0);
        assertEquals(0.0, result.getDouble("longitude"), 0.0);
        assertEquals(3000, result.getInt("accuracy_m"));
        assertEquals(1, result.getInt("age_s"));
        sample.setElapsedRealtimeNanos(now - 300_000_000_001L);
        assertNull(CoarseArea.from(sample, now));
    }
}
