package org.llm4people.hermes.thread;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;

/** A tool return may contain free-form text. Only an explicit outcome earns a success or failure label. */
final class ToolResult {
    private static final int MAX_STATUS_CHARS = 16_384;

    private ToolResult() {}

    static String name(JSONObject row) {
        String name = ThreadData.string(row, "tool_name");
        return name.isEmpty() ? ThreadData.string(row, "name") : name;
    }

    static String raw(JSONObject row) {
        return row.isNull("content") ? "" : row.opt("content").toString();
    }

    static String label(JSONObject row) {
        return label(null, row);
    }

    static String label(Context context, JSONObject row) {
        String name = name(row);
        boolean skill = name.equals("skill_view");
        JSONObject result = envelope(row.opt("content"));
        if (result != null) {
            Object success = result.opt("success");
            Object error = result.opt("error");
            Object status = result.opt("status");
            Object exit = name.equals("terminal") ? result.opt("exit_code") : null;
            if ("failed".equals(status) || "error".equals(status) || Boolean.FALSE.equals(success)
                    || Boolean.TRUE.equals(result.opt("is_error"))
                    || error instanceof String message && !message.isEmpty() || error instanceof JSONObject
                    || exit instanceof Number n && n.doubleValue() != 0) {
                if (skill) return ThreadStrings.get(context, R.string.thread_skill_failed);
                return name.isEmpty()
                        ? ThreadStrings.get(context, R.string.thread_tool_failed)
                        : ThreadStrings.get(context, R.string.thread_tool_failed_named, name);
            }
            boolean settled = !"pending".equals(status) && !"running".equals(status) && !"queued".equals(status)
                    && !"unknown".equals(status);
            if (settled && (Boolean.TRUE.equals(success) || exit instanceof Number n && n.doubleValue() == 0)) {
                if (skill) return ThreadStrings.get(context, R.string.thread_skill_done);
                return name.isEmpty()
                        ? ThreadStrings.get(context, R.string.thread_tool_done)
                        : ThreadStrings.get(context, R.string.thread_tool_done_named, name);
            }
        }
        if (skill) return ThreadStrings.get(context, R.string.thread_skill_result);
        return name.isEmpty()
                ? ThreadStrings.get(context, R.string.thread_tool_result)
                : ThreadStrings.get(context, R.string.thread_tool_result_named, name);
    }

    private static JSONObject envelope(Object content) {
        if (content instanceof JSONObject object) return object;
        if (!(content instanceof String text) || text.length() > MAX_STATUS_CHARS
                || !text.trim().startsWith("{")) return null;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            JSONObject fields = new JSONObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                JsonToken token = reader.peek();
                if ((key.equals("success") || key.equals("is_error")) && token == JsonToken.BOOLEAN)
                    fields.put(key, reader.nextBoolean());
                else if (key.equals("exit_code") && token == JsonToken.NUMBER)
                    fields.put(key, reader.nextDouble());
                else if ((key.equals("error") || key.equals("status")) && token == JsonToken.STRING)
                    fields.put(key, reader.nextString());
                else {
                    if (key.equals("error") && token == JsonToken.BEGIN_OBJECT) fields.put(key, new JSONObject());
                    reader.skipValue();
                }
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT ? fields : null;
        } catch (IOException | JSONException | IllegalStateException | NumberFormatException invalid) {
            return null;
        }
    }
}
