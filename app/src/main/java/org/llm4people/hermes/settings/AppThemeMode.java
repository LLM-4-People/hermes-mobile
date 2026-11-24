package org.llm4people.hermes.settings;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.artifact.ThemePalette;

import java.util.Locale;

/**
 * Single Source of Truth for live native Light / Dark / System theme polarity across Android versions.
 * Applies {@link UiModeManager#setApplicationNightMode(int)} on API 31+ and wraps Activity
 * {@link Configuration#uiMode} via {@link #wrapContext(Context)} + {@link Activity#recreate()} on API 28–30.
 */
public final class AppThemeMode {
    public static final String SYSTEM = AppearanceSettingsModel.THEME_SYSTEM;
    public static final String LIGHT = AppearanceSettingsModel.THEME_LIGHT;
    public static final String DARK = AppearanceSettingsModel.THEME_DARK;

    private static final String PREFS = "hermes.app.theme.v1";
    private static final String KEY_MODE = "mode";

    private AppThemeMode() {}

    /** Normalize any raw theme mode string strictly to {@code system}, {@code light}, or {@code dark}. */
    public static String normalize(String raw) {
        if (raw == null) return SYSTEM;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case LIGHT -> LIGHT;
            case DARK -> DARK;
            default -> SYSTEM;
        };
    }

    /** Resolve effective night polarity for the given mode and current system dark-mode flag. */
    public static boolean resolveNight(String mode, boolean systemNight) {
        String normalized = normalize(mode);
        if (DARK.equals(normalized)) return true;
        if (LIGHT.equals(normalized)) return false;
        return systemNight;
    }

    /** Returns true when switching to {@code nextMode} changes the Activity's effective Day/Night polarity. */
    public static boolean willRecreateOnModeChange(boolean currentActivityNight, String nextMode, boolean systemNight) {
        return resolveNight(nextMode, systemNight) != currentActivityNight;
    }

    /** Compute the updated {@link Configuration#uiMode} bitmask for API 28–30 context wrapping. */
    public static int computeUiMode(int currentUiMode, String mode) {
        String normalized = normalize(mode);
        int base = currentUiMode & ~Configuration.UI_MODE_NIGHT_MASK;
        return switch (normalized) {
            case DARK -> base | Configuration.UI_MODE_NIGHT_YES;
            case LIGHT -> base | Configuration.UI_MODE_NIGHT_NO;
            default -> currentUiMode;
        };
    }

    /** Map normalized mode to {@link UiModeManager} night-mode constant for API 31+. */
    public static int toUiModeManagerNightMode(String mode) {
        return switch (normalize(mode)) {
            case DARK -> UiModeManager.MODE_NIGHT_YES;
            case LIGHT -> UiModeManager.MODE_NIGHT_NO;
            default -> UiModeManager.MODE_NIGHT_AUTO;
        };
    }

    /** Read the persisted app theme polarity ({@code system}, {@code light}, or {@code dark}). */
    public static String readMode(Context context) {
        if (context == null) return SYSTEM;
        try {
            return normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_MODE, SYSTEM));
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("theme", "read_mode_failed", unavailable);
            return SYSTEM;
        }
    }

    /** Persist the selected theme mode synchronously and apply it via {@link UiModeManager} on API 31+. */
    public static String apply(Context context, String mode) {
        String normalized = normalize(mode);
        if (context == null) return normalized;
        try {
            if (!commitMode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), normalized)) {
                Diagnostics.warning("theme", "save_mode_failed");
            }
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("theme", "save_mode_failed", unavailable);
        }
        applyPlatformNightMode(context, normalized);
        Diagnostics.event("theme", "mode_" + normalized);
        return normalized;
    }

    /** Synchronously commit the normalized theme mode so an immediate process kill never drops the preference. */
    static boolean commitMode(SharedPreferences prefs, String mode) {
        if (prefs == null) return false;
        return prefs.edit().putString(KEY_MODE, normalize(mode)).commit();
    }

    /** Re-apply any stored night-mode override during Activity startup (API 31+). */
    public static void applyStoredOnStartup(Context context) {
        if (context == null) return;
        applyPlatformNightMode(context, readMode(context));
    }

    /**
     * Persist and apply {@code mode} live to {@code activity}.
     * Returns {@code true} when an Activity recreation will occur (either triggered natively by
     * {@link UiModeManager#setApplicationNightMode(int)} on API 31+ or via {@link Activity#recreate()}
     * on API 28–30) so callers can skip redundant synchronous re-renders on the outgoing Activity.
     */
    public static boolean applyAndRecreateIfNeeded(Activity activity, String mode) {
        if (activity == null) return false;
        boolean wasNight = ThemePalette.isNight(activity);
        String normalized = apply(activity, mode);
        boolean willRecreate = willRecreateOnModeChange(wasNight, normalized, isSystemNight())
                && !activity.isFinishing() && !activity.isDestroyed();
        if (willRecreate && Build.VERSION.SDK_INT < 31) {
            activity.recreate();
        }
        return willRecreate;
    }

    /** Wrap an Activity base context on API 28–30 so Light/Dark overrides resolve matching night resources. */
    public static Context wrapContext(Context base) {
        if (base == null) return null;
        if (Build.VERSION.SDK_INT >= 31) return base;
        String mode = readMode(base);
        if (SYSTEM.equals(mode)) return base;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = computeUiMode(config.uiMode, mode);
        return base.createConfigurationContext(config);
    }

    private static void applyPlatformNightMode(Context context, String normalizedMode) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            UiModeManager manager = context.getSystemService(UiModeManager.class);
            if (manager != null) {
                manager.setApplicationNightMode(toUiModeManagerNightMode(normalizedMode));
            }
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("theme", "platform_apply_failed", unavailable);
        }
    }

    /** Read the true device/system dark-mode polarity unaffected by per-Activity overrides. */
    public static boolean isSystemNight() {
        try {
            int uiMode = Resources.getSystem().getConfiguration().uiMode;
            return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
