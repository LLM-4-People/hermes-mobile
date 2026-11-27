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

        // Safe example env templates and non-secret dotfiles/names remain readable.
        assertFalse(WorkspacePathGuard.isSensitivePath(".env.example"));
        assertEquals(".env.example", WorkspacePathGuard.normalizeRelativePath(".env.example", false));
        assertFalse(WorkspacePathGuard.isSensitivePath(".gitignore"));
        assertEquals(".gitignore", WorkspacePathGuard.normalizeRelativePath(".gitignore", false));
        assertEquals("docs/ssh.md", WorkspacePathGuard.normalizeRelativePath("docs/ssh.md", false));
        assertEquals("sub/.gitattributes", WorkspacePathGuard.normalizeRelativePath("sub/.gitattributes", true));

        String[] rejected = {
                "", "   ", "/etc/passwd", "\\windows\\win.ini", "../secret.txt", "a/../b",
                "a//b", "a/./b", "C:secret.txt", "https://evil/x", "%2e%2e/x", "a%2fb",
                "file\u0000.txt", ".env", "config/.env.production", ".git", ".git/config",
                "sub/.git", "sub/.git/HEAD", ".ssh", ".ssh/id_rsa", "sub/.ssh", "sub/.ssh/config",
                "certs/server.pem", "private/app.key", "credentials.json"
        };
        for (String bad : rejected) {
            try {
                WorkspacePathGuard.normalizeRelativePath(bad, false);
                fail("Expected path rejection (allowEmptyRoot=false) for: " + bad);
            } catch (IllegalArgumentException expected) {
                // Path safety enforced.
            }
        }

        // Directory browsing (allowEmptyRoot=true) must also reject .git / .ssh segments with or without trailing slash.
        String[] rejectedDirs = {
                ".ssh", ".ssh/", "sub/.ssh", "sub/.ssh/", ".SSH/",
                ".git", ".git/", "sub/.git", "sub/.git/", "SUB/.GIT/"
        };
        for (String badDir : rejectedDirs) {
            assertTrue("Expected isSensitivePath=true for: " + badDir,
                    WorkspacePathGuard.isSensitivePath(badDir));
            try {
                WorkspacePathGuard.normalizeRelativePath(badDir, true);
                fail("Expected path rejection (allowEmptyRoot=true) for: " + badDir);
            } catch (IllegalArgumentException expected) {
                // Path safety enforced.
            }
            try {
                WorkspacePathGuard.normalizeRelativePath(badDir, false);
                fail("Expected path rejection (allowEmptyRoot=false) for: " + badDir);
            } catch (IllegalArgumentException expected) {
                // Path safety enforced.
            }
        }
    }

    @Test
    public void boundsLinesContentAndInfersLanguageAndGitStatus() {
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
