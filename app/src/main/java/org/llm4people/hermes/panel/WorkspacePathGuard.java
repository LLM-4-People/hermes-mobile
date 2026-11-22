package org.llm4people.hermes.panel;

import java.util.Locale;
import java.util.Set;

/** Client-side path validation, secret-file denylist, and bounds for read-only workspace exploration. */
public final class WorkspacePathGuard {
    public static final int DEFAULT_TREE_DEPTH = 2;
    public static final int MAX_TREE_DEPTH = 3;
    public static final int DEFAULT_TREE_LIMIT = 200;
    public static final int DEFAULT_FILE_LINES = 400;
    public static final int MAX_FILE_LINES = 500;
    public static final int MAX_FILE_CHARS = 256 * 1024;
    public static final int MAX_PATH_LENGTH = 512;

    private static final Set<String> SENSITIVE_FILENAMES = Set.of(
            ".env",
            ".netrc",
            ".npmrc",
            ".pypirc",
            "id_rsa",
            "id_ed25519",
            "id_ecdsa",
            "id_dsa",
            "credentials.json",
            "secrets.json",
            "secrets.yaml",
            "secrets.yml",
            "keystore.jks"
    );

    private static final Set<String> SENSITIVE_EXTENSIONS = Set.of(
            ".pem",
            ".key",
            ".p12",
            ".pfx",
            ".jks",
            ".kdbx"
    );

    private WorkspacePathGuard() {}

    /**
     * Validate and normalize a workspace-relative path.
     *
     * @param raw            raw relative path
     * @param allowEmptyRoot true for directory tree root, false when a concrete file is required
     * @return normalized slash-separated relative path (or "" when allowEmptyRoot and blank)
     * @throws IllegalArgumentException if traversal, control chars, absolute path, or sensitive file is detected
     */
    public static String normalizeRelativePath(String raw, boolean allowEmptyRoot) {
        if (raw == null) {
            if (allowEmptyRoot) return "";
            throw new IllegalArgumentException("Chemin de fichier manquant");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || ".".equals(trimmed)) {
            if (allowEmptyRoot) return "";
            throw new IllegalArgumentException("Chemin de fichier manquant");
        }
        if (trimmed.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("Chemin trop long");
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Chemin absolu ou séparateur invalide");
        }
        if (trimmed.contains("://") || (trimmed.length() >= 2 && trimmed.charAt(1) == ':')) {
            throw new IllegalArgumentException("Chemin non relatif");
        }
        String lowerRaw = trimmed.toLowerCase(Locale.ROOT);
        if (lowerRaw.contains("%2e") || lowerRaw.contains("%2f") || lowerRaw.contains("%5c")
                || lowerRaw.contains("%00")) {
            throw new IllegalArgumentException("Encodage de chemin interdit");
        }
        if (trimmed.codePoints().anyMatch(cp -> cp == 0 || Character.isISOControl(cp))) {
            throw new IllegalArgumentException("Caractères de contrôle interdits");
        }

        String[] parts = trimmed.split("/", -1);
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                // Allow a single trailing slash on directory browsing only.
                if (allowEmptyRoot && i == parts.length - 1 && normalized.length() > 0) continue;
                throw new IllegalArgumentException("Segment vide interdit");
            }
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Remontée de dossier interdite");
            }
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(part);
        }
        String clean = normalized.toString();
        if (isSensitivePath(clean)) {
            throw new IllegalArgumentException("Accès restreint à un fichier sensible");
        }
        return clean;
    }

    /** Returns true when the relative path targets credentials, keys, or git config. */
    public static boolean isSensitivePath(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) return false;
        String lower = normalizedPath.toLowerCase(Locale.ROOT);
        if (lower.equals(".git") || lower.startsWith(".git/") || lower.contains("/.git/")) return true;
        if (lower.startsWith(".ssh/") || lower.contains("/.ssh/")) return true;
        int slash = lower.lastIndexOf('/');
        String base = slash >= 0 ? lower.substring(slash + 1) : lower;
        if (SENSITIVE_FILENAMES.contains(base)) return true;
        if (base.startsWith(".env.") && !base.equals(".env.example") && !base.equals(".env.sample")
                && !base.equals(".env.template")) {
            return true;
        }
        for (String ext : SENSITIVE_EXTENSIONS) {
            if (base.endsWith(ext)) return true;
        }
        return false;
    }

    public static int clampDepth(int requested) {
        if (requested <= 0) return DEFAULT_TREE_DEPTH;
        return Math.min(requested, MAX_TREE_DEPTH);
    }

    public static int clampEndLine(int startLine, int requestedEndLine) {
        int safeStart = Math.max(1, startLine);
        int requested = requestedEndLine < safeStart ? safeStart + DEFAULT_FILE_LINES - 1 : requestedEndLine;
        return Math.min(requested, safeStart + MAX_FILE_LINES - 1);
    }

    /** Truncate oversized text safely without splitting a surrogate pair. */
    public static String boundContent(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_FILE_CHARS) return text;
        int cut = MAX_FILE_CHARS;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
        return text.substring(0, cut);
    }

    public static String parentDirectory(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) return "";
        int slash = normalizedPath.lastIndexOf('/');
        return slash <= 0 ? "" : normalizedPath.substring(0, slash);
    }

    public static String inferLanguage(String path, String serverLanguage) {
        if (serverLanguage != null && !serverLanguage.trim().isEmpty()) {
            return serverLanguage.trim().toLowerCase(Locale.ROOT);
        }
        if (path == null) return "text";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "kotlin";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".xml") || lower.endsWith(".html")) return "xml";
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "shell";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".diff") || lower.endsWith(".patch")) return "diff";
        return "text";
    }

    public static String gitStatusLabel(String status) {
        if (status == null || status.trim().isEmpty()) return "";
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "m", "modified" -> "Modifié";
            case "a", "added", "?", "??", "untracked" -> "Ajouté";
            case "d", "deleted" -> "Supprimé";
            case "r", "renamed" -> "Renommé";
            case "c", "copied" -> "Copié";
            case "u", "unmerged", "conflict" -> "Conflit";
            default -> "";
        };
    }
}
