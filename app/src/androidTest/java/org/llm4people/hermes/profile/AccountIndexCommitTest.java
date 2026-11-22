package org.llm4people.hermes.profile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class AccountIndexCommitTest extends InstrumentationTestCase {
    public void testFailedMembershipAndCredentialWritesCanBeRetried() throws Exception {
        checkIndexTransactions(getInstrumentation().getTargetContext());
    }

    static void checkIndexTransactions(Context target) throws Exception {
        IndexContext context = new IndexContext(target);
        AccountStore writer = new AccountStore(context);
        AccountStore reader = new AccountStore(context);
        char[] pass = "index test phrase".toCharArray();
        String endpoint = "https://example.org/";
        try (Account other = writer.create("Other", endpoint, "other bearer", pass)) {
            other.write("draft", "other draft");
            try {
                context.rejectAndVerify(() -> writer.create("Owner", endpoint, "old bearer", pass));
                assertEquals(1, reader.profiles().size());

                try (Account owner = writer.create("Owner", endpoint, "old bearer", pass)) {
                    owner.write("draft", "owner draft");
                    try {
                        context.rejectAndVerify(() -> writer.replaceBearer(owner.id, pass, "new bearer"));
                        checkOwner(reader, owner.id, pass, "old bearer");
                        try (Account rotated = writer.replaceBearer(owner.id, pass, "new bearer")) {
                            assertEquals("owner draft", rotated.read("draft"));
                        }

                        context.rejectAndVerify(() -> writer.forget(owner.id));
                        assertTrue(reader.contains(owner.id));
                        assertEquals("Owner", reader.profiles().get(owner.id));
                        checkOwner(reader, owner.id, pass, "new bearer");
                    } finally {
                        writer.forget(owner.id);
                    }
                    assertFalse(reader.contains(owner.id));
                    assertFalse(reader.profiles().containsKey(owner.id));
                    assertEquals(2, context.disk.size());
                }
                try (Account opened = reader.open(other.id, pass)) {
                    assertEquals("other bearer", opened.bearer);
                    assertEquals("other draft", opened.read("draft"));
                }
            } finally {
                writer.forget(other.id);
            }
        }
        assertTrue(context.disk.isEmpty());
    }

    private static void checkOwner(AccountStore reader, String id, char[] pass, String bearer) throws Exception {
        try (Account opened = reader.open(id, pass)) {
            assertEquals(bearer, opened.bearer);
            assertEquals("owner draft", opened.read("draft"));
        }
    }

    private interface Write { void run() throws Exception; }

    /** A rejecting Android commit publishes its changes in memory before returning false. */
    static final class IndexContext extends ContextWrapper {
        final Map<String, String> memory = new HashMap<>(), disk = new HashMap<>();
        boolean failNext;
        final SharedPreferences index = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    assertTrue("Unsynchronized profile index read or write", Thread.holdsLock(proxy));
                    switch (method.getName()) {
                        case "getAll": return new HashMap<>(memory);
                        case "getString": return memory.getOrDefault(args[0], (String) args[1]);
                        case "contains": return memory.containsKey(args[0]);
                        case "edit": return editor();
                        default: throw new AssertionError(method);
                    }
                });

        IndexContext(Context base) { super(base); }
        void rejectAndVerify(Write write) throws Exception {
            Map<String, String> previous = new HashMap<>(disk);
            failNext = true;
            try { write.run(); fail("Index write reported success after a disk failure"); }
            catch (IOException expected) { }
            assertEquals(previous, memory);
            assertEquals(previous, disk);
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return "hermes.profiles".equals(name) ? index : super.getSharedPreferences(name, mode);
        }

        SharedPreferences.Editor editor() {
            Map<String, String> changes = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        assertTrue(Thread.holdsLock(index));
                        switch (method.getName()) {
                            case "putString": changes.put((String) args[0], (String) args[1]); return proxy;
                            case "remove": changes.put((String) args[0], null); return proxy;
                            case "commit":
                                changes.forEach((key, value) -> {
                                    if (value == null) memory.remove(key); else memory.put(key, value);
                                });
                                if (failNext) { failNext = false; return false; }
                                disk.clear();
                                disk.putAll(memory);
                                return true;
                            default: throw new AssertionError(method);
                        }
                    });
        }
    }
}
