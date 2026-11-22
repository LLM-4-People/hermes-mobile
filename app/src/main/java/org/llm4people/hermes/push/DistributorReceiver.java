package org.llm4people.hermes.push;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.nio.charset.StandardCharsets;

/** The connector verifies its instance token and decrypts Web Push before calling this private service. */
public final class DistributorReceiver extends PushService {
    @Override public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        try {
            DistributorEnrollment.receivedEndpoint(this, instance, endpoint);
        } catch (Exception failure) {
            Diagnostics.warning("push", "subscription_unavailable");
        }
    }

    @Override public void onMessage(PushMessage message, String instance) {
        receive(this, message, instance);
    }

    static void receive(Context context, PushMessage message, String instance) {
        if (!message.getDecrypted() || message.getContent().length > 4096) return;
        try {
            JSONObject payload = new JSONObject(new String(message.getContent(), StandardCharsets.UTF_8));
            if (!instance.equals(payload.optString("installation_id"))) return;
            PushStore.Device owner = PushStore.get(context).forInstallation(instance);
            if (owner != null && owner.unified()) {
                PushNotices.post(context, owner, payload.optString("event_id"), payload.optString("kind"));
            }
        } catch (JSONException ignored) {
            Diagnostics.warning("push", "invalid_message");
        }
    }

    @Override public void onRegistrationFailed(FailedReason reason, String instance) {
        PushStore store = PushStore.get(this);
        PushStore.Device device = store.forInstallation(instance);
        if (device == null || !device.unified()) return;
        store.distributorFailed(device);
        Diagnostics.warning("push", "distributor_registration_failed");
    }

    @Override public void onUnregistered(String instance) {
        PushStore.Device device = PushStore.get(this).forInstallation(instance);
        if (device != null && device.unified() && !PushStore.REMOVING.equals(device.state)) {
            MobilePush.forget(this, device.profile);
        }
    }
}
