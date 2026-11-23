package org.llm4people.hermes.os;

import android.content.Context;
import android.content.SharedPreferences;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.profile.PreferenceTransaction;

import java.util.Collections;

/**
 * Per-profile auto-lock grace policy. Defaults strictly to immediate locking (0s) and always locks
 * immediately on manual lock, expiry, clock anomalies, or unknown stored values.
 */
public final class LockGracePolicy {
    private static final String PREFS = "hermes.os.lock_policy";
    private static final String PREFIX = "grace.";

    public static final Window DEFAULT_FOR_NEW_PROFILE = Window.MINUTES_5;
    public static final long INTERNAL_TRANSITION_MIN_MS = 60_000L;
    public static final long INTERNAL_TRANSITION_MAX_MS = 300_000L;

    public enum Window {
        IMMEDIATE("0s", 0L, R.string.security_grace_0s),
        SECONDS_30("30s", 30_000L, R.string.security_grace_30s),
        MINUTES_1("1m", 60_000L, R.string.lock_grace_1m),
        MINUTES_2("2m", 120_000L, R.string.security_grace_2m),
        MINUTES_5("5m", 300_000L, R.string.security_grace_5m),
        MINUTES_15("15m", 900_000L, R.string.lock_grace_15m),
        MINUTES_30("30m", 1_800_000L, R.string.lock_grace_30m),
        SCREEN_LOCK("screen_lock", -1L, R.string.lock_grace_screen_lock);

        public final String key;
        public final long millis;
        public final int labelRes;

        Window(String key, long millis, int labelRes) {
            this.key = key;
            this.millis = millis;
            this.labelRes = labelRes;
        }

        public boolean locksOnScreenOffOnly() {
            return this == SCREEN_LOCK;
        }

        public static Window fromKey(String raw) {
            if (raw == null || raw.isEmpty()) return IMMEDIATE;
            for (Window value : values()) {
                if (value.key.equals(raw)) return value;
            }
            return IMMEDIATE;
        }
    }

    /** User-facing timeout options shown in Security Settings. */
    public static Window[] selectableWindows() {
        return new Window[]{
                Window.IMMEDIATE,
                Window.MINUTES_1,
                Window.MINUTES_5,
                Window.MINUTES_15,
                Window.MINUTES_30,
                Window.SCREEN_LOCK
        };
    }

    private LockGracePolicy() {}

    /** Fail-closed evaluation of whether returning from background must lock the unlocked profile. */
    public static boolean shouldLockOnReturn(long backgroundedAtElapsedMs, long nowElapsedMs,
                                             long graceMillis, boolean manualLockRequested) {
        if (manualLockRequested) return true;
        if (graceMillis <= 0L) return true;
        if (backgroundedAtElapsedMs < 0L || nowElapsedMs < backgroundedAtElapsedMs) return true;
        return (nowElapsedMs - backgroundedAtElapsedMs) >= graceMillis;
    }

    /**
     * Full intelligent auto-lock evaluation combining timeout window, screen-lock state, and
     * internal child-activity transitions (QR scanner, camera capture, photo/file pickers).
     * Manual lock and screen-off/device-lock ALWAYS fail closed (lock immediately).
     */
    public static boolean shouldLockOnReturn(
            Window window,
            long backgroundedAtElapsedMs,
            long nowElapsedMs,
            boolean manualLockRequested,
            boolean screenLockedOrOff,
            boolean internalTransition) {
        if (manualLockRequested || screenLockedOrOff) return true;
        if (internalTransition) return false;
        if (window == null || window == Window.IMMEDIATE) return true;
        if (window == Window.SCREEN_LOCK) return false;
        return shouldLockOnReturn(backgroundedAtElapsedMs, nowElapsedMs, window.millis, false);
    }

    /**
     * Bounded RAM-safety timeout for internal child activities (QR scanner, camera capture, photo/file
     * pickers, share chooser). Allows legitimate picker flows even under {@link Window#IMMEDIATE} while
     * ensuring an abandoned picker cannot keep keys unlocked indefinitely.
     */
    public static long internalTransitionTimeoutMillis(Window window) {
        if (window == null || window == Window.IMMEDIATE || window.millis <= INTERNAL_TRANSITION_MIN_MS) {
            if (window != null && window.locksOnScreenOffOnly()) return INTERNAL_TRANSITION_MAX_MS;
            return INTERNAL_TRANSITION_MIN_MS;
        }
        return Math.min(window.millis, INTERNAL_TRANSITION_MAX_MS);
    }

    /** Fail-closed expiry check for an in-flight internal child-activity transition. */
    public static boolean isInternalTransitionExpired(long startedAtElapsedMs, long nowElapsedMs, long timeoutMs) {
        if (timeoutMs <= 0L || startedAtElapsedMs < 0L || nowElapsedMs < startedAtElapsedMs) return true;
        return (nowElapsedMs - startedAtElapsedMs) >= timeoutMs;
    }

    public static long remainingMillis(long backgroundedAtElapsedMs, long nowElapsedMs, long graceMillis) {
        if (graceMillis <= 0L || backgroundedAtElapsedMs < 0L || nowElapsedMs < backgroundedAtElapsedMs) return 0L;
        long elapsed = nowElapsedMs - backgroundedAtElapsedMs;
        return elapsed >= graceMillis ? 0L : graceMillis - elapsed;
    }

    public static Window window(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return Window.IMMEDIATE;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            try {
                return Window.fromKey(prefs.getString(PREFIX + profileId, Window.IMMEDIATE.key));
            } catch (ClassCastException corrupt) {
                Diagnostics.warning("security", "grace_read_corrupt");
                return Window.IMMEDIATE;
            }
        }
    }

    public static void setWindow(Context context, String profileId, Window window) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        Window chosen = window == null ? Window.IMMEDIATE : window;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            prefs.edit().putString(PREFIX + profileId, chosen.key).apply();
        }
        Diagnostics.event("security", "grace_updated");
    }

    public static void forget(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            try {
                PreferenceTransaction.commit(prefs, Collections.singletonMap(PREFIX + profileId, null));
            } catch (IllegalStateException fallback) {
                Diagnostics.warning("security", "grace_forget_commit_failed");
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
