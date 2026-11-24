package org.llm4people.hermes.os;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.lang.reflect.Proxy;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure-JVM fail-closed regression test for corrupt SharedPreferences entries in HermesWidgetStore. */
public final class HermesWidgetStoreCorruptPrefsTest {

    @Before
    public void setUp() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void tearDown() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void sharedPrefsBackingReturnsNullAndLogsWarningOnClassCastException() {
        SharedPreferences corruptPrefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> throw new ClassCastException("corrupt int");
                    case "getAll" -> Collections.singletonMap("spec.w1", 7);
                    default -> null;
                });

        HermesWidgetStore.SharedPrefsBacking backing = new HermesWidgetStore.SharedPrefsBacking(corruptPrefs);
        assertNull(backing.get("spec.w1"));
        assertTrue(Diagnostics.exportRedacted().contains("widget.pref_read_corrupt"));

        // Store purgeProfile must complete cleanly with 0 removed instead of crashing profile forget.
        HermesWidgetStore store = new HermesWidgetStore(backing);
        assertEquals(0, store.purgeProfile("profile-a"));
    }
}
