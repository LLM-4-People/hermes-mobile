package org.llm4people.hermes.palette;

import java.util.Objects;

/**
 * Immutable searchable entry presented inside {@link CommandPalette}.
 * Keeps display text clean, bounded, and free of control characters.
 */
public final class CommandItem {
    public static final int MAX_TITLE_CHARS = 96;
    public static final int MAX_SUBTITLE_CHARS = 160;
    public static final int MAX_KEYWORDS_CHARS = 200;

    public enum Category {
        ACTION(0),
        TEMPLATE(1),
        THREAD(2),
        SCREEN(3);

        public final int order;
        Category(int order) { this.order = order; }
    }

    public final String id;
    public final Category category;
    public final String title;
    public final String subtitle;
    public final String keywords;
    public final Runnable action;

    public CommandItem(String id, Category category, String title, String subtitle, String keywords, Runnable action) {
        String safeId = sanitize(id, 96);
        String safeTitle = sanitize(title, MAX_TITLE_CHARS);
        if (safeId.isEmpty()) throw new IllegalArgumentException("CommandItem id required");
        if (safeTitle.isEmpty()) throw new IllegalArgumentException("CommandItem title required");
        this.id = safeId;
        this.category = Objects.requireNonNull(category, "category");
        this.title = safeTitle;
        this.subtitle = sanitize(subtitle, MAX_SUBTITLE_CHARS);
        this.keywords = sanitize(keywords, MAX_KEYWORDS_CHARS);
        this.action = action;
    }

    /** Strips control characters and collapses whitespace into clean single-line text. */
    static String sanitize(String raw, int maxChars) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxChars));
        boolean lastSpace = true;
        for (int i = 0; i < raw.length() && sb.length() < maxChars; ) {
            int cp = raw.codePointAt(i);
            int len = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                if (!lastSpace && sb.length() < maxChars) {
                    sb.append(' ');
                    lastSpace = true;
                }
            } else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.SURROGATE
                    && sb.length() + len <= maxChars) {
                sb.appendCodePoint(cp);
                lastSpace = false;
            }
            i += len;
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof CommandItem other)) return false;
        return id.equals(other.id) && category == other.category
                && title.equals(other.title) && subtitle.equals(other.subtitle);
    }

    @Override public int hashCode() {
        return Objects.hash(id, category, title, subtitle);
    }
}
