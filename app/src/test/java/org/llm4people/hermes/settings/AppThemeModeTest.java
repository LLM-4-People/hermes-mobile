package org.llm4people.hermes.settings;

import android.app.UiModeManager;
import android.content.res.Configuration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppThemeModeTest {

    @Test
    public void normalizeHandlesValidMixedCaseAndInvalidInputs() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.normalize(null));
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.normalize(""));
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.normalize("  "));
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.normalize("sepia"));
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.normalize("SYSTEM"));
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.normalize(" Light "));
        assertEquals(AppThemeMode.DARK, AppThemeMode.normalize("DARK"));
    }

    @Test
    public void resolveNightFollowsExplicitOverrideOrSystemPolarity() {
        assertTrue(AppThemeMode.resolveNight("dark", false));
        assertTrue(AppThemeMode.resolveNight("dark", true));
        assertFalse(AppThemeMode.resolveNight("light", true));
        assertFalse(AppThemeMode.resolveNight("light", false));
        assertTrue(AppThemeMode.resolveNight("system", true));
        assertFalse(AppThemeMode.resolveNight("system", false));
        assertTrue(AppThemeMode.resolveNight("invalid", true));
    }

    @Test
    public void computeUiModePreservesNonNightFlagsAndSwitchesPolarity() {
        int base = Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO;
        int dark = AppThemeMode.computeUiMode(base, "dark");
        assertEquals(Configuration.UI_MODE_NIGHT_YES, dark & Configuration.UI_MODE_NIGHT_MASK);
        assertEquals(Configuration.UI_MODE_TYPE_NORMAL, dark & Configuration.UI_MODE_TYPE_MASK);

        int light = AppThemeMode.computeUiMode(dark, "light");
        assertEquals(Configuration.UI_MODE_NIGHT_NO, light & Configuration.UI_MODE_NIGHT_MASK);
        assertEquals(Configuration.UI_MODE_TYPE_NORMAL, light & Configuration.UI_MODE_TYPE_MASK);

        assertEquals(dark, AppThemeMode.computeUiMode(dark, "system"));
    }

    @Test
    public void toUiModeManagerNightModeMapsAllModes() {
        assertEquals(UiModeManager.MODE_NIGHT_YES, AppThemeMode.toUiModeManagerNightMode("dark"));
        assertEquals(UiModeManager.MODE_NIGHT_NO, AppThemeMode.toUiModeManagerNightMode("light"));
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, AppThemeMode.toUiModeManagerNightMode("system"));
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, AppThemeMode.toUiModeManagerNightMode(null));
    }
}
