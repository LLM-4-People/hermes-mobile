package org.llm4people.hermes.thread;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.perf.BitmapSampling;
import org.llm4people.hermes.perf.ThumbnailCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** A user-selected still image. Only re-encoded pixels leave the phone. */
final class DiscussionPhoto {
    private static final long SOURCE_LIMIT = HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES;
    static final int PAYLOAD_LIMIT = 640 * 1024;
    final JSONObject info;
    final String pixels;

    private DiscussionPhoto(JSONObject info, byte[] bytes) {
        this.info = info;
        pixels = Base64.getEncoder().encodeToString(bytes);
    }

    static DiscussionPhoto read(ContentResolver resolver, Uri uri) throws IOException {
        return read(resolver, uri, SOURCE_LIMIT);
    }

    static long effectiveSourceLimit(long maxSourceBytes) {
        return maxSourceBytes > 0 ? maxSourceBytes : SOURCE_LIMIT;
    }

    static DiscussionPhoto read(ContentResolver resolver, Uri uri, long maxSourceBytes) throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) throw new IOException();
        long limit = effectiveSourceLimit(maxSourceBytes);
        byte[] source;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || data.size() + (long) length > limit) throw new IOException();
                data.write(buffer, 0, length);
            }
            source = data.toByteArray();
        }
        return fromBytes(source, name(resolver, uri), limit);
    }

    static DiscussionPhoto fromBytes(byte[] source, String name) throws IOException {
        return fromBytes(source, name, SOURCE_LIMIT);
    }

    static DiscussionPhoto fromBytes(byte[] source, String name, long maxSourceBytes) throws IOException {
        long limit = effectiveSourceLimit(maxSourceBytes);
        if (source.length == 0 || source.length > limit) throw new IOException("Unsupported image size");
        Bitmap image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source)), (decoder, details, src) -> {
            int width = details.getSize().getWidth(), height = details.getSize().getHeight();
            if (details.isAnimated() || !BitmapSampling.validDimensions(width, height))
                throw new IllegalArgumentException("Unsupported still image");
            int[] target = BitmapSampling.targetDimensions(width, height, 1600);
            if (target[0] != width || target[1] != height) decoder.setTargetSize(target[0], target[1]);
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        try { return encode(image, name); }
        finally { image.recycle(); }
    }

    private static DiscussionPhoto encode(Bitmap image, String name) throws IOException {
        // Decode applies orientation. Encoding a fresh bitmap discards EXIF, GPS, and source filename.
        Bitmap opaque = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(opaque);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(image, null, new android.graphics.Rect(0, 0, opaque.getWidth(), opaque.getHeight()), null);
        opaque.setHasAlpha(false);
        try {
            String mime = "image/png";
            byte[] bytes = compress(opaque, Bitmap.CompressFormat.PNG, 100);
            if (bytes.length > PAYLOAD_LIMIT) {
                mime = "image/jpeg";
                for (int quality : new int[]{87, 72, 55, 38}) {
                    bytes = compress(opaque, Bitmap.CompressFormat.JPEG, quality);
                    if (bytes.length <= PAYLOAD_LIMIT) break;
                }
            }
            if (bytes.length > PAYLOAD_LIMIT) throw new IOException("Image too large");
            int edge = Math.max(opaque.getWidth(), opaque.getHeight());
            Bitmap thumb = Bitmap.createScaledBitmap(opaque, Math.max(1, 72 * opaque.getWidth() / edge),
                    Math.max(1, 72 * opaque.getHeight() / edge), true);
            String preview;
            try { preview = Base64.getEncoder().encodeToString(compress(thumb, Bitmap.CompressFormat.JPEG, 70)); }
            finally { if (thumb != opaque) thumb.recycle(); }
            JSONObject info = new JSONObject();
            ThreadData.put(info, "name", name);
            ThreadData.put(info, "mime", mime);
            ThreadData.put(info, "digest", digest(bytes));
            ThreadData.put(info, "thumbnail", preview);
            return new DiscussionPhoto(info, bytes);
        } finally { opaque.recycle(); }
    }

    private static byte[] compress(Bitmap bitmap, Bitmap.CompressFormat format, int quality) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        if (!bitmap.compress(format, quality, data)) throw new IOException("Image encoding failed");
        return data.toByteArray();
    }

    private static String name(ContentResolver resolver, Uri uri) {
        try (Cursor row = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst()) {
                String raw = row.getString(0);
                if (raw != null) {
                    String result = raw.substring(0, Math.min(raw.length(), 128)).replaceAll("[\\p{Cc}\\p{Cf}/\\\\]", "").trim();
                    if (!result.isEmpty()) return result;
                }
            }
        } catch (RuntimeException unavailable) { /* The name is optional. */ }
        return ThreadStrings.get(org.llm4people.hermes.R.string.thread_photo_selected);
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
