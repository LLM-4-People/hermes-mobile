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
        storeA.putStringList("thread_1", "page/suivi", "etiquettes", Arrays.asList("Urgent", "Code, revue"));
        Map<String, Object> withNote = storeA.load("thread_1", "page/suivi");
        withNote.put("note", "Ligne 1, virgule\nLigne 2\\fin");
        storeA.save("thread_1", "page/suivi", withNote);

        // Reopen with fresh store handle on profile A (simulates restart).
        InstrumentStateStore reopenedA = new InstrumentStateStore(profileA);
        Map<String, Object> loadedA = reopenedA.load("thread_1", "page/suivi");
        assertEquals(Boolean.TRUE, loadedA.get("verifie"));
        assertEquals(3.5, ((Number) loadedA.get("priorite")).doubleValue(), 0.0001);
        assertEquals("Ligne 1, virgule\nLigne 2\\fin", loadedA.get("note"));
        assertEquals(Arrays.asList("Urgent", "Code, revue"), loadedA.get("etiquettes"));

        // Profile B must not observe any state from Profile A.
        assertTrue(storeB.load("thread_1", "page/suivi").isEmpty());

        // Different scope on same profile is also isolated.
        assertTrue(reopenedA.load("thread_2", "page/suivi").isEmpty());

        reopenedA.save("thread_1", "page/suivi", Collections.emptyMap());
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
            store.putBoolean("atelier", "page/suivi", "BAD_ID", true);
            fail("Expected invalid field id rejection");
        } catch (IOException expected) {}
        try {
            store.putStringList("atelier", "page/suivi", "tags",
                    Collections.singletonList("a".repeat(InstrumentStateStore.MAX_STRING_LEN + 1)));
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
