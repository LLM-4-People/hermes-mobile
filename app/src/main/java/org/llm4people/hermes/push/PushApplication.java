package org.llm4people.hermes.push;

import android.app.Application;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.perf.MemoryPressure;
import org.llm4people.hermes.thread.ThreadStrings;

public final class PushApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLanguage.refreshRuntime(this);
        ThreadStrings.attach(this);
        Diagnostics.setCapacity(HermesConfig.load(this).diagnosticsRingCapacity);
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
        PushConnection.WORK.execute(() -> {
            try {
                store.pruneExpired(System.currentTimeMillis());
            } catch (RuntimeException ignored) {
                Diagnostics.debug("push", "startup_prune_skipped", ignored);
            }
        });
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        MemoryPressure.onTrimMemory(level);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        MemoryPressure.clearAll();
    }
}
