package org.llm4people.hermes.panel;

import android.content.Context;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure domain model and validation for Hermes MEMORY.md and USER.md inspection and editing. */
public final class MemoryModel {
    public static final String TARGET_MEMORY = "memory";
    public static final String TARGET_USER = "user";
    public static final int DEFAULT_CHAR_LIMIT = 4000;

    public record Entry(String id, String text, boolean pinned) {
        public Entry {
            id = id == null ? "" : id.trim();
            text = text == null ? "" : text.trim();
        }
    }

    public record Bucket(String target, List<Entry> entries, int chars, int limit, boolean enabled) {
        public Bucket {
            target = validateTarget(target);
            entries = entries == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(entries));
            chars = Math.max(0, chars);
            limit = limit <= 0 ? DEFAULT_CHAR_LIMIT : limit;
        }

        public int remainingChars() {
            return Math.max(0, limit - chars);
        }

        public List<Entry> filter(String query) {
            if (query == null || query.trim().isEmpty()) return entries;
            String needle = PlainTextSanitizer.foldAccents(query.trim());
            List<Entry> matched = new ArrayList<>();
            for (Entry entry : entries) {
                if (PlainTextSanitizer.foldAccents(entry.text()).contains(needle)) matched.add(entry);
            }
            return Collections.unmodifiableList(matched);
        }
    }

    public record Snapshot(Bucket memory, Bucket user) {
        public Snapshot {
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(user, "user");
        }

        public Bucket bucket(String target) {
            return TARGET_USER.equals(validateTarget(target)) ? user : memory;
        }
    }

    public record DeletedEntry(String target, Entry entry) {
        public DeletedEntry {
            target = validateTarget(target);
            Objects.requireNonNull(entry, "entry");
        }
    }

    private MemoryModel() {}

    public static String validateTarget(String target) {
        if (TARGET_MEMORY.equals(target) || TARGET_USER.equals(target)) return target;
        throw new LocalizedInputException("invalid_memory_target", R.string.ws_memory_err_target);
    }

    public static String validateEntryId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new LocalizedInputException("missing_entry_id", R.string.ws_memory_err_id_missing);
        }
        String trimmed = id.trim();
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0 || trimmed.equals(".") || trimmed.equals("..")) {
            throw new LocalizedInputException("invalid_entry_id", R.string.ws_memory_err_id_invalid);
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new LocalizedInputException("invalid_entry_id", R.string.ws_memory_err_id_invalid);
        }
        return trimmed;
    }

    /** Validate text before POST/PATCH; rejects empty text, lone surrogates, control chars, or overflow. */
    public static String validateEntryText(String rawText, int maxChars) {
        if (rawText == null || rawText.trim().isEmpty()) {
            throw new LocalizedInputException("empty_entry_text", R.string.ws_memory_err_empty);
        }
        String cleaned = rawText.trim();
        if (hasLoneSurrogate(cleaned)) {
            throw new LocalizedInputException("invalid_surrogate", R.string.ws_memory_err_surrogate);
        }
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch < 32 && ch != '\n' && ch != '\t') {
                throw new LocalizedInputException("control_char", R.string.ws_memory_err_control);
            }
        }
        int effectiveLimit = maxChars <= 0 ? DEFAULT_CHAR_LIMIT : maxChars;
        if (cleaned.length() > effectiveLimit) {
            throw new LocalizedInputException("entry_too_long", R.string.ws_memory_err_limit, effectiveLimit);
        }
        return cleaned;
    }

    /** Resource ID for HTTP status codes returned by /api/memory endpoints. */
    public static int describeHttpErrorRes(int status, String code) {
        if (status == 409) return R.string.ws_memory_err_conflict;
        if (status == 400 || status == 422) {
            if (code != null && (code.contains("secret") || code.contains("threat") || code.contains("injection"))) {
                return R.string.ws_memory_err_secret;
            }
            if (code != null && code.contains("limit")) {
                return R.string.ws_memory_err_full;
            }
            return R.string.ws_memory_err_invalid;
        }
        if (status == 401 || status == 403) return R.string.ws_memory_err_denied;
        if (status == 404 || status == 405 || status == 501) return R.string.ws_memory_err_upgrade;
        return R.string.ws_http_rejected;
    }

    /** Localized error message for HTTP status codes returned by /api/memory endpoints. */
    public static String describeHttpError(Context context, int status, String code) {
        int resId = describeHttpErrorRes(status, code);
        return resId == R.string.ws_http_rejected ? context.getString(resId, status) : context.getString(resId);
    }

    public static String usageSummary(Context context, int chars, int limit) {
        int safeChars = Math.max(0, chars);
        int safeLimit = limit <= 0 ? DEFAULT_CHAR_LIMIT : limit;
        return context.getString(R.string.ws_memory_char_count, safeChars, safeLimit);
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
