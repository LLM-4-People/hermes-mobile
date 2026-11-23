package org.llm4people.hermes.thread;

import org.llm4people.hermes.artifact.Fences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded native text pages. A continuation carries the fence state across long code blocks. */
final class TranscriptPage {
    static final int PAGE_CHARS = 6000;
    static final int MAX_TABLE_COLS = 12;
    static final int MAX_TABLE_ROWS = 100;
    static final int MAX_LINKS = 24;
    static final int MAX_REASONING_CHARS = 16_000;

    enum Style { CODE, INSERTION, DELETION, INLINE_CODE, HEADING, BOLD, ITALIC, QUOTE, NESTED_LIST, TABLE_LINE }

    enum Align { LEFT, CENTER, RIGHT }

    static final class Mark {
        final int start, end;
        final Style style;
        final int level;

        Mark(int start, int end, Style style) {
            this(start, end, style, 0);
        }

        Mark(int start, int end, Style style, int level) {
            this.start = start;
            this.end = end;
            this.style = style;
            this.level = level;
        }
    }

    static final class Link {
        final String label;
        final String url;
        final String host;

        Link(String label, String url, String host) {
            this.label = label;
            this.url = url;
            this.host = host;
        }
    }

    static final class Table {
        final List<String> headers;
        final List<Align> alignments;
        final List<List<String>> rows;
        final int displayStart;
        final int displayEnd;

        Table(List<String> headers, List<Align> alignments, List<List<String>> rows, int displayStart, int displayEnd) {
            this.headers = Collections.unmodifiableList(headers);
            this.alignments = Collections.unmodifiableList(alignments);
            this.rows = Collections.unmodifiableList(rows);
            this.displayStart = displayStart;
            this.displayEnd = displayEnd;
        }
    }

    /** Result of separating private reasoning blocks ({@code <think>} etc.) from visible prose. */
    record ReasoningSplit(String reasoning, String visible, boolean open) {}

    static final class Cursor {
        int offset;
        int lineEnd = -1;
        Fences.Marker fence;
        int fenceBody;
        Style lineStyle;
        int lineLevel;
        boolean image;

        void extendLine(String source, int oldLength) {
            if (lineEnd != oldLength) return;
            int newline = source.indexOf('\n', oldLength);
            lineEnd = newline < 0 ? source.length() : newline;
        }

        Cursor() {}
        private Cursor(Cursor other) {
            offset = other.offset;
            lineEnd = other.lineEnd;
            fence = other.fence;
            fenceBody = other.fenceBody;
            lineStyle = other.lineStyle;
            lineLevel = other.lineLevel;
            image = other.image;
        }
    }

    /** Source and display offsets are distinct after removing Markdown delimiters. */
    static final class Block {
        final Fences.Marker marker;
        final int bodyStart, displayStart;
        int displayEnd;
        private int closingLine = -1;

        Block(Cursor cursor, int start) {
            marker = cursor.fence;
            bodyStart = cursor.fenceBody;
            displayStart = start;
        }

        String title(String source) {
            String file = marker.filename(source, bodyStart);
            if (file.isEmpty()) return marker.language;
            return marker.language.isEmpty() ? file : file + " · " + marker.language;
        }

        // A block may outlast this page. Resolve its far boundary only when opened.
        String content(String source) {
            int end = closingLine;
            if (end < 0) {
                for (int at = bodyStart; at < source.length();) {
                    int newline = source.indexOf('\n', at);
                    int lineEnd = newline < 0 ? source.length() : newline;
                    Fences.Marker candidate = Fences.marker(source, at, lineEnd);
                    if (candidate != null && candidate.closes(marker)) { end = at; break; }
                    at = lineEnd + 1;
                }
            }
            if (end < 0) return source.substring(bodyStart);
            if (end > bodyStart && source.charAt(end - 1) == '\n') end--;
            if (end > bodyStart && source.charAt(end - 1) == '\r') end--;
            return source.substring(bodyStart, end);
        }
    }

