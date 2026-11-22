package org.llm4people.hermes.files;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import org.llm4people.hermes.net.HermesApi;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;

/** Unlinked app-private staging: only verified bytes reach the preview or the document provider. */
final class VerifiedFile implements Closeable {
    final RandomAccessFile data;
    private VerifiedFile(RandomAccessFile data) { this.data = data; }

    static VerifiedFile fetch(File cache, HermesApi api, PublishedFile item) throws IOException {
        File file = File.createTempFile("transfer-", ".tmp", cache);
        RandomAccessFile data = new RandomAccessFile(file, "rw");
        boolean success = false;
        try {
            // On Android internal storage the open descriptor outlives the directory entry.
            if (!file.delete()) throw new IOException("Could not unlink temporary file");
            MessageDigest digest = PublishedFile.digest();
            try (ParcelFileDescriptor dup = ParcelFileDescriptor.dup(data.getFD());
                 OutputStream copy = new ParcelFileDescriptor.AutoCloseOutputStream(dup);
                 DigestOutputStream sink = new DigestOutputStream(copy, digest)) {
                if (api.download(item.path, sink, item.size) != item.size || !item.matches(digest.digest())) {
                    throw new IOException("File integrity check failed");
                }
            }
            data.seek(0);
            success = true;
            return new VerifiedFile(data);
        } finally {
            if (!success) data.close();
        }
    }

    void save(OutputStream out) throws IOException {
        byte[] block = new byte[32768];
        int n;
        while ((n = data.read(block)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
            out.write(block, 0, n);
        }
    }

    String previewText() throws IOException {
        int length = (int) Math.min(16384, data.length());
        byte[] first = new byte[length];
        data.readFully(first);
        return new String(first, StandardCharsets.UTF_8) + (length < data.length() ? "\n…" : "");
    }

    Bitmap previewImage() throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(data.getFD(), null, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1024) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        data.seek(0);
        Bitmap image = BitmapFactory.decodeFileDescriptor(data.getFD(), null, options);
        if (image == null) throw new IOException("Invalid image");
        return image;
    }

    @Override public void close() throws IOException { data.close(); }
}
