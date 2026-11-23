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
    public static final long DEFAULT_UPDATE_MAX_APK_BYTES = 52_428_800L;
    public static final long DEFAULT_UPDATE_CHECK_INTERVAL_MS = 86_400_000L;

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
            DEFAULT_UPDATE_CHECK_INTERVAL_MS);

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

    public HermesConfig(int httpConnectTimeoutMs, int httpReadTimeoutMs, int httpJsonMaxBytes, int httpEventMaxBytes,
                        int nearbyTimeoutMs, int nearbyFusedGraceMs, long artifactDefaultMaxBytes,
                        long artifactHardMaxBytes, int diagnosticsRingCapacity, int threadPageSize, int maxReconnects) {
        this(httpConnectTimeoutMs, httpReadTimeoutMs, httpJsonMaxBytes, httpEventMaxBytes,
                nearbyTimeoutMs, nearbyFusedGraceMs, artifactDefaultMaxBytes, artifactHardMaxBytes,
                diagnosticsRingCapacity, threadPageSize, maxReconnects,
                DEFAULT_UPDATE_REPO, DEFAULT_UPDATE_API_BASE,
                DEFAULT_UPDATE_MAX_APK_BYTES, DEFAULT_UPDATE_CHECK_INTERVAL_MS);
    }

    public HermesConfig(int httpConnectTimeoutMs, int httpReadTimeoutMs, int httpJsonMaxBytes, int httpEventMaxBytes,
                        int nearbyTimeoutMs, int nearbyFusedGraceMs, long artifactDefaultMaxBytes,
                        long artifactHardMaxBytes, int diagnosticsRingCapacity, int threadPageSize, int maxReconnects,
                        String updateRepo, String updateApiBase, long updateMaxApkBytes, long updateCheckIntervalMs) {
        if (httpConnectTimeoutMs <= 0 || httpReadTimeoutMs <= 0 || httpJsonMaxBytes <= 0 || httpEventMaxBytes <= 0) {
            throw new IllegalArgumentException("HTTP bounds must be positive");
        }
        if (nearbyTimeoutMs <= 0 || nearbyFusedGraceMs <= 0 || nearbyFusedGraceMs > nearbyTimeoutMs) {
            throw new IllegalArgumentException("Nearby timeouts must be positive and ordered");
        }
        if (artifactDefaultMaxBytes <= 0 || artifactHardMaxBytes < artifactDefaultMaxBytes) {
            throw new IllegalArgumentException("Artifact byte limits must be positive and ordered");
        }
        if (diagnosticsRingCapacity <= 0 || threadPageSize <= 0 || maxReconnects < 0) {
            throw new IllegalArgumentException("Operational bounds must be valid");
        }
        String cleanRepo = updateRepo == null ? "" : updateRepo.trim();
        String cleanApiBase = updateApiBase == null ? "" : updateApiBase.trim();
        if (!cleanRepo.contains("/") || cleanApiBase.isEmpty() || !cleanApiBase.startsWith("https://")) {
            throw new IllegalArgumentException("Update repository and HTTPS API base must be valid");
        }
        if (updateMaxApkBytes <= 0 || updateCheckIntervalMs <= 0) {
            throw new IllegalArgumentException("Update bounds must be positive");
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
        this.updateApiBase = cleanApiBase.endsWith("/")
                ? cleanApiBase.substring(0, cleanApiBase.length() - 1) : cleanApiBase;
        this.updateMaxApkBytes = updateMaxApkBytes;
        this.updateCheckIntervalMs = updateCheckIntervalMs;
    }

    private static volatile Object cachedKey;
    private static volatile HermesConfig cachedConfig;

    public static HermesConfig defaults() {
        return DEFAULTS;
    }

    /** Clears the memoized config snapshot (intended for tests or configuration reload). */
    public static void clearCacheForTest() {
        synchronized (HermesConfig.class) {
            cachedKey = null;
            cachedConfig = null;
        }
    }

    static HermesConfig loadCached(Object key, Supplier<HermesConfig> loader) {
        if (key == null || loader == null) return DEFAULTS;
        HermesConfig local = cachedConfig;
        if (local != null && cachedKey == key) return local;
        synchronized (HermesConfig.class) {
            if (cachedConfig != null && cachedKey == key) return cachedConfig;
            HermesConfig loaded = loader.get();
            if (loaded == null) return DEFAULTS;
            cachedKey = key;
            cachedConfig = loaded;
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
                    res.getInteger(R.integer.hermes_update_check_interval_ms));
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
