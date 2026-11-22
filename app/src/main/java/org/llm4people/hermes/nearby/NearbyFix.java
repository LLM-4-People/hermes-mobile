package org.llm4people.hermes.nearby;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class NearbyFix {
    private NearbyFix() {}

    public interface Callback {
        void done(JSONObject fix);
    }

    /** Call from the resumed activity after coarse permission is granted. Cancel on the UI thread
     * when leaving the owning account or discussion. Cancellation and activity pause suppress delivery.
     * Otherwise the main-thread callback receives one neighborhood or null for an unavailable fix. */
    public static Runnable request(Activity activity, Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("UI thread required");
        Objects.requireNonNull(callback);
        Platform source = new Platform(activity);
        NearbyRequest request = new NearbyRequest(source, area -> callback.done(area == null ? null : area.json()));
        source.onLeave = request::cancel;
        activity.getApplication().registerActivityLifecycleCallbacks(source);
        source.after(0, () -> request.start(Build.VERSION.SDK_INT >= 31));
        return request::cancel;
    }

    private static final class Platform implements NearbyRequest.Source, Application.ActivityLifecycleCallbacks {
        private final Activity activity;
        private final LocationManager manager;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final List<Runnable> subscriptions = new ArrayList<>(2);
        private Runnable onLeave;

        Platform(Activity activity) {
            this.activity = activity;
            manager = activity.getSystemService(LocationManager.class);
        }

        @Override public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
        @Override public void after(long millis, Runnable action) { main.postDelayed(action, millis); }

        @Override public boolean available() {
            if (manager == null || activity.isFinishing() || activity.isDestroyed()
                    || activity.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE
                    || activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return false;
            try {
                return manager.isLocationEnabled();
            } catch (RuntimeException serviceUnavailable) {
                return false;
            }
        }

        @SuppressLint("MissingPermission") // NearbyRequest checks the grant before each read and delivery.
        @SuppressWarnings("deprecation")
        @Override public void read(boolean fused, Consumer<CoarseArea> received) {
            String provider = fused ? LocationManager.FUSED_PROVIDER : LocationManager.NETWORK_PROVIDER;
            CoarseArea cached = null;
            try {
                if (manager.isProviderEnabled(provider)) {
                    cached = area(manager.getLastKnownLocation(provider));
                    if (cached == null) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            CancellationSignal signal = new CancellationSignal();
                            subscriptions.add(signal::cancel);
                            manager.getCurrentLocation(provider, signal, activity.getMainExecutor(),
                                    location -> received.accept(area(location)));
                        } else {
                            LocationListener listener = new LocationListener() {
                                @Override public void onLocationChanged(Location location) {
                                    received.accept(area(location));
                                }
                                @Override public void onProviderDisabled(String name) { received.accept(null); }
                                @Override public void onProviderEnabled(String name) {}
                                @Override public void onStatusChanged(String name, int status, Bundle extras) {}
                            };
                            subscriptions.add(() -> manager.removeUpdates(listener));
                            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                        }
                        return;
                    }
                }
            } catch (RuntimeException serviceUnavailable) {
                // Providers and permissions can disappear between the check and the binder call.
            }
            received.accept(cached);
        }

        private static CoarseArea area(Location location) {
            return CoarseArea.from(location, SystemClock.elapsedRealtimeNanos());
        }

        @Override public void close() {
            main.removeCallbacksAndMessages(null);
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            for (Runnable stop : subscriptions) {
                try { stop.run(); }
                catch (RuntimeException serviceUnavailable) { /* The provider may already be gone. */ }
            }
            subscriptions.clear();
        }

        @Override public void onActivityPaused(Activity owner) { if (owner == activity) onLeave.run(); }
        @Override public void onActivityStopped(Activity owner) { if (owner == activity) onLeave.run(); }
        @Override public void onActivityDestroyed(Activity owner) { if (owner == activity) onLeave.run(); }
        @Override public void onActivityCreated(Activity owner, Bundle state) {}
        @Override public void onActivityStarted(Activity owner) {}
        @Override public void onActivityResumed(Activity owner) {}
        @Override public void onActivitySaveInstanceState(Activity owner, Bundle outState) {}
    }
}
