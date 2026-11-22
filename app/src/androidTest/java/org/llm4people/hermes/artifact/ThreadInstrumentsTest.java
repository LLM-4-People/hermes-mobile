package org.llm4people.hermes.artifact;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

@SuppressWarnings("deprecation")
public class ThreadInstrumentsTest extends InstrumentationTestCase {
    protected Context context() { return getInstrumentation().getTargetContext(); }

    private static String page(String title) {
        return "{\"version\":1,\"mode\":\"replace\",\"surface\":\"page/notes\",\"title\":\"" + title
                + "\",\"blocks\":[{\"type\":\"value\",\"source\":\"thread.title\",\"title\":\"Discussion\"}]}";
    }

    private static String fence(String text) { return "```hermes-workspace\n" + text + "\n```"; }

    private static JSONObject message(int id, String segment, String role, Object content) throws Exception {
        return new JSONObject().put("id", id).put("session_id", segment).put("role", role).put("content", content);
    }

    public void testOnlyExactVisibleAssistantNamedPageRevisionsBecomeCandidates() throws Exception {
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(context())) {
            ThreadInstruments store = new ThreadInstruments(profile.account, "root");
            String one = page("Proposition A"), two = page("Proposition B");
            JSONObject original = message(1, "root", "assistant", "```java\n1\n```\n" + fence(one));
            JSONArray blocks = new JSONArray().put(new JSONObject().put("type", "output_text").put("text", fence(two)));
            JSONObject revised = message(1, "compacted", "assistant", blocks);
            JSONArray transcript = new JSONArray().put(message(3, "root", "user", fence(one)))
                    .put(message(4, "root", "assistant", fence(two)).put("display_kind", "hidden"))
                    .put(message(5, "root", "tool", fence(two)))
                    .put(message(6, "root", "assistant", fence("{\"version\":1}")))
                    .put(message(7, "root", "assistant", fence(one.replace("page/notes", "home"))))
                    .put(message(0, "root", "assistant", fence(one))).put(original).put(revised);
            List<ThreadInstruments.Pin> candidates = store.proposals(transcript);
            assertEquals(2, candidates.size());
            ThreadInstruments.Pin a = candidates.get(0), b = candidates.get(1);
            assertEquals(1, a.fenceOrdinal);
            assertEquals(0, b.fenceOrdinal);
            assertEquals(1, a.version);
            assertEquals(2, b.version);
            assertEquals("page/notes", b.pageId);
            assertEquals(two, b.pageJson);
            assertTrue(a.fromMessage(original));
            assertFalse(a.fromMessage(revised));
            assertFalse(a.fromMessage(message(1, "root", "user", fence(one))));
            assertFalse(b.sameSource(a));
            ThreadInstruments.Pin beforePaging = store.proposals(new JSONArray().put(revised)).get(0);
            assertEquals(1, beforePaging.version);
            assertTrue(beforePaging.sameSource(b));

            ThreadInstruments.State empty = store.state();
            try { store.pin(empty, a, new JSONArray().put(revised)); fail("Accepted a replaced source"); }
            catch (IllegalArgumentException expected) { }
            try { store.pin(empty, a, new JSONArray().put(message(1, "root", "assistant", fence(one)))); fail("Wrong ordinal"); }
            catch (IllegalArgumentException expected) { }
            assertNull(store.state().current);
            assertEquals(one, store.pin(empty, a, transcript).current.pageJson);
        }
    }

    public void testEncryptedExactSnapshotReopensAndUndoNeverChangesInstalledPage() throws Exception {
        Context app = context();
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app)) {
            ThreadInstruments store = new ThreadInstruments(profile.account, "root");
            String one = page("En premier"), two = page("En second");
            JSONArray messages = new JSONArray().put(message(11, "old", "assistant", fence(one)))
                    .put(message(12, "tip", "assistant", fence(two)));
            LayoutHistory global = new LayoutHistory(profile.account, "page/notes");
            global.replace(page("Atelier"));
            ThreadInstruments.State initial = store.state();
            List<ThreadInstruments.Pin> proposals = store.proposals(messages);
            ThreadInstruments.State first = store.pin(initial, proposals.get(0), messages);
            ThreadInstruments.State second = store.pin(first, proposals.get(1), messages);
            assertEquals(one, second.previous.pageJson);
            String disk = app.getSharedPreferences("workspace.test." + profile.account.id, Context.MODE_PRIVATE)
                    .getAll().toString();
            assertFalse(disk.contains("En second"));
            assertFalse(disk.contains("thread.instrument"));
            profile.account.close();
            try { store.state(); fail("Read after lock"); } catch (IllegalStateException expected) { }
            try { store.proposals(messages); fail("Projected a transcript after lock"); } catch (IllegalStateException expected) { }
            try (Account reopened = profile.reopen()) {
                ThreadInstruments restored = new ThreadInstruments(reopened, "root");
                ThreadInstruments.State state = restored.state();
                assertEquals(two, state.current.pageJson);
                assertEquals("12", state.current.messageId);
                assertEquals(2, state.current.version);
                assertNull(new ThreadInstruments(reopened, "fork").state().current);
                ThreadInstruments.State undone = restored.undo(state);
                assertEquals(one, undone.current.pageJson);
                assertFalse(undone.canUndo);
                ThreadInstruments.State cleared = restored.clear(undone);
                assertNull(cleared.current);
                assertEquals(one, restored.undo(cleared).current.pageJson);
                assertEquals(page("Atelier"), new LayoutHistory(reopened, "page/notes").current());
                try { restored.clear(second); fail("Previous unlock confirmation reused"); }
                catch (ThreadInstruments.Conflict expected) { }
            }
        }
    }

    public void testAccountAndThreadOwnershipCannotBeRebound() throws Exception {
        try (WorkspaceAccountFixture first = new WorkspaceAccountFixture(context());
             WorkspaceAccountFixture second = new WorkspaceAccountFixture(context())) {
            ThreadInstruments mine = new ThreadInstruments(first.account, "root");
            JSONArray messages = new JSONArray().put(message(8, "tip", "assistant", fence(page("Une page"))));
            ThreadInstruments.Pin proposal = mine.proposals(messages).get(0);
            for (ThreadInstruments other : new ThreadInstruments[]{new ThreadInstruments(second.account, "root"),
                    new ThreadInstruments(first.account, "fork")}) {
                try { other.pin(other.state(), proposal, messages); fail("Pinned a different owner's proposal"); }
                catch (IllegalArgumentException expected) { }
                assertNull(other.state().current);
            }
            ThreadInstruments.State empty = mine.state();
            ThreadInstruments.State pinned = mine.pin(empty, proposal, messages);
            assertNull(mine.undo(pinned).current);
            try { mine.pin(empty, proposal, messages); fail("Stale empty confirmation reused after undo"); }
            catch (ThreadInstruments.Conflict expected) { }
            first.account.write("thread.instrument.v1.root", "{corrupt}");
            try { mine.clear(pinned); fail("Overwrote an unreadable record"); } catch (IllegalStateException expected) { }
            assertEquals("{corrupt}", first.account.read("thread.instrument.v1.root"));
        }
    }

    public void testFailedEncryptedCommitKeepsExistingPinAndUndoState() throws Exception {
        AtomicBoolean reject = new AtomicBoolean();
        Context wrapped = new ContextWrapper(context()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences delegate = super.getSharedPreferences(name, mode);
                return (SharedPreferences) Proxy.newProxyInstance(ThreadInstrumentsTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                        (proxy, method, args) -> {
                            if (!method.getName().equals("edit")) return method.invoke(delegate, args);
                            SharedPreferences.Editor writer = delegate.edit();
                            return Proxy.newProxyInstance(ThreadInstrumentsTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                                    (editor, operation, values) -> {
                                        Object result = operation.invoke(writer, values);
                                        if (operation.getName().equals("commit") && reject.getAndSet(false)) return false;
                                        return result == writer ? editor : result;
                                    });
                        });
            }
        };
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(wrapped)) {
            ThreadInstruments store = new ThreadInstruments(profile.account, "root");
            JSONArray messages = new JSONArray().put(message(1, "tip", "assistant", fence(page("Avant"))))
                    .put(message(2, "tip", "assistant", fence(page("Après"))));
            List<ThreadInstruments.Pin> proposals = store.proposals(messages);
            ThreadInstruments.State before = store.pin(store.state(), proposals.get(0), messages);
            reject.set(true);
            try { store.pin(before, proposals.get(1), messages); fail("Failed commit reported success"); }
            catch (IllegalStateException expected) { }
            try (Account reopened = profile.reopen()) {
                ThreadInstruments.State durable = new ThreadInstruments(reopened, "root").state();
                assertEquals("Avant", durable.current.title);
                assertNull(durable.previous);
            }
            assertEquals("Après", store.pin(before, proposals.get(1), messages).current.title);

            profile.account.write("thread.instrument.v1.root", "{bad}");
            ThreadInstruments.Damaged damage = damaged(store);
            reject.set(true);
            try { store.recover(damage); fail("Failed recovery reported success"); }
            catch (IllegalStateException expected) { }
            assertEquals("{bad}", profile.account.read("thread.instrument.v1.root"));
            assertNull(store.recover(damage).current);
        }
    }

    private static ThreadInstruments.Damaged damaged(ThreadInstruments store) {
        try { store.state(); fail("Expected damaged state"); }
        catch (ThreadInstruments.Damaged expected) { return expected; }
        throw new AssertionError();
    }

    private static void stale(Runnable confirmation) {
        try { confirmation.run(); fail("Accepted a stale or foreign confirmation"); }
        catch (ThreadInstruments.Conflict expected) { }
    }

    public void testRecoverOnlyOriginalSlotWithoutRevivingOlderApprovals() throws Exception {
        Context app = context();
        String key = "thread.instrument.v1.root";
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app);
             WorkspaceAccountFixture neighbor = new WorkspaceAccountFixture(app); Account otherHandle = profile.reopen()) {
            ThreadInstruments store = new ThreadInstruments(profile.account, "root");
            ThreadInstruments sibling = new ThreadInstruments(profile.account, "fork");
            ThreadInstruments otherProfile = new ThreadInstruments(neighbor.account, "root");
            ThreadInstruments concurrent = new ThreadInstruments(otherHandle, "root");
            JSONArray rows = new JSONArray().put(message(1, "tip", "assistant", fence(page("Valide"))));
            ThreadInstruments.State before = store.state();
            ThreadInstruments.Pin approved = store.proposals(rows).get(0);
            sibling.pin(sibling.state(), sibling.proposals(rows).get(0), rows);
            otherProfile.pin(otherProfile.state(), otherProfile.proposals(rows).get(0), rows);
            profile.account.write("thread:root", "brouillon privé");

            profile.account.write(key, "{broken}");
            ThreadInstruments.Damaged original = damaged(store);
            stale(() -> sibling.recover(original));
            stale(() -> otherProfile.recover(original));
            stale(() -> concurrent.recover(original));
            otherHandle.write(key, "{broken}");  // Same invalid text, different committed ciphertext.
            stale(() -> store.recover(original));

            ThreadInstruments.Damaged outdated = damaged(store);
            ThreadInstruments.State reset = concurrent.recover(damaged(concurrent));
            assertNull(reset.current);
            concurrent.pin(reset, concurrent.proposals(rows).get(0), rows);
            stale(() -> store.recover(outdated));
            assertEquals("Valide", store.state().current.title);

            profile.account.write(key, "{\"revision\":\"malformed schema\"}");
            ThreadInstruments.State recovered = store.recover(damaged(store));
            assertNull(recovered.current);
            assertNull(recovered.previous);
            assertFalse(recovered.canUndo);
            stale(() -> store.pin(before, approved, rows));
            assertEquals("Valide", store.pin(recovered, approved, rows).current.title);
            assertEquals("Valide", sibling.state().current.title);
            assertEquals("Valide", otherProfile.state().current.title);
            assertEquals("brouillon privé", profile.account.read("thread:root"));
        }
    }

    public void testBadPreferenceTypeAndCiphertextCanBeRecoveredOnlyWhileUnlocked() throws Exception {
        Context app = context();
        String key = "thread.instrument.v1.root";
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app)) {
            ThreadInstruments store = new ThreadInstruments(profile.account, "root");
            profile.account.write(key, "{}");
            SharedPreferences prefs = app.getSharedPreferences("workspace.test." + profile.account.id, Context.MODE_PRIVATE);
            String slot = prefs.getAll().keySet().iterator().next();
            assertTrue(prefs.edit().putInt(slot, 17).commit());
            ThreadInstruments.Damaged badType = damaged(store);
            assertTrue(prefs.edit().putString(slot, "bad-ciphertext").commit());
            stale(() -> store.recover(badType));
            assertNull(store.recover(damaged(store)).current);

            assertTrue(prefs.edit().putInt(slot, 19).commit());
            ThreadInstruments.Damaged captured = damaged(store);
            profile.account.close();
            try { store.recover(captured); fail("Used a confirmation after lock"); }
            catch (ThreadInstruments.Damaged unexpected) { throw unexpected; }
            catch (IllegalStateException expected) { }
            try (Account reopened = profile.reopen()) {
                ThreadInstruments fresh = new ThreadInstruments(reopened, "root");
                stale(() -> fresh.recover(captured));
                assertNull(fresh.recover(damaged(fresh)).current);
            }
        }
    }

    public void testOnlyOneSimultaneousConfirmationWinsAcrossUnlockedHandles() throws Exception {
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(context()); Account other = profile.reopen()) {
            ThreadInstruments first = new ThreadInstruments(profile.account, "root"), second = new ThreadInstruments(other, "root");
            JSONArray messages = new JSONArray().put(message(1, "tip", "assistant", fence(page("First"))))
                    .put(message(2, "tip", "assistant", fence(page("Second"))));
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            FutureTask<Boolean> a = race(first, messages, 0, ready, start);
            FutureTask<Boolean> b = race(second, messages, 1, ready, start);
            new Thread(a).start();
            new Thread(b).start();
            ready.await();
            start.countDown();
            assertTrue(a.get() ^ b.get());
            assertEquals(first.state().current.pageJson, second.state().current.pageJson);
            assertNull(first.state().previous);
        }
    }

    private FutureTask<Boolean> race(ThreadInstruments owner, JSONArray messages, int i, CountDownLatch ready,
                                     CountDownLatch start) {
        ThreadInstruments.State before = owner.state();
        ThreadInstruments.Pin proposal = owner.proposals(messages).get(i);
        return new FutureTask<>(() -> {
            ready.countDown();
            start.await();
            try { owner.pin(before, proposal, messages); return true; }
            catch (ThreadInstruments.Conflict expected) { return false; }
        });
    }
}
