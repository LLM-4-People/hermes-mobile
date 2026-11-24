package org.llm4people.hermes.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.llm4people.hermes.Diagnostics;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Zero-dependency, non-exported ContentProvider serving ONLY integrity-verified APK updates
 * stored inside {@code cacheDir/updates/} to the Android package installer via read-only
 * {@code FLAG_GRANT_READ_URI_PERMISSION} grants. Strictly rejects path traversal and write modes.
 */
public final class UpdateApkProvider extends ContentProvider {
    public static final String MIME_APK = "application/vnd.android.package-archive";
    static final String DIR_NAME = "updates";
    private static final Pattern VERIFIED_APK_NAME =
            Pattern.compile("hermes-update-[A-Za-z0-9._-]{1,64}\\.apk");

    public static String authority(Context context) {
        return context.getPackageName() + ".updates";
    }

    public static File updatesDir(Context context) {
        return new File(context.getCacheDir(), DIR_NAME);
    }

    /** Validate a candidate verified APK filename (single path segment, no traversal). */
    public static boolean isValidFileName(String fileName) {
        return fileName != null
                && !fileName.contains("..")
                && !fileName.contains("/")
                && !fileName.contains("\\")
                && VERIFIED_APK_NAME.matcher(fileName).matches();
    }

    /** Build a safe filename for a verified release tag. */
    public static String fileNameForTag(String tagName) {
        StringBuilder clean = new StringBuilder();
        if (tagName != null) {
            for (int i = 0; i < tagName.length() && clean.length() < 48; i++) {
                char c = tagName.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                    clean.append(c);
                }
            }
        }
        String suffix = clean.toString().replace("..", "_");
        if (suffix.isEmpty()) suffix = "release";
        return "hermes-update-" + suffix + ".apk";
    }

    /** Vend a content:// URI for a verified APK file inside {@code cacheDir/updates/}. */
    public static Uri uriForFile(Context context, File file) throws IOException {
        if (context == null || file == null) throw new IOException("Missing APK file");
        String name = file.getName();
        if (!isValidFileName(name)) throw new IOException("Invalid verified APK filename");
        File dir = updatesDir(context).getCanonicalFile();
        File canonical = file.getCanonicalFile();
        if (!dir.equals(canonical.getParentFile()) || !canonical.isFile()) {
            throw new IOException("APK file outside private updates directory");
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(name)
                .build();
    }

    /** Resolve a content:// URI strictly to an existing verified APK inside {@code cacheDir/updates/}. */
    public static File resolveVerifiedFile(Context context, Uri uri) {
        if (context == null || uri == null || !"content".equals(uri.getScheme())) return null;
        if (!authority(context).equals(uri.getAuthority())) return null;
        if (uri.getPathSegments().size() != 1) return null;
        String segment = uri.getLastPathSegment();
        if (!isValidFileName(segment)) return null;
        try {
            File dir = updatesDir(context).getCanonicalFile();
            File candidate = new File(dir, segment).getCanonicalFile();
            if (!dir.equals(candidate.getParentFile()) || !candidate.isFile()) return null;
            return candidate;
        } catch (IOException unreadable) {
            Diagnostics.warning("update", "resolve_apk_failed", unreadable);
            return null;
        }
    }

    /** Delete all cached APK and partial download files in {@code cacheDir/updates/}. */
    public static void purgeAll(Context context) {
        if (context == null) return;
        File dir = updatesDir(context);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && !file.delete()) {
                Diagnostics.debug("update", "purge_item_failed");
            }
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !"r".equals(mode)) {
            Diagnostics.warning("update", "write_mode_rejected");
            throw new SecurityException("Verified update packages are read-only");
        }
        File target = resolveVerifiedFile(getContext(), uri);
        if (target == null) {
            Diagnostics.warning("update", "invalid_apk_uri");
            throw new FileNotFoundException("Invalid update package URI");
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return resolveVerifiedFile(getContext(), uri) == null ? null : MIME_APK;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File target = resolveVerifiedFile(getContext(), uri);
        if (target == null) return null;
        String[] cols = projection == null || projection.length == 0
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = target.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = target.length();
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
