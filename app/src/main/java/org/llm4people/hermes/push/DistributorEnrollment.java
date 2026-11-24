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

    // The SDK tracks one distributor for the application. Recover every affected profile
    // even if the uninstall happened while Hermes was stopped and no broadcast was delivered.
    static void reconcile(Context context) { reconcile(context, false, null); }

    // The Android package manager has no historical data-clear marker. A visible entry repairs
    // each still-consented binding with the existing SDK instance, wire token and encryption keys.
    static void reconcile(Context context, boolean repair, String resetPackage) {
        synchronized (PushConnection.class) {
            List<PushStore.Device> devices = PushStore.get(context).devices();
            if (devices.stream().noneMatch(PushStore.Device::unified)) return;
            List<String> installed = UnifiedPush.getDistributors(context);
            String saved = UnifiedPush.getSavedDistributor(context);
            for (PushStore.Device device : devices) {
                if (!device.unified()) continue;
                boolean trusted = DistributorIdentity.matches(context, device.distributor, device.signer);
                if (PushStore.REMOVING.equals(device.state)) {
                    if (!trusted) unregister(context, device);
                    continue;
                }
                if (!trusted
                        || !installed.contains(device.distributor) || saved != null && !saved.equals(device.distributor)
                        || saved == null && device.subscription != null) {
                    unregister(context, device);
                    MobilePush.forget(context, device.profile);
                } else if (repair && (resetPackage == null || resetPackage.equals(device.distributor))) {
                    if (device.subscription != null && (PushStore.ACTIVE.equals(device.state) || PushStore.PENDING.equals(device.state)))
                        repair(context, device, false);
                    else if (resetPackage != null) requestEndpoint(context, device);
                }
            }
        }
    }

    static void enroll(Context context, Account account, String distributor, String signer, String vapid,
                       long before, BooleanSupplier current) throws Exception {
        PushStore.Device device;
        synchronized (PushConnection.class) {
            if (before != PushConnection.version() || !current.getAsBoolean()) return;
            if (!DistributorIdentity.matches(context, distributor, signer) || !choices(context).contains(distributor)) throw new IOException("Distributor unavailable");
            device = PushStore.get(context).prepareUnified(account.id, account.endpoint, distributor, signer, vapid);
        }
        // Reserve before the callback. The full profile bearer never enters the background service.
        new HermesApi(account.endpoint, account.bearer, PushConnection.apiLimits(context)).put(device.path(),
                new JSONObject().put("provider", PROVIDER).put("management_key", device.managementKey()));
        synchronized (PushConnection.class) {
            PushStore.Device stored = PushStore.get(context).forInstallation(device.installation);
            if (stored == null || PushStore.REMOVING.equals(stored.state)) return;
            if (stored.repair != PushStore.NO_REPAIR) repair(context, stored, stored.repair == PushStore.REPAIR_ACTION);
            else if (stored.subscription == null) requestEndpoint(context, stored);
            else PushSyncService.schedule(context);
        }
    }

    private static void requestEndpoint(Context context, PushStore.Device device) {
        synchronized (PushConnection.class) {
            PushStore store = PushStore.get(context);
            device = store.forInstallation(device.installation);
            if (device == null || !PushStore.PENDING.equals(device.state) || device.subscription != null) return;
            long remaining = device.retryAt - System.currentTimeMillis();
            if (remaining > 0 && remaining <= PushStore.MAX_DELAY) return;
            String saved = UnifiedPush.getSavedDistributor(context);
            if (!DistributorIdentity.matches(context, device.distributor, device.signer)
                    || !UnifiedPush.getDistributors(context).contains(device.distributor)
                    || saved != null && !saved.equals(device.distributor)) {
                MobilePush.forget(context, device.profile);
                return;
            }
            // A rejected connector wire token must never be reused. Hermes' server ID stays stable.
            if (device.retryAt != 0 || PushStore.RENEW.equals(device.failure)) unregister(context, device);
            UnifiedPush.saveDistributor(context, device.distributor);
            register(context, device);
            store.registered(device);
        }
    }

    private static void register(Context context, PushStore.Device device) {
        UnifiedPush.register(DistributorIdentity.outgoing(context, device), device.installation, "Hermes Mobile", device.vapid);
    }

    static void repair(Context context, PushStore.Device device, boolean explicit) {
        synchronized (PushConnection.class) {
            PushStore store = PushStore.get(context);
            PushStore.Device current = store.forInstallation(device.installation);
            if (current == null || current.subscription == null
                    || !(PushStore.ACTIVE.equals(current.state) || PushStore.PENDING.equals(current.state))) return;
            if (!DistributorIdentity.matches(context, current.distributor, current.signer)
                    || !current.distributor.equals(UnifiedPush.getSavedDistributor(context))) {
                reconcile(context);
                return;
            }
            if (!store.beginRepair(current, System.currentTimeMillis(), explicit)) return;
            PushSyncService.scheduleRetries(context);
            register(context, current);
        }
    }

    static void retryDue(Context context) {
        for (PushStore.Device device : PushStore.get(context).devices()) {
            if (!device.unified() || device.retryAt == 0) continue;
            if (device.repair != PushStore.NO_REPAIR) repair(context, device, false);
            else if (PushStore.PENDING.equals(device.state)) requestEndpoint(context, device);
        }
    }

    static void unregister(Context context, PushStore.Device device) {
        UnifiedPush.unregister(DistributorIdentity.outgoing(context, device), device.installation);
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
        if (!PushStore.PENDING.equals(device.state) || device.repair != PushStore.NO_REPAIR) return;
        if (device.subscription == null) {
            requestEndpoint(context, device);
            return;
        }
        String json = DeviceSecret.open(device.subscription, device.subscriptionBinding());
        try {
            new HermesApi(device.endpoint, device.managementKey(), PushConnection.apiLimits(context)).put(device.path(),
                    new JSONObject().put("provider", PROVIDER).put("subscription", new JSONObject(json)));
            synchronized (PushConnection.class) {
                PushStore store = PushStore.get(context);
                store.enable(device);
                store.drain(context, device);
            }
        } catch (HermesApi.HttpError failure) {
            String reason = switch (failure.status) {
                case 400, 422 -> PushStore.ENDPOINT_REJECTED;
                case 401, 403, 404, 409 -> PushStore.ACCOUNT_REQUIRED;
                default -> null;
            };
            if (reason == null) throw failure;
            synchronized (PushConnection.class) { PushStore.get(context).uploadRejected(device, reason); }
        }
    }
}
