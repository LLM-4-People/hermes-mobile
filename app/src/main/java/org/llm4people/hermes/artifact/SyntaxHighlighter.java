package org.llm4people.hermes.artifact;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.StyleSpan;

import org.llm4people.hermes.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, bounded, zero-dependency syntax tokenizer, search index, and span applier.
 * Pure tokenization and search run on the host JVM without Android framework calls.
 */
public final class SyntaxHighlighter {
    static final int MAX_HIGHLIGHT_CHARS = 32_768;
    static final int MAX_SEARCH_MATCHES = 2_000;

    public enum Language {
        JAVA, PYTHON, KOTLIN, JAVASCRIPT, JSON, YAML, BASH, SQL, DIFF, PLAIN;

        public static Language fromTag(String raw) {
            if (raw == null) return PLAIN;
            String tag = raw.trim().toLowerCase(Locale.ROOT);
            if (tag.isEmpty()) return PLAIN;
            int space = tag.indexOf(' ');
            if (space > 0) tag = tag.substring(0, space);
            return switch (tag) {
                case "java" -> JAVA;
                case "py", "python", "python3" -> PYTHON;
                case "kt", "kts", "kotlin" -> KOTLIN;
                case "js", "jsx", "mjs", "cjs", "ts", "tsx", "javascript", "typescript" -> JAVASCRIPT;
                case "json", "jsonc", "jsonl" -> JSON;
                case "yml", "yaml" -> YAML;
                case "sh", "bash", "zsh", "shell", "console" -> BASH;
                case "sql", "sqlite", "postgres", "postgresql", "mysql" -> SQL;
                case "diff", "patch" -> DIFF;
                default -> PLAIN;
            };
        }
    }

    public enum Kind {
        KEYWORD, STRING, COMMENT, NUMBER, TYPE, ANNOTATION, DIFF_ADD, DIFF_DEL, DIFF_HUNK, JSON_KEY
    }

    public record Token(int start, int end, Kind kind) {}

    public record SearchMatch(int start, int end) {}

    private static final Set<String> JAVA_KEYWORDS = words(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "false",
            "final", "finally", "float", "for", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "null", "package", "private", "protected",
            "public", "record", "return", "sealed", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "true", "try",
            "var", "void", "volatile", "while", "yield", "permits", "non-sealed");

    private static final Set<String> KOTLIN_KEYWORDS = words(
            "abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion",
            "const", "constructor", "continue", "crossinline", "data", "do", "else", "enum",
            "expect", "external", "false", "final", "finally", "for", "fun", "if", "import", "in",
            "infix", "init", "inline", "inner", "interface", "internal", "is", "lateinit",
            "noinline", "null", "object", "open", "operator", "out", "override", "package",
            "private", "protected", "public", "reified", "return", "sealed", "super", "suspend",
            "tailrec", "this", "throw", "true", "try", "typealias", "val", "var", "vararg", "when",
            "where", "while");

    private static final Set<String> PYTHON_KEYWORDS = words(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "case",
            "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "match", "nonlocal", "not", "or",
            "pass", "raise", "return", "try", "while", "with", "yield");

    private static final Set<String> JS_KEYWORDS = words(
            "any", "as", "async", "await", "boolean", "break", "case", "catch", "class", "const",
            "continue", "debugger", "declare", "default", "delete", "do", "else", "enum", "export",
            "extends", "false", "finally", "for", "from", "function", "get", "if", "implements",
            "import", "in", "instanceof", "interface", "keyof", "let", "module", "namespace",
            "never", "new", "null", "number", "of", "private", "protected", "public", "readonly",
            "return", "set", "static", "string", "super", "switch", "symbol", "this", "throw",
            "true", "try", "type", "typeof", "undefined", "unknown", "var", "void", "while",
            "with", "yield");

    private static final Set<String> BASH_KEYWORDS = words(
            "case", "do", "done", "elif", "else", "esac", "export", "fi", "for", "function", "if",
            "in", "local", "readonly", "return", "select", "set", "shift", "then", "trap", "unset",
            "until", "while", "true", "false");

    private static final Set<String> SQL_KEYWORDS = words(
            "add", "all", "alter", "and", "as", "asc", "begin", "between", "by", "case", "check",
            "column", "commit", "constraint", "create", "cross", "default", "delete", "desc",
            "distinct", "drop", "else", "end", "exists", "explain", "false", "foreign", "from",
            "full", "group", "having", "in", "index", "inner", "insert", "into", "is", "join",
            "key", "left", "like", "limit", "not", "null", "offset", "on", "or", "order", "outer",
            "pragma", "primary", "references", "replace", "returning", "right", "rollback",
            "select", "set", "table", "then", "transaction", "trigger", "true", "union", "unique",
            "update", "using", "values", "view", "when", "where", "with");

