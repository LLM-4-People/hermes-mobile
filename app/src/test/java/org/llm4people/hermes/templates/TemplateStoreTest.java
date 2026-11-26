package org.llm4people.hermes.templates;

import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class TemplateStoreTest {
    private static final class MemoryBacking implements TemplateStore.BackingStore {
        final Map<String, String> map = new HashMap<>();
        @Override public String read(String key) { return map.get(key); }
        @Override public void write(String key, String value) { map.put(key, value); }
        @Override public void remove(String key) { map.remove(key); }
    }

    @Test public void saveEditDeleteAndWipePreserveMultilineDraftsAndEscapes() throws Exception {
        MemoryBacking backing = new MemoryBacking();
        TemplateStore store = new TemplateStore(backing);
        assertTrue(store.listCustom().isEmpty());

        String multiline = "Ligne 1\n\tLigne 2 avec \\ backslash\nLigne 3";
        WorkflowTemplate created = store.saveCustom(null, "Bilan du vendredi", "Résumé hebdo", multiline);
        assertFalse(created.builtIn);
        assertTrue(WorkflowTemplate.validId(created.id));

        List<WorkflowTemplate> loaded = store.listCustom();
        assertEquals(1, loaded.size());
        assertEquals(created, loaded.get(0));
        assertEquals(multiline, loaded.get(0).draft);

        WorkflowTemplate updated = store.saveCustom(created.id, "Bilan mis à jour", "", "Nouveau brouillon");
        assertEquals(created.id, updated.id);
        assertEquals(1, store.listCustom().size());
        assertEquals("Bilan mis à jour", store.listCustom().get(0).title);
        assertEquals("", store.listCustom().get(0).subtitle);

        assertFalse(store.deleteCustom("nonexistent_id"));
        assertTrue(store.deleteCustom(created.id));
        assertTrue(store.listCustom().isEmpty());
        assertNull(backing.read(TemplateStore.STORAGE_KEY));
    }

    @Test public void rejectsSecretsControlCharsOversizedAndInvalidIds() throws Exception {
        TemplateStore store = new TemplateStore(new MemoryBacking());

        for (String secret : new String[]{
                "Utilise ma clé sk-proj-1234567890abcdef",
                "Token ghp_1234567890abcdefghij",
                "Authorization: Bearer abcdef1234567890token",
                "JWT eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ",
                "-----BEGIN RSA PRIVATE KEY-----\nabc"
        }) {
            try {
                store.saveCustom(null, "Test", "", secret);
                fail("Expected SecretRejectedException for: " + secret);
            } catch (WorkflowTemplate.SecretRejectedException expected) {
                // expected
            }
        }
        assertTrue(store.listCustom().isEmpty());

        // Blank, invisible-only, bidi/zero-width/control/surrogate, and oversized inputs
        assertIoRejected(() -> store.saveCustom(null, "   ", "", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "\u3164\u00A0", "", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title\u202Espoof", "", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title\u2067isolate", "", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title\u200Bzwsp", "", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title\uD800lone", "", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title", "Sub\u202Ebidi", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title", "", "   "));
        assertIoRejected(() -> store.saveCustom(null, "Title\nLine2", "", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title", "", "Draft\u0000nul"));
        assertIoRejected(() -> store.saveCustom(null, "a".repeat(WorkflowTemplate.MAX_TITLE_CHARS + 1), "", "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title", "b".repeat(WorkflowTemplate.MAX_SUBTITLE_CHARS + 1), "Draft"));
        assertIoRejected(() -> store.saveCustom(null, "Title", "", "c".repeat(WorkflowTemplate.MAX_DRAFT_CHARS + 1)));
        assertIoRejected(() -> store.saveCustom("../escape", "Title", "", "Draft"));
    }

    @Test public void enforcesCustomLimitAndIsolatesProfilesAndFailsClosedOnCorruption() throws Exception {
        MemoryBacking profileA = new MemoryBacking();
        MemoryBacking profileB = new MemoryBacking();
        TemplateStore storeA = new TemplateStore(profileA);
        TemplateStore storeB = new TemplateStore(profileB);

        WorkflowTemplate first = null;
        for (int i = 0; i < WorkflowTemplate.MAX_CUSTOM_TEMPLATES; i++) {
            WorkflowTemplate saved = storeA.saveCustom(null, "Modèle " + i, "Sous " + i, "Brouillon " + i);
            if (first == null) first = saved;
        }
        assertEquals(WorkflowTemplate.MAX_CUSTOM_TEMPLATES, storeA.listCustom().size());
        assertTrue(storeB.listCustom().isEmpty());

        // Adding beyond limit throws LimitExceededException.
        try {
            storeA.saveCustom(null, "Un de trop", "", "Brouillon");
            fail("Expected LimitExceededException");
        } catch (TemplateStore.LimitExceededException expected) {}

        // Updating an existing template at limit still succeeds.
        storeA.saveCustom(first.id, "Modèle 0 édité", "", "Brouillon édité");
        assertEquals("Modèle 0 édité", storeA.listCustom().get(0).title);

        // Corrupted payload fails closed.
        profileB.write(TemplateStore.STORAGE_KEY, "v99\nbroken");
        assertIoRejected(storeB::listCustom);
    }

    private interface IoAction { void run() throws IOException; }

    private static void assertIoRejected(IoAction action) {
        try {
            action.run();
            fail("Expected IOException");
        } catch (IOException expected) {}
    }
}
