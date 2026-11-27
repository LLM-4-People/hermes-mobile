package org.llm4people.hermes.files;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.net.HermesApi;
import java.io.IOException;

final class PublishedFile {
    final String name, mime, path, hash;
    final long size;

    PublishedFile(String threadPath, JSONObject row, long maxBytes) throws JSONException, IOException {
        long bound = CapturedMedia.effectiveByteLimit(maxBytes);
        String id = row.getString("id");
        name = row.getString("name");
        mime = row.getString("mime");
        size = row.getLong("size");
        hash = row.getString("sha256");
        if (!id.matches("[0-9a-f]{32}") || !hash.matches("[0-9a-f]{64}") ||
                name.isEmpty() || name.length() > 240 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 ||
                PlainTextSanitizer.hasUnsafeSingleLineCodePoint(name) ||
                !PlainTextSanitizer.hasVisibleText(name) ||
                !mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") ||
                size < 0 || size > bound) throw new IOException("Invalid file metadata");
        path = threadPath + "/" + HermesApi.segment(id);
    }

    boolean text() { return mime.startsWith("text/") || mime.equals("application/json"); }
    boolean image() { return mime.equals("image/png") || mime.equals("image/jpeg") || mime.equals("image/webp"); }

    boolean matches(byte[] bytes) {
        return hash.equals(org.llm4people.hermes.Digests.toHex(bytes));
    }
}