    private static final String DATA_HEADER = "data:image/[A-Za-z0-9.+-]{1,64};base64,";
    private static final String IMAGE = "(!\\[[^]\\r\\n]{0,240}\\]\\(" + DATA_HEADER + ")|(" + DATA_HEADER + ")";
    private static final Pattern MEDIA = Pattern.compile(IMAGE, Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE = Pattern.compile(IMAGE
            + "|(?<![`\\\\])`([^`\\r\\n]+)`(?!`)"
            + "|(?<![\\\\*])\\*\\*([^*\\r\\n]+)\\*\\*(?!\\*)"
            + "|(?<![\\w_\\\\])__([^_\\r\\n]+)__(?![\\w_])"
            + "|(?<![\\w*\\\\])\\*([^*\\r\\n]+)\\*(?![\\w*])"
            + "|(?<![\\w_\\\\])_([^_\\r\\n]+)_(?![\\w_])", Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_LINK = Pattern.compile("(?<!!)\\[([^\\]\\r\\n]{1,160})\\]\\(([^\\s)\\r\\n]{1,2048})\\)");
    private static final Pattern OPEN_REASONING = Pattern.compile("(?i)<(think|thinking|reasoning|analysis)(?:\\s[^>]*)?>");

    private final String source;
    private final boolean markdown;
    final Cursor next;
    final StringBuilder text = new StringBuilder();
    final List<Mark> marks = new ArrayList<>();
    final List<Block> blocks = new ArrayList<>();
    final List<Table> tables = new ArrayList<>();
    final List<Link> links = new ArrayList<>();
    private final Set<String> seenLinkUrls = new LinkedHashSet<>();
    int rejectedLinks;

    TranscriptPage(String source, Cursor start, boolean markdown, int limit) {
        this.source = source;
        this.markdown = markdown;
        next = new Cursor(start);
        if (next.image) {
            while (next.offset < next.lineEnd && base64(source.charAt(next.offset))) next.offset++;
            if (next.offset < next.lineEnd && source.charAt(next.offset) == ')') next.offset++;
            next.image = false;
            text.append("[Image jointe]");
        }
        while (next.offset < source.length() && text.length() < limit) {
            if (next.lineEnd < 0 && beginLine(limit)) continue;
            int startOffset = next.offset;
            int end = Math.min(next.lineEnd, startOffset + limit - text.length());
            if (end < next.lineEnd && end > startOffset && Character.isHighSurrogate(source.charAt(end - 1))) {
                if (end - startOffset == 1 && text.length() > 0) break;
                if (end - startOffset > 1) end--;
            }
            int spanStart = text.length();
            Block block = null;
            if (next.fence != null) {
                block = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
                if (block == null || block.bodyStart != next.fenceBody) {
                    block = new Block(next, spanStart);
                    blocks.add(block);
                }
            }
            if (next.fence == null) inline(end); else literal(end);
            mark(spanStart, next.lineStyle, next.lineLevel);
            if (next.offset == next.lineEnd) {
                if (next.offset < source.length()) { text.append('\n'); next.offset++; }
                next.lineEnd = -1;
            }
            if (block != null) block.displayEnd = text.length();
        }
    }

    boolean hasMore() { return next.offset < source.length(); }

    /** Fence-aware extraction of {@code <think>}/{@code <reasoning>} tags from assistant text. */
    static ReasoningSplit splitReasoning(String source) {
        return splitReasoning(source, "");
    }

    static ReasoningSplit splitReasoning(String source, String structuredReasoning) {
        StringBuilder reasoning = new StringBuilder();
        if (structuredReasoning != null && !structuredReasoning.trim().isEmpty()) {
            appendBounded(reasoning, structuredReasoning.trim());
        }
        if (source == null || source.isEmpty()) {
            return new ReasoningSplit(reasoning.toString(), "", false);
        }
        if (source.indexOf('<') < 0) {
            return new ReasoningSplit(reasoning.toString(), source, false);
        }
        StringBuilder visible = new StringBuilder(source.length());
        Fences.Marker openFence = null;
        String activeTag = null;
        StringBuilder currentThought = new StringBuilder();
        for (int pos = 0; pos < source.length();) {
            int nl = source.indexOf('\n', pos);
            int lineEnd = nl < 0 ? source.length() : nl;
            boolean hasNewline = nl >= 0;
            if (activeTag == null) {
                Fences.Marker marker = Fences.marker(source, pos, lineEnd);
                if (openFence != null) {
                    if (marker != null && marker.closes(openFence)) openFence = null;
                    visible.append(source, pos, hasNewline ? lineEnd + 1 : lineEnd);
                    pos = hasNewline ? lineEnd + 1 : lineEnd;
                    continue;
                } else if (marker != null) {
                    openFence = marker;
                    visible.append(source, pos, hasNewline ? lineEnd + 1 : lineEnd);
                    pos = hasNewline ? lineEnd + 1 : lineEnd;
                    continue;
                }
            }
            String line = source.substring(pos, lineEnd);
            int cursor = 0;
            boolean hadVisibleOnLine = false;
            boolean strippedEntireLine = true;
            while (cursor < line.length()) {
                if (activeTag == null) {
                    Matcher m = OPEN_REASONING.matcher(line);
                    m.region(cursor, line.length());
                    if (m.find()) {
                        String before = line.substring(cursor, m.start());
                        if (!before.isEmpty()) {
                            visible.append(before);
                            hadVisibleOnLine = true;
                            strippedEntireLine = false;
                        }
                        activeTag = m.group(1).toLowerCase(Locale.ROOT);
                        currentThought.setLength(0);
                        cursor = m.end();
                    } else {
                        visible.append(line, cursor, line.length());
                        hadVisibleOnLine = true;
                        strippedEntireLine = false;
                        cursor = line.length();
                    }
                } else {
                    String closeToken = "</" + activeTag;
                    int closeIdx = indexOfIgnoreCase(line, closeToken, cursor);
                    if (closeIdx >= 0) {
                        int gt = line.indexOf('>', closeIdx + closeToken.length());
                        if (gt >= 0) {
                            currentThought.append(line, cursor, closeIdx);
                            appendThought(reasoning, currentThought.toString());
                            currentThought.setLength(0);
                            activeTag = null;
                            cursor = gt + 1;
                            continue;
                        }
                    }
                    currentThought.append(line, cursor, line.length());
                    cursor = line.length();
                }
            }
            if (hasNewline) {
                if (activeTag != null) currentThought.append('\n');
                else if (hadVisibleOnLine || !strippedEntireLine) visible.append('\n');
            }
            pos = hasNewline ? lineEnd + 1 : lineEnd;
        }
        boolean stillOpen = activeTag != null;
        if (stillOpen && currentThought.length() > 0) {
            appendThought(reasoning, currentThought.toString());
        }
        String cleanedVisible = visible.toString();
        if (reasoning.length() > 0) {
            cleanedVisible = trimLeadingBlankLines(cleanedVisible);
        }
        return new ReasoningSplit(reasoning.toString().trim(), cleanedVisible, stillOpen);
    }

    private static void appendThought(StringBuilder target, String chunk) {
        String trimmed = chunk.trim();
        if (trimmed.isEmpty()) return;
        if (target.length() > 0 && target.length() < MAX_REASONING_CHARS) target.append("\n\n");
        appendBounded(target, trimmed);
    }

    private static void appendBounded(StringBuilder target, String text) {
        int remaining = MAX_REASONING_CHARS - target.length();
        if (remaining <= 0) return;
        if (text.length() <= remaining) target.append(text);
        else {
            int cut = remaining;
            if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
            target.append(text, 0, cut);
        }
    }

    private static String trimLeadingBlankLines(String text) {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) i++;
        return i == 0 ? text : text.substring(i);
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int max = haystack.length() - needle.length();
        for (int i = from; i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    /** Returns true when the entire line (or multi-line table) was consumed directly. */
    private boolean beginLine(int limit) {
        int start = next.offset;
        int end = source.indexOf('\n', start);
        next.lineEnd = end < 0 ? source.length() : end;
        next.lineStyle = null;
        next.lineLevel = 0;
        if (!markdown || start == next.lineEnd) return false;
        Fences.Marker marker = Fences.marker(source, start, next.lineEnd);
        if (marker != null && (next.fence == null || marker.closes(next.fence))) {
            if (next.fence == null) {
                next.fence = marker;
                next.fenceBody = Math.min(source.length(), next.lineEnd + 1);
            } else {
                if (!blocks.isEmpty() && blocks.get(blocks.size() - 1).bodyStart == next.fenceBody)
                    blocks.get(blocks.size() - 1).closingLine = start;
                next.fence = null;
            }
            next.offset = Math.min(source.length(), next.lineEnd + 1);
            next.lineEnd = -1;
            return true;
        }
        char lead = source.charAt(start);
        if (next.fence != null) {
            next.lineStyle = fencedStyle(next.fence, lead);
            return false;
        }

        // Markdown pipe table (header + separator + body rows).
        if (tryConsumeTable(start, next.lineEnd, limit)) return true;

        // Blockquotes: up to 3 leading spaces then '>' (supports nested '> >').
        int quotePos = start;
        int leadingSpaces = 0;
        while (quotePos < next.lineEnd && source.charAt(quotePos) == ' ' && leadingSpaces < 3) {
            quotePos++;
            leadingSpaces++;
        }
        if (quotePos < next.lineEnd && source.charAt(quotePos) == '>') {
            int depth = 0;
            int at = quotePos;
            while (at < next.lineEnd && source.charAt(at) == '>') {
                depth++;
                at++;
                if (at < next.lineEnd && source.charAt(at) == ' ') at++;
            }
            next.offset = at;
            next.lineStyle = Style.QUOTE;
            next.lineLevel = depth;
            return false;
        }

        if (lead == '#') {
            int i = start + 1;
            while (i < next.lineEnd && source.charAt(i) == '#') i++;
            if (i - start <= 6 && i < next.lineEnd && source.charAt(i) == ' ') {
                next.offset = i + 1;
                next.lineStyle = Style.HEADING;
            }
        } else if ((lead == '-' || lead == '*' || lead == '+') && start + 1 < next.lineEnd
                && source.charAt(start + 1) == ' ') {
            text.append("• ");
            next.offset += 2;
        } else if (lead == ' ' || lead == '\t') {
            int spaces = 0;
            int at = start;
            while (at < next.lineEnd && (source.charAt(at) == ' ' || source.charAt(at) == '\t')) {
                spaces += source.charAt(at) == '\t' ? 4 : 1;
                at++;
            }
            int level = Math.min(4, Math.max(1, spaces / 2));
            if (at + 1 < next.lineEnd && (source.charAt(at) == '-' || source.charAt(at) == '*' || source.charAt(at) == '+')
                    && source.charAt(at + 1) == ' ') {
                String bullet = level == 1 ? "◦ " : "▪ ";
                text.append(bullet);
                next.offset = at + 2;
                next.lineStyle = Style.NESTED_LIST;
                next.lineLevel = level;
            } else if (at < next.lineEnd && isDigit(source.charAt(at))) {
                int numEnd = at;
                while (numEnd < next.lineEnd && isDigit(source.charAt(numEnd)) && numEnd - at < 4) numEnd++;
                if (numEnd + 1 < next.lineEnd && (source.charAt(numEnd) == '.' || source.charAt(numEnd) == ')')
                        && source.charAt(numEnd + 1) == ' ') {
                    next.offset = at;
                    next.lineStyle = Style.NESTED_LIST;
                    next.lineLevel = level;
                }
            }
        }
        return false;
    }

    private boolean tryConsumeTable(int headerStart, int headerEnd, int limit) {
        if (headerEnd >= source.length()) return false;
        String headerLine = stripCr(source.substring(headerStart, headerEnd));
        if (headerLine.indexOf('|') < 0) return false;
        int sepStart = headerEnd + 1;
        int sepNl = source.indexOf('\n', sepStart);
        int sepEnd = sepNl < 0 ? source.length() : sepNl;
        String sepLine = stripCr(source.substring(sepStart, sepEnd));
        List<Align> alignments = parseTableSeparator(sepLine);
        if (alignments == null || alignments.isEmpty()) return false;
        List<String> headers = splitTableRow(headerLine, Math.min(alignments.size(), MAX_TABLE_COLS));
        if (headers.size() != alignments.size()) return false;

        List<List<String>> rows = new ArrayList<>();
        int pos = sepNl < 0 ? source.length() : sepEnd + 1;
        while (pos < source.length() && rows.size() < MAX_TABLE_ROWS) {
            int rowNl = source.indexOf('\n', pos);
            int rowEnd = rowNl < 0 ? source.length() : rowNl;
            String raw = stripCr(source.substring(pos, rowEnd));
            if (raw.trim().isEmpty() || raw.indexOf('|') < 0) break;
            if (Fences.marker(source, pos, rowEnd) != null) break;
            rows.add(splitTableRow(raw, headers.size()));
            pos = rowNl < 0 ? source.length() : rowEnd + 1;
        }

        int displayStart = text.length();
        appendTableRowText(headers, limit);
        for (List<String> row : rows) {
            if (text.length() >= limit) break;
            text.append('\n');
            appendTableRowText(row, limit);
        }
        int displayEnd = text.length();
        if (pos < source.length() && text.length() < limit) text.append('\n');
        tables.add(new Table(headers, alignments, rows, displayStart, displayEnd));
        next.offset = pos;
        next.lineEnd = -1;
        return true;
    }

    private void appendTableRowText(List<String> cells, int limit) {
        int rowStart = text.length();
        for (int i = 0; i < cells.size() && text.length() < limit; i++) {
            if (i > 0) text.append(" | ");
            String cell = cells.get(i);
            int remaining = limit - text.length();
            if (cell.length() <= remaining) text.append(cell);
            else text.append(cell, 0, Math.max(0, remaining));
        }
        mark(rowStart, Style.TABLE_LINE, 0);
    }

    static List<Align> parseTableSeparator(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('|') < 0 || trimmed.indexOf('-') < 0) return null;
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        if (parts.length == 0 || parts.length > MAX_TABLE_COLS) return null;
        List<Align> aligns = new ArrayList<>(parts.length);
        for (String part : parts) {
            String cell = part.trim();
            if (cell.isEmpty()) return null;
            boolean leftColon = cell.startsWith(":");
            boolean rightColon = cell.endsWith(":");
            int dashStart = leftColon ? 1 : 0;
            int dashEnd = rightColon ? cell.length() - 1 : cell.length();
            if (dashEnd - dashStart < 1) return null;
            for (int i = dashStart; i < dashEnd; i++) {
                if (cell.charAt(i) != '-') return null;
            }
            aligns.add(leftColon && rightColon ? Align.CENTER : rightColon ? Align.RIGHT : Align.LEFT);
        }
        return aligns;
    }

    static List<String> splitTableRow(String line, int expectedCols) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '|') {
                current.append('|');
                i++;
            } else if (c == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        while (cells.size() < expectedCols) cells.add("");
        if (cells.size() > expectedCols) return new ArrayList<>(cells.subList(0, expectedCols));
        return cells;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private void literal(int end) {
        text.append(source, next.offset, end);
        next.offset = end;
    }

    private void inline(int end) {
        if (end <= next.offset) return;
        // Include the longest supported image header past the page boundary. Once recognized,
        // skip its payload in the original source so the next page starts after the attachment.
        int lookahead = Math.min(next.lineEnd - end, 384);
        Matcher tokens = (markdown ? INLINE : MEDIA).matcher(source).region(next.offset, end + lookahead);
        while (tokens.find() && tokens.start() < end) {
            boolean image = tokens.start(1) >= 0 || tokens.start(2) >= 0;
            if (!image && tokens.end() > end) {
                // Formatting that continues on another page is literal here. Its images still collapse.
                textWithImages(end, end + lookahead);
                return;
            }
            if (markdown) collectLinks(next.offset, tokens.start());
            literal(tokens.start());
            if (image) {
                appendImage(tokens);
                if (next.offset >= end) return;
                tokens.region(next.offset, end + lookahead);
            } else {
                int field = 3;
                while (tokens.start(field) < 0) field++;
                int spanStart = text.length();
                next.offset = tokens.start(field);
                if (field == 3) {
                    literal(tokens.end(field));
                } else {
                    if (markdown) collectLinks(tokens.start(field), tokens.end(field));
                    textWithImages(tokens.end(field), tokens.end(field));
                }
                mark(spanStart, field == 3 ? Style.INLINE_CODE : field <= 5 ? Style.BOLD : Style.ITALIC, 0);
                next.offset = tokens.end();
            }
        }
        if (markdown) collectLinks(next.offset, end);
        literal(end);
    }

    private void collectLinks(int from, int to) {
        if (from >= to || links.size() >= MAX_LINKS) return;
        Matcher matcher = MD_LINK.matcher(source).region(from, to);
        while (matcher.find()) {
            String label = matcher.group(1).trim();
            String rawUrl = matcher.group(2).trim();
            SafeLinks.Validated safe = SafeLinks.validate(rawUrl);
            if (safe == null) {
                rejectedLinks++;
                continue;
            }
            if (links.size() < MAX_LINKS && seenLinkUrls.add(safe.url())) {
                links.add(new Link(label.isEmpty() ? safe.host() : label, safe.url(), safe.host()));
            }
        }
    }

    private void textWithImages(int end, int searchEnd) {
        Matcher images = MEDIA.matcher(source).region(next.offset, searchEnd);
        while (images.find() && images.start() < end) {
            literal(images.start());
            appendImage(images);
            if (next.offset >= end) return;
            images.region(next.offset, searchEnd);
        }
        literal(end);
    }

    private void appendImage(Matcher image) {
        int after = image.end();
        while (after < next.lineEnd && base64(source.charAt(after))) after++;
        if (image.start(1) >= 0 && after < next.lineEnd && source.charAt(after) == ')') after++;
        text.append("[Image jointe]");
        next.offset = after;
    }

    private void mark(int start, Style style, int level) {
        if (style != null && start < text.length()) marks.add(new Mark(start, text.length(), style, level));
    }

    static Style fencedStyle(Fences.Marker fence, char lead) {
        boolean diff = fence.language.equals("diff") || fence.language.equals("patch");
        return diff && lead == '+' ? Style.INSERTION : diff && lead == '-' ? Style.DELETION : Style.CODE;
    }

    /** Keep the data-URI header available to the renderer when trimming a streaming source. */
    static int mediaBoundary(String source, int cut) {
        Matcher header = MEDIA.matcher(source).region(Math.max(0, cut - 384), Math.min(source.length(), cut + 384));
        while (header.find() && header.start() < cut) if (header.end() > cut) return header.start();
        return cut;
    }

    static boolean cutsMedia(String source, int line, int cut, boolean alreadyInImage) {
        int payload = cut;
        while (payload > line && base64(source.charAt(payload - 1))) payload--;
        if (payload == line) return alreadyInImage;
        if (source.charAt(payload - 1) != ',') return false;
        Matcher header = MEDIA.matcher(source).region(Math.max(line, payload - 384), payload);
        while (header.find()) if (header.end() == payload) return true;
        return false;
    }

    private static boolean base64(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '+' || c == '/' || c == '=';
    }
}
