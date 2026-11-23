package org.llm4people.hermes.net;

import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Blocking, profile-bound Hermes HTTP client. Call on a worker thread. */
public final class HermesApi {
    public static final class Limits {
        public final int connectTimeoutMs, readTimeoutMs, jsonBytes, eventBytes;
        public Limits(int connectTimeoutMs, int readTimeoutMs, int jsonBytes, int eventBytes) {
            if (Math.min(Math.min(connectTimeoutMs, readTimeoutMs), Math.min(jsonBytes, eventBytes)) <= 0) {
                throw new IllegalArgumentException("Transport bounds must be positive");
            }
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.jsonBytes = jsonBytes;
            this.eventBytes = eventBytes;
        }
        public static Limits defaults() {
            return from(HermesConfig.defaults());
        }

        public static Limits from(HermesConfig config) {
            HermesConfig cfg = config == null ? HermesConfig.defaults() : config;
            return new Limits(cfg.httpConnectTimeoutMs, cfg.httpReadTimeoutMs, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
        }
    }

    public static final class HttpError extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;
        public final String code;
        /** Delay in seconds when the server sent delta-seconds, or -1 when unavailable. */
        public final long retryAfterSeconds;
        private HttpError(int status, String code, long retryAfterSeconds) {
            super("Hermes HTTP " + status);
            this.status = status;
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private final Endpoint endpoint;
    private final String bearer;
    private final Limits limits;
    private final Set<RunEvents> openStreams = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
    private volatile boolean revoked;

    public HermesApi(String endpoint, String bearer) {
        this(endpoint, bearer, Limits.defaults());
    }

    public HermesApi(String endpoint, String bearer, Limits limits) {
        this.endpoint = new Endpoint(endpoint);
        if (!visibleAscii(bearer)) throw new IllegalArgumentException("Invalid bearer credential");
        if (limits == null) throw new IllegalArgumentException("Missing transport limits");
        this.bearer = bearer;
        this.limits = limits;
    }

    /** Immediately revokes this profile-bound client, aborting all open HTTP & SSE sockets and rejecting new calls. */
    public void revoke() {
        revoked = true;
        for (RunEvents stream : new ArrayList<>(openStreams)) {
            stream.close();
        }
        openStreams.clear();
        for (HttpURLConnection conn : new ArrayList<>(activeConnections)) {
            disconnectSafely(conn);
        }
        activeConnections.clear();
    }

    static void disconnectSafely(HttpURLConnection conn) {
        if (conn == null) return;
        try {
            conn.disconnect();
        } catch (RuntimeException ignored) {
            // Concurrent revoke() or socket teardown may have already invalidated internal connection state.
        }
    }

    private void trackConnection(HttpURLConnection http) throws IOException {
        activeConnections.add(http);
        if (revoked) {
            releaseConnection(http);
            throw new IOException("Client revoked");
        }
    }

    private void releaseConnection(HttpURLConnection http) {
        activeConnections.remove(http);
        disconnectSafely(http);
    }

    public boolean isRevoked() {
        return revoked;
    }

    /** Request cooperative stop for a running server task. */
    public JSONObject stopRun(String runId) throws IOException {
        return post("/v1/runs/" + segment(runId) + "/stop", new JSONObject());
    }

    public static String segment(String raw) {
        return Endpoint.segment(raw);
    }

    public static String relativePath(String raw) {
        return Endpoint.relativePath(raw);
    }

    public static String queryValue(String raw) {
        return Endpoint.queryValue(raw);
    }

    public JSONObject get(String path) throws IOException {
        return request("GET", path, null, null);
    }

    public JSONObject post(String path, JSONObject body) throws IOException {
        return post(path, body, null);
    }

    public JSONObject post(String path, JSONObject body, String idempotencyKey) throws IOException {
        if (idempotencyKey != null && (!visibleAscii(idempotencyKey) || idempotencyKey.length() > 255)) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        return request("POST", path, body, idempotencyKey);
    }

    public JSONObject put(String path, JSONObject body) throws IOException {
        return request("PUT", path, body, null);
    }

    public JSONObject patch(String path, JSONObject body) throws IOException {
        return request("PATCH", path, body, null);
    }

    public void delete(String path) throws IOException {
        deleteJson(path);
    }

    public JSONObject deleteJson(String path) throws IOException {
        return request("DELETE", path, null, null);
    }

    public JSONObject capabilities() throws IOException {
        return get("/v1/capabilities");
    }

    public JSONObject setSessionPinned(String sessionId, boolean pinned) throws IOException {
        JSONObject body = new JSONObject();
        try { body.put("pinned", pinned); }
        catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
        return patch("/api/sessions/" + segment(sessionId), body);
    }

    public JSONObject forkSession(String sessionId, Long upToMessageId, boolean keepSourceOpen) throws IOException {
        if (upToMessageId != null && upToMessageId <= 0) {
            throw new IllegalArgumentException("Invalid fork message id");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("keep_source_open", keepSourceOpen);
            if (upToMessageId != null) body.put("up_to_message_id", upToMessageId.longValue());
        } catch (org.json.JSONException invalid) {
            throw new IllegalArgumentException(invalid);
        }
        return post("/api/sessions/" + segment(sessionId) + "/fork", body);
    }

    public long exportSession(String sessionId, String format, OutputStream sink, long maxBytes) throws IOException {
        if (!"markdown".equals(format) && !"json".equals(format)) {
            throw new IllegalArgumentException("Unsupported export format");
        }
        String accept = "json".equals(format) ? "application/json" : "text/markdown, text/plain;q=0.9";
        return download("/api/sessions/" + segment(sessionId) + "/export?format=" + queryValue(format),
                accept, sink, maxBytes);
    }

    /** Send a bounded multipart/form-data upload and decode the JSON response. */
    public JSONObject postMultipart(String path, String boundary, byte[] body) throws IOException {
        if (boundary == null || !visibleAscii(boundary) || body == null || body.length == 0) {
            throw new IllegalArgumentException("Invalid multipart payload");
        }
        long maxUpload = Math.max(limits.jsonBytes, HermesConfig.defaults().artifactDefaultMaxBytes + 4096L);
        if (body.length > maxUpload) throw new IOException("Upload exceeded the byte limit");
        HttpURLConnection http = connection(path, "POST", "application/json");
        trackConnection(http);
        try {
            http.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            http.setDoOutput(true);
            http.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = http.getOutputStream()) { out.write(body); }
            int status = checkResponse(http);
            if (status == 204 || status == 205) return new JSONObject();
            try (InputStream in = http.getInputStream()) {
                return Wire.object(Wire.read(in, limits.jsonBytes));
            }
        } catch (IOException error) {
            Diagnostics.event("network", "multipart_upload_failed");
            throw error;
        } catch (RuntimeException error) {
            if (revoked) throw new IOException("Client revoked", error);
            throw error;
        } finally {
            releaseConnection(http);
        }
    }