    private SyntaxHighlighter() {}

    private static Set<String> words(String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }

    /** Tokenize up to MAX_HIGHLIGHT_CHARS in linear time with zero regex backtracking. */
    public static List<Token> tokenize(String text, Language language) {
        if (text == null || text.isEmpty() || language == null || language == Language.PLAIN) {
            return Collections.emptyList();
        }
        int limit = boundedEnd(text, MAX_HIGHLIGHT_CHARS);
        List<Token> tokens = new ArrayList<>();
        switch (language) {
            case DIFF -> scanDiff(text, limit, tokens);
            case JSON -> scanJson(text, limit, tokens);
            case YAML -> scanYaml(text, limit, tokens);
            case PYTHON -> scanGeneral(text, limit, tokens, PYTHON_KEYWORDS, true, false, false, false, true);
            case BASH -> scanGeneral(text, limit, tokens, BASH_KEYWORDS, true, false, false, false, false);
            case SQL -> scanSql(text, limit, tokens);
            case JAVA -> scanGeneral(text, limit, tokens, JAVA_KEYWORDS, false, true, true, true, true);
            case KOTLIN -> scanGeneral(text, limit, tokens, KOTLIN_KEYWORDS, false, true, true, true, true);
            case JAVASCRIPT -> scanGeneral(text, limit, tokens, JS_KEYWORDS, false, true, true, true, false);
            default -> {}
        }
        return tokens;
    }

    static int boundedEnd(String text, int maxChars) {
        if (text.length() <= maxChars) return text.length();
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return end;
    }

    private static void scanDiff(String text, int limit, List<Token> out) {
        for (int p = 0; p < limit;) {
            int next = text.indexOf('\n', p);
            int end = next < 0 || next > limit ? limit : next;
            if (end > p) {
                char lead = text.charAt(p);
                if (lead == '@' && text.startsWith("@@", p)) {
                    out.add(new Token(p, end, Kind.DIFF_HUNK));
                } else if (lead == '+' && !text.startsWith("+++", p)) {
                    out.add(new Token(p, end, Kind.DIFF_ADD));
                } else if (lead == '-' && !text.startsWith("---", p)) {
                    out.add(new Token(p, end, Kind.DIFF_DEL));
                } else if (text.startsWith("diff --git ", p) || text.startsWith("+++", p) || text.startsWith("---", p)) {
                    out.add(new Token(p, end, Kind.KEYWORD));
                }
            }
            p = end + 1;
        }
    }

