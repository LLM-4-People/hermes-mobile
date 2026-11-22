package org.llm4people.hermes.push;

import android.content.Context;

import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import org.unifiedpush.android.connector.UnifiedPush;
import org.unifiedpush.android.connector.data.PublicKeySet;
import org.unifiedpush.android.connector.data.PushEndpoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Registers an opaque per-profile instance. Its management credential can update only this device. */
final class DistributorEnrollment {
    static final String PROVIDER = "unifiedpush";

    private DistributorEnrollment() {}

    static String vapid(JSONObject discovery) throws Exception {
        String key = discovery.getJSONObject(PROVIDER).getString("vapidPublicKey");
        if (!key.matches("[A-Za-z0-9_-]{87}")) throw new IOException("Invalid VAPID key");
        return key;
    }

    static List<String> choices(Context context) {
        List<String> choices = new ArrayList<>(UnifiedPush.getDistributors(context));
        // The connector selects a distributor per application; do not reroute another account.
        for (PushStore.Device device : PushStore.get(context).devices()) {
            if (device.unified() && !PushStore.REMOVING.equals(device.state)) {
                choices.removeIf(name -> !device.distributor.equals(name));
            }
        }
        return choices;
    }

    static void enroll(Context context, Account account, String distributor, String vapid,
                       long before, BooleanSupplier current) throws Exception {
        PushStore.Device device;
        synchronized (PushConnection.class) {
            if (before != PushConnection.version() || !current.getAsBoolean()) return;
            if (!choices(context).contains(distributor)) throw new IOException("Distributor unavailable");
            device = PushStore.get(context).prepareUnified(account.id, account.endpoint, distributor, vapid);
        }
        // Reserve before the callback. The full profile bearer never enters the background service.
        new HermesApi(account.endpoint, account.bearer).put(device.path(),
                new JSONObject().put("provider", PROVIDER).put("management_key", device.managementKey()));
        synchronized (PushConnection.class) {
            PushStore.Device stored = PushStore.get(context).forInstallation(device.installation);
            if (stored == null || PushStore.REMOVING.equals(stored.state)) return;
            UnifiedPush.saveDistributor(context, distributor);
            register(context, stored);
        }
    }

    private static void register(Context context, PushStore.Device device) {
        UnifiedPush.register(context, device.installation, "Hermes Mobile", device.vapid);
    }

    static void unregister(Context context, PushStore.Device device) {
        UnifiedPush.unregister(context, device.installation);
    }

    static void receivedEndpoint(Context context, String instance, PushEndpoint endpoint) throws Exception {
        PushStore store = PushStore.get(context);
        PushStore.Device device = store.forInstallation(instance);
        if (device == null || !device.unified() || PushStore.REMOVING.equals(device.state)) return;
        PublicKeySet keys = endpoint.getPubKeySet();
        if (keys == null || endpoint.getTemporary()) return;
        String url = endpoint.getUrl();
        if (url.length() > 2048 || !keys.getPubKey().matches("[A-Za-z0-9_-]{87}")
                || !keys.getAuth().matches("[A-Za-z0-9_-]{22}")) throw new IOException("Invalid subscription");
        JSONObject subscription = new JSONObject().put("endpoint", url).put("keys",
                new JSONObject().put("p256dh", keys.getPubKey()).put("auth", keys.getAuth()));
        if (store.updateSubscription(device, subscription.toString())) {
            PushSyncService.schedule(context);
            // Registration is interactive. Start the handoff now; the persisted job survives process exit.
            Context app = context.getApplicationContext();
            PushConnection.WORK.execute(() -> {
                try {
                    PushStore.Device current = store.forInstallation(instance);
                    if (current != null && PushStore.PENDING.equals(current.state)) sync(app, current);
                } catch (Exception failure) {
                    org.llm4people.hermes.Diagnostics.warning("push", "subscription_sync_deferred");
                }
            });
        }
    }

    static void sync(Context context, PushStore.Device device) throws Exception {
        if (!PushStore.PENDING.equals(device.state)) return;
        if (device.subscription == null) {
            // Re-request after an interrupted callback. Trigger again only for a still-owned distributor.
            if (device.distributor.equals(UnifiedPush.getSavedDistributor(context))) register(context, device);
            return;
        }
        String json = DeviceSecret.open(device.subscription, device.subscriptionBinding());
        new HermesApi(device.endpoint, device.managementKey()).put(device.path(),
                new JSONObject().put("provider", PROVIDER).put("subscription", new JSONObject(json)));
        PushStore.get(context).enable(device);
    }
}
