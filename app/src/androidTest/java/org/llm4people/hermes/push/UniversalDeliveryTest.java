package org.llm4people.hermes.push;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class UniversalDeliveryTest extends InstrumentationTestCase {
    private Context context;
    private PushStore store;
    private PushStore.Device deviceA;
    private PushStore.Device deviceB;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS);
        }
        store = PushStore.get(context);
        PushConfig config = PushConfig.parse(new JSONObject("{\"providers\":[\"fcm\"],\"firebase\":"
                + "{\"applicationId\":\"1:654321:android:ddeeff\",\"gcmSenderId\":\"654321\","
                + "\"apiKey\":\"public-test-key\",\"projectId\":\"example-delivery-test\"}}"));
        deviceA = store.prepare("profile-a-" + UUID.randomUUID(), "https://example.test/p/a", config);
        deviceB = store.prepare("profile-b-" + UUID.randomUUID(), "https://example.test/p/b", config);
        store.enable(deviceA);
        store.enable(deviceB);
    }

    @Override protected void tearDown() throws Exception {
        try {
            for (PushStore.Device dev : new PushStore.Device[]{deviceA, deviceB}) {
                if (dev == null) continue;
                PushPolicy.clear(context, dev.profile);
                store.deactivate(dev.profile);
                PushNotices.cancel(context, dev.installation);
                store.finishRemoval(dev);
            }
        } finally {
            super.tearDown();
        }
    }

    public void testZeroLeakTypedWakeupHidesPrivateFieldsAndRespectsQuietHoursAndOptOut() {
        String briefingEvent = "11111111111111111111111111111111";
        Map<String, String> decoyWire = new HashMap<>();
        decoyWire.put("installation_id", deviceA.installation);
        decoyWire.put("event_id", briefingEvent);
        decoyWire.put("kind", "briefing");
        decoyWire.put("urgency", "normal");
        decoyWire.put("profile_hint_opaque", "0123456789abcdef");
        decoyWire.put("title", "SECRET-TITLE-MUST-NEVER-SHOW");
        decoyWire.put("snippet", "SECRET-SNIPPET-MUST-NEVER-SHOW");
        PushNotices.deliver(context, deviceA.config.senderId, decoyWire);

        StatusBarNotification posted = awaitNotification(deviceA.installation + "/" + briefingEvent);
        assertNotNull(posted);
        Notification notice = posted.getNotification();
        assertEquals("Votre briefing Hermes est prêt", notice.extras.getString("android.title"));
        assertEquals("Ouvrez votre profil pour voir les détails.", notice.extras.getString("android.text"));
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility);
        assertFalse(notice.extras.toString().contains("SECRET-TITLE-MUST-NEVER-SHOW"));
        assertFalse(notice.extras.toString().contains("SECRET-SNIPPET-MUST-NEVER-SHOW"));

        // Enable Quiet Hours 22:00 -> 07:00 on Profile A only.
        PushPolicy quiet = PushPolicy.defaults().withQuietHours(true, 22 * 60, 7 * 60, true);
        PushPolicy.save(context, deviceA.profile, quiet);

        // Normal briefing at 23:00 is suppressed; Profile B is unaffected!
        String quietEvent = "22222222222222222222222222222222";
        Diagnostics.clear();
        PushNotices.postAt(context, store.forInstallation(deviceA.installation), quietEvent,
                "briefing", DeliveryItem.Urgency.NORMAL, 23 * 60);
        assertNull(store.knownKind(deviceA.installation, quietEvent));
        assertTrue(Diagnostics.exportRedacted().contains("suppressed_quiet_hours"));

        PushNotices.postAt(context, store.forInstallation(deviceB.installation), quietEvent,
                "briefing", DeliveryItem.Urgency.NORMAL, 23 * 60);
        assertEquals("briefing", store.knownKind(deviceB.installation, quietEvent));

        // High urgency breaks through when allowHighUrgencyDuringQuietHours=true.
        String urgentEvent = "33333333333333333333333333333333";
        PushNotices.postAt(context, store.forInstallation(deviceA.installation), urgentEvent,
                "device_action", DeliveryItem.Urgency.HIGH, 23 * 60);
        assertEquals("device_action", store.knownKind(deviceA.installation, urgentEvent));

        // Per-kind category opt-out suppresses files even at noon.
        PushPolicy.save(context, deviceA.profile,
                PushPolicy.defaults().withCategory(DeliveryItem.Category.FILES, false));
        String fileEvent = "44444444444444444444444444444444";
        PushNotices.postAt(context, store.forInstallation(deviceA.installation), fileEvent,
                "file", DeliveryItem.Urgency.HIGH, 12 * 60);
        assertNull(store.knownKind(deviceA.installation, fileEvent));

        // Silent urgency never posts an OS banner.
        String silentEvent = "55555555555555555555555555555555";
        PushNotices.postAt(context, store.forInstallation(deviceA.installation), silentEvent,
                "card", DeliveryItem.Urgency.SILENT, 12 * 60);
        assertNull(store.knownKind(deviceA.installation, silentEvent));
    }

    public void testEncryptedDeliveryInboxSyncAckAndCrossProfileIsolation() throws Exception {
        String deliveryId = "dlv_0123456789abcdef";
        JSONObject delivery = new JSONObject()
                .put("delivery_id", deliveryId)
                .put("kind", "briefing")
                .put("urgency", "urgent")
                .put("title", "Point quotidien")
                .put("payload", new JSONObject().put("summary", "Deux tâches prêtes"))
                .put("created_at", 1700000100L)
                .put("session_id", "sess-daily");
        // Exact Fork PR #15 envelope shapes: {"object":"list","data":[...]} & {"object":"hermes.mobile.delivery.ack","delivery":{...},"acked":true}
        JSONObject listResp = new JSONObject()
                .put("object", "list")
                .put("data", new JSONArray().put(delivery));
        JSONObject ackResp = new JSONObject()
                .put("object", "hermes.mobile.delivery.ack")
                .put("acked", true)
                .put("delivery", new JSONObject(delivery.toString()).put("acked_at", 1700000200L));

        try (Exchange listExchange = new Exchange("200 OK", "", listResp.toString());
             Exchange ackExchange = new Exchange("200 OK", "", ackResp.toString());
             WorkspaceAccountFixture profileA = new WorkspaceAccountFixture(context, listExchange.endpoint());
             WorkspaceAccountFixture profileB = new WorkspaceAccountFixture(context, listExchange.endpoint())) {
            DeliveryInbox inbox = new DeliveryInbox();
            HermesApi apiList = new HermesApi(profileA.account.endpoint, profileA.account.bearer);
            List<DeliveryItem> synced = inbox.sync(profileA.account, apiList);
            assertEquals(1, synced.size());
            assertEquals("Point quotidien", synced.get(0).title);
            assertEquals(DeliveryItem.Urgency.HIGH, synced.get(0).urgency);
            assertFalse(synced.get(0).isAcknowledged());
            assertEquals(1, inbox.unreadCount(profileA.account));
            assertTrue(listExchange.observed().contains("GET /p/travail/api/mobile/deliveries?include_acked=true&limit=50"));

            // Profile B has zero access to Profile A's encrypted inbox.
            assertTrue(inbox.cached(profileB.account).isEmpty());
            assertEquals(0, inbox.unreadCount(profileB.account));

            // Acknowledge delivery and verify encrypted persistence.
            HermesApi apiAck = new HermesApi(ackExchange.endpoint(), profileA.account.bearer);
            DeliveryItem acked = inbox.acknowledge(profileA.account, apiAck, deliveryId);
            assertTrue(acked.isAcknowledged());
            assertEquals(0, inbox.unreadCount(profileA.account));
            assertTrue(ackExchange.observed().contains("POST /p/travail/api/mobile/deliveries/" + deliveryId + "/ack"));
        }
    }

    public void testDeliveryRouterDeviceActionValidationAndActiviteRouteRegistration() throws Exception {
        JSONObject validShare = new JSONObject()
                .put("action", new JSONObject().put("kind", "share_text").put("text", "Bonjour depuis Hermes"));
        assertNotNull(DeliveryRouter.parseSafeDeviceAction(validShare));

        JSONObject validMap = new JSONObject().put("kind", "map_search").put("query", "Montréal");
        assertNotNull(DeliveryRouter.parseSafeDeviceAction(validMap));

        JSONObject invertedCal = new JSONObject()
                .put("kind", "calendar_event")
                .put("title", "Réunion")
                .put("start", "2027-01-01T12:00:00Z")
                .put("end", "2027-01-01T11:00:00Z");
        assertNull(DeliveryRouter.parseSafeDeviceAction(invertedCal));
        assertNull(DeliveryRouter.parseSafeDeviceAction(new JSONObject().put("kind", "shell_exec")));

        RouteRegistry registry = new RouteRegistry();
        DeliveryRouter.registerRoutes(registry, () -> {}, () -> {});
        assertEquals(2, registry.destinationsIn(RouteRegistry.Section.ACTIVITE, null).size());
        assertNotNull(registry.destination("activite/livraisons"));
        assertNotNull(registry.destination("activite/preferences_notifications"));
    }

    private StatusBarNotification awaitNotification(String tag) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        while (SystemClock.elapsedRealtime() < deadline) {
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (tag.equals(sbn.getTag())) return sbn;
            }
            SystemClock.sleep(40);
        }
        return null;
    }
}
