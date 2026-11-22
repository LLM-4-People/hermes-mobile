package org.llm4people.hermes.artifact;

import java.util.Locale;
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
        Marker opening = null;
        int body = 0, ordinal = 0;
        for (int start = 0; start < text.length();) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            Marker current = marker(text, start, end);
            if (current != null && opening != null && current.closes(opening)) {
                int contentEnd = start;
                if (contentEnd > body && text.charAt(contentEnd - 1) == '\n') contentEnd--;
                if (contentEnd > body && text.charAt(contentEnd - 1) == '\r') contentEnd--;
                String content = text.substring(body, contentEnd);
                if (!content.isEmpty() && !visitor.accept(opening.language,
                        opening.filename.isEmpty() ? commentName(content) : opening.filename, content, ordinal++)) return;
                opening = null;
            } else if (current != null && opening == null) {
                opening = current;
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

    private static String commentName(String content) {
        int end = content.indexOf('\n');
        if (end < 0) end = content.length();
        if (end > 160) return "";
        Matcher m = FILE_COMMENT.matcher(content.substring(0, end));
        return m.matches() && safeName(m.group(1), false) ? m.group(1) : "";
    }
}
