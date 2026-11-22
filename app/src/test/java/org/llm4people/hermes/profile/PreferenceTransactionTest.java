package org.llm4people.hermes.profile;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreferenceTransactionTest {
    @Test public void resetAndReplacementPreserveEveryPreferenceTypeOnFailure() {
        for (Object old : Arrays.asList(null, "ciphertext", true, 17, 32L, 0.25f, Set.of("A", "B"))) {
            for (String replacement : Arrays.asList(null, "new ciphertext")) {
                for (int failures : new int[]{1, 2}) {
                    Vault vault = new Vault(old);
                    Map<String, Object> original = new HashMap<>(vault.disk);
                    vault.failures = failures;
                    assertThrows(IllegalStateException.class, () -> PreferenceTransaction.commit(vault.prefs, Collections.singletonMap("target", replacement)));
                    assertEquals(original, vault.memory);
                    assertEquals(original, vault.disk);
                    assertEquals(2, vault.commits);
                    PreferenceTransaction.commit(vault.prefs, Collections.singletonMap("target", replacement));
                    if (replacement == null) original.remove("target"); else original.put("target", replacement);
                    assertEquals(original, vault.memory);
                    assertEquals(original, vault.disk);
                    assertEquals(old == null || old instanceof String ? 0 : 2, vault.mapReads);
                }
            }
        }
    }

    @Test public void failedMultiSlotUpdateRestoresOriginalValuesTogether() {
        Vault vault = new Vault(true);
        vault.memory.put("numeric", 14L);
        vault.disk.put("numeric", 14L);
        Map<String, Object> original = new HashMap<>(vault.disk);
        Map<String, String> updates = new HashMap<>();
        updates.put("target", null);
        updates.put("numeric", "replacement");
        updates.put("new", "new value");
        vault.failures = 1;
        assertThrows(IllegalStateException.class, () -> PreferenceTransaction.commit(vault.prefs, updates));
        assertEquals(original, vault.memory);
        assertEquals(original, vault.disk);
        assertEquals(1, vault.mapReads);
        assertEquals(2, vault.commits);
        PreferenceTransaction.commit(vault.prefs, updates);
        assertEquals(Map.of("numeric", "replacement", "new", "new value", "other", "protected"), vault.disk);
        assertEquals(vault.disk, vault.memory);
        assertEquals(3, vault.commits);
    }

    /** Android changes the process cache before reporting whether the disk commit succeeded. */
    private static final class Vault {
        final Map<String, Object> memory = new HashMap<>();
        final Map<String, Object> disk = new HashMap<>();
        final SharedPreferences prefs;
        int failures, commits, mapReads;

        Vault(Object old) {
            memory.put("other", "protected");
            if (old != null) memory.put("target", old);
            disk.putAll(memory);
            prefs = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getString": return (String) memory.getOrDefault(args[0], args[1]);
                            case "getAll": mapReads++; return new HashMap<>(memory);
                            case "edit": return editor();
                            default: throw new AssertionError(method);
                        }
                    });
        }

        SharedPreferences.Editor editor() {
            Map<String, Object> changes = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("commit")) {
                            commits++;
                            changes.forEach((key, value) -> {
                                if (value == null) memory.remove(key); else memory.put(key, value);
                            });
                            if (failures > 0) { failures--; return false; }
                            disk.clear();
                            disk.putAll(memory);
                            return true;
                        }
                        if (method.getName().equals("remove")) changes.put((String) args[0], null);
                        else if (method.getName().startsWith("put")) changes.put((String) args[0], args[1]);
                        else throw new AssertionError(method);
                        return proxy;
                    });
        }
    }
}