    /** Reads the active profile's live UI state (custom tabs, quick actions, theme overrides). */
    public JSONObject mobileUiState(String sessionId) throws IOException {
        String path = sessionId == null || sessionId.isEmpty()
                ? "/api/mobile/ui/state"
                : "/api/mobile/ui/state?session_id=" + segment(sessionId);
        return get(path);
    }

    /** Reads ordered live UI revision history for the active profile. */
    public JSONObject mobileUiHistory(int limit) throws IOException {
        int bounded = Math.max(1, Math.min(50, limit));
        return get("/api/mobile/ui/history?limit=" + bounded);
    }

    /** Confirms application of a previewed live UI revision on the gateway. */
    public JSONObject applyMobileUiRevision(String revisionId) throws IOException {
        return post("/api/mobile/ui/revisions/" + segment(revisionId) + "/apply", new JSONObject());
    }

    /** Atomically reverts a live UI revision on the gateway. */
    public JSONObject revertMobileUiRevision(String revisionId) throws IOException {
        return post("/api/mobile/ui/revisions/" + segment(revisionId) + "/revert", new JSONObject());
    }

    /**
     * Unauthenticated non-consuming pairing ticket inspection ({@code POST /api/mobile/pairing/inspect}).
     * Verifies ticket validity and returns safe metadata without burning the ticket or returning any API key.
     */
    public static JSONObject inspectPairing(String baseUrl, String code) throws IOException {
        return inspectPairing(baseUrl, code, Limits.defaults());
    }

    public static JSONObject inspectPairing(String baseUrl, String code, Limits limits) throws IOException {
        return postPairingCode(baseUrl, "/api/mobile/pairing/inspect", code, limits, "pairing_inspect_failed");
    }

