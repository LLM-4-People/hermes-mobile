package org.llm4people.hermes.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Reads one Hermes Runs data object at a time, including a replay.truncated notice without an id. */
final class SseReader {
    private final BufferedInputStream input;
    private final int limit;
    private boolean skipLf;
    private boolean first = true;

    SseReader(InputStream input, int limit) {
        this.input = new BufferedInputStream(input);
        this.limit = limit;
    }

    JSONObject next(boolean includeHeartbeats) throws IOException {
        StringBuilder data = new StringBuilder();
        int frameBytes = 0;
        boolean hasData = false;
        boolean comment = false;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = input.read();
            if (b == -1) {
                if (hasData || line.size() != 0) throw new EOFException("Interrupted event frame");
                return null;
            }
            if (skipLf) {
                skipLf = false;
                if (b == '\n') continue;
            }
            if (++frameBytes > limit) throw new IOException("Event exceeded the byte limit");
            if (b != '\r' && b != '\n') {
                line.write(b);
                continue;
            }
            skipLf = b == '\r';
            String field = new String(line.toByteArray(), StandardCharsets.UTF_8);
            line.reset();
            if (first && field.startsWith("\uFEFF")) field = field.substring(1);
            first = false;
            if (field.isEmpty()) {
                if (hasData) return Wire.object(data.toString());
                if (includeHeartbeats && comment) return new JSONObject();
                comment = false;
                frameBytes = 0;
            } else if (field.startsWith(":")) {
                comment = true;
            } else if (field.equals("data") || field.startsWith("data:")) {
                if (hasData) data.append('\n');
                String value = field.length() <= 5 ? "" : field.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
                hasData = true;
            }
        }
    }
}
