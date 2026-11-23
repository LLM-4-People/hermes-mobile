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

    public enum Window {
        IMMEDIATE("0s", 0L, R.string.security_grace_0s),
        SECONDS_30("30s", 30_000L, R.string.security_grace_30s),
        MINUTES_2("2m", 120_000L, R.string.security_grace_2m),
        MINUTES_5("5m", 300_000L, R.string.security_grace_5m);

        public final String key;
        public final long millis;
        public final int labelRes;

        Window(String key, long millis, int labelRes) {
            this.key = key;
            this.millis = millis;
            this.labelRes = labelRes;
        }

        public static Window fromKey(String raw) {
            if (raw == null || raw.isEmpty()) return IMMEDIATE;
            for (Window value : values()) {
                if (value.key.equals(raw)) return value;
            }
            return IMMEDIATE;
        }
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
