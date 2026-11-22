package org.llm4people.hermes.panel;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure SSoT model for Smart Notes ("Notes intelligentes") and Habit-Adaptive Suggestions (count >= 3). */
public final class NotesModel {
    public static final int MAX_TITLE_CHARS = 160;
    public static final int MAX_BODY_CHARS = 64 * 1024;
    public static final int HABIT_MIN_REPETITIONS = 3;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

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
            return ownerProfile.isEmpty() || "owner".equals(permission) || permission.isEmpty();
        }

        public boolean canEdit() {
            return isOwner() || UniversalShareModel.PERM_EDIT.equals(permission);
        }

        public boolean canDelete() {
            return isOwner();
        }

        public boolean canShare() {
            return isOwner();
        }

        public String displayTitle() {
            return title.isEmpty() ? "Note sans titre" : title;
        }
    }

    public record HabitSuggestion(
            String id,
            String label,
            String hint,
            String routeId,
            String draftText,
            int count) {
        public HabitSuggestion {
            id = safe(id);
            label = safe(label);
            hint = safe(hint);
            routeId = safe(routeId);
            draftText = safe(draftText);
            count = Math.max(0, count);
        }

        public boolean isLearned() {
            return count >= HABIT_MIN_REPETITIONS && !label.isEmpty();
        }
    }

    private NotesModel() {}

    public static String validateTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.trim().isEmpty()) {
            throw new IllegalArgumentException("Donnez un titre à cette note.");
        }
        String trimmed = rawTitle.trim();
        if (trimmed.length() > MAX_TITLE_CHARS) {
            throw new IllegalArgumentException("Le titre est trop long (maximum " + MAX_TITLE_CHARS + " caractères).");
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Le titre contient des caractères interdits.");
        }
        return trimmed;
    }

    public static String validateBody(String rawBody) {
        if (rawBody == null || rawBody.trim().isEmpty()) {
            throw new IllegalArgumentException("Écrivez le contenu de la note avant d’enregistrer.");
        }
        String trimmed = rawBody.trim();
        if (trimmed.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("La note dépasse la taille maximale autorisée.");
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
        if (query == null || query.trim().isEmpty()) return new ArrayList<>(notes);
        String needle = fold(query.trim());
        List<Note> matched = new ArrayList<>();
        for (Note note : notes) {
            if (fold(note.title() + " " + note.body() + " " + String.join(" ", note.tags())).contains(needle)) {
                matched.add(note);
            }
        }
        return Collections.unmodifiableList(matched);
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

    public static String draftPromptForNote(Note note) {
        return "Appuie-toi sur la note « " + note.displayTitle() + " » pour cette demande.";
    }

    /** Convert anonymized server habit (category, action) keys into warm plain-French labels. */
    public static String friendlyHabitLabel(String category, String action) {
        String key = safe(action).toLowerCase(Locale.ROOT);
        if (key.isEmpty()) key = safe(category).toLowerCase(Locale.ROOT);
        return switch (key) {
            case "summarize", "resume", "summary" -> "Résumer rapidement";
            case "explain", "explain_photo", "explain_code" -> "Expliquer clairement";
            case "save_note", "create_note", "note" -> "Enregistrer en note";
            case "extract_info", "extract" -> "Extraire les infos clés";
            case "diagnose", "debug", "review_code" -> "Diagnostiquer un problème";
            case "reply", "draft_reply" -> "Rédiger une réponse";
            case "calendar", "schedule", "reminder" -> "Planifier un rappel";
            case "nearby", "places", "find_place" -> "Trouver un lieu proche";
            default -> key.isEmpty() ? "" : Character.toUpperCase(key.charAt(0)) + key.substring(1).replace('_', ' ');
        };
    }

    public static String friendlyHabitDraft(String category, String action) {
        String key = safe(action).toLowerCase(Locale.ROOT);
        return switch (key) {
            case "summarize", "resume", "summary" -> "Peux-tu me faire un résumé clair et structuré ?";
            case "explain", "explain_photo", "explain_code" -> "Peux-tu m’expliquer cela simplement ?";
            case "save_note", "create_note", "note" -> "Crée une note bien organisée à partir de ceci.";
            case "extract_info", "extract" -> "Extrais les informations essentielles de ceci.";
            case "diagnose", "debug", "review_code" -> "Aide-moi à diagnostiquer et corriger ce problème.";
            case "reply", "draft_reply" -> "Propose-moi un brouillon de réponse claire.";
            case "calendar", "schedule", "reminder" -> "Aide-moi à planifier ceci dans mon agenda.";
            default -> friendlyHabitLabel(category, action);
        };
    }

    private static String fold(String value) {
        return MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
