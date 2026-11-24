package org.llm4people.hermes.doctor;

import android.app.NotificationManager;
import android.content.Context;

import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.ambient.HermesNotificationListener;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.panel.BrainCapabilities;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.push.MobilePush;
import org.llm4people.hermes.update.UpdateCheckCoordinator;
import org.llm4people.hermes.update.UpdatePanel;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallel, non-blocking Connection Doctor engine.
 * Runs all 6 health probes concurrently off the UI thread and produces a strictly redacted
 * {@link DoctorCheck.Report} with zero credential or user-content exposure.
 */
public final class ConnectionDoctor implements AutoCloseable {
    private static final int WORKER_THREADS = 6;
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    /** Companion fork signature flags absent from plain upstream Hermes gateways. */
    private static final Set<String> COMPANION_FORK_MARKERS = Set.of(
            BrainCapabilities.SMART_NOTES,
            BrainCapabilities.UNIVERSAL_SHARING,
            BrainCapabilities.SKILL_STUDIO,
            BrainCapabilities.WORKSPACE_FILES,
            BrainCapabilities.WORKSPACE_BROWSER,
            BrainCapabilities.USAGE_ANALYTICS,
            BrainCapabilities.PROFILE_DEFAULTS,
            BrainCapabilities.MEMORY_API,
            BrainCapabilities.SESSION_SEARCH,
            "mobile_push",
            "live_ui_patches",
            "device_actions"
    );

    public record ServerProbe(boolean reachable, boolean authRejected, boolean missingProfile, long latencyMs) {}

    public record CapabilitiesProbe(
            boolean fetched,
            boolean upstreamNonFork,
            int enabledCount,
            int expectedCount,
            boolean companionForkDetected) {}

    public record PushProbe(boolean enabled, boolean notificationsAllowed) {}

    public record UpdateProbe(String installedVersion, boolean updateAvailable, String latestTag, boolean checkedBefore) {}

    public record AmbientProbe(boolean listenerEnabled, boolean serviceConnected) {}

    public record LanguageProbe(String activeTag, String acceptLanguage, boolean synchronizedWithRequests) {}

    /** Pure-Java probe contract for deterministic JVM & instrumented testing. */
    public interface Probes {
        String rawEndpoint();
        String appVersion();
        String languageTag();
        ServerProbe probeServer() throws IOException;
        CapabilitiesProbe probeCapabilities() throws IOException;
        PushProbe probePush();
        UpdateProbe probeUpdates();
        AmbientProbe probeAmbient();
        LanguageProbe probeLanguage();
        default void close() {}
    }

    private final Probes probes;
    private final ExecutorService pool;
    private final long timeoutMs;
    private final long slowLatencyThresholdMs;
    private final int expectedForkCapabilities;

    public ConnectionDoctor(Probes probes) {
        this(probes, HermesConfig.defaults());
    }

