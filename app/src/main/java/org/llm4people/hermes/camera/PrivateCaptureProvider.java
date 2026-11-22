package org.llm4people.hermes.camera;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.llm4people.hermes.Diagnostics;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Zero-dependency private ContentProvider vending single-use UUID capture slots inside cacheDir.
 * Exported=false with grantUriPermissions=true so only the system camera granted an explicit slot URI
 * can write pixels, with strict path-traversal rejection and immediate unlink after EXIF stripping.
 */
public final class PrivateCaptureProvider extends ContentProvider {
    private static final String DIR_NAME = "camera_capture";
    private static final Pattern SLOT_NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");

    public static String authority(Context context) {
        return context.getPackageName() + ".capture";
    }

    public static Uri createSlot(Context context) throws IOException {
        File dir = captureDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create private capture directory");
        }
        String name = UUID.randomUUID() + ".jpg";
        File target = new File(dir, name);
        if (!target.createNewFile()) {
            throw new IOException("Could not allocate capture slot");
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(name)
                .build();
    }

    public static File fileForSlot(Context context, Uri uri) {
        if (context == null || uri == null || !"content".equals(uri.getScheme())) return null;
        if (!authority(context).equals(uri.getAuthority())) return null;
        String segment = uri.getLastPathSegment();
        if (segment == null || uri.getPathSegments().size() != 1 || !SLOT_NAME.matcher(segment).matches()) {
            return null;
        }
        return new File(captureDir(context), segment);
    }

    public static void deleteSlot(Context context, Uri uri) {
        File file = fileForSlot(context, uri);
        if (file != null && file.exists() && !file.delete()) {
            Diagnostics.warning("camera", "slot_delete_failed");
        }
    }

    public static void purgeAll(Context context) {
        File dir = captureDir(context);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && !file.delete()) {
                Diagnostics.debug("camera", "purge_item_failed");
            }
        }
    }

    private static File captureDir(Context context) {
        return new File(context.getCacheDir(), DIR_NAME);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = getContext();
        File target = fileForSlot(context, uri);
        if (target == null) {
            Diagnostics.warning("camera", "invalid_slot_uri");
            throw new FileNotFoundException("Invalid capture slot");
        }
        int parsedMode = ParcelFileDescriptor.parseMode(mode == null ? "r" : mode);
        return ParcelFileDescriptor.open(target, parsedMode);
    }

    @Override public String getType(Uri uri) {
        return fileForSlot(getContext(), uri) == null ? null : "image/jpeg";
    }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String sel, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { throw new UnsupportedOperationException(); }
}
