package org.llm4people.hermes.push;

import android.content.Context;
import android.content.Intent;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.MessagingReceiver;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retains the connector's token checks, Web Push decryption and acknowledgement handling. */
public final class DistributorReceiver extends MessagingReceiver {
    private static final ExecutorService MESSAGES = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        MESSAGES.execute(() -> {
            try {
                process(context, intent);
            } finally { result.finish(); }
        });
    }

    void process(Context context, Intent intent) {
        // A successor is a different package. The SDK would immediately send it all profiles'
        // registrations, including on temporary fallback. Require a fresh choice in Settings.
        Intent consented = new Intent(intent);
        consented.removeExtra("newDistributor");
        synchronized (PushConnection.class) { super.onReceive(context, consented); }
    }

    @Override public void onNewEndpoint(Context context, PushEndpoint endpoint, String instance) {
        try {
            DistributorEnrollment.receivedEndpoint(context, instance, endpoint);
        } catch (Exception failure) {
            Diagnostics.warning("push", "subscription_unavailable");
        }
    }

    @Override public void onMessage(Context context, PushMessage message, String instance) {
        receive(context, message, instance);
    }

    static void receive(Context context, PushMessage message, String instance) {
        if (!message.getDecrypted() || message.getContent().length > 4096) return;
        try {
            JSONObject payload = new JSONObject(new String(message.getContent(), StandardCharsets.UTF_8));
            if (!instance.equals(payload.optString("installation_id"))) return;
            long expiresAt = WebPushExpiry.read(payload.opt("expires_at"));
            if (!WebPushExpiry.mayDispatch(payload.optString("kind"), expiresAt, System.currentTimeMillis())) return;
            PushStore.Device owner = PushStore.get(context).forInstallation(instance);
            if (owner != null && owner.unified()) {
                PushNotices.post(context, owner, payload.optString("event_id"), payload.optString("kind"));
            }
        } catch (JSONException ignored) {
            Diagnostics.warning("push", "invalid_message");
        }
    }

    @Override public void onRegistrationFailed(Context context, FailedReason reason, String instance) {
        PushStore store = PushStore.get(context);
        PushStore.Device device = store.forInstallation(instance);
        if (device == null || !device.unified()) return;
        if (store.registrationFailed(device, reason, System.currentTimeMillis())) {
            PushSyncService.scheduleRetries(context);
            Diagnostics.warning("push", "distributor_registration_failed");
        }
    }

    @Override public void onUnregistered(Context context, String instance) {
        PushStore.Device device = PushStore.get(context).forInstallation(instance);
        if (device != null && device.unified() && !PushStore.REMOVING.equals(device.state)) {
            MobilePush.forget(context, device.profile);
        }
    }
}
