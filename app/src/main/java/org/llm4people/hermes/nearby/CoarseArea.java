package org.llm4people.hermes.nearby;

import android.location.Location;

import org.json.JSONException;
import org.json.JSONObject;

/** Only the neighborhood-sized cell and conservative uncertainty cross the gateway boundary. */
final class CoarseArea {
    private static final long SECOND_NS = 1_000_000_000L;
    final double latitude;
    final double longitude;
    final int accuracyMeters;
    final int ageSeconds;

    private CoarseArea(double latitude, double longitude, int accuracyMeters, int ageSeconds) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.ageSeconds = ageSeconds;
    }

    static CoarseArea from(Location fix, long nowNanos) {
        if (fix == null || !fix.hasAccuracy()) return null;
        return from(fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(), fix.getElapsedRealtimeNanos(), nowNanos);
    }

    static CoarseArea from(double latitude, double longitude, float accuracyMeters,
                           long fixNanos, long nowNanos) {
        if (!Double.isFinite(latitude) || Math.abs(latitude) > 90
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180
                || !Float.isFinite(accuracyMeters) || accuracyMeters <= 0 || accuracyMeters > 5_000
                || fixNanos <= 0 || nowNanos < fixNanos) return null;
        long ageNanos = nowNanos - fixNanos;
        if (ageNanos > 300 * SECOND_NS) return null;

        // A 0.02-degree band is ~2.2 km high. Each band gets enough meridians
        // to keep cells approximately as wide on the ground, including near the poles.
        double bandLatitude = Math.round(latitude * 50) / 50.0;
        int columns = Math.max(1, (int) Math.floor(18_000 * Math.cos(Math.toRadians(bandLatitude))));
        long column = Math.round(longitude * columns / 360.0);
        int hundredths = (int) Math.round(column * 36_000.0 / columns);
        double cellLongitude = (Math.floorMod(hundredths + 18_000, 36_000) - 18_000) / 100.0;
        if (columns == 1) cellLongitude = 0;

        // Include the displacement to the published center, then round the radius up to a kilometre.
        int radius = (int) Math.ceil((accuracyMeters + 2_500.0) / 1_000) * 1_000;
        int age = (int) ((ageNanos + SECOND_NS - 1) / SECOND_NS);
        return new CoarseArea(bandLatitude, cellLongitude, radius, age);
    }

    JSONObject json() {
        try {
            return new JSONObject().put("latitude", latitude).put("longitude", longitude)
                    .put("accuracy_m", accuracyMeters).put("age_s", ageSeconds);
        } catch (JSONException invalidNumber) {
            throw new IllegalStateException(invalidNumber);
        }
    }
}
