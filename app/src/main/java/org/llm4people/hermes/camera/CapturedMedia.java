package org.llm4people.hermes.camera;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Privacy-safe media and document sanitizer shared by camera capture and inbound share intake.
 * Strips all EXIF/GPS metadata from images by decoding and re-encoding fresh opaque bitmaps,
 * and strictly allowlists safe document MIME types within HermesConfig bounds.
 */
public final class CapturedMedia {
    public static final int IMAGE_PAYLOAD_LIMIT = 640 * 1024;
    public static final int IMAGE_MAX_EDGE = 1600;
    public static final Set<String> ALLOWED_DOCUMENT_MIMES = Set.of(
            "application/pdf",
            "application/json",
            "application/xml",
            "application/x-yaml",
            "application/yaml");

    public enum Kind { IMAGE, DOCUMENT }

    public record Item(
            Kind kind,
            String name,
            String mimeType,
            byte[] bytes,
            String sha256Hex,
            String thumbnailBase64) {
        public int size() { return bytes.length; }
    }

    private CapturedMedia() {}

    public static boolean isAllowedMime(String rawMime) {
        if (rawMime == null) return false;
        String mime = rawMime.trim().toLowerCase(Locale.ROOT);
        if (mime.isEmpty() || mime.length() > 128) return false;
        if (mime.startsWith("image/") && !mime.contains("svg")) return true;
        if (mime.startsWith("text/")) return true;
        return ALLOWED_DOCUMENT_MIMES.contains(mime);
    }

    public static String sanitizeFilename(String raw, String fallback) {
        if (raw != null) {
            String cleaned = raw.substring(0, Math.min(raw.length(), 128))
                    .replaceAll("[\\p{Cc}\\p{Cf}/\\\\]", "")
                    .trim();
            if (!cleaned.isEmpty() && !".".equals(cleaned) && !"..".equals(cleaned)) return cleaned;
        }
        return fallback;
    }

    public static Item readFromUri(ContentResolver resolver, Uri uri, String declaredMime, long maxDocumentBytes)
            throws IOException {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("Only content:// URIs are accepted");
        }
        String resolvedMime = declaredMime;
        if (resolvedMime == null || resolvedMime.trim().isEmpty() || "*/*".equals(resolvedMime.trim())) {
            resolvedMime = resolver.getType(uri);
        }
        if (!isAllowedMime(resolvedMime)) {
            Diagnostics.warning("capture", "mime_rejected");
            throw new IOException("Unsupported media type");
        }
        String normalized = resolvedMime.trim().toLowerCase(Locale.ROOT);
        long limit = HermesConfig.defaults().resolveArtifactLimit(maxDocumentBytes);
        byte[] raw = readBounded(resolver, uri, normalized.startsWith("image/") ? Math.min(limit, 16L * 1024 * 1024) : limit);
        String name = queryName(resolver, uri, normalized.startsWith("image/") ? "Photo.jpg" : "Document");
        if (normalized.startsWith("image/")) {
            return stripImageExif(raw, name);
        }
        return new Item(Kind.DOCUMENT, name, normalized, raw, sha256Hex(raw), null);
    }

    /** Re-encode a still image into fresh sRGB opaque pixels, discarding all EXIF, GPS, and ICC tags. */
    public static Item stripImageExif(byte[] source, String name) throws IOException {
        if (source == null || source.length == 0 || source.length > 16 * 1024 * 1024) {
            throw new IOException("Unsupported image size");
        }
        Bitmap image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source)), (decoder, details, src) -> {
            int width = details.getSize().getWidth(), height = details.getSize().getHeight();
            if (details.isAnimated() || width <= 0 || height <= 0 || (long) width * height > 128_000_000L) {
                throw new IllegalArgumentException("Unsupported still image");
            }
            int longEdge = Math.max(width, height);
            if (longEdge > IMAGE_MAX_EDGE) {
                decoder.setTargetSize(
                        Math.max(1, (int) ((long) IMAGE_MAX_EDGE * width / longEdge)),
                        Math.max(1, (int) ((long) IMAGE_MAX_EDGE * height / longEdge)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        try {
            Bitmap opaque = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(image, null, new android.graphics.Rect(0, 0, opaque.getWidth(), opaque.getHeight()), null);
                opaque.setHasAlpha(false);
                String mime = "image/png";
                byte[] bytes = compress(opaque, Bitmap.CompressFormat.PNG, 100);
                if (bytes.length > IMAGE_PAYLOAD_LIMIT) {
                    mime = "image/jpeg";
                    for (int quality : new int[]{87, 72, 55, 38}) {
                        bytes = compress(opaque, Bitmap.CompressFormat.JPEG, quality);
                        if (bytes.length <= IMAGE_PAYLOAD_LIMIT) break;
                    }
                }
                if (bytes.length > IMAGE_PAYLOAD_LIMIT) throw new IOException("Image exceeds payload bound");
                int edge = Math.max(opaque.getWidth(), opaque.getHeight());
                Bitmap thumb = Bitmap.createScaledBitmap(opaque,
                        Math.max(1, 72 * opaque.getWidth() / edge),
                        Math.max(1, 72 * opaque.getHeight() / edge), true);
                String preview;
                try { preview = Base64.getEncoder().encodeToString(compress(thumb, Bitmap.CompressFormat.JPEG, 70)); }
                finally { if (thumb != opaque) thumb.recycle(); }
                return new Item(Kind.IMAGE, sanitizeFilename(name, "Photo"), mime, bytes, sha256Hex(bytes), preview);
            } finally {
                opaque.recycle();
            }
        } finally {
            image.recycle();
        }
    }

    private static byte[] readBounded(ContentResolver resolver, Uri uri, long limit) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Unreadable content URI");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || data.size() + (long) count > limit) {
                    throw new IOException("Content exceeded size bound");
                }
                data.write(buffer, 0, count);
            }
            if (data.size() == 0) throw new IOException("Empty shared item");
            return data.toByteArray();
        }
    }

    private static String queryName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor row = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst()) {
                return sanitizeFilename(row.getString(0), fallback);
            }
        } catch (RuntimeException unavailable) {
            Diagnostics.debug("capture", "display_name_unavailable", unavailable);
        }
        return fallback;
    }

    private static byte[] compress(Bitmap bitmap, Bitmap.CompressFormat format, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(format, quality, out)) throw new IOException("Image compression failed");
        return out.toByteArray();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b & 255) >>> 4, 16))
                        .append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
