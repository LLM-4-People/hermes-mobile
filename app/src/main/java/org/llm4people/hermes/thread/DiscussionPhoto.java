package org.llm4people.hermes.thread;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.perf.ThumbnailCache;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** A user-selected still image. Only re-encoded pixels leave the phone. */
final class DiscussionPhoto {
    static final int PAYLOAD_LIMIT = CapturedMedia.IMAGE_PAYLOAD_LIMIT;
    final JSONObject info;
    final String pixels;

    private DiscussionPhoto(JSONObject info, byte[] bytes) {
        this.info = info;
        pixels = Base64.getEncoder().encodeToString(bytes);
    }

    static DiscussionPhoto read(ContentResolver resolver, Uri uri, long maxSourceBytes) throws IOException {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("Only content:// URIs are accepted");
        }
        long limit = CapturedMedia.effectiveByteLimit(maxSourceBytes);
        byte[] source = CapturedMedia.readBounded(resolver, uri, limit);
        return fromBytes(source, name(resolver, uri), limit);
    }

    static DiscussionPhoto fromBytes(byte[] source, String name, long maxSourceBytes) throws IOException {
        CapturedMedia.Item item = CapturedMedia.stripImageExif(source, name, maxSourceBytes);
        JSONObject info = new JSONObject();
        ThreadData.put(info, "name", item.name());
        ThreadData.put(info, "mime", item.mimeType());
        ThreadData.put(info, "digest", digest(item.bytes()));
        ThreadData.put(info, "thumbnail", item.thumbnailBase64());
        return new DiscussionPhoto(info, item.bytes());
    }

    private static String name(ContentResolver resolver, Uri uri) {
        return CapturedMedia.queryDisplayName(resolver, uri,
                ThreadStrings.get(org.llm4people.hermes.R.string.thread_photo_selected));
    }

    static boolean valid(JSONObject info) {
        return info != null && ThreadData.string(info, "digest").matches("[A-Za-z0-9+/]{43}=")
                && ("image/png".equals(info.optString("mime")) || "image/jpeg".equals(info.optString("mime")))
                && !ThreadData.string(info, "name").isEmpty() && info.optString("name").length() <= 128
                && ThreadData.string(info, "thumbnail").length() <= 16384;
    }

    static Bitmap thumbnail(JSONObject info) {
        if (info == null) return null;
        String digest = ThreadData.string(info, "digest");
        String cacheKey = digest.isEmpty() ? null : "photo:" + digest;
        if (cacheKey != null) {
            Bitmap cached = ThumbnailCache.get(cacheKey);
            if (cached != null) return cached;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(info.optString("thumbnail"));
            return ThumbnailCache.getOrDecode(cacheKey, bytes, ThumbnailCache.DEFAULT_THUMB_MAX_EDGE);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    static String digest(byte[] bytes) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static final class Unavailable extends IOException {
        private static final long serialVersionUID = 1L;
        Unavailable() { super("Saved image unavailable"); }
    }

    static JSONObject wire(JSONObject body, JSONObject info, String pixels) throws IOException {
        if (!valid(info) || pixels == null || pixels.length() > ((PAYLOAD_LIMIT + 2) / 3) * 4)
            throw new Unavailable();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(pixels); }
        catch (IllegalArgumentException failure) { throw new Unavailable(); }
        if (!digest(bytes).equals(info.optString("digest"))) throw new Unavailable();
        JSONObject url = ThreadData.put(new JSONObject(), "url", "data:" + info.optString("mime") + ";base64," + pixels);
        JSONArray parts = new JSONArray().put(ThreadData.put(ThreadData.put(new JSONObject(), "type", "text"),
                "text", ThreadData.string(body, "input"))).put(ThreadData.put(
                ThreadData.put(new JSONObject(), "type", "image_url"), "image_url", url));
        JSONObject message = ThreadData.put(ThreadData.put(new JSONObject(), "role", "user"), "content", parts);
        return ThreadData.put(ThreadData.object(body.toString()), "input", new JSONArray().put(message));
    }
}
