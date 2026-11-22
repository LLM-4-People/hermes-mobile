package org.llm4people.hermes.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.UUID;

/** Exercises real AndroidKeyStore and disk persistence through the same store used by ProfileGate. */
public final class AccountStorageTest extends InstrumentationTestCase {
    public void testUnlockScopedPersistenceAndRemoval() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        AccountStore store = new AccountStore(context);
        String prefix = UUID.randomUUID().toString();
        String secret = prefix + "-test-bearer";
        char[] pass = (prefix + "-phrase").toCharArray();
        Account first = store.create(prefix + " A", "https://example.org/p/a", secret, pass);
        try {
            assertTrue(ProfileGate.contains(context, first.id));
            assertFalse(ProfileGate.contains(context, null));
            first.write("draft", "message privé");
            SharedPreferences entries = context.getSharedPreferences("hermes.vault." + first.id, Context.MODE_PRIVATE);
            Map<String, ?> encrypted = entries.getAll();
            assertEquals(1, encrypted.size());
            assertFalse(encrypted.containsKey("draft"));
            assertFalse(encrypted.containsValue("message privé"));
            String envelope = context.getSharedPreferences("hermes.profiles", Context.MODE_PRIVATE).getString(first.id, "");
            assertFalse(envelope.contains(secret));
            first.close();
            try { first.read("draft"); fail("Locked record read"); } catch (IllegalStateException expected) {}
            try { store.open(first.id, "unrelated phrase".toCharArray()); fail("Wrong phrase accepted"); }
            catch (GeneralSecurityException expected) {}
            try (Account rotated = store.replaceBearer(first.id, pass, secret + "-new")) {
                assertEquals("message privé", rotated.read("draft"));
                assertEquals(first.endpoint, rotated.endpoint);
            }
            try (Account reopened = store.open(first.id, pass)) {
                assertEquals(secret + "-new", reopened.bearer);
                assertEquals("message privé", reopened.read("draft"));
                Account other = store.create(prefix + " B", "https://example.org/p/b", secret, pass);
                try {
                    assertNull(other.read("draft"));
                    other.write("draft", "autre message");
                    String slot = encrypted.keySet().iterator().next();
                    String foreign = (String) context.getSharedPreferences("hermes.vault." + other.id, Context.MODE_PRIVATE)
                            .getAll().values().iterator().next();
                    assertTrue(entries.edit().putString(slot, foreign).commit());
                    try { reopened.read("draft"); fail("Copied ciphertext accepted"); } catch (IllegalStateException expected) {}
                    reopened.remove("draft");
                    assertNull(reopened.read("draft"));
                } finally {
                    other.close();
                    store.forget(other.id);
                }
            }
        } finally {
            first.close();
            store.forget(first.id);
        }
        assertFalse(store.profiles().containsKey(first.id));
        assertFalse(ProfileGate.contains(context, first.id));
        try { store.open(first.id, pass); fail("Removed profile opened"); } catch (GeneralSecurityException expected) {}
    }
}
