package org.llm4people.hermes.profile;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure-JVM fail-closed regression tests for corrupt SharedPreferences in IncomingShare and IncomingMediaShare. */
public final class CorruptSharePrefsFailClosedTest {

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
    public void incomingShareAndMediaShareDiscardCorruptIdPrefsWithoutThrowing() {
        boolean[] textCommitted = {false};
        SharedPreferences corruptTextPrefs = corruptPrefs("id", 42, textCommitted);
        assertNull(IncomingShare.pendingFromPrefs(corruptTextPrefs, 1_000_000L));
        assertTrue("Corrupt IncomingShare record must be cleared fail-closed", textCommitted[0]);
        assertTrue(Diagnostics.exportRedacted().contains("share.text_pref_corrupt"));

        boolean[] mediaCommitted = {false};
        SharedPreferences corruptMediaPrefs = corruptPrefs("id", 99, mediaCommitted);
        assertNull(IncomingMediaShare.pendingIdFromPrefs(corruptMediaPrefs, 1_000_000L));
        assertTrue("Corrupt IncomingMediaShare record must be cleared fail-closed", mediaCommitted[0]);
        assertTrue(Diagnostics.exportRedacted().contains("share.media_pref_corrupt"));
    }

    @Test
    public void incomingShareAndMediaShareTreatCorruptExpiryAsExpiredAndClearFailClosed() {
        boolean[] textCommitted = {false};
        SharedPreferences badTextExpiry = corruptExpiryPrefs("receipt-1", textCommitted);
        assertNull(IncomingShare.pendingFromPrefs(badTextExpiry, 1_000_000L));
        assertTrue(textCommitted[0]);
        assertTrue(Diagnostics.exportRedacted().contains("share.text_expiry_corrupt"));

        boolean[] mediaCommitted = {false};
        SharedPreferences badMediaExpiry = corruptExpiryPrefs("receipt-2", mediaCommitted);
        assertNull(IncomingMediaShare.pendingIdFromPrefs(badMediaExpiry, 1_000_000L));
        assertTrue(mediaCommitted[0]);
        assertTrue(Diagnostics.exportRedacted().contains("share.media_expiry_corrupt"));
    }

    private static SharedPreferences corruptPrefs(String key, Object value, boolean[] committed) {
        SharedPreferences.Editor editor = editorProxy(committed);
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> {
                        if (key.equals(args[0])) throw new ClassCastException("stored non-string");
                        yield args[1];
                    }
                    case "getAll" -> Collections.singletonMap(key, value);
                    case "edit" -> editor;
                    default -> null;
                });
    }

    private static SharedPreferences corruptExpiryPrefs(String idValue, boolean[] committed) {
        SharedPreferences.Editor editor = editorProxy(committed);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("id", idValue);
        snapshot.put("expires", 12345L);
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> {
                        if ("id".equals(args[0])) yield idValue;
                        if ("expires".equals(args[0])) throw new ClassCastException("stored long instead of string");
                        yield args[1];
                    }
                    case "getAll" -> snapshot;
                    case "edit" -> editor;
                    default -> null;
                });
    }

    private static SharedPreferences.Editor editorProxy(boolean[] committed) {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "putString", "putInt", "putLong", "putBoolean", "putFloat", "putStringSet",
                            "remove", "clear" -> proxy;
                    case "commit" -> {
                        committed[0] = true;
                        yield true;
                    }
                    default -> null;
                });
    }
}
