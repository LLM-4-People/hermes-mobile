package org.llm4people.hermes.push;

import android.app.Application;

public final class PushApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        boolean pending = MobilePush.reconcileRemovals(this);
        for (PushStore.Device device : PushStore.get(this).devices())
            pending |= device.unified() && PushStore.PENDING.equals(device.state);
        if (pending) PushSyncService.ensureScheduled(this);
        PushConnection.restore(this);
    }
}
