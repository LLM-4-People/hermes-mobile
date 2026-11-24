package org.llm4people.hermes;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Single Source of Truth for parsing flexible wire timestamps (epoch seconds, epoch milliseconds,
 * ISO-8601 instants, and ISO-8601 offset timestamps) into positive Unix epoch seconds (or {@code 0L}).
 */
public final class EpochTimestamps {
    private static final long MILLIS_THRESHOLD = 10_000_000_000L;

    private EpochTimestamps() {}

    /**
     * Parses numeric epoch seconds/millis or ISO-8601 timestamps into epoch seconds, returning {@code 0L}
     * for null, blank, non-finite, negative, or unparseable inputs.
     */
    public static long parseEpochSeconds(Object value) {
        if (value instanceof Number n) {
            return normalizeNumeric(n.doubleValue());
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return 0L;
            try {
                return Math.max(0L, OffsetDateTime.parse(trimmed).toEpochSecond());
            } catch (RuntimeException ignored) {
                try {
                    return Math.max(0L, Instant.parse(trimmed).getEpochSecond());
                } catch (RuntimeException ignored2) {
                    try {
                        return normalizeNumeric(Double.parseDouble(trimmed));
                    } catch (NumberFormatException ignored3) {
                        return 0L;
                    }
                }
            }
        }
        return 0L;
    }

    private static long normalizeNumeric(double d) {
        if (!Double.isFinite(d) || d <= 0d) return 0L;
        long raw = (long) d;
        return raw > MILLIS_THRESHOLD ? raw / 1000L : raw;
    }
}
