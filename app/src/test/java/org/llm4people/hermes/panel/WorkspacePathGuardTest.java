package org.llm4people.hermes.panel;

import org.junit.Test;
import org.llm4people.hermes.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class WorkspacePathGuardTest {
    @Test
    public void normalizesSafePathsAndBlocksTraversalAndSecrets() {
        assertEquals("", WorkspacePathGuard.normalizeRelativePath("", true));
        assertEquals("", WorkspacePathGuard.normalizeRelativePath(".", true));
        assertEquals("app/src/Main.java", WorkspacePathGuard.normalizeRelativePath("app/src/Main.java", false));
        assertEquals("app/src", WorkspacePathGuard.normalizeRelativePath("app/src/", true));
        assertEquals("app", WorkspacePathGuard.parentDirectory("app/src"));
        assertEquals("", WorkspacePathGuard.parentDirectory("app"));

        // Safe example env templates remain readable.
        assertFalse(WorkspacePathGuard.isSensitivePath(".env.example"));
        assertEquals(".env.example", WorkspacePathGuard.normalizeRelativePath(".env.example", false));

        String[] rejected = {
                "", "   ", "/etc/passwd", "\\windows\\win.ini", "../secret.txt", "a/../b",
                "a//b", "a/./b", "C:secret.txt", "https://evil/x", "%2e%2e/x", "a%2fb",
                "file\u0000.txt", ".env", "config/.env.production", ".git/config",
                ".ssh/id_rsa", "certs/server.pem", "private/app.key", "credentials.json"
        };
        for (String bad : rejected) {
            try {
                WorkspacePathGuard.normalizeRelativePath(bad, false);
                fail("Expected path rejection for: " + bad);
            } catch (IllegalArgumentException expected) {
                // Path safety enforced.
            }
        }
    }

    @Test
    public void boundsDepthLinesContentAndInfersLanguageAndGitStatus() {
        assertEquals(WorkspacePathGuard.DEFAULT_TREE_DEPTH, WorkspacePathGuard.clampDepth(0));
        assertEquals(WorkspacePathGuard.MAX_TREE_DEPTH, WorkspacePathGuard.clampDepth(99));
        assertEquals(500, WorkspacePathGuard.clampEndLine(1, 10000));
        assertEquals(510, WorkspacePathGuard.clampEndLine(11, 900));

        // Surrogate pair boundary must never be split.
        String prefix = "a".repeat(WorkspacePathGuard.MAX_FILE_CHARS - 1);
        String withEmoji = prefix + "\uD83D\uDE00tail";
        String bounded = WorkspacePathGuard.boundContent(withEmoji);
        assertEquals(WorkspacePathGuard.MAX_FILE_CHARS - 1, bounded.length());
        assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)));

        assertEquals("java", WorkspacePathGuard.inferLanguage("Main.java", ""));
        assertEquals("python", WorkspacePathGuard.inferLanguage("tool.py", null));
        assertEquals("yaml", WorkspacePathGuard.inferLanguage("file.txt", "YAML"));
        assertEquals(R.string.ws_git_modified, WorkspacePathGuard.gitStatusLabelRes("M"));
        assertEquals(R.string.ws_git_added, WorkspacePathGuard.gitStatusLabelRes("??"));
        assertEquals(R.string.ws_git_deleted, WorkspacePathGuard.gitStatusLabelRes("deleted"));
    }
}
