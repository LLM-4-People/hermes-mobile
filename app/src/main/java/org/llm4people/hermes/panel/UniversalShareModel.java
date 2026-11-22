package org.llm4people.hermes.panel;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure SSoT model and ACL validation for Universal Same-Server Sharing (/api/shares). */
public final class UniversalShareModel {
    public static final String KIND_NOTE = "note";
    public static final String KIND_SURFACE = "surface";
    public static final String KIND_WIDGET = "widget";
    public static final String KIND_TOOL = "tool";
    public static final String KIND_SKILL = "skill";
    public static final String KIND_ARTIFACT = "artifact";
    public static final String KIND_SESSION = "session";

    public static final String PERM_READ = "read";
    public static final String PERM_EDIT = "edit";
    public static final String PERM_CLONE = "clone";

    private static final Set<String> ALLOWED_KINDS = Set.of(
            KIND_NOTE, KIND_SURFACE, KIND_WIDGET, KIND_TOOL, KIND_SKILL, KIND_ARTIFACT, KIND_SESSION);
    private static final Set<String> ALLOWED_PERMS = Set.of(PERM_READ, PERM_EDIT, PERM_CLONE);
    private static final Pattern VALID_PROFILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public record ShareGrant(
            String shareId,
            String resourceKind,
            String resourceId,
            String label,
            String ownerProfile,
            String targetProfile,
            String permission,
            String createdAt) {
        public ShareGrant {
            shareId = safe(shareId);
            resourceKind = safe(resourceKind).toLowerCase(Locale.ROOT);
            resourceId = safe(resourceId);
            label = safe(label);
            ownerProfile = safe(ownerProfile);
            targetProfile = safe(targetProfile);
            permission = safe(permission).toLowerCase(Locale.ROOT);
            createdAt = safe(createdAt);
        }

        public String displayLabel() {
            return label.isEmpty() ? resourceId : label;
        }

        public boolean canEdit() {
            return PERM_EDIT.equals(permission);
        }

        public boolean canClone() {
            return PERM_CLONE.equals(permission);
        }
    }

    private UniversalShareModel() {}

    public static String validateKind(String rawKind) {
        if (rawKind == null) throw new IllegalArgumentException("Type d’élément manquant.");
        String normalized = rawKind.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_KINDS.contains(normalized)) {
            throw new IllegalArgumentException("Type d’élément non pris en charge pour le partage.");
        }
        return normalized;
    }

    public static String validatePermission(String rawPermission) {
        if (rawPermission == null) throw new IllegalArgumentException("Niveau d’accès manquant.");
        String normalized = rawPermission.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PERMS.contains(normalized)) {
            throw new IllegalArgumentException("Niveau d’accès invalide.");
        }
        return normalized;
    }

    public static String validateTargetProfile(String rawProfile) {
        if (rawProfile == null || rawProfile.trim().isEmpty()) {
            throw new IllegalArgumentException("Indiquez le nom du profil destinataire sur ce serveur.");
        }
        String trimmed = rawProfile.trim();
        if (!VALID_PROFILE.matcher(trimmed).matches() || ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException("Nom de profil invalide (lettres, chiffres, tirets ou points).");
        }
        return trimmed;
    }

    public static String validateResourceId(String rawId) {
        if (rawId == null || rawId.trim().isEmpty()) {
            throw new IllegalArgumentException("Identifiant d’élément manquant.");
        }
        String trimmed = rawId.trim();
        if (trimmed.contains("..") || trimmed.indexOf('\\') >= 0
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Identifiant d’élément invalide.");
        }
        return trimmed;
    }

    public static String validateShareId(String rawShareId) {
        if (rawShareId == null || rawShareId.trim().isEmpty()) {
            throw new IllegalArgumentException("Identifiant de partage manquant.");
        }
        String trimmed = rawShareId.trim();
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0
                || ".".equals(trimmed) || "..".equals(trimmed)
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Identifiant de partage invalide.");
        }
        return trimmed;
    }

    public static String kindLabel(String kind) {
        if (kind == null) return "Élément";
        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case KIND_NOTE -> "Note";
            case KIND_SKILL -> "Savoir-faire";
            case KIND_SURFACE -> "Écran";
            case KIND_WIDGET -> "Widget";
            case KIND_TOOL -> "Outil";
            case KIND_ARTIFACT -> "Artefact";
            case KIND_SESSION -> "Discussion";
            default -> "Élément";
        };
    }

    public static String permissionLabel(String permission) {
        if (permission == null) return "Lecture seule";
        return switch (permission.trim().toLowerCase(Locale.ROOT)) {
            case PERM_EDIT -> "Modification";
            case PERM_CLONE -> "Copie";
            default -> "Lecture seule";
        };
    }

    public static String describeHttpError(int status, String code) {
        if (status == 404) {
            if (code != null && code.contains("profile")) {
                return "Ce profil est introuvable sur ce serveur.";
            }
            return "Cet élément partagé n’est plus accessible ou a été retiré.";
        }
        if (status == 403) {
            return "Vous n’avez pas l’autorisation de modifier ou partager cet élément.";
        }
        if (status == 409) {
            return "Cet élément est déjà partagé avec ce profil.";
        }
        if (status == 400 || status == 422) {
            return "Vérifiez le nom du profil destinataire et le niveau d’accès choisi.";
        }
        if (status == 405 || status == 501) {
            return "Le partage sur ce serveur nécessite une mise à jour de Hermes.";
        }
        return "Hermes a refusé la demande (HTTP " + status + ").";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
