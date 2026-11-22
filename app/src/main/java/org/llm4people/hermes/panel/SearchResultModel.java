package org.llm4people.hermes.panel;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure domain model for cross-thread FTS5 search results, safe snippet highlight ranges, and role labels. */
public final class SearchResultModel {
    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 50;
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

        public String displayTitle() {
            return threadTitle.isEmpty() ? "Discussion sans titre" : threadTitle;
        }
    }

    public record Page(List<Hit> hits, int total, String query) {
        public Page {
            hits = hits == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(hits));
            total = Math.max(hits.size(), total);
            query = safe(query);
        }

        public boolean hasMore(int loadedCount) {
            return total > loadedCount && !hits.isEmpty();
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

    public static String roleLabel(String role) {
        if (role == null) return "";
        return switch (role.trim().toLowerCase(Locale.ROOT)) {
            case "user" -> "Vous";
            case "assistant" -> "Hermes";
            case "tool" -> "Outil";
            default -> "";
        };
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
        if (rawTimestamp == null || rawTimestamp.trim().isEmpty()) return "";
        String value = rawTimestamp.trim();
        ZoneId targetZone = zone == null ? ZoneId.systemDefault() : zone;
        DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.FRENCH);
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(targetZone).format(fmt);
        } catch (DateTimeParseException ignored) {
            // Try instant or epoch seconds/millis below.
        }
        try {
            return Instant.parse(value).atZone(targetZone).format(fmt);
        } catch (DateTimeParseException ignored) {
            // Try numeric epoch below.
        }
        try {
            double numeric = Double.parseDouble(value);
            if (!Double.isFinite(numeric) || numeric <= 0) return "";
            long epochMillis = numeric > 1e11 ? Math.round(numeric) : Math.round(numeric * 1000d);
            return Instant.ofEpochMilli(epochMillis).atZone(targetZone).format(fmt);
        } catch (NumberFormatException ignored) {
            return "";
        }
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
