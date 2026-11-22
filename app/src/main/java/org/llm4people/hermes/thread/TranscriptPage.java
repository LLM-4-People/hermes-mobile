package org.llm4people.hermes.thread;

import org.llm4people.hermes.artifact.Fences;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded native text pages. A continuation carries the fence state across long code blocks. */
final class TranscriptPage {
    static final int PAGE_CHARS = 6000;
    enum Style { CODE, INSERTION, DELETION, INLINE_CODE, HEADING, BOLD, ITALIC }

    static final class Mark {
        final int start, end;
        final Style style;
        Mark(int start, int end, Style style) {
            this.start = start;
            this.end = end;
            this.style = style;
        }
    }

    static final class Cursor {
        int offset;
        int lineEnd = -1;
        Fences.Marker fence;
        int fenceBody;
        Style lineStyle;
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
    private final String source;
    private final boolean markdown;
    final Cursor next;
    final StringBuilder text = new StringBuilder();
    final List<Mark> marks = new ArrayList<>();
    final List<Block> blocks = new ArrayList<>();

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
            if (next.lineEnd < 0 && beginLine()) continue;
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
            mark(spanStart, next.lineStyle);
            if (next.offset == next.lineEnd) {
                if (next.offset < source.length()) { text.append('\n'); next.offset++; }
                next.lineEnd = -1;
            }
            if (block != null) block.displayEnd = text.length();
        }
    }

    boolean hasMore() { return next.offset < source.length(); }

    /** Returns true when the entire line belongs to a fence delimiter. */
    private boolean beginLine() {
        int start = next.offset;
        int end = source.indexOf('\n', start);
        next.lineEnd = end < 0 ? source.length() : end;
        next.lineStyle = null;
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
        } else if (lead == '#') {
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
        }
        return false;
    }

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
                if (field == 3) literal(tokens.end(field));
                else textWithImages(tokens.end(field), tokens.end(field));
                mark(spanStart, field == 3 ? Style.INLINE_CODE : field <= 5 ? Style.BOLD : Style.ITALIC);
                next.offset = tokens.end();
            }
        }
        literal(end);
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

    private void mark(int start, Style style) {
        if (style != null && start < text.length()) marks.add(new Mark(start, text.length(), style));
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
