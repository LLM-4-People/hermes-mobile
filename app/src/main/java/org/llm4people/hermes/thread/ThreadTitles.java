package org.llm4people.hermes.thread;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;

/** Select a readable hint from the user's own opening message when the discussion has no name. */
final class ThreadTitles {
    static final String FIRST_PROMPT = "_mobile_first_prompt";

    static String snippet(String text) {
        StringBuilder out = new StringBuilder();
        boolean space = false;
        int count = 0;
        for (int pos = 0; pos < text.length();) {
            int point = text.codePointAt(pos);
            pos += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = out.length() > 0;
                continue;
            }
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT
                    && point != 0x200c && point != 0x200d) continue;
            // An attachment can be a textual data URL in a legacy message.
            if ((point == 'd' || point == 'D') && text.regionMatches(true, pos - 1, "data:image/", 0, 11)) break;
            if (count + (space ? 1 : 0) >= 80) { out.append('…'); break; }
            if (space) { out.append(' '); count++; }
            out.appendCodePoint(point);
            space = false;
            count++;
        }
        return out.toString();
    }

    static String firstPrompt(JSONArray rows) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (!"user".equals(ThreadData.string(row, "role")) || "hidden".equals(ThreadData.string(row, "display_kind"))) continue;
            Object content = row.opt("content");
            if (content instanceof String) return snippet(ThreadData.caption((String) content));
            if (content instanceof JSONArray parts) {
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    String type = ThreadData.string(part, "type");
                    if (type.equals("text") || type.equals("input_text")) {
                        String text = snippet(ThreadData.string(part, "text"));
                        if (!text.isEmpty()) return text;
                    }
                }
            }
            return "";
        }
        return "";
    }

    static String previewForRow(JSONObject row) {
        String known = ThreadData.string(row, FIRST_PROMPT);
        return known.isEmpty() ? ThreadData.inboxPreview(ThreadData.string(row, "preview")) : known;
    }

    static String inboxPrompt(JSONObject row) {
        String known = ThreadData.string(row, FIRST_PROMPT);
        if (!known.isEmpty()) return known;
        // Older list projections put the first user message of the tip in preview.
        if (compacted(row)) return "";
        return snippet(ThreadData.inboxPreview(ThreadData.string(row, "preview")));
    }

    private static boolean compacted(JSONObject row) {
        String root = ThreadData.string(row, "_lineage_root_id");
        return !root.isEmpty() && !root.equals(ThreadData.string(row, "id"));
    }

    static boolean emptySession(JSONObject row) {
        return !compacted(row) && (row == null || row.optLong("message_count", 0) == 0);
    }

    static String display(String name, String prompt, boolean empty) {
        return display(null, name, prompt, empty);
    }

    static String display(Context context, String name, String prompt, boolean empty) {
        if (!name.isEmpty()) return name;
        if (!prompt.isEmpty()) return prompt;
        return ThreadStrings.get(context, empty ? R.string.thread_new_conversation : R.string.thread_conversation);
    }

    private ThreadTitles() {}
}
