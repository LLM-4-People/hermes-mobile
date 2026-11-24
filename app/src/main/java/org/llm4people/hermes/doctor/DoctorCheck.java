package org.llm4people.hermes.doctor;

import org.llm4people.hermes.HermesConfig;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure-Java diagnostic model and secret-redacting report formatter for Connection Doctor.
 * Single Source of Truth for health check statuses, host sanitization, and copy-safe reports.
 */
public final class DoctorCheck {
    public static final int EXPECTED_FORK_CAPABILITIES = HermesConfig.DEFAULT_EXPECTED_FORK_CAPABILITIES;
    public static final long SLOW_LATENCY_THRESHOLD_MS = HermesConfig.DEFAULT_SLOW_LATENCY_THRESHOLD_MS;

    private static final int MAX_SAFE_VALUE_CHARS = 96;
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_PREFIX_PATTERN = Pattern.compile(
            "(?i)\\b(sk-[a-z0-9_-]{4,}|gh[pousr]_[a-z0-9_]{6,}|xox[baprs]-[a-z0-9-]{6,}|aiza[0-9a-z_-]{10,})");
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(token|api[_-]?key|secret|password|passwd|authorization|cookie|key)\\s*[:=]\\s*[^\\s,&;]+");
    private static final Pattern URL_USERINFO_PATTERN = Pattern.compile("(https?://)[^/@\\s]+@");

    public enum Status {
        OK,
        WARN,
        ERROR
    }

    public enum CheckId {
        SERVER,
        CAPABILITIES,
        PUSH,
        UPDATES,
        AMBIENT,
        LANGUAGE
    }

    public enum FixAction {
        NONE,
        RETRY,
        OPEN_PUSH,
        OPEN_UPDATES,
        OPEN_AMBIENT,
        OPEN_LANGUAGE
    }

    public enum DetailCode {
        SERVER_OK,
        SERVER_SLOW,
        SERVER_AUTH_ERROR,
        SERVER_UNREACHABLE,
        SERVER_MISSING,
        CAPABILITIES_FULL,
        CAPABILITIES_PARTIAL,
        CAPABILITIES_UPSTREAM,
        CAPABILITIES_ERROR,
        PUSH_READY,
        PUSH_DISABLED,
        PUSH_BLOCKED,
        UPDATES_OK,
        UPDATES_AVAILABLE,
        UPDATES_UNCHECKED,
        AMBIENT_CONNECTED,
        AMBIENT_WAITING,
        AMBIENT_OFF,
        LANGUAGE_SYNCED,
        LANGUAGE_MISMATCH
    }

