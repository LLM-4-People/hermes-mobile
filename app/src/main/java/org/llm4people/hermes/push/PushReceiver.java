package org.llm4people.hermes.push;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public final class PushReceiver extends FirebaseMessagingService {
    @Override public void onMessageReceived(RemoteMessage message) {
        PushNotices.deliver(this, message.getFrom(), message.getData());
    }

    @Override public void onNewToken(String token) {
        for (PushStore.Device device : PushStore.get(this).devices()) {
            if (!device.unified()) {
                PushSyncService.schedule(this);
                return;
            }
        }
    }
}
