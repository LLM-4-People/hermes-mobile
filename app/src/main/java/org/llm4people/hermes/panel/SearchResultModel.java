package org.llm4people.hermes.panel;

import android.content.Context;
import org.llm4people.hermes.EpochTimestamps;
import org.llm4people.hermes.R;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure domain model for cross-thread FTS5 search results, safe snippet highlight ranges, and role labels. */
public final class SearchResultModel {
    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_QUERY_LENGTH = 200;

    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid highlight span");
        }
    }

    public record HighlightedSnippet(String plainText, List<Span> highlights) {
        public HighlightedSnippet {
            plainText = plainText == null ? "" : plainText;
            highlights = highlights == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(highlights));
        }
    }

    public record Hit(
            String sessionId,
            String threadId,
            String threadTitle,
            String messageId,
            String role,
            HighlightedSnippet snippet,
            String timestamp) {
        public Hit {
            sessionId = safe(sessionId);
            threadId = safe(threadId);
            threadTitle = safe(threadTitle);
            messageId = safe(messageId);
            role = safe(role);
            snippet = snippet == null ? new HighlightedSnippet("", Collections.emptyList()) : snippet;
            timestamp = safe(timestamp);
        }

        /** Prefer the canonical root threadId when provided; fall back to sessionId. */
        public String targetSessionId() {
            return !threadId.isEmpty() ? threadId : sessionId;
        }

        public String displayTitle(Context context) {
            return threadTitle.isEmpty() ? context.getString(R.string.ws_search_untitled_thread) : threadTitle;
        }
    }

    public record Page(List<Hit> hits, int total, String query, boolean hasMoreFlag, boolean hasExplicitMore) {
        public Page {
            hits = hits == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(hits));
            total = Math.max(hits.size(), total);
            query = safe(query);
        }

        public Page(List<Hit> hits, int total, String query) {
            this(hits, total, query, total > (hits == null ? 0 : hits.size()), false);
        }

        public boolean hasMore(int loadedCount) {
            if (hits.isEmpty()) return false;
            if (hasExplicitMore) return hasMoreFlag || total > loadedCount;
            return hasMoreFlag || total > loadedCount || hits.size() >= DEFAULT_LIMIT;
        }
    }

    private SearchResultModel() {}

    public static String normalizeQuery(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) trimmed = trimmed.substring(0, MAX_QUERY_LENGTH).trim();
        StringBuilder clean = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (!Character.isISOControl(ch)) clean.append(ch);
        }
        return clean.toString().trim();
    }

    public static String validateRoleFilter(String role) {
        if (role == null || role.trim().isEmpty()) return "";
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "user", "assistant", "tool" -> role.trim().toLowerCase(Locale.ROOT);
            default -> "";
        };
    }

    public static int roleLabelRes(String role) {
        if (role == null) return 0;
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "user" -> R.string.ws_role_user;
            case "assistant" -> R.string.ws_role_hermes;
            case "tool" -> R.string.ws_role_tool;
            default -> 0;
        };
    }

    public static String roleLabel(Context context, String role) {
        int res = roleLabelRes(role);
        return res == 0 ? "" : context.getString(res);
    }

    /**
     * Parse FTS5 `<mark>...</mark>` markers safely into plain text + highlight ranges.
     * Strips any raw `<mark>`/`</mark>` tokens without using HTML/WebView parsing.
     */
    public static HighlightedSnippet parseSnippet(String rawSnippet) {
        if (rawSnippet == null || rawSnippet.isEmpty()) {
            return new HighlightedSnippet("", Collections.emptyList());
        }
        StringBuilder out = new StringBuilder(rawSnippet.length());
        List<Span> spans = new ArrayList<>();
        int index = 0;
        int markStart = -1;
        while (index < rawSnippet.length()) {
            if (regionMatchesIgnoreCase(rawSnippet, index, "<mark>")) {
                if (markStart < 0) markStart = out.length();
                index += 6;
            } else if (regionMatchesIgnoreCase(rawSnippet, index, "</mark>")) {
                if (markStart >= 0 && out.length() > markStart) {
                    spans.add(new Span(markStart, out.length()));
                }
                markStart = -1;
                index += 7;
            } else {
                out.append(rawSnippet.charAt(index));
                index++;
            }
        }
        if (markStart >= 0 && out.length() > markStart) {
            spans.add(new Span(markStart, out.length()));
        }
        return new HighlightedSnippet(out.toString().trim(), adjustSpansAfterTrim(out.toString(), spans));
    }

    public static String formatTimestamp(String rawTimestamp, ZoneId zone) {
        return formatTimestamp(rawTimestamp, zone, Locale.getDefault());
    }

    public static String formatTimestamp(String rawTimestamp, ZoneId zone, Locale locale) {
        long epochSec = EpochTimestamps.parseEpochSeconds(rawTimestamp);
        if (epochSec <= 0L) return "";
        ZoneId targetZone = zone == null ? ZoneId.systemDefault() : zone;
        Locale effective = locale == null ? Locale.getDefault() : locale;
        DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(effective);
        return Instant.ofEpochSecond(epochSec).atZone(targetZone).format(fmt);
    }

    private static List<Span> adjustSpansAfterTrim(String untrimmed, List<Span> rawSpans) {
        if (rawSpans.isEmpty()) return Collections.emptyList();
        int leading = 0;
        while (leading < untrimmed.length() && untrimmed.charAt(leading) <= ' ') leading++;
        int trimmedLen = untrimmed.trim().length();
        List<Span> adjusted = new ArrayList<>(rawSpans.size());
        for (Span span : rawSpans) {
            int s = Math.max(0, Math.min(trimmedLen, span.start() - leading));
            int e = Math.max(s, Math.min(trimmedLen, span.end() - leading));
            if (e > s) adjusted.add(new Span(s, e));
        }
        return adjusted;
    }

    private static boolean regionMatchesIgnoreCase(String text, int offset, String token) {
        return text.regionMatches(true, offset, token, 0, token.length());
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
