package org.llm4people.hermes.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lightweight 24-hour throttled silent GitHub update check coordinator returning an optional
 * non-intrusive badge state for Activité. Stores only check timestamps and public release tags —
 * never any profile data or user secrets.
 */
public final class UpdateCheckCoordinator {
    private static final String PREFS_NAME = "hermes_update_check";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    private static final String KEY_LATEST_TAG = "latest_tag";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-update-check");
        t.setDaemon(true);
        return t;
    });

    public record BadgeState(boolean updateAvailable, String latestTag, String installedVersion) {}

    /** Pure-Java persistence abstraction for deterministic JVM throttle testing. */
    public interface ThrottleStore {
        long lastCheckMs();
        String latestTag();
        void save(long lastCheckMs, String latestTag);
    }

    private final HermesConfig config;
    private final ThrottleStore store;
    private final GithubReleaseClient client;

    public UpdateCheckCoordinator(Context context) {
        this(HermesConfig.load(context), new PrefsThrottleStore(context), null);
    }

    public UpdateCheckCoordinator(HermesConfig config, ThrottleStore store, GithubReleaseClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.client = client != null ? client : new GithubReleaseClient(config);
    }

    public boolean isDue(long nowMs) {
        long last = store.lastCheckMs();
        return last <= 0L || nowMs < last || (nowMs - last) >= config.updateCheckIntervalMs;
    }

    public BadgeState cachedBadge(String installedVersion) {
        return badgeFor(store.latestTag(), installedVersion);
    }

    public void recordManualCheck(long nowMs, String latestTag) {
        store.save(nowMs, latestTag == null ? "" : latestTag.trim());
    }

    /** Synchronous check honoring the configured interval; safe on background threads and JVM tests. */
    public BadgeState checkIfDue(long nowMs, String installedVersion) {
        if (!isDue(nowMs)) {
            return cachedBadge(installedVersion);
        }
        try {
            GithubReleaseClient.ReleaseInfo latest = client.fetchLatestRelease();
            store.save(nowMs, latest.tagName());
            return badgeFor(latest.tagName(), installedVersion);
        } catch (IOException | RuntimeException offline) {
            logDebug("silent_check_skipped");
            return cachedBadge(installedVersion);
        }
    }

    /** Non-blocking silent check delivering the resulting badge state on the Android main thread. */
    public static void checkIfDueAsync(Context context, Consumer<BadgeState> onResult) {
        if (context == null || onResult == null) return;
        Context app = context.getApplicationContext();
        String installed = UpdatePanel.installedVersionName(app);
        UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator(app);
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            BadgeState state = coordinator.checkIfDue(System.currentTimeMillis(), installed);
            main.post(() -> onResult.accept(state));
        });
    }

    static BadgeState badgeFor(String latestTag, String installedVersion) {
        ReleaseVersion latest = ReleaseVersion.parseOrNull(latestTag);
        ReleaseVersion current = ReleaseVersion.parseOrNull(installedVersion);
        boolean available = latest != null && current != null && latest.isNewerThan(current);
        return new BadgeState(available, available ? latestTag.trim() : "", installedVersion == null ? "" : installedVersion);
    }

    private static void logDebug(String action) {
        try { Diagnostics.event("update", action); }
        catch (RuntimeException unmockedHostJvm) {}
    }

    private static final class PrefsThrottleStore implements ThrottleStore {
        private final SharedPreferences prefs;

        PrefsThrottleStore(Context context) {
            this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        @Override public long lastCheckMs() { return prefs.getLong(KEY_LAST_CHECK_MS, 0L); }
        @Override public String latestTag() { return prefs.getString(KEY_LATEST_TAG, ""); }

        @Override public void save(long lastCheckMs, String latestTag) {
            prefs.edit()
                    .putLong(KEY_LAST_CHECK_MS, lastCheckMs)
                    .putString(KEY_LATEST_TAG, latestTag == null ? "" : latestTag)
                    .apply();
        }
    }
}