    private static void scanJson(String text, int limit, List<Token> out) {
        int i = 0;
        while (i < limit) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = scanQuoted(text, i, limit, '"');
                int probe = end;
                while (probe < limit && (text.charAt(probe) == ' ' || text.charAt(probe) == '\t')) probe++;
                Kind kind = probe < limit && text.charAt(probe) == ':' ? Kind.JSON_KEY : Kind.STRING;
                out.add(new Token(i, end, kind));
                i = end;
            } else if (c == '/' && i + 1 < limit && text.charAt(i + 1) == '/') {
                int end = lineEnd(text, i, limit);
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
            } else if ((c >= '0' && c <= '9') || (c == '-' && i + 1 < limit && isDigit(text.charAt(i + 1)))) {
                int end = scanNumber(text, i, limit);
                out.add(new Token(i, end, Kind.NUMBER));
                i = end;
            } else if (isIdentStart(c)) {
                int end = scanIdent(text, i, limit);
                String word = text.substring(i, end);
                if ("true".equals(word) || "false".equals(word) || "null".equals(word)) {
                    out.add(new Token(i, end, Kind.KEYWORD));
                }
                i = end;
            } else {
                i++;
            }
        }
    }

    private static void scanYaml(String text, int limit, List<Token> out) {
        for (int p = 0; p < limit;) {
            int end = lineEnd(text, p, limit);
            int i = p;
            while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
            if (i < end && text.charAt(i) == '#') {
                out.add(new Token(i, end, Kind.COMMENT));
            } else {
                if (i + 1 < end && text.charAt(i) == '-' && text.charAt(i + 1) == ' ') i += 2;
                int colon = findYamlKeyColon(text, i, end);
                if (colon > i) {
                    out.add(new Token(i, colon, Kind.JSON_KEY));
                    scanYamlValue(text, colon + 1, end, out);
                } else {
                    scanYamlValue(text, i, end, out);
                }
            }
            p = end + 1;
        }
    }

    private static int findYamlKeyColon(String text, int start, int end) {
        if (start >= end || text.charAt(start) == '"' || text.charAt(start) == '\'') return -1;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '#') return -1;
            if (c == ':' && (i + 1 == end || text.charAt(i + 1) == ' ' || text.charAt(i + 1) == '\t')) return i;
        }
        return -1;
    }

    private static void scanYamlValue(String text, int start, int end, List<Token> out) {
        int i = start;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '#' && (i == start || text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) {
                out.add(new Token(i, end, Kind.COMMENT));
                return;
            } else if (c == '"' || c == '\'') {
                int qEnd = scanQuoted(text, i, end, c);
                out.add(new Token(i, qEnd, Kind.STRING));
                i = qEnd;
            } else if ((c >= '0' && c <= '9') && (i == start || !isIdentPart(text.charAt(i - 1)))) {
                int nEnd = scanNumber(text, i, end);
                out.add(new Token(i, nEnd, Kind.NUMBER));
                i = nEnd;
            } else if (isIdentStart(c)) {
                int wEnd = scanIdent(text, i, end);
                String word = text.substring(i, wEnd);
                if ("true".equals(word) || "false".equals(word) || "null".equals(word) || "yes".equals(word) || "no".equals(word)) {
                    out.add(new Token(i, wEnd, Kind.KEYWORD));
                }
                i = wEnd;
            } else {
                i++;
            }
        }
    }

    private static void scanSql(String text, int limit, List<Token> out) {
        int i = 0;
        while (i < limit) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < limit && text.charAt(i + 1) == '-') {
                int end = lineEnd(text, i, limit);
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
            } else if (c == '/' && i + 1 < limit && text.charAt(i + 1) == '*') {
                int end = scanBlockComment(text, i, limit);
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
            } else if (c == '\'' || c == '"') {
                int end = scanQuoted(text, i, limit, c);
                out.add(new Token(i, end, Kind.STRING));
                i = end;
            } else if (isDigit(c)) {
                int end = scanNumber(text, i, limit);
                out.add(new Token(i, end, Kind.NUMBER));
                i = end;
            } else if (isIdentStart(c)) {
                int end = scanIdent(text, i, limit);
                if (SQL_KEYWORDS.contains(text.substring(i, end).toLowerCase(Locale.ROOT))) {
                    out.add(new Token(i, end, Kind.KEYWORD));
                }
                i = end;
            } else {
                i++;
            }
        }
    }

    private static void scanGeneral(String text, int limit, List<Token> out, Set<String> keywords,
                                    boolean hashComment, boolean slashComment, boolean blockComment,
                                    boolean highlightTypes, boolean annotations) {
        int i = 0;
        while (i < limit) {
            char c = text.charAt(i);
            if (hashComment && c == '#') {
                int end = lineEnd(text, i, limit);
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
            } else if (slashComment && c == '/' && i + 1 < limit && text.charAt(i + 1) == '/') {
                int end = lineEnd(text, i, limit);
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
            } else if (blockComment && c == '/' && i + 1 < limit && text.charAt(i + 1) == '*') {
                int end = scanBlockComment(text, i, limit);
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
            } else if (c == '"' || c == '\'' || (!hashComment && c == '`')) {
                int end = scanString(text, i, limit, c);
                out.add(new Token(i, end, Kind.STRING));
                i = end;
            } else if (annotations && c == '@' && i + 1 < limit && isIdentStart(text.charAt(i + 1))) {
                int end = scanIdent(text, i + 1, limit);
                out.add(new Token(i, end, Kind.ANNOTATION));
                i = end;
            } else if (isDigit(c) && (i == 0 || !isIdentPart(text.charAt(i - 1)))) {
                int end = scanNumber(text, i, limit);
                out.add(new Token(i, end, Kind.NUMBER));
                i = end;
            } else if (isIdentStart(c)) {
                int end = scanIdent(text, i, limit);
                String word = text.substring(i, end);
                if (keywords.contains(word)) {
                    out.add(new Token(i, end, Kind.KEYWORD));
                } else if (highlightTypes && Character.isUpperCase(c) && hasLowercase(word)) {
                    out.add(new Token(i, end, Kind.TYPE));
                }
                i = end;
            } else {
                i++;
            }
        }
    }

    private static int scanString(String text, int start, int limit, char quote) {
        if ((quote == '"' || quote == '\'') && start + 2 < limit
                && text.charAt(start + 1) == quote && text.charAt(start + 2) == quote) {
            int i = start + 3;
            while (i + 2 < limit) {
                if (text.charAt(i) == '\\') { i += 2; continue; }
                if (text.charAt(i) == quote && text.charAt(i + 1) == quote && text.charAt(i + 2) == quote) {
                    return i + 3;
                }
                i++;
            }
            return limit;
        }
        return scanQuoted(text, start, limit, quote);
    }

    private static int scanQuoted(String text, int start, int limit, char quote) {
        int i = start + 1;
        while (i < limit) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < limit) { i += 2; continue; }
            if (c == quote) return i + 1;
            if (c == '\n' && quote != '`') return i;
            i++;
        }
        return limit;
    }

    private static int scanBlockComment(String text, int start, int limit) {
        int i = start + 2;
        while (i + 1 < limit) {
            if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') return i + 2;
            i++;
        }
        return limit;
    }

    private static int scanNumber(String text, int start, int limit) {
        int i = start;
        if (i < limit && text.charAt(i) == '-') i++;
        if (i + 1 < limit && text.charAt(i) == '0' && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
            i += 2;
            while (i < limit && (isHex(text.charAt(i)) || text.charAt(i) == '_')) i++;
            return i;
        }
        while (i < limit && (isDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
        if (i + 1 < limit && text.charAt(i) == '.' && isDigit(text.charAt(i + 1))) {
            i++;
            while (i < limit && (isDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
        }
        if (i < limit && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int exp = i + 1;
            if (exp < limit && (text.charAt(exp) == '+' || text.charAt(exp) == '-')) exp++;
            if (exp < limit && isDigit(text.charAt(exp))) {
                i = exp;
                while (i < limit && isDigit(text.charAt(i))) i++;
            }
        }
        while (i < limit && (text.charAt(i) == 'L' || text.charAt(i) == 'l' || text.charAt(i) == 'f'
                || text.charAt(i) == 'F' || text.charAt(i) == 'd' || text.charAt(i) == 'D')) i++;
        return i;
    }

    private static int scanIdent(String text, int start, int limit) {
        int i = start + 1;
        while (i < limit && isIdentPart(text.charAt(i))) i++;
        return i;
    }

    private static int lineEnd(String text, int start, int limit) {
        int nl = text.indexOf('\n', start);
        return nl < 0 || nl > limit ? limit : nl;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    private static boolean hasLowercase(String word) {
        for (int i = 1; i < word.length(); i++) {
            if (Character.isLowerCase(word.charAt(i))) return true;
        }
        return false;
    }

    /** Case-insensitive bounded literal search across the full source. */
    public static List<SearchMatch> findMatches(String source, String query) {
        return findMatches(source, query, MAX_SEARCH_MATCHES);
    }

    static List<SearchMatch> findMatches(String source, String query, int maxMatches) {
        if (source == null || query == null || query.isEmpty() || maxMatches <= 0) {
            return Collections.emptyList();
        }
        String lowerSource = source.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        int qLen = lowerQuery.length();
        List<SearchMatch> matches = new ArrayList<>();
        int from = 0;
        while (from <= lowerSource.length() - qLen && matches.size() < maxMatches) {
            int hit = lowerSource.indexOf(lowerQuery, from);
            if (hit < 0) break;
            matches.add(new SearchMatch(hit, hit + qLen));
            from = hit + qLen;
        }
        return matches;
    }

    /** 1-based line number of character offset inside source. */
    public static int lineNumberAt(String source, int offset) {
        if (source == null || offset <= 0) return 1;
        int limit = Math.min(offset, source.length());
        int line = 1;
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** Count total lines in a text segment (minimum 1). */
    public static int lineCount(String text) {
        if (text == null || text.isEmpty()) return 1;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    /** Apply syntax colors, optional search match highlights, and optional non-copied line-number gutter. */
    public static SpannableString render(Context context, String pageText, Language language,
                                         int pageOffset, boolean showLineNumbers, int firstLineNumber,
                                         List<SearchMatch> matches, int activeMatchIndex) {
        SpannableString spanned = new SpannableString(pageText);
        List<Token> tokens = tokenize(pageText, language);
        int kwColor = context.getColor(R.color.syntax_keyword);
        int strColor = context.getColor(R.color.syntax_string);
        int cmtColor = context.getColor(R.color.syntax_comment);
        int numColor = context.getColor(R.color.syntax_number);
        int typeColor = context.getColor(R.color.syntax_type);
        int annColor = context.getColor(R.color.syntax_annotation);
        int addColor = context.getColor(R.color.accent);
        int delColor = context.getColor(R.color.danger);
        int addBg = context.getColor(R.color.syntax_diff_add_bg);
        int delBg = context.getColor(R.color.syntax_diff_del_bg);
        int mutedColor = context.getColor(R.color.muted);

        for (Token t : tokens) {
            if (t.start < 0 || t.end > pageText.length() || t.start >= t.end) continue;
            switch (t.kind) {
                case KEYWORD -> {
                    spanned.setSpan(new ForegroundColorSpan(kwColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanned.setSpan(new StyleSpan(Typeface.BOLD), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case STRING -> spanned.setSpan(new ForegroundColorSpan(strColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                case COMMENT -> {
                    spanned.setSpan(new ForegroundColorSpan(cmtColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanned.setSpan(new StyleSpan(Typeface.ITALIC), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case NUMBER -> spanned.setSpan(new ForegroundColorSpan(numColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                case TYPE -> spanned.setSpan(new ForegroundColorSpan(typeColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                case ANNOTATION -> spanned.setSpan(new ForegroundColorSpan(annColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                case JSON_KEY -> spanned.setSpan(new ForegroundColorSpan(kwColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                case DIFF_ADD -> {
                    spanned.setSpan(new ForegroundColorSpan(addColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanned.setSpan(lineTint(addBg), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case DIFF_DEL -> {
                    spanned.setSpan(new ForegroundColorSpan(delColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanned.setSpan(lineTint(delBg), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case DIFF_HUNK -> {
                    spanned.setSpan(new ForegroundColorSpan(mutedColor), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanned.setSpan(new StyleSpan(Typeface.BOLD), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        if (matches != null && !matches.isEmpty()) {
            int pageEnd = pageOffset + pageText.length();
            int matchBg = context.getColor(R.color.syntax_search_match_bg);
            int currentBg = context.getColor(R.color.syntax_search_current_bg);
            for (int i = 0; i < matches.size(); i++) {
                SearchMatch m = matches.get(i);
                if (m.end <= pageOffset) continue;
                if (m.start >= pageEnd) break;
                int s = Math.max(0, m.start - pageOffset);
                int e = Math.min(pageText.length(), m.end - pageOffset);
                if (s < e) {
                    spanned.setSpan(new BackgroundColorSpan(i == activeMatchIndex ? currentBg : matchBg),
                            s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        if (showLineNumbers && !pageText.isEmpty()) {
            int totalLines = lineCount(pageText);
            int lastLine = firstLineNumber + totalLines - 1;
            int digits = Math.max(2, Integer.toString(lastLine).length());
            int gutterWidth = Math.round(context.getResources().getDisplayMetrics().density * (digits * 9 + 14));
            int ruleColor = context.getColor(R.color.line);
            int lineNo = firstLineNumber;
            int pos = 0;
            while (pos < pageText.length()) {
                int nl = pageText.indexOf('\n', pos);
                int lineEnd = nl < 0 ? pageText.length() : nl + 1;
                spanned.setSpan(new LineNumberSpan(lineNo++, gutterWidth, mutedColor, ruleColor),
                        pos, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                pos = lineEnd;
            }
        }
        return spanned;
    }

    private static LineBackgroundSpan lineTint(int color) {
        return (canvas, paint, left, right, top, baseline, bottom, text, start, end, lineNumber) -> {
            int previous = paint.getColor();
            paint.setColor(color);
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setColor(previous);
        };
    }

    /** Draws right-aligned line numbers in the left margin without polluting copied text. */
    private static final class LineNumberSpan implements LeadingMarginSpan {
        private final String label;
        private final int width;
        private final int inkColor;
        private final int ruleColor;

        LineNumberSpan(int lineNumber, int width, int inkColor, int ruleColor) {
            this.label = Integer.toString(lineNumber);
            this.width = width;
            this.inkColor = inkColor;
            this.ruleColor = ruleColor;
        }

        @Override public int getLeadingMargin(boolean first) {
            return width;
        }

        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                                CharSequence text, int start, int end, boolean first, Layout layout) {
            int oldColor = p.getColor();
            Paint.Align oldAlign = p.getTextAlign();
            float oldSize = p.getTextSize();
            int ruleX = x + dir * (width - 8);
            p.setColor(ruleColor);
            c.drawLine(ruleX, top, ruleX, bottom, p);
            if (first) {
                p.setColor(inkColor);
                p.setTextAlign(Paint.Align.RIGHT);
                p.setTextSize(oldSize * 0.85f);
                c.drawText(label, ruleX - 6, baseline, p);
            }
            p.setTextSize(oldSize);
            p.setTextAlign(oldAlign);
            p.setColor(oldColor);
        }
    }
}
