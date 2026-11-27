package org.llm4people.hermes;

import android.content.Context;
import android.content.res.Resources;

import java.util.function.Supplier;

/** Single source of truth for bounded runtime timeouts, payload limits, and diagnostic settings. */
public final class HermesConfig {
    public static final int DEFAULT_HTTP_CONNECT_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_HTTP_READ_TIMEOUT_MS = 45_000;
    public static final int DEFAULT_HTTP_JSON_MAX_BYTES = 8 * 1024 * 1024;
    public static final int DEFAULT_HTTP_EVENT_MAX_BYTES = 1024 * 1024;
    public static final int DEFAULT_NEARBY_TIMEOUT_MS = 12_000;
    public static final int DEFAULT_NEARBY_FUSED_GRACE_MS = 4_000;
    public static final long DEFAULT_ARTIFACT_MAX_BYTES = 16L * 1024 * 1024;
    public static final long HARD_ARTIFACT_MAX_BYTES = 64L * 1024 * 1024;
    public static final int DEFAULT_DIAGNOSTICS_RING_CAPACITY = 200;
    public static final int DEFAULT_THREAD_PAGE_SIZE = 40;
    public static final int DEFAULT_MAX_RECONNECTS = 5;
    public static final String DEFAULT_UPDATE_REPO = "LLM-4-People/hermes-mobile";
    public static final String DEFAULT_UPDATE_API_BASE = "https://api.github.com";
    public static final String DEFAULT_GITHUB_WEB_BASE_URL = "https://github.com";
    public static final String DEFAULT_OSM_BASE_URL = "https://www.openstreetmap.org";
    public static final long DEFAULT_UPDATE_MAX_APK_BYTES = 52_428_800L;
    public static final long DEFAULT_UPDATE_CHECK_INTERVAL_MS = 86_400_000L;
    public static final int DEFAULT_DOCTOR_CHECK_TIMEOUT_MS = 8_000;
    public static final int DEFAULT_SLOW_LATENCY_THRESHOLD_MS = 1_500;
    public static final int DEFAULT_EXPECTED_FORK_CAPABILITIES = 51;
    public static final int DEFAULT_PROBE_TIMEOUT_MS = 8_000;
    public static final int DEFAULT_VOICE_SERVICE_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_TTS_INIT_TIMEOUT_MS = 12_000;
    public static final int DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS = 120_000;
    public static final int DEFAULT_HTTP_MAX_REDIRECTS = 5;

    private static final HermesConfig DEFAULTS = new HermesConfig(
            DEFAULT_HTTP_CONNECT_TIMEOUT_MS,
            DEFAULT_HTTP_READ_TIMEOUT_MS,
            DEFAULT_HTTP_JSON_MAX_BYTES,
            DEFAULT_HTTP_EVENT_MAX_BYTES,
            DEFAULT_NEARBY_TIMEOUT_MS,
            DEFAULT_NEARBY_FUSED_GRACE_MS,
            DEFAULT_ARTIFACT_MAX_BYTES,
            HARD_ARTIFACT_MAX_BYTES,
            DEFAULT_DIAGNOSTICS_RING_CAPACITY,
            DEFAULT_THREAD_PAGE_SIZE,
            DEFAULT_MAX_RECONNECTS,
            DEFAULT_UPDATE_REPO,
            DEFAULT_UPDATE_API_BASE,
            DEFAULT_UPDATE_MAX_APK_BYTES,
            DEFAULT_UPDATE_CHECK_INTERVAL_MS,
            DEFAULT_GITHUB_WEB_BASE_URL,
            DEFAULT_OSM_BASE_URL,
            DEFAULT_DOCTOR_CHECK_TIMEOUT_MS,
            DEFAULT_SLOW_LATENCY_THRESHOLD_MS,
            DEFAULT_EXPECTED_FORK_CAPABILITIES,
            DEFAULT_PROBE_TIMEOUT_MS,
            DEFAULT_VOICE_SERVICE_TIMEOUT_MS,
            DEFAULT_TTS_INIT_TIMEOUT_MS,
            DEFAULT_VOICE_MODEL_RELEASE_TIMEOUT_MS,
            DEFAULT_HTTP_MAX_REDIRECTS);

