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
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.perf.BitmapSampling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Privacy-safe media and document sanitizer shared by camera capture and inbound share intake.
 * Strips all EXIF/GPS metadata from images by decoding and re-encoding fresh opaque bitmaps,
 * and strictly allowlists safe document MIME types within HermesConfig bounds.
 */
public final class CapturedMedia {
    public static final int IMAGE_PAYLOAD_LIMIT = 640 * 1024;
    public static final int IMAGE_MAX_EDGE = 1600;
    private static final Pattern RFC6838_MIME = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}");
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
        for (int i = 0; i < rawMime.length(); i++) {
            char c = rawMime.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;
        }
        String mime = rawMime.trim().toLowerCase(Locale.ROOT);
        if (mime.isEmpty() || mime.length() > 128 || !RFC6838_MIME.matcher(mime).matches()) return false;
        if (mime.startsWith("image/") && !mime.contains("svg")) return true;
        if (mime.startsWith("text/")) return true;
        return ALLOWED_DOCUMENT_MIMES.contains(mime);
    }

    public static String resolveMime(String declaredMime, String resolverMime) {
        String declared = declaredMime == null ? "" : declaredMime.trim();
        if (declared.isEmpty() || declared.endsWith("/*")) {
            String queried = resolverMime == null ? "" : resolverMime.trim();
            if (!queried.isEmpty() && !queried.endsWith("/*")) {
                return queried;
            }
            if ("text/*".equalsIgnoreCase(declared)) {
                return "text/plain";
            }
            return queried;
        }
        return declared;
    }

    public static String sanitizeFilename(String raw, String fallback) {
        if (raw != null) {
            StringBuilder out = new StringBuilder(Math.min(raw.length(), 128));
            boolean prevVisibleBase = false;
            for (int i = 0; i < raw.length(); ) {
                int cp = raw.codePointAt(i);
                int step = Character.charCount(cp);
                i += step;
                int type = Character.getType(cp);
                if (cp == '/' || cp == '\\' || Character.isISOControl(cp)
                        || type == Character.FORMAT || type == Character.SURROGATE
                        || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                        || PlainTextSanitizer.isStrippableInvisible(cp)) {
                    continue;
                }
                if (PlainTextSanitizer.isVariationSelector(cp)) {
                    if (!prevVisibleBase) continue;
                    if (out.length() + step > 128) break;
                    out.appendCodePoint(cp);
                    prevVisibleBase = false;
                    continue;
                }
                if (out.length() + step > 128) break;
                out.appendCodePoint(cp);
                prevVisibleBase = !Character.isWhitespace(cp) && !Character.isSpaceChar(cp);
            }
            String cleaned = out.toString().trim();
            if (!cleaned.isEmpty() && !".".equals(cleaned) && !"..".equals(cleaned)
                    && PlainTextSanitizer.hasVisibleText(cleaned)) {
                return cleaned;
            }
        }
        return fallback;
    }

    /** Honors the caller's pre-resolved positive artifact byte ceiling, defaulting only when {@code <= 0}. */
    public static long effectiveByteLimit(long requestedBytes) {
        return requestedBytes > 0 ? requestedBytes : HermesConfig.defaults().artifactDefaultMaxBytes;
    }

    static String resolveAndValidateMime(String declaredMime, String resolverMime) {
        String declared = declaredMime == null ? "" : declaredMime.trim();
        if (!declared.isEmpty() && !declared.endsWith("/*") && !isAllowedMime(declared)) {
            return null;
        }
        String queried = resolverMime == null ? "" : resolverMime.trim();
        if (!queried.isEmpty() && !queried.endsWith("/*")) {
            return isAllowedMime(queried) ? queried.toLowerCase(Locale.ROOT) : null;
        }
        String resolved = resolveMime(declaredMime, queried);
        return isAllowedMime(resolved) ? resolved.trim().toLowerCase(Locale.ROOT) : null;
    }

    public static Item readFromUri(ContentResolver resolver, Uri uri, String declaredMime, long maxDocumentBytes)
            throws IOException {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("Only content:// URIs are accepted");
        }
        String normalized = resolveAndValidateMime(declaredMime, resolver.getType(uri));
        if (normalized == null) {
            Diagnostics.warning("capture", "mime_rejected");
            throw new IOException("Unsupported media type");
        }
        long limit = effectiveByteLimit(maxDocumentBytes);
        byte[] raw = readBounded(resolver, uri, limit);
        String name = queryDisplayName(resolver, uri, normalized.startsWith("image/") ? "Photo.jpg" : "Document");
        if (normalized.startsWith("image/")) {
            return stripImageExif(raw, name, limit);
        }
        return new Item(Kind.DOCUMENT, name, normalized, raw, sha256Hex(raw), null);
    }

    /** Re-encode a still image into fresh sRGB opaque pixels, honoring a caller-resolved byte ceiling. */
    public static Item stripImageExif(byte[] source, String name, long maxBytes) throws IOException {
        long limit = effectiveByteLimit(maxBytes);
        if (source == null || source.length == 0 || source.length > limit) {
            throw new IOException("Unsupported image size");
        }
        Bitmap image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source)), (decoder, details, src) -> {
            int width = details.getSize().getWidth(), height = details.getSize().getHeight();
            if (details.isAnimated() || !BitmapSampling.validDimensions(width, height)) {
                throw new IllegalArgumentException("Unsupported still image");
            }
            int[] target = BitmapSampling.targetDimensions(width, height, IMAGE_MAX_EDGE);
            if (target[0] != width || target[1] != height) {
                decoder.setTargetSize(target[0], target[1]);
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

    public static byte[] readBounded(ContentResolver resolver, Uri uri, long limit) throws IOException {
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

    public static String queryDisplayName(ContentResolver resolver, Uri uri, String fallback) {
        if (resolver == null || uri == null) return fallback;
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
        return org.llm4people.hermes.Digests.sha256Hex(bytes);
    }
}
