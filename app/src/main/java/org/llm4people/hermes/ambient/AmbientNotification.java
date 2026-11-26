package org.llm4people.hermes.ambient;

import org.llm4people.hermes.JsonValues;

import java.util.Objects;

/**
 * Sanitized, bounded, in-memory representation of a single opt-in notification.
 * Never persisted to disk; discarded immediately on dismissal, lock, or profile switch.
 */
public final class AmbientNotification {
    public final String key;
    public final String packageName;
    public final String appLabel;
    public final String sender;
    public final String title;
    public final String text;
    public final long postedAtMillis;
    public final boolean replyable;
    public final String replyLabel;

    public AmbientNotification(
            String key,
            String packageName,
            String appLabel,
            String sender,
            String title,
            String text,
            long postedAtMillis,
            boolean replyable,
            String replyLabel) {
        this.key = Objects.requireNonNull(key, "key");
        this.packageName = Objects.requireNonNull(packageName, "packageName");
        this.appLabel = org.llm4people.hermes.PlainTextSanitizer.collapseWithEllipsis(appLabel, NotificationPrivacyGuard.MAX_LABEL_CHARS);
        this.sender = org.llm4people.hermes.PlainTextSanitizer.collapseWithEllipsis(sender, NotificationPrivacyGuard.MAX_SENDER_CHARS);
        this.title = org.llm4people.hermes.PlainTextSanitizer.collapseWithEllipsis(title, NotificationPrivacyGuard.MAX_TITLE_CHARS);
        this.text = org.llm4people.hermes.PlainTextSanitizer.collapseWithEllipsis(text, NotificationPrivacyGuard.MAX_TEXT_CHARS);
        this.postedAtMillis = Math.max(0L, postedAtMillis);
        this.replyable = replyable;
        this.replyLabel = org.llm4people.hermes.PlainTextSanitizer.collapseWithEllipsis(replyLabel, 64);
    }

    public String displaySource() {
        return !appLabel.isEmpty() ? appLabel : packageName;
    }

    public String displaySender() {
        if (!sender.isEmpty()) return sender;
        if (!title.isEmpty()) return title;
        return displaySource();
    }

    public String displayBody() {
        if (!title.isEmpty() && !text.isEmpty() && !title.equals(text)) {
            return title + " — " + text;
        }
        return !text.isEmpty() ? text : title;
    }

    public String toSanitizedJson() {
        return "{"
                + "\"key\":" + JsonValues.quote(key)
                + ",\"package\":" + JsonValues.quote(packageName)
                + ",\"app\":" + JsonValues.quote(displaySource())
                + ",\"sender\":" + JsonValues.quote(displaySender())
                + ",\"title\":" + JsonValues.quote(title)
                + ",\"text\":" + JsonValues.quote(text)
                + ",\"posted_at_ms\":" + postedAtMillis
                + ",\"replyable\":" + replyable
                + "}";
    }
}
