package org.llm4people.hermes.push;

import android.app.Application;

public final class PushApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DistributorEnrollment.reconcile(this);
        boolean pending = MobilePush.reconcileRemovals(this);
        PushStore store = PushStore.get(this);
        for (PushStore.Device device : store.devices()) {
            if (!device.unified()) continue;
            pending |= PushStore.PENDING.equals(device.state);
            if (PushStore.ACTIVE.equals(device.state)) store.drain(this, device);
        }
        PushSyncService.scheduleRetries(this);
        if (pending) PushSyncService.ensureScheduled(this);
        PushConnection.restore(this);
    }
}