    /**
     * Unauthenticated single-use pairing ticket claim ({@code POST /api/mobile/pairing/claim}).
     * Enforces HTTPS/loopback via {@link Endpoint}, {@code Cache-Control: no-store}, and active
     * {@code Accept-Language} / {@code X-Hermes-Lang} headers without sending any bearer token.
     */
    public static JSONObject claimPairing(String baseUrl, String code) throws IOException {
        return claimPairing(baseUrl, code, Limits.defaults());
    }

    public static JSONObject claimPairing(String baseUrl, String code, Limits limits) throws IOException {
        return postPairingCode(baseUrl, "/api/mobile/pairing/claim", code, limits, "pairing_claim_failed");
    }

    private static JSONObject postPairingCode(
            String baseUrl, String path, String code, Limits limits, String failEvent) throws IOException {
        String cleanCode = code == null ? "" : code.trim();
        if (!cleanCode.matches("[A-Za-z0-9._-]{8,160}")) {
            throw new IllegalArgumentException("Invalid pairing code");
        }
        Limits safeLimits = limits == null ? Limits.defaults() : limits;
        Endpoint target = new Endpoint(baseUrl);
        byte[] encoded = ("{\"code\":\"" + cleanCode + "\"}").getBytes(StandardCharsets.UTF_8);
        if (encoded.length > safeLimits.jsonBytes) {
            throw new IOException("Request exceeded the byte limit");
        }
        HttpURLConnection http = (HttpURLConnection) target.url(path).openConnection();
        applyCommonHeaders(http, "POST", "application/json", safeLimits);
        try {
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            http.setDoOutput(true);
            http.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream out = http.getOutputStream()) { out.write(encoded); }
            checkResponseStatic(http, safeLimits);
            try (InputStream in = http.getInputStream()) {
                return Wire.object(Wire.read(in, safeLimits.jsonBytes));
            }
        } catch (IOException error) {
            Diagnostics.event("network", failEvent);
            throw error;
        } finally {
            disconnectSafely(http);
        }
    }

    /** Mint a short-lived single-use pairing ticket for another device or invited user. */
    public JSONObject createPairing(JSONObject body) throws IOException {
        return post("/api/mobile/pairing/create", body == null ? new JSONObject() : body);
    }

    /** List active non-expired pairing ticket metadata for the caller profile (never returns secret codes). */
    public JSONObject activePairings() throws IOException {
        return activePairings(false);
    }

    /**
     * List active non-expired pairing ticket metadata (never returns secret codes).
     * When {@code allProfiles} is true, requests cross-profile tickets ({@code ?all=1}) for admin callers.
     */
    public JSONObject activePairings(boolean allProfiles) throws IOException {
        return get(allProfiles ? "/api/mobile/pairing/active?all=1" : "/api/mobile/pairing/active");
    }

    /** Revoke an unused pairing ticket immediately. */
    public JSONObject revokePairing(String ticketId) throws IOException {
        return deleteJson("/api/mobile/pairing/" + segment(ticketId));
    }

    /** List server profiles visible to the active caller (all for admin, own only for member). */
    public JSONObject listProfiles() throws IOException {
        return get("/api/mobile/profiles");
    }

    /** Create an isolated server profile (admin-only; optionally mints a pairing ticket atomically). */
    public JSONObject createProfile(JSONObject body) throws IOException {
        return post("/api/mobile/profiles", body == null ? new JSONObject() : body);
    }

    /** Update profile display name, language, default model, badge color, or admin role. */
    public JSONObject updateProfile(String slug, JSONObject body) throws IOException {
        return patch("/api/mobile/profiles/" + segment(slug), body == null ? new JSONObject() : body);
    }

    /** Mint a fresh single-use pairing link for the specified profile. */
    public JSONObject inviteProfile(String slug, JSONObject body) throws IOException {
        return post("/api/mobile/profiles/" + segment(slug) + "/invite", body == null ? new JSONObject() : body);
    }

    /** Rotate a profile's API key (optionally returning a fresh pairing ticket). */
    public JSONObject rotateProfileKey(String slug, JSONObject body) throws IOException {
        return post("/api/mobile/profiles/" + segment(slug) + "/rotate-key", body == null ? new JSONObject() : body);
    }

    /** Safely offboard a server profile (admin-only; requires explicit confirmSlug == slug). */
    public JSONObject deleteProfile(String slug, String confirmSlug) throws IOException {
        String cleanSlug = org.llm4people.hermes.profile.GatewayAddress.normalizeProfileSlug(slug);
        String cleanConfirm = org.llm4people.hermes.profile.GatewayAddress.normalizeProfileSlug(confirmSlug);
        if (cleanSlug.isEmpty() || !cleanSlug.equals(cleanConfirm)) {
            throw new IllegalArgumentException("Profile deletion requires matching confirm_slug");
        }
        return deleteJson("/api/mobile/profiles/" + segment(cleanSlug)
                + "?confirm_slug=" + queryValue(cleanConfirm));
    }

