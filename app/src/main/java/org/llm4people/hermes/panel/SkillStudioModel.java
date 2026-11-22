package org.llm4people.hermes.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Pure domain rules, SKILL.md composer, and optimistic toggle rollback state for Skill Studio. */
public final class SkillStudioModel {
    public static final int MAX_SKILL_CONTENT_CHARS = 128 * 1024;
    private static final Pattern VALID_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public record SkillDetail(
            String name,
            String category,
            String description,
            List<String> tags,
            String body,
            String raw,
            boolean enabled,
            boolean writable,
            String source,
            List<String> supportingFiles,
            List<String> lintWarnings) {
        public SkillDetail {
            name = safe(name);
            category = safe(category);
            description = safe(description);
            tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tags));
            body = body == null ? "" : body;
            raw = raw == null ? "" : raw;
            source = safe(source);
            supportingFiles = supportingFiles == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(supportingFiles));
            lintWarnings = lintWarnings == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(lintWarnings));
        }
    }

    /** Tracks optimistic skill enable/disable toggles and performs deterministic rollback on server failure. */
    public static final class ToggleState {
        private final Map<String, Boolean> enabledByName = new LinkedHashMap<>();

        public void sync(String name, boolean enabled) {
            if (name != null && !name.isEmpty()) enabledByName.put(name, enabled);
        }

        public boolean isEnabled(String name, boolean defaultValue) {
            return enabledByName.getOrDefault(name, defaultValue);
        }

        /** Apply an optimistic toggle and return the previous state for rollback. */
        public boolean beginToggle(String name, boolean targetEnabled) {
            boolean previous = isEnabled(name, !targetEnabled);
            enabledByName.put(name, targetEnabled);
            return previous;
        }

        /** Restore previous state when PUT /v1/skills/{name}/enabled fails. */
        public void rollback(String name, boolean previousEnabled) {
            if (name != null && !name.isEmpty()) enabledByName.put(name, previousEnabled);
        }
    }

    private SkillStudioModel() {}

    /** Validate a new user skill name (single slug or category/slug), rejecting traversal or control chars. */
    public static String validateNewSkillName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("Choisissez un nom pour ce savoir-faire.");
        }
        String trimmed = rawName.trim();
        if (trimmed.startsWith("/") || trimmed.endsWith("/") || trimmed.contains("//") || trimmed.contains("\\")) {
            throw new IllegalArgumentException("Le nom contient un séparateur invalide.");
        }
        String[] parts = trimmed.split("/", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("Utilisez au plus un dossier (ex. travail/relecture).");
        }
        for (String part : parts) {
            if (".".equals(part) || "..".equals(part) || !VALID_SEGMENT.matcher(part).matches()) {
                throw new IllegalArgumentException("Utilisez uniquement des lettres, chiffres, tirets ou points.");
            }
        }
        return trimmed;
    }

    /** Validate raw SKILL.md content before creating or saving. */
    public static String validateContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Ajoutez des instructions avant d’enregistrer.");
        }
        if (content.length() > MAX_SKILL_CONTENT_CHARS) {
            throw new IllegalArgumentException("Le texte est trop volumineux pour un savoir-faire.");
        }
        return content;
    }

    /** Compose a clean SKILL.md document from friendly non-technical fields (no manual YAML needed). */
    public static String buildSkillDocument(String name, String description, String category, String instructions) {
        String validName = validateNewSkillName(name);
        String body = validateContent(instructions).trim();
        String cleanDesc = sanitizeFrontmatterValue(description);
        String cleanCat = sanitizeFrontmatterValue(category);

        StringBuilder doc = new StringBuilder();
        doc.append("---\n");
        doc.append("name: ").append(validName).append('\n');
        if (!cleanDesc.isEmpty()) doc.append("description: ").append(quoteYaml(cleanDesc)).append('\n');
        if (!cleanCat.isEmpty()) doc.append("category: ").append(quoteYaml(cleanCat)).append('\n');
        doc.append("---\n\n");
        doc.append(body).append('\n');
        return doc.toString();
    }

    public static String draftPromptForSkill(String skillName) {
        return "Utilise le savoir-faire « " + safe(skillName) + " » pour cette demande.";
    }

    public static String draftPromptToCreateSkill() {
        return "Aide-moi à créer un nouveau savoir-faire réutilisable pour mes prochaines discussions.";
    }

    public static String sourceLabel(String source, boolean writable) {
        if (source == null || source.trim().isEmpty()) {
            return writable ? "Personnel" : "Intégré";
        }
        return switch (source.trim().toLowerCase(Locale.ROOT)) {
            case "user", "local", "profile" -> "Personnel";
            case "builtin", "bundled", "core" -> "Intégré";
            case "plugin", "extension" -> "Extension";
            default -> writable ? "Personnel" : "Intégré";
        };
    }

    private static String sanitizeFrontmatterValue(String raw) {
        if (raw == null) return "";
        return raw.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String quoteYaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
