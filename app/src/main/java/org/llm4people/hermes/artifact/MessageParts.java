package org.llm4people.hermes.artifact;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** The visible transcript and the eligible assistant source share the same original typed parts. */
public final class MessageParts {
    private MessageParts() {}

    public static String display(JSONObject message) {
        Object value = message == null ? null : message.opt("content");
        if (value instanceof String text) return text;
        if (!(value instanceof JSONArray parts)) return "";
        return assemble(parts, new StringBuilder(), null);
    }

    static String eligible(Object value) {
        if (value instanceof String text) return text.length() <= ArtifactCatalog.MAX_MESSAGE_CHARS ? text : null;
        if (!(value instanceof JSONArray parts)) return "";
        if (parts.length() > 1024) return null;
        Source source = new Source(false);
        assemble(parts, null, source);
        return source.text == null ? null : source.text.toString();
    }

    public static String project(JSONObject row, Map<Integer,String> verified, IntFunction<String> description) {
        if (verified.isEmpty() || row == null || !"assistant".equals(row.optString("role"))
                || "hidden".equals(row.optString("display_kind"))) return display(row);
        Object content = row.opt("content");
        if (content instanceof String text) return Fences.described(text, verified, description);
        if (!(content instanceof JSONArray parts) || parts.length() > 1024) return display(row);
        Source source = new Source(true);
        String visible = assemble(parts, new StringBuilder(), source);
        if (source.text == null) return visible;
        String eligible = source.text.toString();
        Projection projection = new Projection(visible, source.spans);
        Fences.verifiedRanges(eligible, verified, (start, end, ordinal) ->
                projection.replace(start, end, description.apply(ordinal)));
        return projection.result();
    }

    private static String assemble(JSONArray parts, StringBuilder visible, Source source) {
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String type = part.optString("type", "");
            boolean eligible = "text".equals(type) || "output_text".equals(type);
            if (!eligible && !"input_text".equals(type)) {
                if (visible != null && type.contains("image")) visible.append("\n[Image jointe]");
                continue;
            }
            Object value = part.opt("text");
            int separator = -1, start = 0;
            if (visible != null) {
                if (visible.length() > 0) {
                    separator = visible.length();
                    visible.append('\n');
                }
                start = visible.length();
                if (!part.isNull("text")) visible.append(part.optString("text", ""));
            }
            if (source != null && eligible && value instanceof String text) {
                source.add(text, separator, start);
                if (source.text == null && visible == null) break;
            }
        }
        return visible == null ? "" : visible.toString();
    }

    // A range may cross eligible text fragments separated in the transcript by an image or input_text.
    // Only mapped characters can be replaced. Intervening display-only parts retain their original bytes.
    private record Span(int start, int end, int visibleStart) {}

    private static final class Source {
        StringBuilder text = new StringBuilder();
        final List<Span> spans;
        int previousVisibleEnd = -1;
        Source(boolean map) { spans = map ? new ArrayList<>() : null; }

        void add(String value, int separator, int visibleStart) {
            if (text == null) return;
            int start = text.length();
            boolean joined = start != 0;
            if (value.length() > ArtifactCatalog.MAX_MESSAGE_CHARS - start - (joined ? 1 : 0)) {
                text = null;
                return;
            }
            if (joined) text.append('\n');
            text.append(value);
            if (spans == null) return;
            // Map a synthetic newline only when it joins the same adjacent parts in both texts.
            boolean sameJoin = joined && separator == previousVisibleEnd;
            if (joined && !sameJoin) start++;
            if (text.length() > start) spans.add(new Span(start, text.length(), sameJoin ? separator : visibleStart));
            previousVisibleEnd = visibleStart + value.length();
        }
    }

    private static final class Projection {
        final String visible;
        final List<Span> spans;
        final StringBuilder result = new StringBuilder();
        int retainedFrom;
        int firstSpan;
        Projection(String visible, List<Span> spans) { this.visible = visible; this.spans = spans; }

        void replace(int start, int end, String label) {
            while (firstSpan < spans.size() && spans.get(firstSpan).end <= start) firstSpan++;
            boolean insert = true;
            for (int i = firstSpan; i < spans.size(); i++) {
                Span span = spans.get(i);
                if (span.start >= end) break;
                int left = span.visibleStart + Math.max(start, span.start) - span.start;
                int right = span.visibleStart + Math.min(end, span.end) - span.start;
                result.append(visible, retainedFrom, left);
                if (insert) { result.append(label); insert = false; }
                if (!label.isEmpty() && end <= span.end && visible.charAt(right - 1) == '\n'
                        && result.charAt(result.length() - 1) != '\n') result.append('\n');
                retainedFrom = right;
            }
        }

        String result() { return retainedFrom == 0 ? visible : result.append(visible, retainedFrom, visible.length()).toString(); }
    }
}
