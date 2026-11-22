package org.llm4people.hermes.push;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.ProfileGate;

import java.io.IOException;
import java.util.List;

public final class MobilePush {
    public interface EventHost {
        void openPushEvent(Account owner, JSONObject event);
    }

    private final Activity activity;

    public MobilePush(Activity activity) { this.activity = activity; }

    public void settings(Account account) {
        lock();
        PushSettings flow = new PushSettings(activity, account);
        decor().setTag(R.id.push_settings_flow, flow);
        flow.show();
    }

    public void onPermissionResult(int requestCode) {
        PushSettings flow = (PushSettings) decor().getTag(R.id.push_settings_flow);
        if (flow != null && requestCode == PushSettings.PERMISSION) flow.permissionResult();
    }

    public void lock() {
        PushSettings flow = (PushSettings) decor().getTag(R.id.push_settings_flow);
        decor().setTag(R.id.push_settings_flow, null);
        if (flow != null) flow.close();
    }

    private View decor() { return activity.getWindow().getDecorView(); }

    public static boolean isEnabled(Context context, String profileId) {
        PushStore.Device device = PushStore.get(context).forProfile(profileId);
        return device != null && PushStore.ACTIVE.equals(device.state) && PushNotices.allowed(context);
    }

    public static void forget(Context context, String profileId) {
        PushStore.Device device = PushConnection.forget(context, profileId);
        if (device == null) return;
        PushNotices.cancel(context, device.installation);
        PushSyncService.schedule(context);
    }

    static boolean reconcileRemovals(Context context) {
        boolean pending = false;
        for (PushStore.Device device : PushStore.get(context).devices()) {
            if (!PushStore.REMOVING.equals(device.state) && ProfileGate.contains(context, device.profile)) continue;
            PushConnection.forget(context, device.profile);
            PushNotices.cancel(context, device.installation);
            pending = true;
        }
        return pending;
    }

    public static final class Target {
        public final String profileId, eventId, kind;

        private Target(PushStore.Device owner, String event, String kind) {
            this.profileId = owner.profile;
            this.eventId = event;
            this.kind = kind;
        }
    }

    public static Target target(Context context, Intent intent) {
        if (intent == null || !PushNotices.ACTION.equals(intent.getAction())) return null;
        Uri uri = intent.getData();
        if (uri == null || !"hermes-mobile".equals(uri.getScheme()) || !"event".equals(uri.getAuthority())) {
            return null;
        }
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !PushNotices.validIds(path.get(0), path.get(1))) return null;
        PushStore store = PushStore.get(context);
        PushStore.Device owner = store.forInstallation(path.get(0));
        if (owner == null || !PushStore.ACTIVE.equals(owner.state)) return null;
        String kind = store.knownKind(owner.installation, path.get(1));
        return kind == null ? null : new Target(owner, path.get(1), kind);
    }

    public static JSONObject resolve(Account account, Target target) throws IOException {
        if (target == null || !target.profileId.equals(account.id)) throw new IOException("profile mismatch");
        JSONObject event = new HermesApi(account.endpoint,account.bearer)
                .get(PushConnection.BASE + "/events/" + target.eventId);
        if (!target.eventId.equals(event.optString("event_id")) || !target.kind.equals(event.optString("kind"))) {
            throw new IOException("event mismatch");
        }
        return event;
    }
}
