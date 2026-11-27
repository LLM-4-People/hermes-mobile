package org.llm4people.hermes.templates;

import android.content.Context;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;
import org.llm4people.hermes.SecretPatterns;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable reusable everyday workflow template.
 * Strictly follows The Editable Draft Rule: every template stages editable composer text and never auto-sends.
 */
public final class WorkflowTemplate {
    public static final int MAX_TITLE_CHARS = 64;
    public static final int MAX_SUBTITLE_CHARS = 120;
    public static final int MAX_DRAFT_CHARS = 2000;
    public static final int MAX_CUSTOM_TEMPLATES = 24;
    public static final int MAX_STORAGE_CHARS = 64 * 1024;

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    public static final class SecretRejectedException extends IOException {
        public SecretRejectedException() {
            super("Template contains credential-like secret material");
        }
    }

    public final String id;
    public final String title;
    public final String subtitle;
    public final String draft;
    public final boolean builtIn;

    public WorkflowTemplate(String id, String title, String subtitle, String draft, boolean builtIn) throws IOException {
        this.id = validateId(id);
        this.title = validateSingleLine(title, MAX_TITLE_CHARS, true);
        this.subtitle = validateSingleLine(subtitle, MAX_SUBTITLE_CHARS, false);
        this.draft = validateDraft(draft);
        this.builtIn = builtIn;
    }

    public static boolean validId(String raw) {
        return raw != null && ID_PATTERN.matcher(raw).matches();
    }

    public static String validateId(String raw) throws IOException {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IOException("Invalid template id");
        }
        return id;
    }

    static String validateSingleLine(String raw, int maxChars, boolean required) throws IOException {
        if (raw == null) {
            if (required) throw new IOException("Required template text missing");
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            if (required) throw new IOException("Required template text blank");
            return "";
        }
        if (trimmed.length() > maxChars) {
            throw new IOException("Template text exceeds limit");
        }
        if (PlainTextSanitizer.hasUnsafeSingleLineCodePoint(trimmed)) {
            throw new IOException("Control character rejected in template metadata");
        }
        if (!PlainTextSanitizer.hasVisibleText(trimmed)) {
            if (required) throw new IOException("Required template text blank");
            return "";
        }
        if (SecretPatterns.containsCredential(trimmed)) throw new SecretRejectedException();
        return trimmed;
    }

    static String validateDraft(String raw) throws IOException {
        if (raw == null) throw new IOException("Template draft missing");
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (!PlainTextSanitizer.hasVisibleText(normalized)) throw new IOException("Template draft blank");
        if (normalized.length() > MAX_DRAFT_CHARS) throw new IOException("Template draft exceeds limit");
        if (SecretPatterns.containsCredential(normalized)) throw new SecretRejectedException();
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            int type = Character.getType(cp);
            if ((Character.isISOControl(cp) && cp != '\n' && cp != '\t')
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || PlainTextSanitizer.isStrippableInvisible(cp)
                    || (type == Character.FORMAT && cp != 0x200C && cp != 0x200D)) {
                throw new IOException("Invalid control character in template draft");
            }
            i += Character.charCount(cp);
        }
        return normalized;
    }

    /** 6 curated everyday bilingual zero-jargon workflows. */
    public static List<WorkflowTemplate> builtIn(Context context) {
        Objects.requireNonNull(context, "context");
        List<WorkflowTemplate> out = new ArrayList<>(6);
        try {
            out.add(new WorkflowTemplate(
                    "everyday_photo_receipt",
                    context.getString(R.string.tpl_photo_title),
                    context.getString(R.string.tpl_photo_subtitle),
                    context.getString(R.string.tpl_photo_draft),
                    true));
            out.add(new WorkflowTemplate(
                    "everyday_polite_reply",
                    context.getString(R.string.tpl_reply_title),
                    context.getString(R.string.tpl_reply_subtitle),
                    context.getString(R.string.tpl_reply_draft),
                    true));
            out.add(new WorkflowTemplate(
                    "everyday_plan_day",
                    context.getString(R.string.tpl_plan_day_title),
                    context.getString(R.string.tpl_plan_day_subtitle),
                    context.getString(R.string.tpl_plan_day_draft),
                    true));
            out.add(new WorkflowTemplate(
                    "everyday_explain_code",
                    context.getString(R.string.tpl_code_title),
                    context.getString(R.string.tpl_code_subtitle),
                    context.getString(R.string.tpl_code_draft),
                    true));
            out.add(new WorkflowTemplate(
                    "everyday_nearby_places",
                    context.getString(R.string.tpl_nearby_title),
                    context.getString(R.string.tpl_nearby_subtitle),
                    context.getString(R.string.tpl_nearby_draft),
                    true));
            out.add(new WorkflowTemplate(
                    "everyday_home_widget",
                    context.getString(R.string.tpl_widget_title),
                    context.getString(R.string.tpl_widget_subtitle),
                    context.getString(R.string.tpl_widget_draft),
                    true));
        } catch (IOException impossible) {
            throw new IllegalStateException("Built-in workflow template invalid", impossible);
        }
        return Collections.unmodifiableList(out);
    }

    /** Deterministic pure-Java wire encoding (no Android JSON dependency; safe on host JVM). */
    public static String encodeList(List<WorkflowTemplate> templates) throws IOException {
        if (templates == null || templates.isEmpty()) return "v1\n";
        if (templates.size() > MAX_CUSTOM_TEMPLATES) {
            throw new IOException("Too many custom templates");
        }
        StringBuilder sb = new StringBuilder("v1\n");
        for (WorkflowTemplate t : templates) {
            if (t == null) continue;
            sb.append(escape(t.id)).append('\t')
                    .append(escape(t.title)).append('\t')
                    .append(escape(t.subtitle)).append('\t')
                    .append(escape(t.draft)).append('\n');
        }
        if (sb.length() > MAX_STORAGE_CHARS) {
            throw new IOException("Encoded templates exceed storage budget");
        }
        return sb.toString();
    }

    public static List<WorkflowTemplate> decodeList(String raw) throws IOException {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        if (raw.length() > MAX_STORAGE_CHARS) throw new IOException("Template payload oversized");
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !"v1".equals(lines[0])) {
            throw new IOException("Unsupported template storage format");
        }
        List<WorkflowTemplate> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (out.size() >= MAX_CUSTOM_TEMPLATES) throw new IOException("Too many stored templates");
            String[] parts = line.split("\t", -1);
            if (parts.length != 4) throw new IOException("Malformed template row");
            out.add(new WorkflowTemplate(
                    unescape(parts[0]),
                    unescape(parts[1]),
                    unescape(parts[2]),
                    unescape(parts[3]),
                    false));
        }
        return Collections.unmodifiableList(out);
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String text) throws IOException {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 >= text.length()) throw new IOException("Truncated escape sequence");
                char next = text.charAt(++i);
                switch (next) {
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> throw new IOException("Unknown escape sequence");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof WorkflowTemplate other)) return false;
        return builtIn == other.builtIn
                && id.equals(other.id)
                && title.equals(other.title)
                && subtitle.equals(other.subtitle)
                && draft.equals(other.draft);
    }

    @Override public int hashCode() {
        return Objects.hash(id, title, subtitle, draft, builtIn);
    }
}
