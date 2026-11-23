package org.llm4people.hermes.net;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.llm4people.hermes.Diagnostics;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class Wire {
    private Wire() {}

    static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (n > limit - buf.size()) throw new IOException("Response exceeded the byte limit");
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    static JSONObject object(String text) throws IOException {
        try {
            JSONTokener parser = new JSONTokener(text);
            Object result = parser.nextValue();
            if (result instanceof JSONObject && parser.nextClean() == 0) return (JSONObject) result;
        } catch (JSONException ignored) {
            // Android JSON exceptions may contain portions of the input body.
            Diagnostics.warning("network", "json_malformed");
        }
        throw new IOException("Malformed JSON response");
    }
}
