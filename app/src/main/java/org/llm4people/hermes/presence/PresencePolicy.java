package org.llm4people.hermes.presence;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.profile.Account;

import java.util.Objects;

/**
 * Single source of truth for per-profile live presence &amp; proactive Android privacy toggles.
 * Fail-closed by default: every sensor, presence stream, and proactive nudge starts OFF until
 * explicitly enabled by the user. Stored encrypted inside the unlocked {@link Account} vault.
 */
public final class PresencePolicy {
    public static final String VAULT_KEY = "workspace.presence_policy.v1";
    public static final int DEFAULT_MAX_NUDGES_PER_HOUR = 3;
    public static final int MIN_MAX_NUDGES_PER_HOUR = 1;
    public static final int MAX_MAX_NUDGES_PER_HOUR = 10;

    public final boolean shareAppPresence;
    public final boolean continuousLocationEnabled;
    public final boolean preciseLocation;
    public final boolean locationPaused;
    public final boolean proactiveNudgesEnabled;
    public final int maxNudgesPerHour;

    public PresencePolicy(
            boolean shareAppPresence,
            boolean continuousLocationEnabled,
            boolean preciseLocation,
            boolean locationPaused,
            boolean proactiveNudgesEnabled,
            int maxNudgesPerHour) {
        this.shareAppPresence = shareAppPresence;
        this.continuousLocationEnabled = continuousLocationEnabled;
        this.preciseLocation = continuousLocationEnabled && preciseLocation;
        this.locationPaused = continuousLocationEnabled && locationPaused;
        this.proactiveNudgesEnabled = proactiveNudgesEnabled;
        this.maxNudgesPerHour = clamp(maxNudgesPerHour, MIN_MAX_NUDGES_PER_HOUR, MAX_MAX_NUDGES_PER_HOUR);
    }

    /** Fail-closed default: every sharing and sensor toggle is OFF. */
    public static PresencePolicy defaults() {
        return new PresencePolicy(false, false, false, false, false, DEFAULT_MAX_NUDGES_PER_HOUR);
    }

    public boolean allowsLiveLocation() {
        return continuousLocationEnabled && !locationPaused;
    }

    public boolean anySharingEnabled() {
        return shareAppPresence || allowsLiveLocation() || proactiveNudgesEnabled;
    }

    public PresencePolicy withAppPresence(boolean enabled) {
        return new PresencePolicy(enabled, continuousLocationEnabled, preciseLocation, locationPaused,
                proactiveNudgesEnabled, maxNudgesPerHour);
    }

    public PresencePolicy withContinuousLocation(boolean enabled) {
        return new PresencePolicy(shareAppPresence, enabled, enabled && preciseLocation, false,
                proactiveNudgesEnabled, maxNudgesPerHour);
    }

    public PresencePolicy withPreciseLocation(boolean precise) {
        return new PresencePolicy(shareAppPresence, continuousLocationEnabled, precise, locationPaused,
                proactiveNudgesEnabled, maxNudgesPerHour);
    }

    public PresencePolicy withLocationPaused(boolean paused) {
        return new PresencePolicy(shareAppPresence, continuousLocationEnabled, preciseLocation, paused,
                proactiveNudgesEnabled, maxNudgesPerHour);
    }

    public PresencePolicy withProactiveNudges(boolean enabled) {
        return new PresencePolicy(shareAppPresence, continuousLocationEnabled, preciseLocation, locationPaused,
                enabled, maxNudgesPerHour);
    }

    /** Compact pure-Java serialization (host-JVM testable, zero PII). */
    public String encode() {
        return "v2|" + bit(shareAppPresence)
                + "|" + bit(continuousLocationEnabled)
                + "|" + bit(preciseLocation)
                + "|" + bit(locationPaused)
                + "|" + bit(proactiveNudgesEnabled)
                + "|" + maxNudgesPerHour;
    }

    /** Fail-closed decode: missing or malformed records always return all-OFF {@link #defaults()}. */
    public static PresencePolicy decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return defaults();
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 7 || !"v2".equals(parts[0])) {
            Diagnostics.warning("presence", "policy_decode_invalid");
            return defaults();
        }
        try {
            return new PresencePolicy(
                    parseBit(parts[1]),
                    parseBit(parts[2]),
                    parseBit(parts[3]),
                    parseBit(parts[4]),
                    parseBit(parts[5]),
                    Integer.parseInt(parts[6]));
        } catch (IllegalArgumentException invalid) {
            Diagnostics.warning("presence", "policy_decode_invalid");
            return defaults();
        }
    }

    public static PresencePolicy load(Account account) {
        if (account == null) return defaults();
        try {
            String raw = account.read(VAULT_KEY);
            return decode(raw);
        } catch (RuntimeException failClosed) {
            Diagnostics.warning("presence", "policy_read_failed");
            return defaults();
        }
    }

    public static void save(Account account, PresencePolicy policy) {
        if (account == null || policy == null) return;
        account.write(VAULT_KEY, policy.encode());
        Diagnostics.event("presence", "policy_saved");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String bit(boolean flag) { return flag ? "1" : "0"; }

    private static boolean parseBit(String raw) {
        if ("1".equals(raw)) return true;
        if ("0".equals(raw)) return false;
        throw new IllegalArgumentException("Invalid bit");
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PresencePolicy o)) return false;
        return shareAppPresence == o.shareAppPresence
                && continuousLocationEnabled == o.continuousLocationEnabled
                && preciseLocation == o.preciseLocation
                && locationPaused == o.locationPaused
                && proactiveNudgesEnabled == o.proactiveNudgesEnabled
                && maxNudgesPerHour == o.maxNudgesPerHour;
    }

    @Override public int hashCode() {
        return Objects.hash(shareAppPresence, continuousLocationEnabled, preciseLocation,
                locationPaused, proactiveNudgesEnabled, maxNudgesPerHour);
    }
}