    public record Item(
            CheckId id,
            Status status,
            DetailCode detailCode,
            long latencyMs,
            int capabilityCount,
            int expectedCapabilities,
            boolean companionForkDetected,
            String value,
            FixAction fixAction) {
        public Item {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detailCode, "detailCode");
            Objects.requireNonNull(fixAction, "fixAction");
            latencyMs = Math.max(-1L, latencyMs);
            capabilityCount = Math.max(0, capabilityCount);
            expectedCapabilities = Math.max(1, expectedCapabilities);
            value = sanitizeValue(value);
        }
    }

    public record Report(
            String sanitizedHost,
            String appVersion,
            String languageTag,
            long generatedAtMs,
            List<Item> items) {
        public Report {
            sanitizedHost = sanitizeHost(sanitizedHost);
            appVersion = sanitizeValue(appVersion).isEmpty() ? "unknown" : sanitizeValue(appVersion);
            languageTag = sanitizeValue(languageTag).isEmpty() ? "fr" : sanitizeValue(languageTag);
            generatedAtMs = Math.max(0L, generatedAtMs);
            items = items == null ? Collections.emptyList() : Collections.unmodifiableList(items);
        }

        public Status overallStatus() {
            Status worst = Status.OK;
            for (Item item : items) {
                if (item.status() == Status.ERROR) return Status.ERROR;
                if (item.status() == Status.WARN) worst = Status.WARN;
            }
            return worst;
        }

        public int count(Status status) {
            int count = 0;
            for (Item item : items) {
                if (item.status() == status) count++;
            }
            return count;
        }

        public Item find(CheckId id) {
            for (Item item : items) {
                if (item.id() == id) return item;
            }
            return null;
        }

        /**
         * Produce a strictly redacted diagnostic text report suitable for clipboard sharing.
         * Never includes bearer credentials, profile names, URL paths/queries, notes, or messages.
         */
        public String toSafeReportText() {
            StringBuilder out = new StringBuilder();
            out.append("Hermes Mobile Connection Doctor Report\n");
            out.append("overall=").append(overallStatus().name().toLowerCase(Locale.ROOT)).append('\n');
            out.append("host=").append(sanitizedHost).append('\n');
            out.append("app_version=").append(appVersion).append('\n');
            out.append("language=").append(languageTag).append('\n');
            out.append("timestamp_ms=").append(generatedAtMs).append('\n');
            for (Item item : items) {
                out.append("- ")
                        .append(item.id().name().toLowerCase(Locale.ROOT))
                        .append(": ")
                        .append(item.status().name())
                        .append(" (")
                        .append(item.detailCode().name().toLowerCase(Locale.ROOT));
                if (item.id() == CheckId.SERVER && item.latencyMs() >= 0L) {
                    out.append(", latency_ms=").append(item.latencyMs());
                } else if (item.id() == CheckId.CAPABILITIES) {
                    out.append(", count=").append(item.capabilityCount())
                            .append('/').append(item.expectedCapabilities())
                            .append(", companion_fork=").append(item.companionForkDetected());
                } else if (!item.value().isEmpty()) {
                    out.append(", value=").append(item.value());
                }
                out.append(")\n");
            }
            return redactSecrets(out.toString());
        }
    }

    private DoctorCheck() {}

    /**
     * Extract only {@code scheme://host[:port]} from an HTTP(S) endpoint.
     * Strips userinfo, profile path segments, query parameters, fragments, and control characters.
     */
    public static String sanitizeHost(String rawEndpoint) {
        if (rawEndpoint == null) return "none";
        String trimmed = rawEndpoint.trim();
        if (trimmed.isEmpty() || containsControlChar(trimmed)) return "none";
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) return "none";
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) return "none";
            if (containsControlChar(host)) return "none";
            int port = uri.getPort();
            StringBuilder clean = new StringBuilder();
            clean.append(lowerScheme).append("://").append(host.toLowerCase(Locale.ROOT));
            if (port > 0 && port <= 65535) {
                boolean defaultPort = ("http".equals(lowerScheme) && port == 80)
                        || ("https".equals(lowerScheme) && port == 443);
                if (!defaultPort) clean.append(':').append(port);
            }
            return clean.toString();
        } catch (IllegalArgumentException invalid) {
            return "none";
        }
    }

    /** Defense-in-depth secret redactor applied to all text emitted into diagnostic reports. */
    public static String redactSecrets(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String cleaned = stripUnsafeControls(raw);
        cleaned = URL_USERINFO_PATTERN.matcher(cleaned).replaceAll("$1[REDACTED]@");
        cleaned = BEARER_PATTERN.matcher(cleaned).replaceAll("Bearer [REDACTED]");
        cleaned = SECRET_PREFIX_PATTERN.matcher(cleaned).replaceAll("[REDACTED]");
        cleaned = KEY_VALUE_SECRET_PATTERN.matcher(cleaned).replaceAll("$1=[REDACTED]");
        return cleaned;
    }

    static String sanitizeValue(String raw) {
        if (raw == null) return "";
        String cleaned = redactSecrets(raw).replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.length() > MAX_SAFE_VALUE_CHARS) {
            cleaned = cleaned.substring(0, MAX_SAFE_VALUE_CHARS);
        }
        return cleaned;
    }

    private static boolean containsControlChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    private static String stripUnsafeControls(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7f)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
