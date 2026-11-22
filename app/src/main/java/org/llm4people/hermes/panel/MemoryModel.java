package org.llm4people.hermes.panel;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Pure domain model and validation for Hermes MEMORY.md and USER.md inspection and editing. */
public final class MemoryModel {
    public static final String TARGET_MEMORY = "memory";
    public static final String TARGET_USER = "user";
    public static final int DEFAULT_CHAR_LIMIT = 4000;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

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
            String needle = fold(query.trim());
            List<Entry> matched = new ArrayList<>();
            for (Entry entry : entries) {
                if (fold(entry.text()).contains(needle)) matched.add(entry);
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
        throw new IllegalArgumentException("Cible mémoire invalide");
    }

    public static String validateEntryId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Identifiant d’entrée manquant");
        }
        String trimmed = id.trim();
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0 || trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException("Identifiant d’entrée invalide");
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Identifiant d’entrée invalide");
        }
        return trimmed;
    }

    /** Validate text before POST/PATCH; rejects empty text, lone surrogates, control chars, or overflow. */
    public static String validateEntryText(String rawText, int maxChars) {
        if (rawText == null || rawText.trim().isEmpty()) {
            throw new IllegalArgumentException("Le texte ne peut pas être vide.");
        }
        String cleaned = rawText.trim();
        if (hasLoneSurrogate(cleaned)) {
            throw new IllegalArgumentException("Le texte contient des caractères invalides.");
        }
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch < 32 && ch != '\n' && ch != '\t') {
                throw new IllegalArgumentException("Le texte contient des caractères de contrôle interdits.");
            }
        }
        int effectiveLimit = maxChars <= 0 ? DEFAULT_CHAR_LIMIT : maxChars;
        if (cleaned.length() > effectiveLimit) {
            throw new IllegalArgumentException("Le texte dépasse la limite autorisée (" + effectiveLimit + " caractères).");
        }
        return cleaned;
    }

    /** Plain-French error message for HTTP status codes returned by /api/memory endpoints. */
    public static String describeHttpError(int status, String code) {
        if (status == 409) {
            return "Cet élément a été modifié sur un autre appareil. La liste va être actualisée.";
        }
        if (status == 400 || status == 422) {
            if (code != null && (code.contains("secret") || code.contains("threat") || code.contains("injection"))) {
                return "Enregistrement refusé : le texte semble contenir une donnée sensible ou un format non autorisé.";
            }
            if (code != null && code.contains("limit")) {
                return "La mémoire est pleine. Supprimez ou raccourcissez un élément existant avant d’ajouter celui-ci.";
            }
            return "Le texte n’a pas pu être enregistré. Vérifiez sa longueur et son contenu.";
        }
        if (status == 401 || status == 403) {
            return "Ce profil n’a pas l’autorisation de modifier la mémoire.";
        }
        if (status == 404 || status == 405 || status == 501) {
            return "La gestion de la mémoire nécessite une mise à jour de votre serveur Hermes.";
        }
        return "Hermes a refusé la demande (HTTP " + status + ").";
    }

    public static String usageSummary(int chars, int limit) {
        int safeChars = Math.max(0, chars);
        int safeLimit = limit <= 0 ? DEFAULT_CHAR_LIMIT : limit;
        return safeChars + " / " + safeLimit + " caractères";
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

    private static String fold(String value) {
        return MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }
}
