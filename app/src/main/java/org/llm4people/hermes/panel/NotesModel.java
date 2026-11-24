package org.llm4people.hermes.panel;

import android.content.Context;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure SSoT model for Smart Notes and Habit-Adaptive Suggestions (count >= 3). */
public final class NotesModel {
    public static final int MAX_TITLE_CHARS = 160;
    public static final int MAX_BODY_CHARS = 64 * 1024;
    public static final int HABIT_MIN_REPETITIONS = 3;

    public enum FilterTab {
        MINE(R.string.brain_notes_tab_mine),
        SHARED(R.string.brain_notes_tab_shared);

        public final int labelRes;

        FilterTab(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    public record Note(
            String id,
            String title,
            String body,
            List<String> tags,
            boolean pinned,
            String updatedAt,
            String ownerProfile,
            String permission) {
        public Note {
            id = safe(id);
            title = safe(title);
            body = body == null ? "" : body.trim();
            tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tags));
            updatedAt = safe(updatedAt);
            ownerProfile = safe(ownerProfile);
            permission = safe(permission).toLowerCase(Locale.ROOT);
        }

        public boolean isOwner() {
            return "owner".equals(permission) || (permission.isEmpty() && ownerProfile.isEmpty());
        }

        public boolean canEdit() {
            return isOwner() || UniversalShareModel.PERM_EDIT.equals(permission);
        }

        /** Fork {@code tools/mobile_notes.py} rejects pin/archive from non-owners with HTTP 403 {@code note_owner_required}. */
        public boolean canPin() {
            return isOwner();
        }

        public boolean canDelete() {
            return isOwner();
        }

        public boolean canShare() {
            return isOwner();
        }

        public String displayTitle(Context context) {
            return title.isEmpty() ? context.getString(R.string.ws_note_untitled) : title;
        }
    }

    public record HabitSuggestion(
            String id,
            String label,
            int labelRes,
            String hint,
            String routeId,
            String draftText,
            int draftRes,
            int count) {
        public HabitSuggestion {
            id = safe(id);
            label = safe(label);
            hint = safe(hint);
            routeId = safe(routeId);
            draftText = safe(draftText);
            count = Math.max(0, count);
        }

        public HabitSuggestion(String id, String label, String hint, String routeId, String draftText, int count) {
            this(id, label, 0, hint, routeId, draftText, 0, count);
        }

        public boolean isLearned() {
            return count >= HABIT_MIN_REPETITIONS && (!label.isEmpty() || labelRes != 0);
        }

        public String displayLabel(Context context) {
            if (!label.isEmpty()) return label;
            return labelRes != 0 ? context.getString(labelRes) : "";
        }

        public String resolveDraft(Context context) {
            if (!draftText.isEmpty()) return draftText;
            if (draftRes != 0) return context.getString(draftRes);
            return displayLabel(context);
        }
    }

    private NotesModel() {}

