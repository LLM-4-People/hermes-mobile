package org.llm4people.hermes;

import android.content.Context;
import android.content.res.Resources;

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
            DEFAULT_MAX_RECONNECTS);

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

    public HermesConfig(int httpConnectTimeoutMs, int httpReadTimeoutMs, int httpJsonMaxBytes, int httpEventMaxBytes,
                        int nearbyTimeoutMs, int nearbyFusedGraceMs, long artifactDefaultMaxBytes,
                        long artifactHardMaxBytes, int diagnosticsRingCapacity, int threadPageSize, int maxReconnects) {
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
    }

    public static HermesConfig defaults() {
        return DEFAULTS;
    }

    public static HermesConfig load(Context context) {
        if (context == null) return DEFAULTS;
        Resources res = context.getResources();
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
                res.getInteger(R.integer.hermes_max_reconnects));
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
