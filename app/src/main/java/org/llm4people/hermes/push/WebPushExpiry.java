package org.llm4people.hermes.push;

/** UTC Unix milliseconds authenticated inside the encrypted Web Push message. */
final class WebPushExpiry {
    private WebPushExpiry() {}

    static long read(Object value) {
        if (!(value instanceof Long) && !(value instanceof Integer)) return 0;
        long millis = ((Number) value).longValue();
        return Math.max(0L, millis);
    }

    static boolean mayDispatch(String kind, long expiresAt, long now) {
        // A delayed settlement only removes a previously displayed notification.
        return expiresAt > 0 && (expiresAt > now || "question_settled".equals(kind) || "approval_settled".equals(kind));
    }
}
