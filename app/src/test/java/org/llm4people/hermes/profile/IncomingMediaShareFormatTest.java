package org.llm4people.hermes.profile;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IncomingMediaShareFormatTest {
    @Test
    public void resolveFileNameUsesTrimmedNameOrLocalizedFallback() {
        assertEquals("report.pdf", IncomingMediaShare.resolveFileName("report.pdf", "File"));
        assertEquals("photo.jpg", IncomingMediaShare.resolveFileName("  photo.jpg  ", "Fichier"));
        assertEquals("File", IncomingMediaShare.resolveFileName(null, "File"));
        assertEquals("File", IncomingMediaShare.resolveFileName("", "File"));
        assertEquals("Fichier", IncomingMediaShare.resolveFileName("   \t\n ", "Fichier"));
        assertEquals("", IncomingMediaShare.resolveFileName(null, null));
    }

    @Test
    public void mediaShareCameraAndDeepActionsUseLocalizedResources() throws Exception {
        Path root = repoRoot();

        String incomingShare = readUtf8(root.resolve(
                "app/src/main/java/org/llm4people/hermes/profile/IncomingMediaShare.java"));
        assertFalse("IncomingMediaShare must not hardcode French fallback", incomingShare.contains("\"Fichier\""));
        assertTrue(incomingShare.contains("R.string.share_default_file_name"));
        assertTrue(incomingShare.contains("AppLanguage.wrapContext(context)"));

        String camera = readUtf8(root.resolve(
                "app/src/main/java/org/llm4people/hermes/camera/ThreadCameraCapture.java"));
        assertFalse("ThreadCameraCapture must not hardcode French toast", camera.contains("Caméra indisponible"));
        assertTrue(camera.contains("R.string.camera_unavailable"));

        String deepActions = readUtf8(root.resolve(
                "app/src/main/java/org/llm4people/hermes/presence/DeepAndroidActions.java"));
        assertTrue("DeepAndroidActions must wrap app context with AppLanguage.wrapContext",
                deepActions.contains("AppLanguage.wrapContext(app)"));
    }

    private static String readUtf8(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve("app/src/main/java")) ? cwd : cwd.getParent();
    }
}