    public ConnectionDoctor(Probes probes, HermesConfig config) {
        HermesConfig cfg = config == null ? HermesConfig.defaults() : config;
        this.probes = Objects.requireNonNull(probes, "probes");
        this.timeoutMs = Math.max(250L, cfg.doctorCheckTimeoutMs);
        this.slowLatencyThresholdMs = Math.max(1L, cfg.slowLatencyThresholdMs);
        this.expectedForkCapabilities = Math.max(1, cfg.expectedForkCapabilities);
        this.pool = Executors.newFixedThreadPool(WORKER_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "hermes-doctor-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public ConnectionDoctor(Probes probes, long timeoutMs) {
        HermesConfig defaults = HermesConfig.defaults();
        this.probes = Objects.requireNonNull(probes, "probes");
        this.timeoutMs = Math.max(250L, timeoutMs);
        this.slowLatencyThresholdMs = Math.max(1L, defaults.slowLatencyThresholdMs);
        this.expectedForkCapabilities = Math.max(1, defaults.expectedForkCapabilities);
        this.pool = Executors.newFixedThreadPool(WORKER_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "hermes-doctor-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Create a live Android ConnectionDoctor bound to the active profile, gateway API, and {@link HermesConfig}. */
    public static ConnectionDoctor forAndroid(Context context, Account account, HermesApi api) {
        Context app = context.getApplicationContext();
        HermesConfig cfg = HermesConfig.load(app);
        return new ConnectionDoctor(new AndroidProbes(app, account, api, cfg.expectedForkCapabilities), cfg);
    }

    /** Execute all 6 health checks in parallel and assemble a deterministic redacted report. */
    public DoctorCheck.Report runAll() {
        long now = System.currentTimeMillis();
        Future<DoctorCheck.Item> serverF = submitSafe(this::evalServer, fallbackItem(
                DoctorCheck.CheckId.SERVER, DoctorCheck.DetailCode.SERVER_UNREACHABLE, DoctorCheck.FixAction.RETRY));
        Future<DoctorCheck.Item> capsF = submitSafe(this::evalCapabilities, fallbackItem(
                DoctorCheck.CheckId.CAPABILITIES, DoctorCheck.DetailCode.CAPABILITIES_ERROR, DoctorCheck.FixAction.RETRY));
        Future<DoctorCheck.Item> pushF = submitSafe(this::evalPush, fallbackItem(
                DoctorCheck.CheckId.PUSH, DoctorCheck.DetailCode.PUSH_DISABLED, DoctorCheck.FixAction.OPEN_PUSH));
        Future<DoctorCheck.Item> updatesF = submitSafe(this::evalUpdates, fallbackItem(
                DoctorCheck.CheckId.UPDATES, DoctorCheck.DetailCode.UPDATES_UNCHECKED, DoctorCheck.FixAction.OPEN_UPDATES));
        Future<DoctorCheck.Item> ambientF = submitSafe(this::evalAmbient, fallbackItem(
                DoctorCheck.CheckId.AMBIENT, DoctorCheck.DetailCode.AMBIENT_OFF, DoctorCheck.FixAction.OPEN_AMBIENT));
        Future<DoctorCheck.Item> langF = submitSafe(this::evalLanguage, fallbackItem(
                DoctorCheck.CheckId.LANGUAGE, DoctorCheck.DetailCode.LANGUAGE_MISMATCH, DoctorCheck.FixAction.OPEN_LANGUAGE));

        List<DoctorCheck.Item> items = Arrays.asList(
                await(serverF, DoctorCheck.CheckId.SERVER, DoctorCheck.DetailCode.SERVER_UNREACHABLE, DoctorCheck.FixAction.RETRY),
                await(capsF, DoctorCheck.CheckId.CAPABILITIES, DoctorCheck.DetailCode.CAPABILITIES_ERROR, DoctorCheck.FixAction.RETRY),
                await(pushF, DoctorCheck.CheckId.PUSH, DoctorCheck.DetailCode.PUSH_DISABLED, DoctorCheck.FixAction.OPEN_PUSH),
                await(updatesF, DoctorCheck.CheckId.UPDATES, DoctorCheck.DetailCode.UPDATES_UNCHECKED, DoctorCheck.FixAction.OPEN_UPDATES),
                await(ambientF, DoctorCheck.CheckId.AMBIENT, DoctorCheck.DetailCode.AMBIENT_OFF, DoctorCheck.FixAction.OPEN_AMBIENT),
                await(langF, DoctorCheck.CheckId.LANGUAGE, DoctorCheck.DetailCode.LANGUAGE_MISMATCH, DoctorCheck.FixAction.OPEN_LANGUAGE)
        );
        return new DoctorCheck.Report(
                probes.rawEndpoint(),
                probes.appVersion(),
                probes.languageTag(),
                now,
                items);
    }

    @Override
    public void close() {
        probes.close();
        pool.shutdownNow();
    }

    /** Parse {@code /v1/capabilities} JSON into a structured fork-detection result. */
    public static CapabilitiesProbe parseCapabilitiesJson(JSONObject response) {
        return parseCapabilitiesJson(response, HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES);
    }

    /** Parse {@code /v1/capabilities} JSON using a configured expected-capability baseline. */
    public static CapabilitiesProbe parseCapabilitiesJson(JSONObject response, int expectedForkCapabilities) {
        if (response == null) {
            return evaluateCapabilityFlags(null, expectedForkCapabilities);
        }
        JSONObject features = response.optJSONObject("features");
        if (features == null) features = response.optJSONObject("capabilities");
        if (features == null || features.length() == 0) {
            return evaluateCapabilityFlags(null, expectedForkCapabilities);
        }
        Map<String, Boolean> flags = new LinkedHashMap<>();
        Iterator<String> keys = features.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = features.opt(key);
            if (raw instanceof Boolean flag) {
                flags.put(key, flag);
            }
        }
        return evaluateCapabilityFlags(flags, expectedForkCapabilities);
    }

    /** Pure-Java capability flag evaluator (SSoT for fork detection and host JVM testing). */
    public static CapabilitiesProbe evaluateCapabilityFlags(Map<String, Boolean> flags) {
        return evaluateCapabilityFlags(flags, HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES);
    }

    /** Pure-Java capability flag evaluator using a configured expected-capability baseline. */
    public static CapabilitiesProbe evaluateCapabilityFlags(Map<String, Boolean> flags, int expectedForkCapabilities) {
        int baseline = Math.max(1, expectedForkCapabilities);
        if (flags == null || flags.isEmpty()) {
            return new CapabilitiesProbe(true, true, 0, baseline, false);
        }
        int trueCount = 0;
        boolean hasMarker = false;
        for (Map.Entry<String, Boolean> entry : flags.entrySet()) {
            String key = entry.getKey();
            if (key == null || !Boolean.TRUE.equals(entry.getValue())) continue;
            String trimmed = key.trim();
            if (trimmed.isEmpty()) continue;
            trueCount++;
            if (COMPANION_FORK_MARKERS.contains(trimmed)) {
                hasMarker = true;
            }
        }
        boolean companionDetected = hasMarker || trueCount >= 10;
        if (!companionDetected) {
            return new CapabilitiesProbe(true, true, trueCount, baseline, false);
        }
        int expected = Math.max(baseline, trueCount);
        return new CapabilitiesProbe(true, false, trueCount, expected, true);
    }

    private Future<DoctorCheck.Item> submitSafe(Callable<DoctorCheck.Item> task, DoctorCheck.Item onError) {
        return pool.submit(() -> {
            try {
                return task.call();
            } catch (Throwable error) {
                logWarning("probe_failed_" + onError.id().name().toLowerCase(Locale.ROOT));
                return onError;
            }
        });
    }

    private DoctorCheck.Item await(Future<DoctorCheck.Item> future, DoctorCheck.CheckId id,
                                   DoctorCheck.DetailCode fallbackCode, DoctorCheck.FixAction fallbackFix) {
        try {
            DoctorCheck.Item item = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return item != null ? item : fallbackItem(id, fallbackCode, fallbackFix);
        } catch (Exception timeoutOrCancel) {
            future.cancel(true);
            logWarning("probe_timeout_" + id.name().toLowerCase(Locale.ROOT));
            return fallbackItem(id, fallbackCode, fallbackFix);
        }
    }

    private DoctorCheck.Item fallbackItem(DoctorCheck.CheckId id, DoctorCheck.DetailCode code,
                                          DoctorCheck.FixAction fix) {
        DoctorCheck.Status status = (id == DoctorCheck.CheckId.SERVER || id == DoctorCheck.CheckId.CAPABILITIES)
                ? DoctorCheck.Status.ERROR
                : DoctorCheck.Status.WARN;
        return new DoctorCheck.Item(id, status, code, -1L, 0, expectedForkCapabilities, false, "", fix);
    }

    private DoctorCheck.Item evalServer() {
        try {
            ServerProbe res = probes.probeServer();
            if (res == null || res.missingProfile()) {
                return new DoctorCheck.Item(DoctorCheck.CheckId.SERVER, DoctorCheck.Status.WARN,
                        DoctorCheck.DetailCode.SERVER_MISSING, -1L, 0, expectedForkCapabilities,
                        false, "", DoctorCheck.FixAction.RETRY);
            }
            if (res.authRejected()) {
                return new DoctorCheck.Item(DoctorCheck.CheckId.SERVER, DoctorCheck.Status.ERROR,
                        DoctorCheck.DetailCode.SERVER_AUTH_ERROR, res.latencyMs(), 0,
                        expectedForkCapabilities, false, "", DoctorCheck.FixAction.RETRY);
            }
            if (!res.reachable()) {
                return new DoctorCheck.Item(DoctorCheck.CheckId.SERVER, DoctorCheck.Status.ERROR,
                        DoctorCheck.DetailCode.SERVER_UNREACHABLE, res.latencyMs(), 0,
                        expectedForkCapabilities, false, "", DoctorCheck.FixAction.RETRY);
            }
            boolean slow = res.latencyMs() >= slowLatencyThresholdMs;
            return new DoctorCheck.Item(DoctorCheck.CheckId.SERVER,
                    slow ? DoctorCheck.Status.WARN : DoctorCheck.Status.OK,
                    slow ? DoctorCheck.DetailCode.SERVER_SLOW : DoctorCheck.DetailCode.SERVER_OK,
                    Math.max(0L, res.latencyMs()), 0, expectedForkCapabilities,
                    false, "", slow ? DoctorCheck.FixAction.RETRY : DoctorCheck.FixAction.NONE);
        } catch (IOException err) {
            if (err instanceof HermesApi.HttpError http && (http.status == 401 || http.status == 403)) {
                return new DoctorCheck.Item(DoctorCheck.CheckId.SERVER, DoctorCheck.Status.ERROR,
                        DoctorCheck.DetailCode.SERVER_AUTH_ERROR, -1L, 0,
                        expectedForkCapabilities, false, "", DoctorCheck.FixAction.RETRY);
            }
            logWarning("server_probe_io");
            return new DoctorCheck.Item(DoctorCheck.CheckId.SERVER, DoctorCheck.Status.ERROR,
                    DoctorCheck.DetailCode.SERVER_UNREACHABLE, -1L, 0,
                    expectedForkCapabilities, false, "", DoctorCheck.FixAction.RETRY);
        }
    }

    private DoctorCheck.Item evalCapabilities() {
        try {
            CapabilitiesProbe res = probes.probeCapabilities();
            if (res == null || !res.fetched()) {
                return new DoctorCheck.Item(DoctorCheck.CheckId.CAPABILITIES, DoctorCheck.Status.ERROR,
                        DoctorCheck.DetailCode.CAPABILITIES_ERROR, -1L, 0,
                        expectedForkCapabilities, false, "", DoctorCheck.FixAction.RETRY);
            }
            int baseExpected = Math.max(1, res.expectedCount());
            if (res.upstreamNonFork() || !res.companionForkDetected()) {
                return new DoctorCheck.Item(DoctorCheck.CheckId.CAPABILITIES, DoctorCheck.Status.WARN,
                        DoctorCheck.DetailCode.CAPABILITIES_UPSTREAM, -1L, res.enabledCount(), baseExpected,
                        false, "", DoctorCheck.FixAction.NONE);
            }
            int expected = Math.max(baseExpected, res.enabledCount());
            boolean full = res.enabledCount() >= baseExpected;
            return new DoctorCheck.Item(DoctorCheck.CheckId.CAPABILITIES, DoctorCheck.Status.OK,
                    full ? DoctorCheck.DetailCode.CAPABILITIES_FULL : DoctorCheck.DetailCode.CAPABILITIES_PARTIAL,
                    -1L, res.enabledCount(), expected, true, "", DoctorCheck.FixAction.NONE);
        } catch (IOException err) {
            if (err instanceof HermesApi.HttpError http && (http.status == 404 || http.status == 405 || http.status == 501)) {
                return new DoctorCheck.Item(DoctorCheck.CheckId.CAPABILITIES, DoctorCheck.Status.WARN,
                        DoctorCheck.DetailCode.CAPABILITIES_UPSTREAM, -1L, 0,
                        expectedForkCapabilities, false, "", DoctorCheck.FixAction.NONE);
            }
            logWarning("capabilities_probe_io");
            return new DoctorCheck.Item(DoctorCheck.CheckId.CAPABILITIES, DoctorCheck.Status.ERROR,
                    DoctorCheck.DetailCode.CAPABILITIES_ERROR, -1L, 0,
                    expectedForkCapabilities, false, "", DoctorCheck.FixAction.RETRY);
        }
    }

    private DoctorCheck.Item evalPush() {
        PushProbe res = probes.probePush();
        if (res == null) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.PUSH, DoctorCheck.Status.WARN,
                    DoctorCheck.DetailCode.PUSH_DISABLED, -1L, 0, expectedForkCapabilities,
                    false, "", DoctorCheck.FixAction.OPEN_PUSH);
        }
        if (!res.notificationsAllowed()) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.PUSH, DoctorCheck.Status.WARN,
                    DoctorCheck.DetailCode.PUSH_BLOCKED, -1L, 0, expectedForkCapabilities,
                    false, "", DoctorCheck.FixAction.OPEN_PUSH);
        }
        if (res.enabled()) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.PUSH, DoctorCheck.Status.OK,
                    DoctorCheck.DetailCode.PUSH_READY, -1L, 0, expectedForkCapabilities,
                    false, "", DoctorCheck.FixAction.NONE);
        }
        return new DoctorCheck.Item(DoctorCheck.CheckId.PUSH, DoctorCheck.Status.WARN,
                DoctorCheck.DetailCode.PUSH_DISABLED, -1L, 0, expectedForkCapabilities,
                false, "", DoctorCheck.FixAction.OPEN_PUSH);
    }

    private DoctorCheck.Item evalUpdates() {
        UpdateProbe res = probes.probeUpdates();
        if (res == null) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.UPDATES, DoctorCheck.Status.WARN,
                    DoctorCheck.DetailCode.UPDATES_UNCHECKED, -1L, 0, expectedForkCapabilities,
                    false, "unknown", DoctorCheck.FixAction.OPEN_UPDATES);
        }
        String installed = res.installedVersion() == null || res.installedVersion().isEmpty()
                ? "unknown" : res.installedVersion();
        if (res.updateAvailable() && res.latestTag() != null && !res.latestTag().isEmpty()) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.UPDATES, DoctorCheck.Status.WARN,
                    DoctorCheck.DetailCode.UPDATES_AVAILABLE, -1L, 0, expectedForkCapabilities,
                    false, res.latestTag(), DoctorCheck.FixAction.OPEN_UPDATES);
        }
        if (!res.checkedBefore()) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.UPDATES, DoctorCheck.Status.WARN,
                    DoctorCheck.DetailCode.UPDATES_UNCHECKED, -1L, 0, expectedForkCapabilities,
                    false, installed, DoctorCheck.FixAction.OPEN_UPDATES);
        }
        return new DoctorCheck.Item(DoctorCheck.CheckId.UPDATES, DoctorCheck.Status.OK,
                DoctorCheck.DetailCode.UPDATES_OK, -1L, 0, expectedForkCapabilities,
                false, installed, DoctorCheck.FixAction.NONE);
    }

    private DoctorCheck.Item evalAmbient() {
        AmbientProbe res = probes.probeAmbient();
        if (res != null && res.listenerEnabled() && res.serviceConnected()) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.AMBIENT, DoctorCheck.Status.OK,
                    DoctorCheck.DetailCode.AMBIENT_CONNECTED, -1L, 0, expectedForkCapabilities,
                    false, "", DoctorCheck.FixAction.NONE);
        }
        if (res != null && res.listenerEnabled()) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.AMBIENT, DoctorCheck.Status.WARN,
                    DoctorCheck.DetailCode.AMBIENT_WAITING, -1L, 0, expectedForkCapabilities,
                    false, "", DoctorCheck.FixAction.OPEN_AMBIENT);
        }
        return new DoctorCheck.Item(DoctorCheck.CheckId.AMBIENT, DoctorCheck.Status.WARN,
                DoctorCheck.DetailCode.AMBIENT_OFF, -1L, 0, expectedForkCapabilities,
                false, "", DoctorCheck.FixAction.OPEN_AMBIENT);
    }

    private DoctorCheck.Item evalLanguage() {
        LanguageProbe res = probes.probeLanguage();
        if (res == null) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.LANGUAGE, DoctorCheck.Status.WARN,
                    DoctorCheck.DetailCode.LANGUAGE_MISMATCH, -1L, 0, expectedForkCapabilities,
                    false, "fr", DoctorCheck.FixAction.OPEN_LANGUAGE);
        }
        String label = res.activeTag() == null || res.activeTag().isEmpty() ? "fr" : res.activeTag();
        if (res.synchronizedWithRequests()) {
            return new DoctorCheck.Item(DoctorCheck.CheckId.LANGUAGE, DoctorCheck.Status.OK,
                    DoctorCheck.DetailCode.LANGUAGE_SYNCED, -1L, 0, expectedForkCapabilities,
                    false, label, DoctorCheck.FixAction.NONE);
        }
        return new DoctorCheck.Item(DoctorCheck.CheckId.LANGUAGE, DoctorCheck.Status.WARN,
                DoctorCheck.DetailCode.LANGUAGE_MISMATCH, -1L, 0, expectedForkCapabilities,
                false, label, DoctorCheck.FixAction.OPEN_LANGUAGE);
    }

    private static void logWarning(String action) {
        Diagnostics.warning("doctor", action);
    }

    /** Live Android probe implementation reading real local state and gateway endpoints. */
    static final class AndroidProbes implements Probes {
        private final Context app;
        private final Account account;
        private final HermesApi api;
        private final int expectedForkCapabilities;

        AndroidProbes(Context app, Account account, HermesApi api) {
            this(app, account, api, HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES);
        }

        AndroidProbes(Context app, Account account, HermesApi api, int expectedForkCapabilities) {
            this.app = app;
            this.account = account;
            this.api = api;
            this.expectedForkCapabilities = Math.max(1, expectedForkCapabilities);
        }

        @Override
        public String rawEndpoint() {
            return account == null ? "" : account.endpoint;
        }

        @Override
        public String appVersion() {
            String v = UpdatePanel.installedVersionName(app);
            return v == null ? "unknown" : v;
        }

        @Override
        public String languageTag() {
            Locale locale = AppLanguage.resolvedLocale(app);
            return locale.toLanguageTag();
        }

        @Override
        public ServerProbe probeServer() throws IOException {
            if (account == null || api == null) {
                return new ServerProbe(false, false, true, -1L);
            }
            long start = System.nanoTime();
            try {
                api.get("/api/health");
                long ms = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                return new ServerProbe(true, false, false, ms);
            } catch (HermesApi.HttpError healthErr) {
                if (healthErr.status == 401 || healthErr.status == 403) {
                    long ms = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    return new ServerProbe(false, true, false, ms);
                }
                // Fallback to /v1/capabilities when a gateway only mounts /v1/*.
                api.capabilities();
                long ms = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                return new ServerProbe(true, false, false, ms);
            }
        }

        @Override
        public CapabilitiesProbe probeCapabilities() throws IOException {
            if (account == null || api == null) {
                return new CapabilitiesProbe(false, false, 0, expectedForkCapabilities, false);
            }
            JSONObject payload = api.capabilities();
            return parseCapabilitiesJson(payload, expectedForkCapabilities);
        }

        @Override
        public PushProbe probePush() {
            boolean notificationsAllowed = true;
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) {
                notificationsAllowed = nm.areNotificationsEnabled();
            }
            boolean enabled = account != null && MobilePush.isEnabled(app, account.id);
            return new PushProbe(enabled, notificationsAllowed);
        }

        @Override
        public UpdateProbe probeUpdates() {
            String installed = appVersion();
            UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator(app);
            UpdateCheckCoordinator.BadgeState badge = coordinator.cachedBadge(installed);
            boolean checkedBefore = !coordinator.isDue(System.currentTimeMillis()) || badge.updateAvailable();
            return new UpdateProbe(installed, badge.updateAvailable(), badge.latestTag(), checkedBefore);
        }

        @Override
        public AmbientProbe probeAmbient() {
            boolean enabled = HermesNotificationListener.isListenerEnabled(app);
            boolean connected = enabled && HermesNotificationListener.isServiceConnected();
            return new AmbientProbe(enabled, connected);
        }

        @Override
        public LanguageProbe probeLanguage() {
            String tag = languageTag();
            String header = AppLanguage.currentAcceptLanguage();
            String base = tag.toLowerCase(Locale.ROOT).startsWith("en") ? AppLanguage.TAG_ENGLISH : AppLanguage.TAG_FRENCH;
            boolean synced = header != null && header.toLowerCase(Locale.ROOT).startsWith(base);
            return new LanguageProbe(tag, header == null ? "" : header, synced);
        }

        @Override
        public void close() {
            if (api != null) {
                api.revoke();
            }
        }
    }
}
