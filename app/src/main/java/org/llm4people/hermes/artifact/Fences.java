package org.llm4people.hermes.artifact;

import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded line scanner. Only completed Markdown fences become artifacts. */
public final class Fences {
    interface Visitor {
        boolean accept(String language, String filename, String content, int ordinal);
    }

    private static final Pattern INFO = Pattern.compile(
            "(?:([a-zA-Z0-9_.+#-]{1,40})(?:\\s+|$))?(?:(file=)?([^\\s]{1,120}))?");
    private static final Pattern PATH_SEGMENT = Pattern.compile("[\\p{L}\\p{N}_.-]+");
    private static final Pattern FILE_COMMENT = Pattern.compile(
            "^\\s*(?://|#|--|/\\*|<!--)\\s*([^\\s]+?)(?:\\s*\\*/|\\s*-->)?\\s*$");

    /** A Markdown fence delimiter. Chat rendering and artifact extraction share its grammar. */
    public static final class Marker {
        public final char symbol;
        public final int width;
        private final boolean bare;
        public final String language;
        private final String filename;

        private Marker(char symbol, int width, boolean bare, String language, String filename) {
            this.symbol = symbol;
            this.width = width;
            this.bare = bare;
            this.language = language;
            this.filename = filename;
        }

        /** Explicit fence filename or a safe filename from the first line of its body. */
        public String filename(String source, int bodyStart) {
            if (!filename.isEmpty()) return filename;
            if (bodyStart < 0 || bodyStart >= source.length()) return "";
            return commentName(source, bodyStart);
        }

        public boolean workspace() { return WorkspaceLayout.FENCE.equals(language); }

        public boolean closes(Marker opening) {
            return bare && opening.symbol == symbol && width >= opening.width;
        }
    }

    /** Restore only the delimiter and language required to render an already open fence. */
    public static Marker continuation(String delimiter, int width, String language) {
        if (!("`".equals(delimiter) || "~".equals(delimiter)) || width < 3) return null;
        return new Marker(delimiter.charAt(0), width, true, language.length() <= 40 ? language : "", "");
    }

    public static Marker marker(String text, int start, int end) {
        int last = end > start && text.charAt(end - 1) == '\r' ? end - 1 : end;
        int p = start;
        while (p < last && p - start < 3 && text.charAt(p) == ' ') p++;
        if (p >= last) return null;
        char symbol = text.charAt(p);
        if (symbol != '`' && symbol != '~') return null;
        int after = p + 1;
        while (after < last && text.charAt(after) == symbol) after++;
        if (after - p < 3 || (symbol == '`' && hasBacktick(text, after, last))) return null;
        String info = last - after <= 180 ? text.substring(after, last).trim() : "";
        Matcher m = INFO.matcher(info);
        boolean valid = m.matches();
        String language = valid && m.group(1) != null ? m.group(1).toLowerCase(Locale.ROOT) : "";
        String filename = valid && safeName(m.group(3), m.group(2) != null) ? m.group(3) : "";
        return new Marker(symbol, after - p, blank(text, after, last), language, filename);
    }

    static void scan(String text, Visitor visitor) {
        ranges(text, (opening, start, body, contentEnd, end, ordinal) -> {
            String content = text.substring(body, contentEnd);
            return visitor.accept(opening.language, opening.filename(content, 0), content, ordinal);
        });
    }

    /** Redact the exact verified bodies. The visible transcript can contain additional typed parts. */
    public static String without(String text, Map<Integer,String> proposals) {
        return described(text, proposals, ignored -> "");
    }

    /** Substitute a spoken description at each verified source, preserving the order of the authored reply. */
    public static String described(String text, Map<Integer,String> proposals, IntFunction<String> description) {
        if (proposals.isEmpty() || text.length() > ArtifactCatalog.MAX_MESSAGE_CHARS) return text;
        StringBuilder copy = new StringBuilder();
        int[] retainedFrom = {0};
        ranges(text, (opening, start, body, contentEnd, end, ordinal) -> {
            String expected = proposals.get(ordinal);
            if (opening.workspace() && expected != null
                    && expected.length() == contentEnd - body && text.regionMatches(body, expected, 0, expected.length())) {
                copy.append(text, retainedFrom[0], start);
                String label = description.apply(ordinal);
                if (!label.isEmpty()) {
                    copy.append(label);
                    if (end > 0 && text.charAt(end - 1) == '\n') copy.append('\n');
                }
                retainedFrom[0] = end;
            }
            return true;
        });
        return retainedFrom[0] == 0 ? text : copy.append(text, retainedFrom[0], text.length()).toString();
    }

    private interface RangeVisitor {
        boolean accept(Marker opening, int start, int body, int contentEnd, int end, int ordinal);
    }

    private static void ranges(String text, RangeVisitor visitor) {
        Marker opening = null;
        int fenceStart = 0, body = 0, ordinal = 0;
        for (int start = 0; start < text.length();) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            Marker current = marker(text, start, end);
            if (current != null && opening != null && current.closes(opening)) {
                int contentEnd = start;
                if (contentEnd > body && text.charAt(contentEnd - 1) == '\n') contentEnd--;
                if (contentEnd > body && text.charAt(contentEnd - 1) == '\r') contentEnd--;
                int after = end < text.length() ? end + 1 : end;
                if (contentEnd > body && !visitor.accept(opening, fenceStart, body, contentEnd, after, ordinal++))
                    return;
                opening = null;
            } else if (current != null && opening == null) {
                opening = current;
                fenceStart = start;
                body = end + 1;
            }
            start = end + 1;
        }
    }

    private static boolean hasBacktick(String s, int start, int end) {
        for (int i = start; i < end; i++) if (s.charAt(i) == '`') return true;
        return false;
    }

    private static boolean blank(String s, int start, int end) {
        for (int i = start; i < end; i++) if (s.charAt(i) != ' ' && s.charAt(i) != '\t') return false;
        return true;
    }

    private static boolean safeName(String name, boolean explicit) {
        if (name == null || name.length() > 120) return false;
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment) || !PATH_SEGMENT.matcher(segment).matches()) return false;
        }
        // Free-form language tags and first-line comments often contain ordinary words.
        // Only an explicit file= declaration identifies an extensionless filename.
        String basename = segments[segments.length - 1];
        int dot = basename.lastIndexOf('.');
        return explicit || (dot > 0 && dot < basename.length() - 1);
    }

    private static String commentName(String source, int start) {
        int end = start;
        while (end < source.length() && source.charAt(end) != '\n') {
            if (end - start >= 160) return "";
            end++;
        }
        Matcher m = FILE_COMMENT.matcher(source.substring(start, end));
        return m.matches() && safeName(m.group(1), false) ? m.group(1) : "";
    }
}
