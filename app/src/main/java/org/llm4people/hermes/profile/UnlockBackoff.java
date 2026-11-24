package org.llm4people.hermes.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.llm4people.hermes.Diagnostics;

import java.util.Collections;
import java.util.Objects;

/**
 * Persisted monotonic wrong-passphrase unlock backoff per profile.
 * Survives activity/process restarts via synchronous commits and boot-count checked elapsedRealtime
 * (with bounded wall-clock fallback across reboots). Cleared immediately on valid unlock or forget.
 */
public final class UnlockBackoff {
    private static final String PREFS = "hermes.profile.backoff.v1";
    private static final String PREFIX = "backoff.";
    public static final int FREE_ATTEMPTS = 2;
    public static final long MAX_COOLDOWN_MS = 300_000L;
    private static final long CORRUPT_COOLDOWN_MS = 5_000L;
    private static final long[] SCHEDULE_MS = {
            5_000L, 15_000L, 30_000L, 60_000L, 120_000L, MAX_COOLDOWN_MS
    };

    public interface Backing {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
    }

    public interface Clock {
        long elapsedMs();
        long wallMs();
        int bootCount();
    }

    private final Backing backing;
    private final Clock clock;

    public UnlockBackoff(Backing backing, Clock clock) {
        this.backing = Objects.requireNonNull(backing, "backing");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static UnlockBackoff forContext(Context context) {
        Context app = Objects.requireNonNull(context, "context").getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Backing backing = new Backing() {
            @Override public String get(String key) {
                synchronized (prefs) { return prefs.getString(key, null); }
            }
            @Override public void put(String key, String value) {
                synchronized (prefs) {
                    PreferenceTransaction.commit(prefs, Collections.singletonMap(key, value));
                }
            }
            @Override public void remove(String key) {
                synchronized (prefs) {
                    PreferenceTransaction.commit(prefs, Collections.singletonMap(key, null));
                }
            }
        };
        Clock clock = new Clock() {
            @Override public long elapsedMs() { return SystemClock.elapsedRealtime(); }
            @Override public long wallMs() { return System.currentTimeMillis(); }
            @Override public int bootCount() {
                return Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            }
        };
        return new UnlockBackoff(backing, clock);
    }

    public static void forget(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        forContext(context).reset(profileId);
    }

    /** Pure schedule helper: attempts 1..FREE_ATTEMPTS have zero delay, then exponential up to MAX_COOLDOWN_MS. */
    public static long cooldownForFailures(int failures) {
        if (failures <= FREE_ATTEMPTS) return 0L;
        int idx = Math.min(SCHEDULE_MS.length - 1, failures - FREE_ATTEMPTS - 1);
        return SCHEDULE_MS[idx];
    }

    /** Remaining lock duration in milliseconds (0 when unlocked to try). */
    public synchronized long remainingMs(String profileId) {
        if (profileId == null || profileId.isEmpty()) return 0L;
        String raw;
        try {
            raw = backing.get(PREFIX + profileId);
        } catch (ClassCastException corrupt) {
            warnCorrupt();
            return CORRUPT_COOLDOWN_MS;
        }
        if (raw == null || raw.isEmpty()) return 0L;
        Record parsed = parse(raw);
        if (parsed == null) {
            warnCorrupt();
            return CORRUPT_COOLDOWN_MS;
        }
        return remainingFor(parsed, clock.elapsedMs(), clock.wallMs(), clock.bootCount());
    }

    /** Current recorded consecutive failure count for {@code profileId}. */
    public synchronized int failureCount(String profileId) {
        if (profileId == null || profileId.isEmpty()) return 0;
        String raw;
        try {
            raw = backing.get(PREFIX + profileId);
        } catch (ClassCastException corrupt) {
            warnCorrupt();
            return FREE_ATTEMPTS + 1;
        }
        if (raw == null || raw.isEmpty()) return 0;
        Record parsed = parse(raw);
        return parsed == null ? FREE_ATTEMPTS + 1 : parsed.failures;
    }

    /** Record one failed unlock attempt and persist the resulting monotonic cooldown. Returns cooldown ms. */
    public synchronized long recordFailure(String profileId) {
        if (profileId == null || profileId.isEmpty()) return 0L;
        int prior = failureCount(profileId);
        int next = Math.min(100, prior + 1);
        long delay = cooldownForFailures(next);
        long nowElapsed = clock.elapsedMs();
        long nowWall = clock.wallMs();
        int boot = clock.bootCount();
        long untilElapsed = delay > 0L ? nowElapsed + delay : 0L;
        long untilWall = delay > 0L ? nowWall + delay : 0L;
        backing.put(PREFIX + profileId, encode(next, untilElapsed, untilWall, boot));
        return delay;
    }

    /** Clear failure history and cooldown immediately after valid unlock or profile removal. */
    public synchronized void reset(String profileId) {
        if (profileId == null || profileId.isEmpty()) return;
        backing.remove(PREFIX + profileId);
    }

    /** Plain, locale-neutral compact countdown formatting ("5 s", "2 min 10 s"). */
    public static String formatRemaining(long remainingMs) {
        long seconds = Math.max(1L, (remainingMs + 999L) / 1_000L);
        if (seconds < 60L) return seconds + " s";
        long mins = seconds / 60L;
        long rem = seconds % 60L;
        return rem == 0L ? mins + " min" : mins + " min " + rem + " s";
    }

    private record Record(int failures, long untilElapsedMs, long untilWallMs, int bootCount) {}

    private static long remainingFor(Record rec, long nowElapsed, long nowWall, int currentBoot) {
        if (rec.untilElapsedMs <= 0L && rec.untilWallMs <= 0L) return 0L;
        if (rec.bootCount >= 0 && currentBoot == rec.bootCount && rec.untilElapsedMs > 0L && nowElapsed >= 0L) {
            long rem = rec.untilElapsedMs - nowElapsed;
            return rem <= 0L ? 0L : Math.min(MAX_COOLDOWN_MS, rem);
        }
        if (rec.untilWallMs > 0L && nowWall > 0L) {
            long rem = rec.untilWallMs - nowWall;
            return rem <= 0L ? 0L : Math.min(MAX_COOLDOWN_MS, rem);
        }
        // Clock rollback or invalid wall/monotonic clock while a cooldown deadline exists: fail closed.
        long fallback = cooldownForFailures(rec.failures);
        return fallback > 0L ? Math.min(MAX_COOLDOWN_MS, fallback) : CORRUPT_COOLDOWN_MS;
    }

    private static String encode(int failures, long untilElapsed, long untilWall, int bootCount) {
        return failures + "|" + untilElapsed + "|" + untilWall + "|" + bootCount;
    }

    private static Record parse(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 4) return null;
        try {
            int failures = Integer.parseInt(parts[0]);
            long untilElapsed = Long.parseLong(parts[1]);
            long untilWall = Long.parseLong(parts[2]);
            int boot = Integer.parseInt(parts[3]);
            if (failures < 0 || untilElapsed < 0L || untilWall < 0L) return null;
            if (failures > FREE_ATTEMPTS && untilElapsed <= 0L && untilWall <= 0L) return null;
            return new Record(failures, untilElapsed, untilWall, boot);
        } catch (NumberFormatException corrupt) {
            return null;
        }
    }

    private static void warnCorrupt() {
        Diagnostics.warning("profile", "backoff_record_corrupt");
    }
}
