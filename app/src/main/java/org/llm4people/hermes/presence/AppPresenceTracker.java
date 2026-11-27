package org.llm4people.hermes.presence;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.JsonValues;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Profile-scoped ({@code profileEpoch}-fenced) live app presence tracker.
 * Captures active screen/thread and concurrent run count, emitting sanitized wire JSON matching
 * Track 9A's {@code mobile_app_presence} contract and failing closed whenever opt-in toggles are OFF.
 */
public final class AppPresenceTracker {
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern SCREEN_ID = Pattern.compile("[a-z0-9_/-]{1,64}");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,96}");

    private final Object lock = new Object();
    private String boundProfileId;
    private long profileEpoch;
    private String currentScreen = "";
    private String activeSessionId = "";
    private int activeRunCount;

    /** Bind tracker to an unlocked profile and epoch, clearing any prior profile's transient state. */
    public long bindProfile(String profileId, long epoch) {
        synchronized (lock) {
            clearStateLocked();
            boundProfileId = profileId == null ? "" : profileId.trim();
            profileEpoch = epoch;
            Diagnostics.debug("presence", "tracker_bound");
            return profileEpoch;
        }
    }

    /** Immediately wipe all live state and fence out stale callbacks on profile lock/switch/forget. */
    public void clearOnLock() {
        synchronized (lock) {
            clearStateLocked();
            boundProfileId = null;
            profileEpoch++;
            Diagnostics.debug("presence", "tracker_cleared");
        }
    }

    public boolean updateScreen(long epoch, String screenId, String sessionId) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            currentScreen = sanitizeScreen(screenId);
            activeSessionId = sanitizeId(sessionId);
            return true;
        }
    }

    public boolean updateActiveRunCount(long epoch, int count) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            activeRunCount = Math.max(0, Math.min(32, count));
            return true;
        }
    }

    /**
     * Produces sanitized wire JSON string matching Track 9A's {@code app_presence} contract.
     * Returns {@code null} (fail-closed) when no presence stream is opted in or profile is locked.
     */
    public String toWireContextJson(
            PresencePolicy policy,
            ContinuousLocationTracker.Snapshot locationSnapshot,
            long nowMs) {
        if (policy == null) return null;
        boolean includeScreen = policy.shareAppPresence;
        ContinuousLocationTracker.Snapshot validLoc =
                (policy.allowsLiveLocation() && locationSnapshot != null && !locationSnapshot.isStale(nowMs)
                        && (policy.preciseLocation || !locationSnapshot.precise()))
                        ? locationSnapshot : null;
        if (!includeScreen && validLoc == null) {
            return null;
        }

        synchronized (lock) {
            if (boundProfileId == null) return null;
            StringBuilder json = new StringBuilder(256);
            json.append("{\"schema_version\":").append(SCHEMA_VERSION);
            if (includeScreen) {
                json.append(",\"current_screen\":").append(JsonValues.quote(currentScreen));
                json.append(",\"active_session_id\":").append(JsonValues.quote(activeSessionId));
                json.append(",\"active_run_count\":").append(activeRunCount);
            }
            if (validLoc != null) {
                boolean emitPrecise = policy.preciseLocation && validLoc.precise();
                String coordFmt = emitPrecise ? "%.5f" : "%.2f";
                double latVal = emitPrecise ? validLoc.lat() : ContinuousLocationTracker.quantizeCoarse(validLoc.lat());
                double lonVal = emitPrecise ? validLoc.lon() : ContinuousLocationTracker.quantizeCoarse(validLoc.lon());
                float accVal = emitPrecise ? validLoc.accuracyM()
                        : Math.max(ContinuousLocationTracker.COARSE_MIN_ACCURACY_M, validLoc.accuracyM());
                json.append(",\"continuous_location\":{");
                json.append("\"opted_in\":true");
                json.append(",\"mode\":").append(JsonValues.quote(emitPrecise ? "precise" : "coarse"));
                json.append(",\"lat\":").append(String.format(Locale.ROOT, coordFmt, latVal));
                json.append(",\"lon\":").append(String.format(Locale.ROOT, coordFmt, lonVal));
                json.append(",\"accuracy_m\":").append(Math.round(accVal));
                json.append(",\"place_label\":").append(JsonValues.quote(validLoc.placeLabel()));
                json.append(",\"moving_state\":").append(JsonValues.quote(validLoc.movingState().wire));
                json.append(",\"age_s\":").append(validLoc.ageSeconds(nowMs));
                json.append('}');
            }
            json.append('}');
            return json.toString();
        }
    }

    /** Android convenience wrapper returning parsed {@link JSONObject}, or {@code null} when opted out. */
    public JSONObject toWireContext(
            PresencePolicy policy,
            ContinuousLocationTracker.Snapshot locationSnapshot,
            long nowMs) {
        String encoded = toWireContextJson(policy, locationSnapshot, nowMs);
        if (encoded == null) return null;
        try {
            return new JSONObject(encoded);
        } catch (JSONException unexpected) {
            Diagnostics.warning("presence", "wire_json_invalid");
            return null;
        }
    }

    private boolean matchesEpochLocked(long epoch) {
        return boundProfileId != null && epoch == profileEpoch;
    }

    private void clearStateLocked() {
        currentScreen = "";
        activeSessionId = "";
        activeRunCount = 0;
    }

    static String sanitizeScreen(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return SCREEN_ID.matcher(lower).matches() ? lower : "";
    }

    static String sanitizeId(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : "";
    }
}
