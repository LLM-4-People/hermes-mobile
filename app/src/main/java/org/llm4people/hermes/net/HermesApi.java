package org.llm4people.hermes.net;

import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

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
            HermesConfig cfg = HermesConfig.defaults();
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

    public static String segment(String raw) {
        return Endpoint.segment(raw);
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
        request("DELETE", path, null, null);
    }

    /** Send a bounded multipart/form-data upload and decode the JSON response. */
    public JSONObject postMultipart(String path, String boundary, byte[] body) throws IOException {
        if (boundary == null || !visibleAscii(boundary) || body == null || body.length == 0) {
            throw new IllegalArgumentException("Invalid multipart payload");
        }
        long maxUpload = Math.max(limits.jsonBytes, HermesConfig.defaults().artifactDefaultMaxBytes + 4096L);
        if (body.length > maxUpload) throw new IOException("Upload exceeded the byte limit");
        HttpURLConnection http = connection(path, "POST", "application/json");
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
        } finally {
            http.disconnect();
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

    private static boolean visibleAscii(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 33 || text.charAt(i) > 126) return false;
        }
        return true;
    }

    private HttpURLConnection connection(String path, String method, String accept) throws IOException {
        HttpURLConnection http = (HttpURLConnection) endpoint.url(path).openConnection();
        http.setInstanceFollowRedirects(false);
        http.setUseCaches(false);
        http.setConnectTimeout(limits.connectTimeoutMs);
        http.setReadTimeout(limits.readTimeoutMs);
        http.setRequestMethod(method);
        http.setRequestProperty("Authorization", "Bearer " + bearer);
        http.setRequestProperty("Accept", accept);
        http.setRequestProperty("Cache-Control", "no-store");
        return http;
    }

    private JSONObject request(String method, String path, JSONObject body, String key) throws IOException {
        HttpURLConnection http = connection(path, method, "application/json");
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
        } finally {
            http.disconnect();
        }
    }

    private int checkResponse(HttpURLConnection http) throws IOException {
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
        if (sink == null || maxBytes < 0) throw new IllegalArgumentException("Invalid download bounds");
        HttpURLConnection http = connection(path, "GET", "application/octet-stream");
        try {
            checkResponse(http);
            if (http.getContentLengthLong() > maxBytes) throw new IOException("File exceeded the byte limit");
            long total = 0;
            try (InputStream source = http.getInputStream()) {
                byte[] block = new byte[32768];
                int count;
                while ((count = source.read(block)) != -1) {
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
        } finally {
            http.disconnect();
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
            HttpURLConnection stream = connection(path, "GET", "text/event-stream");
            if (lastEventId >= 0) stream.setRequestProperty("Last-Event-ID", Long.toString(lastEventId));
            synchronized (lifecycle) {
                if (closed) { stream.disconnect(); throw new IOException("Stream closed"); }
                http = stream;
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
            }
        }

        @Override public void close() {
            HttpURLConnection stream;
            synchronized (lifecycle) {
                closed = true;
                stream = http;
                http = null;
            }
            if (stream != null) stream.disconnect();
        }
    }
}
