package org.llm4people.hermes.thread;

import android.content.Context;
import android.test.InstrumentationTestCase;

import org.json.JSONObject;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.os.HermesWidgetStore;
import org.llm4people.hermes.os.LiveRunNotification;
import org.llm4people.hermes.os.LockGracePolicy;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.IncomingMediaShare;
import org.llm4people.hermes.profile.IncomingShare;
import org.llm4people.hermes.profile.UnlockBackoff;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class MultiRunAndProfileSecurityTest extends InstrumentationTestCase {
    public void testFiveConcurrentApiCallsExecuteInParallelAndRevokeFailsClosed() throws Exception {
        final int concurrency = 5;
        final CountDownLatch allArrived = new CountDownLatch(concurrency);
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxOverlap = new AtomicInteger();
        ExecutorService serverPool = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            serverPool.execute(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        serverPool.execute(() -> {
                            try (Socket s = client) {
                                byte[] buf = new byte[1024];
                                s.getInputStream().read(buf);
                                int cur = inFlight.incrementAndGet();
                                maxOverlap.accumulateAndGet(cur, Math::max);
                                allArrived.countDown();
                                allArrived.await(5, TimeUnit.SECONDS);
                                inFlight.decrementAndGet();
                                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                                String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                        + body.length + "\r\nConnection: close\r\n\r\n";
                                OutputStream out = s.getOutputStream();
                                out.write(head.getBytes(StandardCharsets.US_ASCII));
                                out.write(body);
                                out.flush();
                            } catch (Exception ignored) {
                            }
                        });
                    } catch (IOException closed) {
                        break;
                    }
                }
            });

            HermesApi api = new HermesApi("http://127.0.0.1:" + server.getLocalPort(), "test-bearer-key");
            ExecutorService clients = Executors.newFixedThreadPool(concurrency);
            try {
                List<Future<JSONObject>> futures = new ArrayList<>();
                for (int i = 0; i < concurrency; i++) {
                    final int idx = i;
                    futures.add(clients.submit(() -> api.get("/v1/runs/run-" + idx)));
                }
                for (Future<JSONObject> f : futures) {
                    assertTrue(f.get(8, TimeUnit.SECONDS).optBoolean("ok"));
                }
                assertTrue("Expected true parallel overlap across 5 requests, observed " + maxOverlap.get(),
                        maxOverlap.get() >= concurrency);

                // Revoking the profile client immediately blocks all subsequent calls fail-closed.
                api.revoke();
                assertTrue(api.isRevoked());
                try {
                    api.get("/v1/runs/after-lock");
                    fail("Expected IOException after HermesApi.revoke()");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("revoked"));
                }
            } finally {
                clients.shutdownNow();
            }
        } finally {
            serverPool.shutdownNow();
        }
    }

    public void testMultiRunLiveNotificationLocalizedAndIsolatedPerSession() {
        Context context = getInstrumentation().getTargetContext();
        ActiveRunTracker tracker = new ActiveRunTracker();
        for (int i = 1; i <= 5; i++) {
            tracker.markBackgrounded("thread-" + i, "run-" + i, new LiveRunNotification.Snapshot(
                    LiveRunNotification.Phase.WORKING, 0, 0, false, true));
        }
        LiveRunNotification.Snapshot agg = tracker.aggregateSnapshot();
        assertEquals(5, agg.activeCount());
        LiveRunNotification.Presentation model = LiveRunNotification.present(context, agg);
        assertNotNull(model);
        assertTrue(model.title().contains("5"));
        assertTrue(model.shortCriticalText().contains("5"));
        // Zero thread IDs or private text may ever appear in the Live Update notification.
        assertFalse(model.title().contains("thread-"));
        assertFalse(model.body().contains("thread-"));

        tracker.markBackgrounded("thread-2", "run-2", new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.AWAITING_DECISION, 2, 0, true, true));
        LiveRunNotification.Presentation withDecision = LiveRunNotification.present(context, tracker.aggregateSnapshot());
        assertNotNull(withDecision);
        assertTrue(withDecision.shortCriticalText().contains("1/5"));

        tracker.stopAllWatchers();
        assertEquals(0, tracker.activeCount());
        assertNull(LiveRunNotification.present(context, tracker.aggregateSnapshot()));
    }

    public void testUnlockBackoffPersistsAndForgetWipesAllProfileStoresAtomically() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Class<?> storeClass = Class.forName("org.llm4people.hermes.profile.AccountStore");
        Constructor<?> ctor = storeClass.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        Object store = ctor.newInstance(context);

        Method create = storeClass.getDeclaredMethod("create", String.class, String.class, String.class, char[].class);
        create.setAccessible(true);
        Method forget = storeClass.getDeclaredMethod("forget", String.class);
        forget.setAccessible(true);
        Method contains = storeClass.getDeclaredMethod("contains", String.class);
        contains.setAccessible(true);

        String uniqueSuffix = Long.toHexString(System.nanoTime());
        Account alice = null;
        Account bob = null;
        String textReceipt = null;
        String mediaReceipt = null;
        try {
            alice = (Account) create.invoke(store, "Alice-" + uniqueSuffix, "https://alice.example/p/a",
                    "alice-token-secret", "alice-passphrase-1234".toCharArray());
            bob = (Account) create.invoke(store, "Bob-" + uniqueSuffix, "https://bob.example/p/b",
                    "bob-token-secret", "bob-passphrase-1234".toCharArray());

            // Seed every store for Alice (vault, grace policy, backoff, widget, incoming text & media shares).
            alice.write("secret-note", "confidential-alice");
            LockGracePolicy.setWindow(context, alice.id, LockGracePolicy.Window.MINUTES_5);

            UnlockBackoff backoff = UnlockBackoff.forContext(context);
            backoff.recordFailure(alice.id);
            backoff.recordFailure(alice.id);
            assertEquals(5_000L, backoff.recordFailure(alice.id));
            // Re-instantiating UnlockBackoff confirms cooldown survives process/Activity restart.
            assertTrue(UnlockBackoff.forContext(context).remainingMs(alice.id) > 0L);

            HermesWidgetStore widgets = new HermesWidgetStore(context);
            String aliceWid = HermesWidgetStore.profileScopedId("summary", alice.id);
            String bobWid = HermesWidgetStore.profileScopedId("summary", bob.id);
            widgets.upsertSpec(new HermesWidgetSpec(aliceWid, "Alice Widget", "", HermesWidgetSpec.Template.STATUS,
                    Collections.emptyList(), null, true, alice.id), alice.id);
            widgets.upsertSpec(new HermesWidgetSpec(bobWid, "Bob Widget", "", HermesWidgetSpec.Template.STATUS,
                    Collections.emptyList(), null, true, bob.id), bob.id);

            textReceipt = IncomingShare.create(context, "Alice private share");
            assertNotNull("Expected text share receipt", textReceipt);
            IncomingShare.open(context, textReceipt, alice);
            mediaReceipt = IncomingMediaShare.create(context, Collections.singletonList(
                    new CapturedMedia.Item(CapturedMedia.Kind.DOCUMENT, "doc.txt", "text/plain",
                            "hello".getBytes(StandardCharsets.UTF_8), "deadbeef", null)));
            assertNotNull("Expected media share receipt", mediaReceipt);
            IncomingMediaShare.open(context, mediaReceipt, alice);

            alice.close();
            forget.invoke(store, alice.id);

            // Every single store for Alice must be completely wiped.
            assertFalse((Boolean) contains.invoke(store, alice.id));
            assertEquals(LockGracePolicy.Window.IMMEDIATE, LockGracePolicy.window(context, alice.id));
            assertEquals(0L, UnlockBackoff.forContext(context).remainingMs(alice.id));
            assertEquals(0, UnlockBackoff.forContext(context).failureCount(alice.id));
            assertNull(widgets.spec(aliceWid));
            assertNull(IncomingShare.owner(context, textReceipt));
            assertNull(IncomingMediaShare.owner(context, mediaReceipt));

            // Bob's profile & widget remain completely intact.
            assertTrue((Boolean) contains.invoke(store, bob.id));
            assertNotNull(widgets.spec(bobWid));
        } finally {
            if (textReceipt != null) IncomingShare.discard(context, textReceipt);
            if (mediaReceipt != null) IncomingMediaShare.discard(context, mediaReceipt);
            if (alice != null) {
                try { alice.close(); } finally { forget.invoke(store, alice.id); }
            }
            if (bob != null) {
                try { bob.close(); } finally { forget.invoke(store, bob.id); }
            }
        }
    }
}
