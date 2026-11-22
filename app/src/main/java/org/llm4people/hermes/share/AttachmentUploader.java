package org.llm4people.hermes.share;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.net.HermesApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Capability-gated uploader targeting Fork PR #14 POST /api/sessions/{session_id}/uploads.
 * Enforces HermesConfig SSoT size limits and builds clean attachments[] arrays for POST /v1/runs.
 */
public final class AttachmentUploader {
    public record UploadReceipt(String uploadId, String filename, String mimeType, long size, String sha256) {}

    public interface MultipartTransport {
        JSONObject postMultipart(String path, String boundary, byte[] body) throws IOException;
    }

    private AttachmentUploader() {}

    public static boolean supportsUploads(JSONObject features) {
        return features != null && Boolean.TRUE.equals(features.opt("session_uploads"));
    }

    public static String uploadPath(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing session id");
        }
        return "/api/sessions/" + HermesApi.segment(sessionId) + "/uploads";
    }

    public static byte[] buildMultipartBody(String boundary, CapturedMedia.Item item) throws IOException {
        if (boundary == null || !boundary.matches("[A-Za-z0-9_-]{8,70}")) {
            throw new IllegalArgumentException("Invalid multipart boundary");
        }
        if (item == null || item.bytes() == null || item.bytes().length == 0) {
            throw new IllegalArgumentException("Empty attachment");
        }
        String safeName = CapturedMedia.sanitizeFilename(item.name(), "fichier")
                .replace("\"", "_")
                .replace("\r", "")
                .replace("\n", "");
        String safeMime = CapturedMedia.isAllowedMime(item.mimeType())
                ? item.mimeType() : "application/octet-stream";

        ByteArrayOutputStream out = new ByteArrayOutputStream(item.size() + 256);
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + safeMime + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(item.bytes());
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public static UploadReceipt upload(
            MultipartTransport transport, String sessionId, CapturedMedia.Item item, long maxArtifactBytes)
            throws IOException {
        if (transport == null || item == null) throw new IllegalArgumentException("Missing upload inputs");
        long bound = HermesConfig.defaults().resolveArtifactLimit(maxArtifactBytes);
        if (item.size() <= 0 || item.size() > bound) {
            Diagnostics.warning("share", "upload_exceeds_limit");
            throw new IOException("Attachment exceeds configured size limit");
        }
        String boundary = "HermesUpload" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipartBody(boundary, item);
        JSONObject reply = transport.postMultipart(uploadPath(sessionId), boundary, body);
        if (reply == null) throw new IOException("Empty upload response");
        String uploadId = reply.optString("upload_id", "").trim();
        String filename = reply.optString("filename", item.name()).trim();
        String mimeType = reply.optString("mime_type", item.mimeType()).trim();
        long size = reply.optLong("size", item.size());
        String sha256 = reply.optString("sha256", "").trim();
        if (uploadId.isEmpty() || uploadId.length() > 128) {
            Diagnostics.warning("share", "upload_invalid_receipt");
            throw new IOException("Invalid upload receipt");
        }
        if (!sha256.isEmpty() && !sha256.equalsIgnoreCase(item.sha256Hex())) {
            Diagnostics.warning("share", "upload_digest_mismatch");
            throw new IOException("Uploaded digest mismatch");
        }
        return new UploadReceipt(uploadId, filename, mimeType, size, sha256);
    }

    public static List<UploadReceipt> uploadAll(
            MultipartTransport transport, String sessionId, List<CapturedMedia.Item> items, long maxArtifactBytes)
            throws IOException {
        List<UploadReceipt> receipts = new ArrayList<>();
        if (items == null) return receipts;
        for (CapturedMedia.Item item : items) {
            receipts.add(upload(transport, sessionId, item, maxArtifactBytes));
        }
        return receipts;
    }

    /** Attach verified upload_ids to a POST /v1/runs JSON body without mutating other fields. */
    public static JSONObject attachToRunBody(JSONObject runBody, List<UploadReceipt> receipts) {
        if (runBody == null || receipts == null || receipts.isEmpty()) return runBody;
        JSONArray ids = new JSONArray();
        for (UploadReceipt r : receipts) {
            if (r != null && r.uploadId() != null && !r.uploadId().isEmpty()) {
                ids.put(r.uploadId());
            }
        }
        if (ids.length() > 0) {
            try {
                runBody.put("attachments", ids);
            } catch (JSONException invalid) {
                throw new IllegalStateException(invalid);
            }
        }
        return runBody;
    }
}
