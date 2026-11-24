package org.llm4people.hermes.artifact;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for WorkspaceBlocks value sanitization and boundary truncation. */
public final class WorkspaceBlocksTest {

    @Test
    public void formatValueTextFallsBackOnBlankAndSanitizesSurrogatesAndBidi() {
        assertEquals("Indisponible", WorkspaceBlocks.formatValueText(null, "Indisponible"));
        assertEquals("Indisponible", WorkspaceBlocks.formatValueText("  \u202E\u034F\n ", "Indisponible"));
        assertEquals("Bonjour monde", WorkspaceBlocks.formatValueText("Bon\u202E\u034F\uDB40\uDC20jour\u00A0monde", "N/A"));

        assertEquals("A".repeat(240) + "…", WorkspaceBlocks.formatValueText("A".repeat(260), "N/A"));

        String emojiBoundary = "a".repeat(239) + "\uD83D\uDE00tail";
        String formatted = WorkspaceBlocks.formatValueText(emojiBoundary, "N/A");
        assertTrue(formatted.length() <= 241);
        assertTrue(formatted.endsWith("…"));
        assertFalse("Must never split surrogate pair at 241-char boundary",
                Character.isHighSurrogate(formatted.charAt(formatted.length() - 2)));
    }
}
