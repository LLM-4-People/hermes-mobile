package org.llm4people.hermes.push;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;

import org.json.JSONObject;

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
        for (StatusBarNotification active : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (!tag.equals(active.getTag())) continue;
            count++;
            assertEquals("Un rappel vous attend", active.getNotification().extras.getString("android.title"));
            assertFalse(active.getNotification().extras.toString().contains("sensitive-body-never-display"));
        }
        assertEquals(1, count);
    }
}
