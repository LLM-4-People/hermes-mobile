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
    public static final int DEFAULT_READ_GRACE_SEC = 45;
    public static final int MIN_READ_GRACE_SEC = 5;
    public static final int MAX_READ_GRACE_SEC = 600;
    public static final int DEFAULT_MAX_NUDGES_PER_HOUR = 3;
    public static final int MIN_MAX_NUDGES_PER_HOUR = 1;
    public static final int MAX_MAX_NUDGES_PER_HOUR = 10;

    public final boolean shareAppPresence;
    public final boolean shareReadWithoutReply;
    public final boolean shareDraftPreview;
    public final boolean continuousLocationEnabled;
    public final boolean preciseLocation;
    public final boolean locationPaused;
    public final boolean proactiveNudgesEnabled;
    public final int readWithoutReplyGraceSec;
    public final int maxNudgesPerHour;

    public PresencePolicy(
            boolean shareAppPresence,
            boolean shareReadWithoutReply,
            boolean shareDraftPreview,
            boolean continuousLocationEnabled,
            boolean preciseLocation,
            boolean locationPaused,
            boolean proactiveNudgesEnabled,
            int readWithoutReplyGraceSec,
            int maxNudgesPerHour) {
        this.shareAppPresence = shareAppPresence;
        this.shareReadWithoutReply = shareReadWithoutReply;
        this.shareDraftPreview = shareAppPresence && shareDraftPreview;
        this.continuousLocationEnabled = continuousLocationEnabled;
        this.preciseLocation = continuousLocationEnabled && preciseLocation;
        this.locationPaused = continuousLocationEnabled && locationPaused;
        this.proactiveNudgesEnabled = proactiveNudgesEnabled;
        this.readWithoutReplyGraceSec = clamp(readWithoutReplyGraceSec, MIN_READ_GRACE_SEC, MAX_READ_GRACE_SEC);
        this.maxNudgesPerHour = clamp(maxNudgesPerHour, MIN_MAX_NUDGES_PER_HOUR, MAX_MAX_NUDGES_PER_HOUR);
    }

    /** Fail-closed default: every sharing and sensor toggle is OFF. */
    public static PresencePolicy defaults() {
        return new PresencePolicy(
                false, false, false, false, false, false, false,
                DEFAULT_READ_GRACE_SEC, DEFAULT_MAX_NUDGES_PER_HOUR);
    }

    public boolean allowsLiveLocation() {
        return continuousLocationEnabled && !locationPaused;
    }

    public boolean anySharingEnabled() {
        return shareAppPresence || shareReadWithoutReply || allowsLiveLocation() || proactiveNudgesEnabled;
    }

    public PresencePolicy withAppPresence(boolean enabled) {
        return new PresencePolicy(enabled, shareReadWithoutReply, enabled && shareDraftPreview,
                continuousLocationEnabled, preciseLocation, locationPaused, proactiveNudgesEnabled,
                readWithoutReplyGraceSec, maxNudgesPerHour);
    }

    public PresencePolicy withReadWithoutReply(boolean enabled) {
        return new PresencePolicy(shareAppPresence, enabled, shareDraftPreview,
                continuousLocationEnabled, preciseLocation, locationPaused, proactiveNudgesEnabled,
                readWithoutReplyGraceSec, maxNudgesPerHour);
    }

    public PresencePolicy withDraftPreview(boolean enabled) {
        return new PresencePolicy(shareAppPresence, shareReadWithoutReply, enabled,
                continuousLocationEnabled, preciseLocation, locationPaused, proactiveNudgesEnabled,
                readWithoutReplyGraceSec, maxNudgesPerHour);
    }

    public PresencePolicy withContinuousLocation(boolean enabled) {
        return new PresencePolicy(shareAppPresence, shareReadWithoutReply, shareDraftPreview,
                enabled, enabled && preciseLocation, false, proactiveNudgesEnabled,
                readWithoutReplyGraceSec, maxNudgesPerHour);
    }

    public PresencePolicy withPreciseLocation(boolean precise) {
        return new PresencePolicy(shareAppPresence, shareReadWithoutReply, shareDraftPreview,
                continuousLocationEnabled, precise, locationPaused, proactiveNudgesEnabled,
                readWithoutReplyGraceSec, maxNudgesPerHour);
    }

    public PresencePolicy withLocationPaused(boolean paused) {
        return new PresencePolicy(shareAppPresence, shareReadWithoutReply, shareDraftPreview,
                continuousLocationEnabled, preciseLocation, paused, proactiveNudgesEnabled,
                readWithoutReplyGraceSec, maxNudgesPerHour);
    }

    public PresencePolicy withProactiveNudges(boolean enabled) {
        return new PresencePolicy(shareAppPresence, shareReadWithoutReply, shareDraftPreview,
                continuousLocationEnabled, preciseLocation, locationPaused, enabled,
                readWithoutReplyGraceSec, maxNudgesPerHour);
    }

    public PresencePolicy withReadGraceSec(int graceSec) {
        return new PresencePolicy(shareAppPresence, shareReadWithoutReply, shareDraftPreview,
                continuousLocationEnabled, preciseLocation, locationPaused, proactiveNudgesEnabled,
                graceSec, maxNudgesPerHour);
    }

    public PresencePolicy withMaxNudgesPerHour(int maxPerHour) {
        return new PresencePolicy(shareAppPresence, shareReadWithoutReply, shareDraftPreview,
                continuousLocationEnabled, preciseLocation, locationPaused, proactiveNudgesEnabled,
                readWithoutReplyGraceSec, maxPerHour);
    }

    /** Compact pure-Java serialization (host-JVM testable, zero PII). */
    public String encode() {
        return "v1|" + bit(shareAppPresence)
                + "|" + bit(shareReadWithoutReply)
                + "|" + bit(shareDraftPreview)
                + "|" + bit(continuousLocationEnabled)
                + "|" + bit(preciseLocation)
                + "|" + bit(locationPaused)
                + "|" + bit(proactiveNudgesEnabled)
                + "|" + readWithoutReplyGraceSec
                + "|" + maxNudgesPerHour;
    }

    /** Fail-closed decode: missing or malformed records always return all-OFF {@link #defaults()}. */
    public static PresencePolicy decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return defaults();
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 10 || !"v1".equals(parts[0])) {
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
                    parseBit(parts[6]),
                    parseBit(parts[7]),
                    Integer.parseInt(parts[8]),
                    Integer.parseInt(parts[9]));
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

    /** Atomic removal during profile forget/wipe. */
    public static void wipe(Account account) {
        if (account == null) return;
        try {
            account.remove(VAULT_KEY);
            Diagnostics.debug("presence", "policy_wiped");
        } catch (RuntimeException ignoredIfLocked) {
            Diagnostics.warning("presence", "policy_wipe_skipped");
        }
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
                && shareReadWithoutReply == o.shareReadWithoutReply
                && shareDraftPreview == o.shareDraftPreview
                && continuousLocationEnabled == o.continuousLocationEnabled
                && preciseLocation == o.preciseLocation
                && locationPaused == o.locationPaused
                && proactiveNudgesEnabled == o.proactiveNudgesEnabled
                && readWithoutReplyGraceSec == o.readWithoutReplyGraceSec
                && maxNudgesPerHour == o.maxNudgesPerHour;
    }

    @Override public int hashCode() {
        return Objects.hash(shareAppPresence, shareReadWithoutReply, shareDraftPreview,
                continuousLocationEnabled, preciseLocation, locationPaused, proactiveNudgesEnabled,
                readWithoutReplyGraceSec, maxNudgesPerHour);
    }
}
