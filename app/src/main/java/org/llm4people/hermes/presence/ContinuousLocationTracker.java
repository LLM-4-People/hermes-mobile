package org.llm4people.hermes.presence;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.SecretPatterns;

import java.util.List;

/**
 * Explicit opt-in, battery-efficient continuous location tracker.
 * Defaults OFF (fail-closed), uses Android {@link LocationManager} passive + significant-change
 * thresholds (minTime=120s, minDistance=75m) with warm {@code getLastKnownLocation()} seed,
 * classifies movement ({@code stationary}/{@code walking}/{@code transit}), and holds snapshots
 * strictly in volatile RAM — zero data is persisted or retained after lock, pause, disable, or wipe.
 */
public final class ContinuousLocationTracker {
    public static final long MIN_TIME_MS = 120_000L;
    public static final float MIN_DISTANCE_M = 75.0f;
    public static final long STALE_AFTER_MS = 30L * 60_000L;
    public static final float COARSE_MIN_ACCURACY_M = 500.0f;
    public static final int MAX_PLACE_LABEL_CHARS = 64;

    private static final double WALKING_MIN_MPS = 0.5;
    private static final double TRANSIT_MIN_MPS = 2.8;
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    public enum MovingState {
        STATIONARY("stationary"),
        WALKING("walking"),
        TRANSIT("transit");

        public final String wire;

        MovingState(String wire) { this.wire = wire; }
    }

    public enum Indicator {
        OFF,
        PAUSED,
        ACTIVE_COARSE,
        ACTIVE_PRECISE
    }

