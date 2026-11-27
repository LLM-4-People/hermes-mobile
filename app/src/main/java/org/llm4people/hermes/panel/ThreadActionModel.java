package org.llm4people.hermes.panel;

import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure validation and naming helpers for Thread Power Actions (pin, branch/fork, transcript export). */
public final class ThreadActionModel {
    public static final String FORMAT_MARKDOWN = "markdown";
    public static final String FORMAT_JSON = "json";

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    public record TurnChoice(long messageId, String speakerLabel, String preview) {
        public TurnChoice {
            if (messageId <= 0) {
                throw new LocalizedInputException("invalid_message_id", R.string.ws_fork_err_message_id);
            }
            speakerLabel = speakerLabel == null ? "" : speakerLabel.trim();
            preview = preview == null ? "" : preview.trim();
        }

        public String displayLabel() {
            String snippet = PlainTextSanitizer.collapseWithEllipsis(preview, 80);
            return speakerLabel.isEmpty() ? snippet : speakerLabel + " · " + snippet;
        }
    }

    public record ExportSpec(String format, String mimeType, String extension, String suggestedFilename) {}

    private ThreadActionModel() {}

    public static String validateExportFormat(String rawFormat) {
        if (rawFormat == null) {
            throw new LocalizedInputException("export_format_missing", R.string.ws_export_err_format_missing);
        }
        String lower = rawFormat.trim().toLowerCase(Locale.ROOT);
        if (FORMAT_MARKDOWN.equals(lower) || FORMAT_JSON.equals(lower)) return lower;
        throw new LocalizedInputException("export_format_unsupported", R.string.ws_export_err_format_unsupported);
    }

    public static ExportSpec buildExportSpec(String threadTitle, String rawFormat) {
        String format = validateExportFormat(rawFormat);
        String mime = FORMAT_JSON.equals(format) ? "application/json" : "text/markdown";
        String ext = FORMAT_JSON.equals(format) ? ".json" : ".md";
        String base = slugifyTitle(threadTitle);
        return new ExportSpec(format, mime, ext, base + ext);
    }

    public static String slugifyTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.trim().isEmpty()) return "discussion-hermes";
        String folded = PlainTextSanitizer.foldAccents(rawTitle.trim());
        String slug = NON_SLUG.matcher(folded).replaceAll("-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) return "discussion-hermes";
        if (slug.length() > 48) slug = slug.substring(0, 48).replaceAll("-+$", "");
        return slug.isEmpty() ? "discussion-hermes" : slug;
    }

    public static Long validateForkMessageId(Long upToMessageId) {
        if (upToMessageId == null) return null;
        if (upToMessageId <= 0) {
            throw new LocalizedInputException("invalid_turn_id", R.string.ws_fork_err_turn_id);
        }
        return upToMessageId;
    }
}