    /** Fetch personalized first-run readiness, admin role flag, and localized starter prompts. */
    public JSONObject onboardingStatus() throws IOException {
        return get("/api/mobile/onboarding");
    }

    /** Fetch live server admin settings and summary counts (admin-only). */
    public JSONObject adminSettings() throws IOException {
        return get("/api/mobile/admin/settings");
    }

    /** Atomically update server admin settings (admin-only). */
    public JSONObject updateAdminSettings(JSONObject body) throws IOException {
        return patch("/api/mobile/admin/settings", body == null ? new JSONObject() : body);
    }

    private static boolean visibleAscii(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 33 || text.charAt(i) > 126) return false;
        }
        return true;
    }

    private static void applyCommonHeaders(HttpURLConnection http, String method, String accept, Limits limits)
            throws IOException {
        http.setInstanceFollowRedirects(false);
        http.setUseCaches(false);
        http.setConnectTimeout(limits.connectTimeoutMs);
        http.setReadTimeout(limits.readTimeoutMs);
        http.setRequestMethod(method);
        http.setRequestProperty("Accept", accept);
        http.setRequestProperty("Cache-Control", "no-store");
        String acceptLanguage = AppLanguage.currentAcceptLanguage();
        if (acceptLanguage == null || acceptLanguage.isEmpty()) {
            acceptLanguage = Locale.getDefault().toLanguageTag();
        }
        if (acceptLanguage != null && !acceptLanguage.isEmpty()) {
            http.setRequestProperty("Accept-Language", acceptLanguage);
            String langTag = acceptLanguage.toLowerCase(Locale.ROOT).startsWith("fr") ? "fr" : "en";
            http.setRequestProperty("X-Hermes-Lang", langTag);
        }
    }

    private HttpURLConnection connection(String path, String method, String accept) throws IOException {
        if (revoked) throw new IOException("Client revoked");
        HttpURLConnection http = (HttpURLConnection) endpoint.url(path).openConnection();
        applyCommonHeaders(http, method, accept, limits);
        http.setRequestProperty("Authorization", "Bearer " + bearer);
        return http;
    }

    private JSONObject request(String method, String path, JSONObject body, String key) throws IOException {
        HttpURLConnection http = connection(path, method, "application/json");
        trackConnection(http);
        try {
            if (key != null) http.setRequestProperty("Idempotency-Key", key);
            if (body != null) {
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                if (encoded.length > limits.jsonBytes) throw new IOException("Request exceeded the byte limit");
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                http.setDoOutput(true);
                http.setFixedLengthStreamingMode(encoded.length);
                try (OutputStream out = http.getOutputStream()) { out.write(encoded); }
            }
            int status = checkResponse(http);
            if (status == 204 || status == 205) return new JSONObject();
            try (InputStream in = http.getInputStream()) {
                String reply = Wire.read(in, limits.jsonBytes);
                if (reply.isEmpty() && method.equals("DELETE")) return new JSONObject();
                return Wire.object(reply);
            }
        } catch (IOException error) {
            Diagnostics.event("network", "request_failed");
            throw error;
        } catch (RuntimeException error) {
            if (revoked) throw new IOException("Client revoked", error);
            throw error;
        } finally {
            releaseConnection(http);
        }
    }

    private int checkResponse(HttpURLConnection http) throws IOException {
        return checkResponseStatic(http, limits);
    }

    private static int checkResponseStatic(HttpURLConnection http, Limits limits) throws IOException {
        int status = http.getResponseCode();
        if (status >= 200 && status < 300) return status;
        String code = "";
        try (InputStream error = http.getErrorStream()) {
            if (error != null) {
                JSONObject body = Wire.object(Wire.read(error, Math.min(65536, limits.jsonBytes)));
                JSONObject nested = body.optJSONObject("error");
                String raw = (nested == null ? body : nested).optString("code", "");
                if (raw.matches("[a-z0-9_]{1,80}")) code = raw;
            }
        } catch (IOException ignored) {
            // A malformed, truncated or oversized error body still has an authoritative HTTP status.
            Diagnostics.debug("network", "error_body_unreadable", ignored);
        }
        long delay = -1;
        String retry = http.getHeaderField("Retry-After");
        if (retry != null && retry.matches("[0-9]{1,18}")) delay = Long.parseLong(retry);
        throw new HttpError(status, code, delay);
    }

    /** Stream an authenticated file into a caller-owned sink with a strict byte ceiling. */
    public long download(String path, OutputStream sink, long maxBytes) throws IOException {
        return download(path, "application/octet-stream", sink, maxBytes);
    }

    /** Stream an authenticated response with a custom Accept header and strict byte ceiling. */
    public long download(String path, String accept, OutputStream sink, long maxBytes) throws IOException {
        if (sink == null || maxBytes < 0) throw new IllegalArgumentException("Invalid download bounds");
        String header = accept == null || accept.trim().isEmpty() ? "application/octet-stream" : accept.trim();
        HttpURLConnection http = connection(path, "GET", header);
        trackConnection(http);
        try {
            checkResponse(http);
            if (http.getContentLengthLong() > maxBytes) throw new IOException("File exceeded the byte limit");
            long total = 0;
            try (InputStream source = http.getInputStream()) {
                byte[] block = new byte[32768];
                int count;
                while ((count = source.read(block)) != -1) {
                    if (revoked) throw new IOException("Client revoked");
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
                    if (count > maxBytes - total) throw new IOException("File exceeded the byte limit");
                    sink.write(block, 0, count);
                    total += count;
                }
            }
            return total;
        } catch (IOException error) {
            Diagnostics.event("network", "download_failed");
            throw error;
        } catch (RuntimeException error) {
            if (revoked) throw new IOException("Client revoked", error);
            throw error;
        } finally {
            releaseConnection(http);
        }
    }

    /** Retain the handle to cancel a blocking next(). Supply -1 for the first subscription. */
    public RunEvents runEvents(String runId, long lastEventId) {
        if (lastEventId < -1) throw new IllegalArgumentException("Invalid event cursor");
        return new RunEvents("/v1/runs/" + segment(runId) + "/events", lastEventId);
    }

    public final class RunEvents implements Closeable {
        private final String path;
        private final long lastEventId;
        private final Object lifecycle = new Object();
        private volatile boolean closed;
        private HttpURLConnection http;
        private SseReader reader;

        private RunEvents(String path, long lastEventId) {
            this.path = path;
            this.lastEventId = lastEventId;
        }

        private SseReader open() throws IOException {
            if (revoked) throw new IOException("Client revoked");
            if (closed) throw new IOException("Stream closed");
            HttpURLConnection stream = connection(path, "GET", "text/event-stream");
            if (lastEventId >= 0) stream.setRequestProperty("Last-Event-ID", Long.toString(lastEventId));
            synchronized (lifecycle) {
                if (closed || revoked) {
                    disconnectSafely(stream);
                    throw new IOException(revoked ? "Client revoked" : "Stream closed");
                }
                http = stream;
            }
            openStreams.add(this);
            if (revoked) {
                close();
                throw new IOException("Client revoked");
            }
            checkResponse(stream);
            String type = stream.getContentType();
            if (type == null || !type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("text/event-stream")) {
                throw new IOException("Expected an event stream");
            }
            return new SseReader(stream.getInputStream(), limits.eventBytes);
        }

        /** Returns one JSON event, or null after the stream closes. Reconnect using the last committed seq. */
        public JSONObject next() throws IOException { return next(false); }

        /** Include empty objects for SSE comment heartbeats so an idle follower can reconcile status. */
        public synchronized JSONObject next(boolean includeHeartbeats) throws IOException {
            if (revoked) {
                close();
                throw new IOException("Client revoked");
            }
            if (closed) return null;
            try {
                if (reader == null) reader = open();
                JSONObject item = reader.next(includeHeartbeats);
                if (item == null) close();
                return closed ? null : item;
            } catch (IOException error) {
                if (!closed) Diagnostics.event("network", "stream_failed");
                close();
                throw error;
            } catch (RuntimeException error) {
                boolean wasRevoked = revoked;
                boolean wasClosed = closed;
                close();
                if (wasRevoked) throw new IOException("Client revoked", error);
                if (wasClosed) throw new IOException("Stream closed", error);
                throw error;
            }
        }

        @Override public void close() {
            openStreams.remove(this);
            HttpURLConnection stream;
            synchronized (lifecycle) {
                closed = true;
                stream = http;
                http = null;
            }
            disconnectSafely(stream);
        }
    }
}
