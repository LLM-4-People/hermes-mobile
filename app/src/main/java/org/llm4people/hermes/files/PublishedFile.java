package org.llm4people.hermes.files;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class PublishedFile {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    final String name, mime, path, hash;
    final long size;

    PublishedFile(String threadPath, JSONObject row) throws JSONException, IOException {
        String id = row.getString("id");
        name = row.getString("name");
        mime = row.getString("mime");
        size = row.getLong("size");
        hash = row.getString("sha256");
        if (!id.matches("[0-9a-f]{32}") || !hash.matches("[0-9a-f]{64}") ||
                name.isEmpty() || name.length() > 240 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 ||
                name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) ||
                !mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") ||
                size < 0 || size > MAX_BYTES) throw new IOException("Invalid file metadata");
        path = threadPath + "/" + HermesApi.segment(id);
    }

    boolean text() { return mime.startsWith("text/") || mime.equals("application/json"); }
    boolean image() { return mime.equals("image/png") || mime.equals("image/jpeg") || mime.equals("image/webp"); }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    boolean matches(byte[] bytes) {
        StringBuilder actual = new StringBuilder(64);
        for (byte b : bytes) actual.append(Character.forDigit((b & 255) >>> 4, 16))
                .append(Character.forDigit(b & 15, 16));
        return hash.contentEquals(actual);
    }
}
