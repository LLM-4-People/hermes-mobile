package org.llm4people.hermes.artifact;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LiveUiDeliveryDefaultsTest {
    @Test public void missingRevisionAndSummaryFallBackToDeliveryEnvelope() {
        assertEquals("dlv_0123456789abcdef", LiveUiDeliveryDefaults.revisionId(null, "dlv_0123456789abcdef"));
        assertEquals("dlv_0123456789abcdef", LiveUiDeliveryDefaults.revisionId("  ", "dlv_0123456789abcdef"));
        assertTrue(LiveUiPatch.validRevisionId(LiveUiDeliveryDefaults.revisionId("", "dlv_0123456789abcdef")));
        assertEquals("Nouveau tableau", LiveUiDeliveryDefaults.summary("", "Nouveau tableau"));
        assertEquals("Nouveau tableau", LiveUiDeliveryDefaults.summary(null, "Nouveau tableau"));
    }

    @Test public void presentValuesAreNeverReplacedSoInvalidOnesStillFailValidation() {
        assertEquals("rev-7", LiveUiDeliveryDefaults.revisionId("rev-7", "dlv_0123456789abcdef"));
        assertEquals("bad id/../x", LiveUiDeliveryDefaults.revisionId("bad id/../x", "dlv_0123456789abcdef"));
        assertFalse(LiveUiPatch.validRevisionId(LiveUiDeliveryDefaults.revisionId("bad id/../x", "dlv_0123456789abcdef")));
        assertEquals("Résumé Hermes", LiveUiDeliveryDefaults.summary("Résumé Hermes", "Titre"));
        assertNull(LiveUiDeliveryDefaults.revisionId(null, null));
    }
}
