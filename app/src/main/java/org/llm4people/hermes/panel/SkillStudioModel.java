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
    /** Fork {@code agent/skill_utils.SKILL_PROMPT_DESC_LIMIT}: the narrowest skill-index width any profile accepts. */
    public static final int MAX_DESCRIPTION_CHARS = 60;
    /** Fork {@code tools/skill_manager_tool.VALID_NAME_RE} bounded by {@code MAX_NAME_LENGTH = 64}. */
    private static final Pattern VALID_SEGMENT = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern CLIP_TRAIL = Pattern.compile("[\\s.,;:\\-\\u2013\\u2014(/]+$");

    /** Normalized lowercase single-segment skill name plus optional category for Fork POST /v1/skills. */
    public record SkillLocation(String name, String category) {
        public SkillLocation {
            name = safe(name);
            category = safe(category);
        }
    }

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

    /** Validate and lowercase a new user skill name (single slug or category/slug), rejecting traversal or control chars. */
    public static String validateNewSkillName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("Choisissez un nom pour ce savoir-faire.");
        }
        String normalized = rawName.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/") || normalized.endsWith("/") || normalized.contains("//") || normalized.contains("\\")) {
            throw new IllegalArgumentException("Le nom contient un séparateur invalide.");
        }
        String[] parts = normalized.split("/", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("Utilisez au plus un dossier (ex. travail/relecture).");
        }
        for (String part : parts) {
            if (".".equals(part) || "..".equals(part) || !VALID_SEGMENT.matcher(part).matches()) {
                throw new IllegalArgumentException("Utilisez uniquement des lettres, chiffres, tirets ou points.");
            }
        }
        return normalized;
    }

    /**
     * Resolve user-entered name (`slug` or `category/slug`) and optional explicit category into a
     * lowercase single-segment `name` + `category` compatible with Fork `_validate_name()`.
     */
    public static SkillLocation resolveSkillLocation(String rawName, String rawCategory) {
        String normalized = validateNewSkillName(rawName);
        String explicitCat = validateCategory(rawCategory);
        int slash = normalized.indexOf('/');
        if (slash > 0) {
            String derivedCat = normalized.substring(0, slash);
            String slug = normalized.substring(slash + 1);
            return new SkillLocation(slug, explicitCat.isEmpty() ? derivedCat : explicitCat);
        }
        return new SkillLocation(normalized, explicitCat);
    }

    /** Optional category: lowercased single directory name accepted by Fork {@code _validate_category()}. */
    public static String validateCategory(String rawCategory) {
        String category = sanitizeFrontmatterValue(rawCategory).toLowerCase(Locale.ROOT);
        if (category.isEmpty()) return "";
        if (!VALID_SEGMENT.matcher(category).matches()) {
            throw new IllegalArgumentException(
                    "La catégorie doit être un seul mot : lettres minuscules sans accent, chiffres, tirets ou points.");
        }
        return category;
    }

    /** Optional description: must fit the Fork skill index, which rejects longer descriptions on create. */
    public static String validateDescription(String rawDescription) {
        String description = sanitizeFrontmatterValue(rawDescription);
        int length = description.codePointCount(0, description.length());
        if (length > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("La description doit tenir en " + MAX_DESCRIPTION_CHARS
                    + " caractères (actuellement " + length + "). Mettez les détails dans les instructions.");
        }
        return description;
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

    /**
     * Compose a clean SKILL.md document from friendly non-technical fields (no manual YAML needed).
     * Always emits a lowercase single-segment `name:` and non-empty `description:` required by Fork frontmatter validation.
     */
    public static String buildSkillDocument(String name, String description, String category, String instructions) {
        SkillLocation loc = resolveSkillLocation(name, category);
        String body = validateContent(instructions).trim();
        String cleanDesc = validateDescription(description);
        if (cleanDesc.isEmpty()) {
            cleanDesc = deriveDescription(body, loc.name());
        }

        StringBuilder doc = new StringBuilder();
        doc.append("---\n");
        doc.append("name: ").append(loc.name()).append('\n');
        doc.append("description: ").append(quoteYaml(cleanDesc)).append('\n');
        if (!loc.category().isEmpty()) doc.append("category: ").append(quoteYaml(loc.category())).append('\n');
        doc.append("---\n\n");
        doc.append(body).append('\n');
        return doc.toString();
    }

    private static String deriveDescription(String body, String fallbackName) {
        for (String line : body.split("\\r?\\n")) {
            String cleaned = line.trim().replaceFirst("^[#*\\->\\s]+", "").trim();
            if (!cleaned.isEmpty()) return clipDescription(cleaned);
        }
        return clipDescription(fallbackName);
    }

    /** Fits {@code text} into {@link #MAX_DESCRIPTION_CHARS} code points, cutting at a word boundary when possible. */
    static String clipDescription(String text) {
        String value = text == null ? "" : text.trim();
        if (value.codePointCount(0, value.length()) <= MAX_DESCRIPTION_CHARS) return value;
        int end = value.offsetByCodePoints(0, MAX_DESCRIPTION_CHARS);
        String head = value.substring(0, end);
        if (!Character.isWhitespace(value.codePointAt(end))) {
            int boundary = -1;
            for (int i = head.length() - 1; i > 0; i--) {
                if (Character.isWhitespace(head.charAt(i))) { boundary = i; break; }
            }
            if (boundary > 0) head = head.substring(0, boundary);
        }
        String clipped = CLIP_TRAIL.matcher(head).replaceAll("").trim();
        return clipped.isEmpty() ? head.trim() : clipped;
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
