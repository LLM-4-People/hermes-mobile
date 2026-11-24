package org.llm4people.hermes.settings;

import android.app.UiModeManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public void willRecreateDetectsPolarityTransitionsIncludingReturnToSystem() {
        // Light system device: Light -> Dark triggers recreate; Dark -> System returns to Light and triggers recreate.
        assertTrue(AppThemeMode.willRecreateOnModeChange(false, "dark", false));
        assertTrue(AppThemeMode.willRecreateOnModeChange(true, "system", false));
        assertFalse(AppThemeMode.willRecreateOnModeChange(false, "light", false));
        assertFalse(AppThemeMode.willRecreateOnModeChange(false, "system", false));

        // Dark system device: Dark -> System stays Dark (no recreate); Dark -> Light triggers recreate.
        assertFalse(AppThemeMode.willRecreateOnModeChange(true, "system", true));
        assertTrue(AppThemeMode.willRecreateOnModeChange(true, "light", true));
        assertTrue(AppThemeMode.willRecreateOnModeChange(false, "system", true));
    }

    @Test
    public void commitModePersistsSynchronouslyViaCommitRatherThanAsyncApply() {
        Map<String, String> backing = new HashMap<>();
        AtomicBoolean commitCalled = new AtomicBoolean(false);
        AtomicBoolean applyCalled = new AtomicBoolean(false);

        SharedPreferences prefs = new SharedPreferences() {
            @Override public Map<String, ?> getAll() { return backing; }
            @Override public String getString(String key, String defValue) { return backing.getOrDefault(key, defValue); }
            @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
            @Override public int getInt(String key, int defValue) { return defValue; }
            @Override public long getLong(String key, long defValue) { return defValue; }
            @Override public float getFloat(String key, float defValue) { return defValue; }
            @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
            @Override public boolean contains(String key) { return backing.containsKey(key); }
            @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
            @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
            @Override public Editor edit() {
                Map<String, String> staged = new HashMap<>();
                return new Editor() {
                    @Override public Editor putString(String key, String value) { staged.put(key, value); return this; }
                    @Override public Editor putStringSet(String key, Set<String> values) { return this; }
                    @Override public Editor putInt(String key, int value) { return this; }
                    @Override public Editor putLong(String key, long value) { return this; }
                    @Override public Editor putFloat(String key, float value) { return this; }
                    @Override public Editor putBoolean(String key, boolean value) { return this; }
                    @Override public Editor remove(String key) { staged.remove(key); return this; }
                    @Override public Editor clear() { staged.clear(); return this; }
                    @Override public boolean commit() {
                        commitCalled.set(true);
                        backing.putAll(staged);
                        return true;
                    }
                    @Override public void apply() {
                        applyCalled.set(true);
                    }
                };
            }
        };

        assertTrue(AppThemeMode.commitMode(prefs, " DARK "));
        assertTrue("commitMode must call commit() synchronously", commitCalled.get());
        assertFalse("commitMode must not call async apply()", applyCalled.get());
        assertEquals("dark", backing.get("mode"));

        assertTrue(AppThemeMode.commitMode(prefs, "unknown"));
        assertEquals("system", backing.get("mode"));
        assertFalse(AppThemeMode.commitMode(null, "dark"));
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
