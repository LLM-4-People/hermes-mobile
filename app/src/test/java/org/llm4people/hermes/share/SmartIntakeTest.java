package org.llm4people.hermes.share;

import org.junit.Test;
import org.llm4people.hermes.camera.CapturedMedia;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SmartIntakeTest {
    @Test
    public void classifiesPhotoUrlCodeLogDocumentAndMessageAccurately() {
        byte[] dummy = "x".getBytes(StandardCharsets.UTF_8);
        CapturedMedia.Item photo = new CapturedMedia.Item(
                CapturedMedia.Kind.IMAGE, "recu.jpg", "image/jpeg", dummy, CapturedMedia.sha256Hex(dummy), null);
        CapturedMedia.Item logFile = new CapturedMedia.Item(
                CapturedMedia.Kind.DOCUMENT, "crash.log", "text/plain", dummy, CapturedMedia.sha256Hex(dummy), null);
        CapturedMedia.Item pdf = new CapturedMedia.Item(
                CapturedMedia.Kind.DOCUMENT, "facture.pdf", "application/pdf", dummy, CapturedMedia.sha256Hex(dummy), null);

        assertEquals(SmartIntake.ContentType.PHOTO_OR_RECEIPT, SmartIntake.classify(null, List.of(photo)));
        assertEquals(SmartIntake.ContentType.CODE_OR_LOG, SmartIntake.classify(null, List.of(logFile)));
        assertEquals(SmartIntake.ContentType.DOCUMENT, SmartIntake.classify(null, List.of(pdf)));
        assertEquals(SmartIntake.ContentType.URL, SmartIntake.classify("https://exemple.fr/article", null));
        assertEquals(SmartIntake.ContentType.CODE_OR_LOG,
                SmartIntake.classify("Traceback (most recent call last):\n  File \"a.py\"", null));
        assertEquals(SmartIntake.ContentType.MESSAGE,
                SmartIntake.classify("On se retrouve mardi à 14h devant le café ?", null));
    }

    @Test
    public void respectsMaxThreeChipsAndOnlyAdaptsWhenHabitCountReachesThreshold() {
        byte[] dummy = "x".getBytes(StandardCharsets.UTF_8);
        CapturedMedia.Item photo = new CapturedMedia.Item(
                CapturedMedia.Kind.IMAGE, "recu.jpg", "image/jpeg", dummy, CapturedMedia.sha256Hex(dummy), null);

        // Below threshold (< 3): default calm ordering preserved.
        List<SmartIntake.Suggestion> below = SmartIntake.suggest(null, List.of(photo), Map.of("save_note", 2));
        assertEquals(3, below.size());
        assertEquals(org.llm4people.hermes.R.string.share_intake_explain, below.get(0).labelRes());

        // At/above threshold (>= 3): learned habit promoted cleanly to first chip.
        List<SmartIntake.Suggestion> learned = SmartIntake.suggest(null, List.of(photo), Map.of("save_note", 4));
        assertTrue(learned.size() <= SmartIntake.MAX_CHIPS);
        assertEquals(org.llm4people.hermes.R.string.share_intake_save_note, learned.get(0).labelRes());

        // URL and message stay at 2 calm chips.
        assertEquals(2, SmartIntake.suggest("https://exemple.fr", null, null).size());
        assertEquals(2, SmartIntake.suggest("Salut, tu es libre demain ?", null, null).size());
    }
}
