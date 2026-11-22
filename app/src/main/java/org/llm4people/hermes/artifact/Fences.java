package org.llm4people.hermes.artifact;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded line scanner. Only completed Markdown fences become artifacts. */
final class Fences {
    interface Visitor {
        boolean accept(String language, String filename, String content, int ordinal);
    }

    private static final Pattern INFO = Pattern.compile(
            "([a-zA-Z0-9_.+#-]{1,40})(?:\\s+(?:file=)?([^\\s]{1,120}))?");
    private static final Pattern FILENAME = Pattern.compile(
            "(?:[a-zA-Z0-9_.-]+/)*[a-zA-Z0-9_.-]+\\.[a-zA-Z0-9]{1,16}");
    private static final Pattern FILE_COMMENT = Pattern.compile(
            "^\\s*(?://|#|--|/\\*|<!--)\\s*([^\\s]+?)(?:\\s*\\*/|\\s*-->)?\\s*$");

    static void scan(String text, Visitor visitor) {
        int opening = -1, width = 0, ordinal = 0;
        char marker = 0;
        String language = "", filename = "";
        for (int start = 0; start < text.length();) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            int trimmedEnd = end > start && text.charAt(end - 1) == '\r' ? end - 1 : end;
            int p = start;
            while (p < trimmedEnd && p - start < 3 && text.charAt(p) == ' ') p++;
            char c = p < trimmedEnd ? text.charAt(p) : 0;
            if (c == '`' || c == '~') {
                int after = p + 1;
                while (after < trimmedEnd && text.charAt(after) == c) after++;
                int run = after - p;
                if (opening >= 0 && c == marker && run >= width && blank(text, after, trimmedEnd)) {
                    int contentEnd = start;
                    if (contentEnd > opening && text.charAt(contentEnd - 1) == '\n') contentEnd--;
                    if (contentEnd > opening && text.charAt(contentEnd - 1) == '\r') contentEnd--;
                    String content = text.substring(opening, contentEnd);
                    if (!content.isEmpty() && !visitor.accept(language,
                            filename.isEmpty() ? commentName(content) : filename, content, ordinal++)) return;
                    opening = -1;
                } else if (opening < 0 && run >= 3) {
                    if (c == '`' && hasBacktick(text, after, trimmedEnd)) {
                        start = end + 1;
                        continue;
                    }
                    String info = trimmedEnd - after <= 180 ? text.substring(after, trimmedEnd).trim() : "";
                    Matcher m = INFO.matcher(info);
                    boolean valid = m.matches();
                    language = valid ? m.group(1).toLowerCase(Locale.ROOT) : "";
                    filename = valid && safeName(m.group(2)) ? m.group(2) : "";
                    opening = end + 1;
                    width = run;
                    marker = c;
                }
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

    private static boolean safeName(String name) {
        return name != null && name.length() <= 120 && !name.contains("..") && FILENAME.matcher(name).matches();
    }

    private static String commentName(String content) {
        int end = content.indexOf('\n');
        if (end < 0) end = content.length();
        if (end > 160) return "";
        Matcher m = FILE_COMMENT.matcher(content.substring(0, end));
        return m.matches() && safeName(m.group(1)) ? m.group(1) : "";
    }
}
