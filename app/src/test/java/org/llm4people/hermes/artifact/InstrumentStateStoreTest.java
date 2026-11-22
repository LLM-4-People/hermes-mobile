package org.llm4people.hermes.artifact;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class InstrumentStateStoreTest {
    private static final class MemoryStore implements InstrumentStateStore.BackingStore {
        final Map<String, String> data = new HashMap<>();
        @Override public String read(String key) { return data.get(key); }
        @Override public void write(String key, String value) { data.put(key, value); }
        @Override public void remove(String key) { data.remove(key); }
    }

    @Test public void persistsTypedValuesAcrossRestartsAndIsolatesProfiles() throws Exception {
        MemoryStore profileA = new MemoryStore();
        MemoryStore profileB = new MemoryStore();

        InstrumentStateStore storeA = new InstrumentStateStore(profileA);
        InstrumentStateStore storeB = new InstrumentStateStore(profileB);

        storeA.putBoolean("thread_1", "page/suivi", "verifie", true);
        storeA.putNumber("thread_1", "page/suivi", "priorite", 3.5);
        storeA.putString("thread_1", "page/suivi", "note", "Ligne 1, virgule\nLigne 2\\fin");
        storeA.putStringList("thread_1", "page/suivi", "etiquettes", Arrays.asList("Urgent", "Code, revue"));

        // Reopen with fresh store handle on profile A (simulates restart).
        InstrumentStateStore reopenedA = new InstrumentStateStore(profileA);
        assertTrue(reopenedA.getBoolean("thread_1", "page/suivi", "verifie", false));
        assertEquals(3.5, reopenedA.getNumber("thread_1", "page/suivi", "priorite", 0), 0.0001);
        assertEquals("Ligne 1, virgule\nLigne 2\\fin", reopenedA.getString("thread_1", "page/suivi", "note", ""));
        assertEquals(Arrays.asList("Urgent", "Code, revue"),
                reopenedA.getStringList("thread_1", "page/suivi", "etiquettes", Collections.emptyList()));

        // Profile B must not observe any state from Profile A.
        assertFalse(storeB.getBoolean("thread_1", "page/suivi", "verifie", false));
        assertEquals(0.0, storeB.getNumber("thread_1", "page/suivi", "priorite", 0), 0.0001);
        assertTrue(storeB.load("thread_1", "page/suivi").isEmpty());

        // Different scope on same profile is also isolated.
        assertTrue(reopenedA.load("thread_2", "page/suivi").isEmpty());

        reopenedA.clear("thread_1", "page/suivi");
        assertTrue(reopenedA.load("thread_1", "page/suivi").isEmpty());
    }

    @Test public void rejectsInvalidSegmentsBoundsAndRecoversCleanlyFromCorruptData() throws Exception {
        MemoryStore backing = new MemoryStore();
        InstrumentStateStore store = new InstrumentStateStore(backing);

        for (String badScope : new String[]{"", "../other", "a b", "scope/slash"}) {
            try {
                store.putBoolean(badScope, "page/suivi", "ok", true);
                fail("Expected rejection for scope " + badScope);
            } catch (IOException expected) {}
        }
        try {
            store.putNumber("atelier", "page/suivi", "score", Double.NaN);
            fail("Expected NaN rejection");
        } catch (IOException expected) {}
        try {
            store.putNumber("atelier", "page/suivi", "score", Double.POSITIVE_INFINITY);
            fail("Expected Infinity rejection");
        } catch (IOException expected) {}
        try {
            store.putString("atelier", "page/suivi", "BAD_ID", "x");
            fail("Expected invalid field id rejection");
        } catch (IOException expected) {}
        try {
            store.putString("atelier", "page/suivi", "note", "a".repeat(InstrumentStateStore.MAX_STRING_LEN + 1));
            fail("Expected oversized string rejection");
        } catch (IOException expected) {}
        try {
            List<String> tooMany = Collections.nCopies(InstrumentStateStore.MAX_LIST_ITEMS + 1, "x");
            store.putStringList("atelier", "page/suivi", "tags", tooMany);
            fail("Expected oversized list rejection");
        } catch (IOException expected) {}

        // Corrupt persisted record must fail closed (return empty map without crashing).
        backing.write(InstrumentStateStore.storageKey("atelier", "page/suivi"), "corrupt-line-without-equals");
        assertTrue(store.load("atelier", "page/suivi").isEmpty());
    }
}
