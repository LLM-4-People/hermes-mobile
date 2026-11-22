package org.llm4people.hermes.push;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public final class PushReceiver extends FirebaseMessagingService {
    @Override public void onMessageReceived(RemoteMessage message) {
        PushNotices.deliver(this, message.getFrom(), message.getData());
    }

    @Override public void onNewToken(String token) {
        if (!PushStore.get(this).devices().isEmpty()) PushSyncService.schedule(this);
    }
}
