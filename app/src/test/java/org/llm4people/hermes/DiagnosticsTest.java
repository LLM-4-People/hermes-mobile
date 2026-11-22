package org.llm4people.hermes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DiagnosticsTest {
    private final List<String> captured = new ArrayList<>();

    @Before
    public void setUp() {
        Diagnostics.clear();
        Diagnostics.setCapacity(HermesConfig.DEFAULT_DIAGNOSTICS_RING_CAPACITY);
        Diagnostics.setDebugEnabled(false);
        Diagnostics.setSink((level, tag, message) -> captured.add(level.name() + ":" + tag + ":" + message));
    }

    @After
    public void tearDown() {
        Diagnostics.clear();
        Diagnostics.setCapacity(HermesConfig.DEFAULT_DIAGNOSTICS_RING_CAPACITY);
        Diagnostics.setDebugEnabled(false);
        Diagnostics.setSink(null);
    }

    @Test
    public void ringBufferEnforcesCapacityAndRedactsSensitiveReasons() {
        Diagnostics.setCapacity(3);
        Diagnostics.event("network", "ok");
        Diagnostics.warning("profile", "unlock_failed", new IllegalStateException("secret=Bearer_XYZ https://private"));
        Diagnostics.error("share", "rejected", "contains spaces\nand\nsecret");
        Diagnostics.event("files", "exported", "status_200");

        List<Diagnostics.Entry> entries = Diagnostics.snapshot();
        assertEquals(3, entries.size());
        assertEquals("profile.unlock_failed reason=IllegalStateException", entries.get(0).message());
        assertEquals("share.rejected reason=redacted", entries.get(1).message());
        assertEquals("files.exported reason=status_200", entries.get(2).message());

        String exported = Diagnostics.exportRedacted();
        assertFalse(exported.contains("Bearer_XYZ"));
        assertFalse(exported.contains("https://private"));
        assertTrue(exported.contains("profile.unlock_failed reason=IllegalStateException"));
    }

    @Test
    public void debugRespectsToggleAndRejectsNonPositiveCapacity() {
        Diagnostics.debug("threads", "ignored_when_off");
        assertTrue(Diagnostics.snapshot().isEmpty());

        Diagnostics.setDebugEnabled(true);
        Diagnostics.debug("threads", "stream_interrupted", "IOException");
        assertEquals(1, Diagnostics.snapshot().size());
        assertEquals(Diagnostics.Level.DEBUG, Diagnostics.snapshot().get(0).level());

        try {
            Diagnostics.setCapacity(0);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
    }

    @Test
    public void productionSourcesOnlyCallAndroidLogInsideDiagnostics() throws IOException {
        Path mainJava = Path.of("src/main/java");
        if (!Files.isDirectory(mainJava)) mainJava = Path.of("app/src/main/java");
        assertTrue("Expected app/src/main/java", Files.isDirectory(mainJava));
        try (Stream<Path> files = Files.walk(mainJava)) {
            List<Path> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("Diagnostics.java"))
                    .filter(p -> {
                        try {
                            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            return text.contains("android.util.Log") || text.contains("Log.d(")
                                    || text.contains("Log.i(") || text.contains("Log.w(") || text.contains("Log.e(");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
            assertTrue("Direct android.util.Log usage outside Diagnostics.java: " + offenders, offenders.isEmpty());
        }
    }
}
