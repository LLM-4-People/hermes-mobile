package org.llm4people.hermes.panel;

import org.junit.Test;
import org.llm4people.hermes.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void turnChoiceRejectsNonPositiveMessageIds() {
        for (long invalidId : new long[]{0L, -1L}) {
            try {
                new ThreadActionModel.TurnChoice(invalidId, "Vous", "Salut");
                fail("Expected LocalizedInputException for messageId=" + invalidId);
            } catch (LocalizedInputException expected) {
                assertEquals("invalid_message_id", expected.getMessage());
                assertEquals(R.string.ws_fork_err_message_id, expected.resId);
            }
        }
    }

    @Test
    public void turnChoiceHandlesNullsAndInvisibleOnlyPreview() {
        ThreadActionModel.TurnChoice nullFields = new ThreadActionModel.TurnChoice(3L, null, null);
        assertEquals("", nullFields.speakerLabel());
        assertEquals("", nullFields.preview());
        assertEquals("", nullFields.displayLabel());

        String invisibleOnly = "  \u202E\u034F\n ";
        ThreadActionModel.TurnChoice invisibleNoSpeaker = new ThreadActionModel.TurnChoice(4L, "", invisibleOnly);
        assertEquals("", invisibleNoSpeaker.displayLabel());

        ThreadActionModel.TurnChoice invisibleWithSpeaker = new ThreadActionModel.TurnChoice(5L, "Vous", invisibleOnly);
        assertEquals("Vous · ", invisibleWithSpeaker.displayLabel());
    }
}
