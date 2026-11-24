package org.llm4people.hermes.push;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for PushConnection config limits/timeouts, PushHistory revocation, and PushSettings lifecycle. */
public final class PushConnectionConfigAndSettingsTest {

    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void resetDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void pushConnectionDerivesApiLimitsAndFcmTimeoutFromHermesConfig() {
        HermesConfig defaults = HermesConfig.defaults();
        HermesApi.Limits limits = PushConnection.apiLimits(null);
        assertEquals(defaults.httpConnectTimeoutMs, limits.connectTimeoutMs);
        assertEquals(defaults.httpReadTimeoutMs, limits.readTimeoutMs);
        assertEquals(defaults.httpJsonMaxBytes, limits.jsonBytes);
        assertEquals(defaults.httpEventMaxBytes, limits.eventBytes);
        assertEquals(defaults.httpReadTimeoutMs, PushConnection.fcmTokenTimeoutMs(null));
    }

    @Test
    public void pushHistoryRejectsNullOrRevokedApiFailClosed() {
        assertThrows(IOException.class, () -> PushHistory.load(null, (HermesApi) null, 0L, Locale.ROOT));

        HermesApi revoked = new HermesApi("https://hermes.example.com", "secret-token", PushConnection.apiLimits(null));
        revoked.revoke();
        assertTrue(revoked.isRevoked());
        assertThrows(IOException.class, () -> PushHistory.load(null, revoked, 0L, Locale.ROOT));
    }

    @Test
    public void pushSettingsOwnsConfiguredApiAndRevokesItOnClose() throws Exception {
        java.lang.reflect.Constructor<Account> ctor = Account.class.getDeclaredConstructor(
                String.class, String.class, String.class, String.class,
                android.content.SharedPreferences.class, byte[].class);
        ctor.setAccessible(true);
        Account account = ctor.newInstance(
                "profile-1", "Personal", "https://hermes.example.com", "bearer-token",
                null, new byte[64]);
        try {
            PushSettings settings = new PushSettings(null, account);
            HermesApi owned = settings.ownedApi();
            assertNotNull("PushSettings must own a revocable HermesApi", owned);
            assertFalse(owned.isRevoked());
            settings.close();
            assertTrue("PushSettings.close() must revoke its owned HermesApi", owned.isRevoked());

            // Revoked or null owned api must fail closed across enroll and remove operations
            assertThrows(IOException.class, () -> PushConnection.enroll(null, account, owned, null, 0L, () -> true));
            assertThrows(IOException.class, () -> PushConnection.enroll(null, account, null, null, 0L, () -> true));
            assertThrows(IOException.class, () -> DistributorEnrollment.enroll(null, account, owned, "pkg", "sig", "vapid", 0L, () -> true));
            assertThrows(IOException.class, () -> DistributorEnrollment.enroll(null, account, null, "pkg", "sig", "vapid", 0L, () -> true));
            assertThrows(IOException.class, () -> PushConnection.remove(null, null, owned));
            assertThrows(IOException.class, () -> PushConnection.remove(null, null, (HermesApi) null));
        } finally {
            account.close();
        }
    }

    @Test
    public void pushSettingsRetainsSuppliedDeliveryActionsAndDefaultsToNull() throws Exception {
        java.lang.reflect.Constructor<Account> ctor = Account.class.getDeclaredConstructor(
                String.class, String.class, String.class, String.class,
                android.content.SharedPreferences.class, byte[].class);
        ctor.setAccessible(true);
        Account account = ctor.newInstance(
                "profile-2", "Work", "https://hermes.example.com", "bearer-token",
                null, new byte[64]);
        try {
            PushSettings withoutActions = new PushSettings(null, account);
            try {
                assertNull("2-arg PushSettings must default deliveryActions to null",
                        withoutActions.deliveryActions());
            } finally {
                withoutActions.close();
            }

            DeliveryRouter.Actions actions = new DeliveryRouter.Actions() {};
            PushSettings withActions = new PushSettings(null, account, actions);
            try {
                assertSame("3-arg PushSettings must retain the supplied DeliveryRouter.Actions",
                        actions, withActions.deliveryActions());
            } finally {
                withActions.close();
            }
        } finally {
            account.close();
        }
    }
}
