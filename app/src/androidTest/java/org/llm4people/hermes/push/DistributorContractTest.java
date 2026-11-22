package org.llm4people.hermes.push;

import android.Manifest;
import android.content.Context;
import android.database.DataSetObserver;
import android.os.Build;
import android.test.InstrumentationTestCase;

import org.json.JSONObject;
import org.llm4people.hermes.net.Exchange;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.data.PushMessage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class DistributorContractTest extends InstrumentationTestCase {
    private static final String EVENT = "12abcd1234abcd1234abcd1234abcd12";
    private PushStore store;
    private PushStore.Device device;
    private Context context;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        store = PushStore.get(context);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (device != null) {
                store.deactivate(device.profile);
                PushNotices.cancel(context, device.installation);
                store.finishRemoval(device);
            }
        } finally { super.tearDown(); }
    }

    private void prepare(String endpoint) throws Exception {
        device = store.prepareUnified(UUID.randomUUID().toString(), endpoint, "example.ntfy", "test-signer", "test-vapid");
    }

    public void testRotationRejectsStaleAckAndUsesOnlyDeviceCredential() throws Exception {
        try (Exchange bridge = new Exchange("204 No Content", "", "")) {
            prepare(bridge.endpoint());
            String original = new JSONObject().put("endpoint", "https://ntfy.invalid/up-first").toString();
            String revised = new JSONObject().put("endpoint", "https://ntfy.invalid/up-second").toString();
            store.updateSubscription(device, original);
            PushStore.Device first = store.forProfile(device.profile);
            store.updateSubscription(first, revised);
            store.enable(first);
            PushStore.Device second = store.forProfile(device.profile);
            assertEquals(PushStore.PENDING, second.state);
            assertEquals(revised, DeviceSecret.open(second.subscription, second.subscriptionBinding()));
            assertFalse(new String(second.subscription, StandardCharsets.ISO_8859_1).contains("up-second"));
            DistributorEnrollment.sync(context, second);
            String wire = bridge.observed();
            assertTrue(wire.contains("Bearer " + second.managementKey()));
            assertTrue(wire.contains("\"unifiedpush\""));
            assertTrue(wire.contains("up-second"));
            assertFalse(wire.contains("management_key"));
            assertEquals(PushStore.ACTIVE, store.forProfile(device.profile).state);
        }
    }

    public void testRegistrationFailureCannotUndoNewEndpointBeforeOrAfterUpload() throws Exception {
        prepare("https://example.test");
        store.updateSubscription(device, "{}");
        PushStore.Device uploading = store.forProfile(device.profile);
        for (FailedReason reason : FailedReason.values()) assertFalse(store.registrationFailed(device, reason, 10));
        assertEquals(uploading.subscriptionVersion, store.forProfile(device.profile).subscriptionVersion);
        store.enable(uploading);
        for (FailedReason reason : FailedReason.values()) assertFalse(store.registrationFailed(device, reason, 10));
        assertEquals(PushStore.ACTIVE, store.forProfile(device.profile).state);
    }

    public void testNetworkBackoffSurvivesReopenCapsAndDisableWins() throws Exception {
        prepare("https://example.test");
        for (int i = 0; i < 10; i++) {
            assertTrue(store.registrationFailed(device, FailedReason.NETWORK, 1000));
            assertFalse(store.registrationFailed(device, FailedReason.NETWORK, 2000));
            try (PushStore reopened = new PushStore(context, "push.db")) {
                device = reopened.forProfile(device.profile);
                assertEquals(PushStore.PENDING, device.state);
                assertEquals(1000L + Math.min(30_000L << i, PushStore.MAX_DELAY), device.retryAt);
            }
            store.registered(device);
            device = store.forProfile(device.profile);
        }
        store.deactivate(device.profile);
        assertFalse(store.registrationFailed(device, FailedReason.NETWORK, 1000));
        assertFalse(store.updateSubscription(device, "{}"));
        assertEquals(PushStore.REMOVING, store.forProfile(device.profile).state);
    }

    public void testActionableFailuresAndSingleInternalRetry() throws Exception {
        prepare("https://example.test");
        assertTrue(store.registrationFailed(device, FailedReason.INTERNAL_ERROR, 1000));
        device = store.forProfile(device.profile);
        assertTrue(device.retryAt > 1000);
        store.registered(device);
        for (FailedReason reason : new FailedReason[] {FailedReason.INTERNAL_ERROR, FailedReason.ACTION_REQUIRED,
                FailedReason.VAPID_REQUIRED}) {
            assertTrue(store.registrationFailed(store.forProfile(device.profile), reason, 1000));
            device = store.forProfile(device.profile);
            assertEquals(PushStore.FAILED, device.state);
            assertEquals(0L, device.retryAt);
            assertFalse(store.updateSubscription(device, "{}"));
            device = store.prepareUnified(device.profile, device.endpoint, device.distributor, device.signer, device.vapid);
            assertEquals(PushStore.RENEW, device.failure);
            store.registered(device);
        }
    }

    public void testRegistrationHandoffNotifiesOnceAndRetainsNewerRetry() throws Exception {
        prepare("https://example.test");
        assertTrue(store.registrationFailed(device, FailedReason.NETWORK, 1000));
        PushStore.Device due = store.forProfile(device.profile);
        int[] changes = {0};
        DataSetObserver observer = new DataSetObserver() {
            @Override public void onChanged() { changes[0]++; }
        };
        store.observe(observer);
        try {
            store.registered(due);
            assertEquals(1, changes[0]);
            PushStore.Device waiting = store.forProfile(device.profile);
            assertEquals(0L, waiting.retryAt);
            assertEquals(due.state, waiting.state);
            assertEquals(due.failure, waiting.failure);
            store.registered(due);
            assertEquals(1, changes[0]);
            assertTrue(store.registrationFailed(waiting, FailedReason.NETWORK, 2000));
            long next = store.forProfile(device.profile).retryAt;
            store.registered(due);
            assertEquals(2, changes[0]);
            assertEquals(next, store.forProfile(device.profile).retryAt);
        } finally { store.stopObserving(observer); }
    }

    public void testRejectedServerEndpointDeliberatelyRenewsBinding() throws Exception {
        try (Exchange bridge = new Exchange("400 Bad Request", "", "{}")) {
            prepare(bridge.endpoint());
            store.updateSubscription(device, "{\"endpoint\":\"https://ntfy.invalid/old\"}");
            DistributorEnrollment.sync(context, store.forProfile(device.profile));
            assertTrue(bridge.observed().contains("Bearer " + device.managementKey()));
            PushStore.Device rejected = store.forProfile(device.profile);
            assertEquals(PushStore.ENDPOINT_REJECTED, rejected.failure);
            PushStore.Device retry = store.prepareUnified(device.profile, device.endpoint, device.distributor, device.signer, device.vapid);
            assertEquals(device.installation, retry.installation);
            assertEquals(device.managementKey(), retry.managementKey());
            assertEquals(PushStore.RENEW, retry.failure);
            assertNull(retry.subscription);
        }
    }

    public void testGatewayAuthRejectionRequiresSuccessfulRebindBeforeAnyReceipt() throws Exception {
        try (Exchange bridge = new Exchange("401 Unauthorized", "", "{}")) {
            prepare(bridge.endpoint());
            store.updateSubscription(device, "{}");
            store.enable(store.forProfile(device.profile));
            store.updateSubscription(store.forProfile(device.profile), "{\"revision\":2}");
            PushStore.Device sending = store.forProfile(device.profile);
            DistributorEnrollment.sync(context, sending);
            assertTrue(bridge.observed().contains("Bearer " + device.managementKey()));
            PushStore.Device refused = store.forProfile(device.profile);
            assertEquals(PushStore.ACCOUNT_REQUIRED, refused.failure);
            store.enable(sending);
            assertFalse(store.forProfile(device.profile).receives());
            PushStore.Device confirming = store.prepareUnified(device.profile, device.endpoint, device.distributor, device.signer, device.vapid);
            // The owner bearer may fail to reserve here. A stale acknowledgement cannot restore trust.
            store.enable(sending);
            assertFalse(store.forProfile(device.profile).receives());
            assertFalse(store.remember(device.installation, EVENT, "reminder"));
            store.enable(confirming);
            assertTrue(store.forProfile(device.profile).receives());
        }
    }

    public void testFirstReceiptBeforeBindingResponseIsReleasedOnlyOnConfirmedBinding() throws Exception {
        if (Build.VERSION.SDK_INT >= 33)
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        for (String status : new String[]{"403 Forbidden", "204 No Content"}) {
            Runnable deliverBeforeResponse = () -> {
                PushStore.Device pending = store.forProfile(device.profile);
                assertFalse(pending.receives());
                try {
                    byte[] wire = new JSONObject().put("event_id", EVENT).put("installation_id", pending.installation)
                            .put("kind", "reminder").put("expires_at", System.currentTimeMillis() + 60_000)
                            .toString().getBytes(StandardCharsets.UTF_8);
                    for (int i = 0; i < 2; i++)
                        DistributorReceiver.receive(context, new PushMessage(wire, true), pending.installation);
                } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
                assertNull(store.knownKind(pending.installation, EVENT));
                assertNull(MobilePush.target(context, PushNotices.tapIntent(context, pending.installation, EVENT)));
                try { assertFalse(store.updateSubscription(pending, "{}")); }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            try (Exchange bridge = new Exchange(status, "", deliverBeforeResponse, "")) {
                prepare(bridge.endpoint());
                store.updateSubscription(device, "{}");
                DistributorEnrollment.sync(context, store.forProfile(device.profile));
                assertTrue(bridge.observed().contains("PUT "));
                assertEquals(status.startsWith("204") ? "reminder" : null, store.knownKind(device.installation, EVENT));
            }
            store.deactivate(device.profile);
            PushNotices.cancel(context, device.installation);
            store.finishRemoval(device);
            device = null;
        }
    }

    public void testObsoleteExpiredOrRemovedPendingReceiptsCannotBeReleased() throws Exception {
        prepare("https://example.test");
        store.updateSubscription(device, "{}");
        PushStore.Device first = store.forProfile(device.profile);
        long now = System.currentTimeMillis();
        store.defer(first, EVENT, "reminder", now + 60_000, now);
        store.updateSubscription(first, "{\"v\":2}");
        PushStore.Device next = store.forProfile(device.profile);
        store.enable(first);
        store.drain(context, first);
        assertNull(store.knownKind(device.installation, EVENT));
        store.defer(next, EVENT, "reminder", now - 1, now - 2000);
        store.enable(next);
        try (PushStore reopened = new PushStore(context, "push.db")) {
            reopened.drain(context, next);
            assertNull(reopened.knownKind(device.installation, EVENT));
        }
        store.deactivate(device.profile);
        store.drain(context, next);
        assertNull(MobilePush.target(context, PushNotices.tapIntent(context, device.installation, EVENT)));
    }

    public void testEncryptedReceiptMatchesInstanceAndRemovalStopsDelivery() throws Exception {
        if (Build.VERSION.SDK_INT >= 33) {
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS);
        }
        prepare("https://example.test");
        store.updateSubscription(device, "{}");
        assertFalse(store.forProfile(device.profile).receives());
        store.enable(store.forProfile(device.profile));
        store.updateSubscription(store.forProfile(device.profile), "{\"rotation\":2}");
        assertEquals(PushStore.PENDING, store.forProfile(device.profile).state);
        JSONObject notice = new JSONObject().put("event_id", EVENT).put("installation_id", device.installation)
                .put("kind", "reminder");
        // A distributor can replay cached ciphertext when the app process next receives a broadcast.
        byte[] expired = notice.put("expires_at", System.currentTimeMillis() - 1).toString().getBytes(StandardCharsets.UTF_8);
        DistributorReceiver.receive(context, new PushMessage(expired, true), device.installation);
        assertNull(store.knownKind(device.installation, EVENT));
        byte[] data = notice.put("expires_at", System.currentTimeMillis() + 60_000).toString().getBytes(StandardCharsets.UTF_8);
        DistributorReceiver.receive(context, new PushMessage(data, false), device.installation);
        DistributorReceiver.receive(context, new PushMessage(data, true), UUID.randomUUID().toString());
        assertNull(store.knownKind(device.installation, EVENT));
        DistributorReceiver.receive(context, new PushMessage(data, true), device.installation);
        assertEquals("reminder", store.knownKind(device.installation, EVENT));
        assertEquals(device.profile, MobilePush.target(context, PushNotices.tapIntent(context, device.installation, EVENT)).profileId);
        store.deactivate(device.profile);
        assertNull(MobilePush.target(context, PushNotices.tapIntent(context, device.installation, EVENT)));
    }
}
