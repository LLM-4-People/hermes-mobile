package org.llm4people.hermes;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;

import org.json.JSONObject;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.thread.ThreadWorkspace;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumentation regression tests for {@code MainActivity.openDiscussionComposer(Account, boolean, boolean)}:
 * verifies inline composer routing, hidden-composer fallback to {@link WorkspaceNavigation#COMPOSER}
 * (both existing thread and {@code createNew=true}), and fail-closed rejection of null/mismatched accounts.
 */
@SuppressWarnings("deprecation")
public final class OpenDiscussionComposerRegressionTest extends InstrumentationTestCase {
    private static final String HIDDEN_COMPOSER_LAYOUT = "{\"version\":1,\"surface\":\"thread\",\"mode\":\"replace\","
            + "\"title\":\"Fil\",\"blocks\":[{\"type\":\"section\",\"title\":\"Accueil\",\"note\":\"Sans champ\"}]}";

    public void testOpenDiscussionComposerRoutesFallbackInlineAndFailsClosedOnAccountMismatch() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        try {
            try (SessionEchoGateway gateway = new SessionEchoGateway();
                 WorkspaceAccountFixture owner = new WorkspaceAccountFixture(context, gateway.endpoint());
                 WorkspaceAccountFixture foreign = new WorkspaceAccountFixture(context, gateway.endpoint())) {
                MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(launch);
                try {
                    owner.account.write("last-thread", "seed-thread");
                    ThreadWorkspace threads = main(() -> unlock(activity, owner.account));
                    WorkspaceNavigation navigation = main(() -> navigationOf(activity));
                    assertNotNull(threads);
                    assertNotNull(navigation);

                    // 1. Positive inline: default layout keeps composer visible and stays on DISCUSSION.
                    runTestOnUiThread(() -> {
                        assertTrue(threads.composerVisible());
                        activity.onAction("nav_apps");
                        assertEquals(WorkspaceNavigation.APPS, navigation.current());
                        invokeOpenDiscussionComposer(activity, owner.account, false, false);
                        assertEquals(WorkspaceNavigation.DISCUSSION, navigation.current());
                        assertTrue(threads.composerVisible());
                        assertEquals("seed-thread", threads.sessionId());
                    });

                    // 2. Positive fallback (existing thread): hidden inline composer routes to COMPOSER.
                    String hiddenRecord = new JSONObject().put("current", HIDDEN_COMPOSER_LAYOUT).toString();
                    runTestOnUiThread(() -> {
                        owner.account.write("workspace.v1.thread", hiddenRecord);
                        activity.onLayoutChanged();
                        assertFalse("Custom replace layout must hide inline composer", threads.composerVisible());
                        activity.onAction("nav_apps");
                        assertEquals(WorkspaceNavigation.APPS, navigation.current());
                        invokeOpenDiscussionComposer(activity, owner.account, false, false);
                        assertEquals(WorkspaceNavigation.COMPOSER, navigation.current());
                        assertEquals("seed-thread", threads.sessionId());
                    });

                    // 3. Positive fallback (createNew=true): creates a new session and then routes to COMPOSER.
                    runTestOnUiThread(() -> {
                        activity.onAction("nav_apps");
                        assertEquals(WorkspaceNavigation.APPS, navigation.current());
                        invokeOpenDiscussionComposer(activity, owner.account, true, false);
                    });
                    awaitCondition("createNew thread creation and fallback to COMPOSER", () -> {
                        String sessionId = threads.sessionId();
                        return sessionId != null
                                && !"seed-thread".equals(sessionId)
                                && WorkspaceNavigation.COMPOSER.equals(navigation.current());
                    });
                    String createdSessionId = main(threads::sessionId);
                    assertNotNull(createdSessionId);
                    assertFalse("seed-thread".equals(createdSessionId));

                    // 4. Negative / fail-closed: null or mismatched Account is a strict no-op.
                    runTestOnUiThread(() -> {
                        activity.onAction("nav_apps");
                        assertEquals(WorkspaceNavigation.APPS, navigation.current());
                        invokeOpenDiscussionComposer(activity, null, false, false);
                        assertEquals(WorkspaceNavigation.APPS, navigation.current());
                        assertEquals(createdSessionId, threads.sessionId());

                        invokeOpenDiscussionComposer(activity, foreign.account, true, false);
                        assertEquals(WorkspaceNavigation.APPS, navigation.current());
                        assertEquals(createdSessionId, threads.sessionId());
                    });

                    // 5. Restoring default layout returns inline composer behavior to DISCUSSION.
                    runTestOnUiThread(() -> {
                        owner.account.remove("workspace.v1.thread");
                        activity.onLayoutChanged();
                        activity.onAction("nav_apps");
                        assertEquals(WorkspaceNavigation.APPS, navigation.current());
                        invokeOpenDiscussionComposer(activity, owner.account, false, false);
                        assertEquals(WorkspaceNavigation.DISCUSSION, navigation.current());
                        assertTrue(threads.composerVisible());
                        assertEquals(createdSessionId, threads.sessionId());
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    private <T> T main(Callable<T> work) throws Throwable {
        AtomicReference<T> result = new AtomicReference<>();
        runTestOnUiThread(() -> {
            try {
                result.set(work.call());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
        return result.get();
    }

    private void awaitCondition(String expectation, Callable<Boolean> ready) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            getInstrumentation().waitForIdleSync();
            if (main(ready)) return;
            SystemClock.sleep(25L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for " + expectation);
    }

    private static ThreadWorkspace unlock(MainActivity activity, Account account) throws Exception {
        Method opened = MainActivity.class.getDeclaredMethod("opened", Account.class);
        opened.setAccessible(true);
        opened.invoke(activity, account);
        Field threadsField = MainActivity.class.getDeclaredField("threads");
        threadsField.setAccessible(true);
        return (ThreadWorkspace) threadsField.get(activity);
    }

    private static WorkspaceNavigation navigationOf(MainActivity activity) throws Exception {
        Field navField = MainActivity.class.getDeclaredField("navigation");
        navField.setAccessible(true);
        return (WorkspaceNavigation) navField.get(activity);
    }

    private static void invokeOpenDiscussionComposer(
            MainActivity activity, Account owner, boolean createNew, boolean startDictation) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(
                    "openDiscussionComposer", Account.class, boolean.class, boolean.class);
            method.setAccessible(true);
            method.invoke(activity, owner, createNew, startDictation);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    /** Loopback gateway that echoes the requested session ID on POST /api/sessions and serves empty lists otherwise. */
    private static final class SessionEchoGateway implements Closeable {
        private final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        private volatile boolean closed;

        SessionEchoGateway() throws IOException {
            Thread server = new Thread(this::serveLoop, "composer-regression-gateway");
            server.setDaemon(true);
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + listener.getLocalPort() + "/p/travail/";
        }

        private void serveLoop() {
            while (!closed) {
                try (Socket socket = listener.accept()) {
                    handle(socket);
                } catch (IOException ignored) {
                    if (closed) return;
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            socket.setSoTimeout(3_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            StringBuilder bodyBuf = new StringBuilder();
            for (int i = 0; i < contentLength; i++) {
                int ch = reader.read();
                if (ch < 0) break;
                bodyBuf.append((char) ch);
            }
            String replyBody = "{\"data\":[],\"has_more\":false}";
            if (requestLine.startsWith("POST ") && requestLine.contains("/api/sessions")) {
                try {
                    String id = new JSONObject(bodyBuf.toString()).optString("id", "created-thread");
                    replyBody = new JSONObject()
                            .put("session", new JSONObject().put("id", id).put("title", "Nouvelle discussion"))
                            .toString();
                } catch (Exception ignored) {
                    replyBody = "{\"session\":{\"id\":\"created-thread\",\"title\":\"Nouvelle discussion\"}}";
                }
            }
            byte[] payload = replyBody.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + payload.length + "\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
        }

        @Override public void close() throws IOException {
            closed = true;
            listener.close();
        }
    }
}
