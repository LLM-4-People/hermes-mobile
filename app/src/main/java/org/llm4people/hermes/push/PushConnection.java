package org.llm4people.hermes.push;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class PushConnection {
    static final String BASE = "/api/mobile/push";
    static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static long forgetVersion;

    static final class DifferentProject extends IOException {}
    static final class MissingPlayServices extends IOException {}

    static synchronized long version() { return forgetVersion; }
    static synchronized PushStore.Device forget(Context context, String id) {
        forgetVersion++;
        PushStore store = PushStore.get(context);
        PushStore.Device device = store.forProfile(id);
        if (device != null) store.deactivate(id);
        return device;
    }

    static synchronized void configure(Context context, PushConfig config) throws DifferentProject {
        FirebaseApp app;
        try { app = FirebaseApp.getInstance(); }
        catch (IllegalStateException missing) {
            FirebaseApp.initializeApp(context.getApplicationContext(), config.options());
            return;
        }
        if (!config.matches(app.getOptions())) {
            for (PushStore.Device device : PushStore.get(context).devices())
                if (!device.unified() && !PushStore.REMOVING.equals(device.state)) throw new DifferentProject();
            FirebaseMessaging.getInstance().setAutoInitEnabled(false);
            app.delete();
            FirebaseApp.initializeApp(context.getApplicationContext(), config.options());
        }
    }

    static synchronized void restore(Context context) {
        for (PushStore.Device device : PushStore.get(context).devices()) {
            if (PushStore.REMOVING.equals(device.state) || device.unified()) continue;
            try {
                configure(context, device.config);
                FirebaseMessaging.getInstance().setAutoInitEnabled(true);
            } catch (DifferentProject | RuntimeException failure) {
                Diagnostics.event("push", "initialization_failed");
            }
            return;
        }
    }

    static synchronized void releaseIfEmpty(Context context) {
        for (PushStore.Device device : PushStore.get(context).devices()) {
            if (!device.unified() && !PushStore.REMOVING.equals(device.state)) return;
        }
        try {
            FirebaseApp current = FirebaseApp.getInstance();
            FirebaseMessaging.getInstance().setAutoInitEnabled(false);
            current.delete();
        } catch (IllegalStateException absent) {
            // There may be no Firebase app when removing a registration after process death.
        }
    }

    private static String token(Context context, PushConfig config) throws Exception {
        int availability = com.google.android.gms.common.GoogleApiAvailabilityLight.getInstance()
                .isGooglePlayServicesAvailable(context);
        if (availability != com.google.android.gms.common.ConnectionResult.SUCCESS) throw new MissingPlayServices();
        configure(context, config);
        return Tasks.await(FirebaseMessaging.getInstance().getToken(), 45, TimeUnit.SECONDS);
    }

    static void enroll(Context context, Account account, PushConfig config, long before, BooleanSupplier current) throws Exception {
        HermesApi api = new HermesApi(account.endpoint, account.bearer);
        if (!current.getAsBoolean()) return;
        String token = token(context, config);
        PushStore store = PushStore.get(context);
        PushStore.Device device;
        synchronized (PushConnection.class) {
            if (before != forgetVersion || !current.getAsBoolean()) return;
            device = store.prepare(account.id, account.endpoint, config);
        }
        api.put(device.path(), fcmRegistration(token).put("management_key", device.managementKey()));
        store.enable(device);
        if (PushStore.ACTIVE.equals(store.forProfile(account.id).state)) {
            FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        }
        Diagnostics.event("push", "enrolled");
    }

    static void remove(Context context, PushStore.Device device, String bearer) throws Exception {
        if (device.unified()) DistributorEnrollment.unregister(context, device);
        new HermesApi(device.endpoint, bearer).delete(device.path());
        PushStore.get(context).finishRemoval(device);
        releaseIfEmpty(context);
    }

    static JSONObject fcmRegistration(String token) throws JSONException {
        return new JSONObject().put("token", token);
    }

    static boolean synchronize(Context context, BooleanSupplier stopped) {
        PushStore store = PushStore.get(context);
        boolean retry = false;
        DistributorEnrollment.reconcile(context);
        for (PushStore.Device device : store.devices()) {
            if (stopped.getAsBoolean()) return true;
            try {
                if (PushStore.REMOVING.equals(device.state)) {
                    remove(context, device, device.managementKey());
                } else if (device.unified()) {
                    DistributorEnrollment.sync(context, device);
                } else {
                    String token = token(context, device.config);
                    new HermesApi(device.endpoint, device.managementKey()).put(device.path(), fcmRegistration(token));
                    store.enable(device);
                }
            } catch (HermesApi.HttpError failure) {
                if (failure.status == 404 && PushStore.REMOVING.equals(device.state)) {
                    store.finishRemoval(device);
                } else if (failure.status == 400 || failure.status == 401 || failure.status == 403 || failure.status == 409 || failure.status == 422) {
                    store.pending(device);
                    Diagnostics.event("push", "registration_needs_unlock");
                } else {
                    retry = true;
                }
            } catch (Exception failure) {
                retry = true;
                Diagnostics.event("push", "sync_deferred");
            }
        }
        releaseIfEmpty(context);
        return retry;
    }
}