    public static String validateTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.trim().isEmpty()) {
            throw new LocalizedInputException("empty_note_title", R.string.ws_note_err_title_empty);
        }
        String trimmed = rawTitle.trim();
        if (trimmed.length() > MAX_TITLE_CHARS) {
            throw new LocalizedInputException("note_title_too_long", R.string.ws_note_err_title_long, MAX_TITLE_CHARS);
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new LocalizedInputException("note_title_control_char", R.string.ws_note_err_title_invalid);
        }
        return trimmed;
    }

    public static String validateBody(String rawBody) {
        if (rawBody == null || rawBody.trim().isEmpty()) {
            throw new LocalizedInputException("empty_note_body", R.string.ws_note_err_body_empty);
        }
        String trimmed = rawBody.trim();
        if (trimmed.length() > MAX_BODY_CHARS) {
            throw new LocalizedInputException("note_body_too_long", R.string.ws_note_err_body_long);
        }
        return trimmed;
    }

    public static List<String> parseTagsInput(String rawCsv) {
        if (rawCsv == null || rawCsv.trim().isEmpty()) return Collections.emptyList();
        String[] parts = rawCsv.split(",");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part.trim();
            if (!cleaned.isEmpty() && cleaned.length() <= 32 && !out.contains(cleaned)) {
                out.add(cleaned);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static List<Note> filter(List<Note> notes, String query) {
        if (notes == null || notes.isEmpty()) return Collections.emptyList();
        if (query == null || query.trim().isEmpty()) {
            List<Note> clean = new ArrayList<>(notes.size());
            for (Note note : notes) {
                if (note != null) clean.add(note);
            }
            return Collections.unmodifiableList(clean);
        }
        String needle = fold(query.trim());
        List<Note> matched = new ArrayList<>();
        for (Note note : notes) {
            if (note == null) continue;
            if (fold(note.title() + " " + note.body() + " " + String.join(" ", note.tags())).contains(needle)) {
                matched.add(note);
            }
        }
        return Collections.unmodifiableList(matched);
    }

    /** Filters notes by ownership tab ({@link FilterTab#MINE} vs {@link FilterTab#SHARED}) and query. */
    public static List<Note> filterForTab(List<Note> notes, FilterTab tab, String query) {
        if (notes == null || notes.isEmpty()) return Collections.emptyList();
        FilterTab active = tab == null ? FilterTab.MINE : tab;
        List<Note> scoped = new ArrayList<>(notes.size());
        for (Note note : notes) {
            if (note == null) continue;
            if (active == FilterTab.MINE && note.isOwner()) {
                scoped.add(note);
            } else if (active == FilterTab.SHARED && !note.isOwner()) {
                scoped.add(note);
            }
        }
        return filter(scoped, query);
    }

    /** Returns the localized empty-state string resource for the active Notes filter tab. */
    public static int emptyMessageRes(FilterTab tab, boolean unfilteredEmpty) {
        if (!unfilteredEmpty) return R.string.brain_memory_empty_filter;
        return tab == FilterTab.SHARED ? R.string.brain_notes_shared_empty : R.string.brain_notes_empty;
    }

    /** Strictly enforce count >= 3 so unlearned guesses are NEVER surfaced. */
    public static List<HabitSuggestion> filterLearned(List<HabitSuggestion> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<HabitSuggestion> learned = new ArrayList<>();
        for (HabitSuggestion suggestion : raw) {
            if (suggestion != null && suggestion.isLearned()) learned.add(suggestion);
        }
        return Collections.unmodifiableList(learned);
    }

    public static String draftPromptForNote(Context context, Note note) {
        return context.getString(R.string.ws_note_draft_prompt, note.displayTitle(context));
    }

    /** Maps anonymized server habit (category, action) keys to localized string resources (0 when custom). */
    public static int friendlyHabitLabelRes(String category, String action) {
        String key = safe(action).toLowerCase(Locale.ROOT);
        if (key.isEmpty()) key = safe(category).toLowerCase(Locale.ROOT);
        return switch (key) {
            case "summarize", "resume", "summary" -> R.string.ws_habit_summarize;
            case "explain", "explain_photo", "explain_code" -> R.string.ws_habit_explain;
            case "save_note", "create_note", "note" -> R.string.ws_habit_save_note;
            case "extract_info", "extract" -> R.string.ws_habit_extract;
            case "diagnose", "debug", "review_code" -> R.string.ws_habit_diagnose;
            case "reply", "draft_reply" -> R.string.ws_habit_reply;
            case "calendar", "schedule", "reminder" -> R.string.ws_habit_calendar;
            case "nearby", "places", "find_place" -> R.string.ws_habit_nearby;
            default -> 0;
        };
    }

    public static String fallbackHabitKey(String category, String action) {
        String key = safe(action).toLowerCase(Locale.ROOT);
        if (key.isEmpty()) key = safe(category).toLowerCase(Locale.ROOT);
        return key.isEmpty() ? "" : Character.toUpperCase(key.charAt(0)) + key.substring(1).replace('_', ' ');
    }

    public static int friendlyHabitDraftRes(String category, String action) {
        String key = safe(action).toLowerCase(Locale.ROOT);
        return switch (key) {
            case "summarize", "resume", "summary" -> R.string.ws_habit_draft_summarize;
            case "explain", "explain_photo", "explain_code" -> R.string.ws_habit_draft_explain;
            case "save_note", "create_note", "note" -> R.string.ws_habit_draft_save_note;
            case "extract_info", "extract" -> R.string.ws_habit_draft_extract;
            case "diagnose", "debug", "review_code" -> R.string.ws_habit_draft_diagnose;
            case "reply", "draft_reply" -> R.string.ws_habit_draft_reply;
            case "calendar", "schedule", "reminder" -> R.string.ws_habit_draft_calendar;
            default -> friendlyHabitLabelRes(category, action);
        };
    }

    private static String fold(String value) {
        return PlainTextSanitizer.foldAccents(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