    public final int httpConnectTimeoutMs;
    public final int httpReadTimeoutMs;
    public final int httpJsonMaxBytes;
    public final int httpEventMaxBytes;
    public final int nearbyTimeoutMs;
    public final int nearbyFusedGraceMs;
    public final long artifactDefaultMaxBytes;
    public final long artifactHardMaxBytes;
    public final int diagnosticsRingCapacity;
    public final int threadPageSize;
    public final int maxReconnects;
    public final String updateRepo;
    public final String updateApiBase;
    public final long updateMaxApkBytes;
    public final long updateCheckIntervalMs;
    public final String githubWebBaseUrl;
    public final String openStreetMapBaseUrl;
    public final int doctorCheckTimeoutMs;
    public final int slowLatencyThresholdMs;
    public final int expectedForkCapabilities;
    public final int probeTimeoutMs;
    public final int voiceServiceTimeoutMs;
    public final int ttsInitTimeoutMs;
    public final int voiceModelReleaseTimeoutMs;
    public final int httpMaxRedirects;

    public HermesConfig(int httpConnectTimeoutMs, int httpReadTimeoutMs, int httpJsonMaxBytes, int httpEventMaxBytes,
                        int nearbyTimeoutMs, int nearbyFusedGraceMs, long artifactDefaultMaxBytes,
                        long artifactHardMaxBytes, int diagnosticsRingCapacity, int threadPageSize, int maxReconnects,
                        String updateRepo, String updateApiBase, long updateMaxApkBytes, long updateCheckIntervalMs,
                        String githubWebBaseUrl, String openStreetMapBaseUrl, int doctorCheckTimeoutMs,
                        int slowLatencyThresholdMs, int expectedForkCapabilities, int probeTimeoutMs,
                        int voiceServiceTimeoutMs, int ttsInitTimeoutMs,
                        int voiceModelReleaseTimeoutMs, int httpMaxRedirects) {
        if (httpConnectTimeoutMs <= 0 || httpReadTimeoutMs <= 0 || httpJsonMaxBytes <= 0 || httpEventMaxBytes <= 0) {
            throw new IllegalArgumentException("HTTP bounds must be positive");
        }
        if (nearbyTimeoutMs <= 0 || nearbyFusedGraceMs <= 0 || nearbyFusedGraceMs > nearbyTimeoutMs) {
            throw new IllegalArgumentException("Nearby timeouts must be positive and ordered");
        }
        if (artifactDefaultMaxBytes <= 0 || artifactHardMaxBytes < artifactDefaultMaxBytes) {
            throw new IllegalArgumentException("Artifact byte limits must be positive and ordered");
        }
        if (diagnosticsRingCapacity <= 0 || threadPageSize <= 0 || maxReconnects < 0 || httpMaxRedirects < 0) {
            throw new IllegalArgumentException("Operational bounds must be valid");
        }
        String cleanRepo = updateRepo == null ? "" : updateRepo.trim();
        String cleanApiBase = updateApiBase == null ? "" : updateApiBase.trim();
        String cleanGithubWeb = githubWebBaseUrl == null ? "" : githubWebBaseUrl.trim();
        String cleanOsmBase = openStreetMapBaseUrl == null ? "" : openStreetMapBaseUrl.trim();
        if (!cleanRepo.contains("/") || cleanApiBase.isEmpty() || !cleanApiBase.startsWith("https://")) {
            throw new IllegalArgumentException("Update repository and HTTPS API base must be valid");
        }
        if (!cleanGithubWeb.startsWith("https://") || !cleanOsmBase.startsWith("https://")) {
            throw new IllegalArgumentException("External base URLs must use HTTPS");
        }
        if (updateMaxApkBytes <= 0 || updateCheckIntervalMs <= 0
                || doctorCheckTimeoutMs <= 0 || slowLatencyThresholdMs <= 0
                || expectedForkCapabilities <= 0 || probeTimeoutMs <= 0
                || voiceServiceTimeoutMs <= 0 || ttsInitTimeoutMs <= 0
                || voiceModelReleaseTimeoutMs <= 0) {
            throw new IllegalArgumentException("Operational timeouts and thresholds must be positive");
        }
        this.httpConnectTimeoutMs = httpConnectTimeoutMs;
        this.httpReadTimeoutMs = httpReadTimeoutMs;
        this.httpJsonMaxBytes = httpJsonMaxBytes;
        this.httpEventMaxBytes = httpEventMaxBytes;
        this.nearbyTimeoutMs = nearbyTimeoutMs;
        this.nearbyFusedGraceMs = nearbyFusedGraceMs;
        this.artifactDefaultMaxBytes = artifactDefaultMaxBytes;
        this.artifactHardMaxBytes = artifactHardMaxBytes;
        this.diagnosticsRingCapacity = diagnosticsRingCapacity;
        this.threadPageSize = threadPageSize;
        this.maxReconnects = maxReconnects;
        this.updateRepo = cleanRepo;
        this.updateApiBase = stripTrailingSlash(cleanApiBase);
        this.updateMaxApkBytes = updateMaxApkBytes;
        this.updateCheckIntervalMs = updateCheckIntervalMs;
        this.githubWebBaseUrl = stripTrailingSlash(cleanGithubWeb);
        this.openStreetMapBaseUrl = stripTrailingSlash(cleanOsmBase);
        this.doctorCheckTimeoutMs = doctorCheckTimeoutMs;
        this.slowLatencyThresholdMs = slowLatencyThresholdMs;
        this.expectedForkCapabilities = expectedForkCapabilities;
        this.probeTimeoutMs = probeTimeoutMs;
        this.voiceServiceTimeoutMs = voiceServiceTimeoutMs;
        this.ttsInitTimeoutMs = ttsInitTimeoutMs;
        this.voiceModelReleaseTimeoutMs = voiceModelReleaseTimeoutMs;
        this.httpMaxRedirects = httpMaxRedirects;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record CacheSnapshot(Object key, HermesConfig config) {}

    private static volatile CacheSnapshot cached;

    public static HermesConfig defaults() {
        return DEFAULTS;
    }

    /** Clears the memoized config snapshot (intended for tests or configuration reload). */
    public static void clearCacheForTest() {
        synchronized (HermesConfig.class) {
            cached = null;
        }
    }

    static HermesConfig loadCached(Object key, Supplier<HermesConfig> loader) {
        if (key == null || loader == null) return DEFAULTS;
        CacheSnapshot snapshot = cached;
        if (snapshot != null && snapshot.key() == key) return snapshot.config();
        synchronized (HermesConfig.class) {
            snapshot = cached;
            if (snapshot != null && snapshot.key() == key) return snapshot.config();
            HermesConfig loaded = loader.get();
            if (loaded == null) return DEFAULTS;
            cached = new CacheSnapshot(key, loaded);
            return loaded;
        }
    }

    public static HermesConfig load(Context context) {
        if (context == null) return DEFAULTS;
        Context app = context.getApplicationContext();
        Context key = app != null ? app : context;
        return loadCached(key, () -> {
            Resources res = key.getResources();
            if (res == null) return DEFAULTS;
            return new HermesConfig(
                    res.getInteger(R.integer.hermes_http_connect_timeout_ms),
                    res.getInteger(R.integer.hermes_http_read_timeout_ms),
                    res.getInteger(R.integer.hermes_http_json_max_bytes),
                    res.getInteger(R.integer.hermes_http_event_max_bytes),
                    res.getInteger(R.integer.hermes_nearby_timeout_ms),
                    res.getInteger(R.integer.hermes_nearby_fused_grace_ms),
                    res.getInteger(R.integer.hermes_artifact_default_max_bytes),
                    res.getInteger(R.integer.hermes_artifact_hard_max_bytes),
                    res.getInteger(R.integer.hermes_diagnostics_ring_capacity),
                    res.getInteger(R.integer.hermes_thread_page_size),
                    res.getInteger(R.integer.hermes_max_reconnects),
                    res.getString(R.string.hermes_update_repo),
                    res.getString(R.string.hermes_update_api_base),
                    res.getInteger(R.integer.hermes_update_max_apk_bytes),
                    res.getInteger(R.integer.hermes_update_check_interval_ms),
                    res.getString(R.string.hermes_github_web_base_url),
                    res.getString(R.string.hermes_osm_base_url),
                    res.getInteger(R.integer.hermes_doctor_check_timeout_ms),
                    res.getInteger(R.integer.hermes_slow_latency_threshold_ms),
                    res.getInteger(R.integer.hermes_expected_fork_capabilities),
                    res.getInteger(R.integer.hermes_probe_timeout_ms),
                    res.getInteger(R.integer.hermes_voice_service_timeout_ms),
                    res.getInteger(R.integer.hermes_tts_init_timeout_ms),
                    res.getInteger(R.integer.hermes_voice_model_release_timeout_ms),
                    res.getInteger(R.integer.hermes_http_max_redirects));
        });
    }

    /** Resolve the effective artifact byte limit from a gateway-advertised value without exceeding the hard ceiling. */
    public long resolveArtifactLimit(long advertisedBytes) {
        if (advertisedBytes <= 0) return artifactDefaultMaxBytes;
        return Math.min(advertisedBytes, artifactHardMaxBytes);
    }

    /** Parse an optional gateway max_artifact_bytes field safely against client bounds. */
    public long resolveArtifactLimit(Object advertised) {
        if (advertised instanceof Integer || advertised instanceof Long) {
            return resolveArtifactLimit(((Number) advertised).longValue());
        }
        return artifactDefaultMaxBytes;
    }
}
