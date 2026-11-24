package org.llm4people.hermes.panel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for ThreadActionModel turn choice previews and export naming. */
public final class ThreadActionModelTest {

    @Test
    public void displayLabelSanitizesBidiCgjAndNeverSplitsSurrogatePairs() {
        ThreadActionModel.TurnChoice clean = new ThreadActionModel.TurnChoice(
                1L, "Vous", "Bon\u202E\u034F\uDB40\uDC20jour\n\u00A0monde");
        assertEquals("Vous · Bonjour monde", clean.displayLabel());

        ThreadActionModel.TurnChoice noSpeaker = new ThreadActionModel.TurnChoice(
                2L, "", "a".repeat(78) + "\uD83D\uDE00tail");
        String label = noSpeaker.displayLabel();
        assertTrue(label.length() <= 80);
        assertTrue(label.endsWith("…"));
        assertFalse("Must never split surrogate pair at 80-char boundary",
                Character.isHighSurrogate(label.charAt(label.length() - 2)));
    }
}
