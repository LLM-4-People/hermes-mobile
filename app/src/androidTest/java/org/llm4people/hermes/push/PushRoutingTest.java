package org.llm4people.hermes.push;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;

import org.llm4people.hermes.net.Exchange;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.IOException;
import java.util.Locale;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PushRoutingTest extends InstrumentationTestCase {
    private Context context;
    private PushStore store;
    private PushStore.Device device;
    private final String event = "35db19fc00bd4d7cb516cb64e6a4f000";

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        store = PushStore.get(context);
        PushConfig config = PushConfig.parse(new JSONObject("{\"provider\":\"fcm\",\"firebase\":"
                + "{\"applicationId\":\"1:123456:android:aabbcc\",\"gcmSenderId\":\"123456\","
                + "\"apiKey\":\"public-test-key\",\"projectId\":\"example-test\"}}"));
        device = store.prepare(UUID.randomUUID().toString(), "https://example.test/p/a", config);
        store.enable(device);
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

    private Intent tapIntent() {
        return new Intent(PushNotices.ACTION, new Uri.Builder().scheme("hermes-mobile")
                .authority("event").appendPath(device.installation).appendPath(event).build());
    }

    public void testMissingOrRemovedEventCannotSelectProfile() {
        assertNull(MobilePush.target(context, tapIntent()));
        assertTrue(store.remember(device.installation,event,"run_completed"));
        assertFalse(store.remember(device.installation,event,"run_completed"));
        MobilePush.Target target = MobilePush.target(context, tapIntent());
        assertEquals(device.profile, target.profileId);
        assertEquals(event, target.eventId);
        assertEquals("run_completed", target.kind);
        assertNull(MobilePush.target(context, tapIntent().setAction(Intent.ACTION_VIEW)));
        store.deactivate(device.profile);
        assertNull(MobilePush.target(context, tapIntent()));
        assertFalse(store.remember(device.installation, "45db19fc00bd4d7cb516cb64e6a4f000", "reminder"));
    }

    public void testAcknowledgedDeviceRevocationClearsDurableTombstone() throws Exception {
        try (Exchange bridge = new Exchange("204 No Content", "", "")) {
            PushStore.Device removing = store.prepare(UUID.randomUUID().toString(), bridge.endpoint(), device.config);
            try {
                store.deactivate(removing.profile);
                String key = removing.managementKey();
                PushConnection.remove(context, removing, key);
                String request = bridge.observed();
                assertTrue(request.startsWith("DELETE /p/travail/api/mobile/push/devices/"
                        + removing.installation + " HTTP/1.1"));
                assertTrue(request.toLowerCase(Locale.ROOT).contains("authorization: bearer " + key));
                assertNull(store.forProfile(removing.profile));
            } finally {
                store.deactivate(removing.profile);
                store.finishRemoval(removing);
            }
        }
    }

    public void testColdStartRevokesEnrolledDeviceMissingItsProfile() {
        assertTrue(store.remember(device.installation, event, "reminder"));
        assertNotNull(MobilePush.target(context, tapIntent()));
        assertTrue(MobilePush.reconcileRemovals(context));
        assertEquals(PushStore.REMOVING, store.forProfile(device.profile).state);
        assertNull(MobilePush.target(context, tapIntent()));
        assertFalse(MobilePush.isEnabled(context, device.profile));
        assertFalse(store.remember(device.installation, "55db19fc00bd4d7cb516cb64e6a4f000", "reminder"));
        assertTrue(MobilePush.reconcileRemovals(context));
    }

    public void testHistorySkipsUnknownEventsAndChecksPagination() throws Exception {
        JSONArray events = new JSONArray()
                .put(new JSONObject().put("event_id", event).put("kind", "reminder").put("created_at", 1700000000))
                .put(new JSONObject().put("event_id", "../other").put("kind", "reminder"));
        JSONObject response = new JSONObject().put("events", events).put("next_cursor", 12).put("has_more", true);
        PushHistory.Page page = new PushHistory.Page(response, 0, Locale.CANADA_FRENCH);
        assertEquals(1, page.events.size());
        assertEquals(event, page.events.get(0).getString("event_id"));
        assertTrue(page.labels.get(0).startsWith("Un rappel vous attend"));
        try {
            new PushHistory.Page(response, 12, Locale.CANADA_FRENCH);
            fail("Non-advancing cursor accepted");
        } catch (IOException expected) {}
    }

    public void testDeviceCapabilityCannotBeCopiedAcrossProfiles() throws Exception {
        assertTrue(device.managementKey().matches("[a-f0-9]{64}"));
        try { DeviceSecret.open(device.secret, "other-profile"); fail("unbound device key"); }
        catch (GeneralSecurityException expected) {}
    }

    public void testDataOnlyDeliveryFiltersSenderAndKeepsPayloadPrivate() {
        if (Build.VERSION.SDK_INT >= 33) {
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS);
        }
        Map<String,String> data = new HashMap<>();
        data.put("installation_id", device.installation);
        data.put("kind", "reminder");
        data.put("event_id", event);
        data.put("title", "sensitive-body-never-display");
        PushNotices.deliver(context, "some-other-sender", data);
        assertNull(MobilePush.target(context, tapIntent()));
        PushNotices.deliver(context, device.config.senderId, data);
        assertNotNull(MobilePush.target(context, tapIntent()));
        String tag = device.installation + "/" + event;
        int count = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (count == 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (postedNotice(tag)) count++;
            else android.os.SystemClock.sleep(40);
        }
        assertEquals(1, count);
    }

    private boolean postedNotice(String tag) {
        for (StatusBarNotification active : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (!tag.equals(active.getTag())) continue;
            assertEquals("Un rappel vous attend", active.getNotification().extras.getString("android.title"));
            assertFalse(active.getNotification().extras.toString().contains("sensitive-body-never-display"));
            return true;
        }
        return false;
    }
}