    public record Snapshot(
            double lat,
            double lon,
            float accuracyM,
            String placeLabel,
            MovingState movingState,
            long capturedAtMs,
            boolean precise) {
        public Snapshot {
            if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
                throw new IllegalArgumentException("Latitude out of range");
            }
            if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0) {
                throw new IllegalArgumentException("Longitude out of range");
            }
            if (!Float.isFinite(accuracyM) || accuracyM < 0.0f) {
                throw new IllegalArgumentException("Accuracy out of range");
            }
            placeLabel = sanitizeAreaLabel(placeLabel);
            movingState = movingState == null ? MovingState.STATIONARY : movingState;
        }

        public long ageSeconds(long nowMs) {
            return Math.max(0L, (nowMs - capturedAtMs) / 1000L);
        }

        public boolean isStale(long nowMs) {
            return nowMs - capturedAtMs > STALE_AFTER_MS;
        }
    }

    private final Object lock = new Object();
    private LocationManager activeManager;
    private LocationListener activeListener;
    private boolean running;
    private boolean preciseMode;
    private Snapshot previous;
    private Snapshot latest;

    /** Pure-Java live privacy indicator for UI badges. */
    public static Indicator indicatorFor(PresencePolicy policy) {
        if (policy == null || !policy.continuousLocationEnabled) return Indicator.OFF;
        if (policy.locationPaused) return Indicator.PAUSED;
        return policy.preciseLocation ? Indicator.ACTIVE_PRECISE : Indicator.ACTIVE_COARSE;
    }

    /** Pure-Java movement classifier using speed or Haversine displacement over elapsed time. */
    public static MovingState classifyMovingState(float speedMps, double deltaMeters, long deltaMs) {
        double effectiveSpeed = (Float.isFinite(speedMps) && speedMps > 0.0f) ? speedMps : -1.0;
        if (effectiveSpeed < 0.0 && deltaMs > 0L && Double.isFinite(deltaMeters) && deltaMeters >= 0.0) {
            effectiveSpeed = deltaMeters / (deltaMs / 1000.0);
        }
        if (!Double.isFinite(effectiveSpeed) || effectiveSpeed < WALKING_MIN_MPS) {
            return MovingState.STATIONARY;
        }
        if (effectiveSpeed < TRANSIT_MIN_MPS) {
            return MovingState.WALKING;
        }
        return MovingState.TRANSIT;
    }

    /** Haversine distance in meters between two WGS84 points. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        return EARTH_RADIUS_M * c;
    }

    /** Rounds coordinate to ~1.1 km neighborhood grid when coarse mode is active. */
    public static double quantizeCoarse(double degrees) {
        return Math.rint(degrees * 100.0) / 100.0;
    }

    public static String sanitizeAreaLabel(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (SecretPatterns.containsCredential(raw)) return "";
        return PlainTextSanitizer.collapseBounded(raw, MAX_PLACE_LABEL_CHARS);
    }

    public static boolean hasCoarsePermission(Context context) {
        if (context == null) return false;
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasFinePermission(Context context) {
        if (context == null) return false;
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Synchronizes tracker lifecycle with the current {@link PresencePolicy}.
     * Fails closed whenever opted out, paused, or lacking Android location permission.
     * Returns {@code true} only when actively listening.
     */
    @SuppressLint("MissingPermission")
    public boolean syncWithPolicy(Context context, PresencePolicy policy) {
        if (context == null || policy == null || !policy.allowsLiveLocation()) {
            stopAndWipe();
            return false;
        }
        if (!hasCoarsePermission(context)) {
            stopAndWipe();
            Diagnostics.warning("presence", "location_permission_missing");
            return false;
        }
        boolean effectivePrecise = policy.preciseLocation && hasFinePermission(context);
        Context app = context.getApplicationContext();
        LocationManager manager = app.getSystemService(LocationManager.class);
        if (manager == null) {
            stopAndWipe();
            return false;
        }

        synchronized (lock) {
            if (running && preciseMode == effectivePrecise) {
                return true;
            }
            boolean downgradedFromPrecise = !effectivePrecise && (preciseMode || (latest != null && latest.precise()));
            unregisterLocked();
            if (downgradedFromPrecise) {
                latest = null;
                previous = null;
            }
            preciseMode = effectivePrecise;
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null) return;
                    recordFromActiveListener(
                            this,
                            location.getLatitude(),
                            location.getLongitude(),
                            location.hasAccuracy() ? location.getAccuracy() : COARSE_MIN_ACCURACY_M,
                            location.hasSpeed() ? location.getSpeed() : -1.0f,
                            System.currentTimeMillis());
                }
            };
            boolean subscribed = false;
            subscribed |= tryRegister(manager, LocationManager.PASSIVE_PROVIDER, listener);
            subscribed |= tryRegister(manager, LocationManager.NETWORK_PROVIDER, listener);
            if (effectivePrecise) {
                subscribed |= tryRegister(manager, LocationManager.GPS_PROVIDER, listener);
            }
            if (!subscribed) {
                latest = null;
                previous = null;
                preciseMode = false;
                Diagnostics.warning("presence", "location_no_provider");
                return false;
            }
            activeManager = manager;
            activeListener = listener;
            running = true;
            seedLastKnownLocked(manager, effectivePrecise);
            Diagnostics.event("presence", "location_started", effectivePrecise ? "precise" : "coarse");
            return true;
        }
    }

    /** Records a validated fix in volatile RAM (also usable directly in host-JVM tests). */
    public Snapshot recordFix(
            double lat, double lon, float accuracyM, float speedMps,
            String areaLabel, long nowMs, boolean precise) {
        synchronized (lock) {
            return recordFixLocked(lat, lon, accuracyM, speedMps, areaLabel, nowMs, precise);
        }
    }

    /**
     * Records a fix from a registered {@link LocationListener} ONLY if the tracker is still running
     * and the callback belongs to the currently active listener (blocking stale queued MainLooper fixes).
     */
    Snapshot recordFromActiveListener(
            LocationListener source, double lat, double lon, float accuracyM, float speedMps, long nowMs) {
        synchronized (lock) {
            if (!running || activeListener == null || activeListener != source) {
                return null;
            }
            return recordFixLocked(lat, lon, accuracyM, speedMps, "", nowMs, preciseMode);
        }
    }

    /** Package-private seam for host-JVM fencing tests without Android LocationManager. */
    void activateListenerForTest(LocationListener listener, boolean precise) {
        synchronized (lock) {
            activeListener = listener;
            preciseMode = precise;
            running = (listener != null);
        }
    }

    private Snapshot recordFixLocked(
            double lat, double lon, float accuracyM, float speedMps,
            String areaLabel, long nowMs, boolean precise) {
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0
                || !Double.isFinite(lon) || lon < -180.0 || lon > 180.0) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        double effectiveLat = precise ? lat : quantizeCoarse(lat);
        double effectiveLon = precise ? lon : quantizeCoarse(lon);
        float safeAccuracy = (!Float.isFinite(accuracyM) || accuracyM < 0.0f) ? COARSE_MIN_ACCURACY_M : accuracyM;
        if (!precise && safeAccuracy < COARSE_MIN_ACCURACY_M) {
            safeAccuracy = COARSE_MIN_ACCURACY_M;
        }
        double deltaM = 0.0;
        long deltaMs = 0L;
        if (latest != null && nowMs > latest.capturedAtMs()) {
            deltaM = distanceMeters(latest.lat(), latest.lon(), effectiveLat, effectiveLon);
            deltaMs = nowMs - latest.capturedAtMs();
        }
        MovingState state = classifyMovingState(speedMps, deltaM, deltaMs);
        previous = latest;
        latest = new Snapshot(effectiveLat, effectiveLon, safeAccuracy, areaLabel, state, nowMs, precise);
        return latest;
    }

    /** Returns the latest non-stale snapshot ONLY when policy permits live location (and precision); else null. */
    public Snapshot currentSnapshot(PresencePolicy policy, long nowMs) {
        if (policy == null || !policy.allowsLiveLocation()) return null;
        synchronized (lock) {
            if (latest == null || latest.isStale(nowMs)) return null;
            if (!policy.preciseLocation && latest.precise()) return null;
            return latest;
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    /** Stops all Android listeners and wipes all in-memory fixes immediately (lock/pause/disable/forget). */
    public void stopAndWipe() {
        synchronized (lock) {
            boolean wasActive = running || latest != null || previous != null;
            unregisterLocked();
            latest = null;
            previous = null;
            preciseMode = false;
            if (wasActive) {
                Diagnostics.event("presence", "location_wiped");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void seedLastKnownLocked(LocationManager manager, boolean precise) {
        Location best = null;
        List<String> providers = manager.getProviders(true);
        if (providers == null) return;
        for (String provider : providers) {
            if (!precise && LocationManager.GPS_PROVIDER.equals(provider)) continue;
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null) continue;
                if (best == null || candidate.getTime() > best.getTime()) {
                    best = candidate;
                }
            } catch (RuntimeException ignored) {
                // Skip unavailable provider safely.
            }
        }
        if (best != null) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - best.getTime());
            if (ageMs <= STALE_AFTER_MS) {
                recordFix(best.getLatitude(), best.getLongitude(),
                        best.hasAccuracy() ? best.getAccuracy() : COARSE_MIN_ACCURACY_M,
                        best.hasSpeed() ? best.getSpeed() : -1.0f,
                        "", best.getTime() > 0 ? best.getTime() : System.currentTimeMillis(), precise);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean tryRegister(LocationManager manager, String provider, LocationListener listener) {
        try {
            if (!manager.isProviderEnabled(provider) && !LocationManager.PASSIVE_PROVIDER.equals(provider)) {
                return false;
            }
            manager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper());
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void unregisterLocked() {
        if (activeManager != null && activeListener != null) {
            try {
                activeManager.removeUpdates(activeListener);
            } catch (RuntimeException ignored) {
                // Already detached.
            }
        }
        activeManager = null;
        activeListener = null;
        running = false;
    }
}
