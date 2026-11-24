package org.llm4people.hermes.push;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM regression tests for R3.3.4: MobilePush.resolve(Account, HermesApi, Target)
 * reuses the session HermesApi so lock/switch/destroy revokes in-flight resolution immediately
 * and rejects mismatched profiles or null arguments.
 */
public final class MobilePushResolveTest {

    @Before
    public void setUpDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, msg) -> {});
    }

    @After
    public void tearDownDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    private static Account testAccount(String id, String endpoint) throws Exception {
        Constructor<Account> ctor = Account.class.getDeclaredConstructor(
                String.class, String.class, String.class, String.class, SharedPreferences.class, byte[].class);
        ctor.setAccessible(true);
        return ctor.newInstance(id, "Test", endpoint, "tok_test", null, new byte[64]);
    }

    @Test
    public void resolveUsesProvidedApiAndFailsImmediatelyWhenRevoked() throws Exception {
        AtomicInteger serverHits = new AtomicInteger();
        AtomicReference<String> lastRequestLine = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        serverHits.incrementAndGet();
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                        String requestLine = reader.readLine();
                        lastRequestLine.set(requestLine);
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            // consume HTTP request headers
                        }
                        OutputStream out = client.getOutputStream();
                        String headers = "HTTP/1.1 503 Service Unavailable\r\n"
                                + "Content-Length: 0\r\n"
                                + "Connection: close\r\n\r\n";
                        out.write(headers.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    } catch (IOException ignored) {
                        return;
                    }
                }
            }, "test-push-resolve-server");
            responder.setDaemon(true);
            responder.start();

            String endpoint = "http://127.0.0.1:" + server.getLocalPort();
            Account account = testAccount("prof-a", endpoint);
            MobilePush.Target deliveryTarget = new MobilePush.Target("prof-a", "del-123", "briefing");
            MobilePush.Target eventTarget = new MobilePush.Target("prof-a", "evt-456", "reply_ready");

            HermesApi liveApi = new HermesApi(endpoint, account.bearer,
                    HermesApi.Limits.from(HermesConfig.defaults()));
            HermesApi.HttpError deliveryErr = assertThrows(HermesApi.HttpError.class,
                    () -> MobilePush.resolve(account, liveApi, deliveryTarget));
            assertEquals(503, deliveryErr.status);
            assertEquals(1, serverHits.get());
            assertTrue("Expected delivery endpoint request, got: " + lastRequestLine.get(),
                    lastRequestLine.get() != null
                            && lastRequestLine.get().startsWith("GET " + MobilePush.deliveryPath("del-123") + " "));

            HermesApi.HttpError eventErr = assertThrows(HermesApi.HttpError.class,
                    () -> MobilePush.resolve(account, liveApi, eventTarget));
            assertEquals(503, eventErr.status);
            assertEquals(2, serverHits.get());
            assertTrue("Expected push event endpoint request, got: " + lastRequestLine.get(),
                    lastRequestLine.get() != null
                            && lastRequestLine.get().startsWith("GET " + PushConnection.BASE + "/events/evt-456 "));

            // Revoking the session HermesApi must fail closed immediately without touching the server.
            liveApi.revoke();
            assertThrows(IOException.class, () -> MobilePush.resolve(account, liveApi, deliveryTarget));
            assertEquals(2, serverHits.get());
        }
    }

    @Test
    public void resolveRejectsNullAccountTargetOrProfileMismatch() throws Exception {
        Account account = testAccount("prof-a", "http://127.0.0.1:1");
        HermesApi api = new HermesApi(account.endpoint, account.bearer,
                HermesApi.Limits.from(HermesConfig.defaults()));

        assertThrows(IOException.class, () -> MobilePush.resolve(null, api,
                new MobilePush.Target("prof-a", "del-1", "briefing")));
        assertThrows(IOException.class, () -> MobilePush.resolve(account, api, null));
        assertThrows(IOException.class, () -> MobilePush.resolve(account, api,
                new MobilePush.Target("prof-b", "del-1", "briefing")));
    }
}
