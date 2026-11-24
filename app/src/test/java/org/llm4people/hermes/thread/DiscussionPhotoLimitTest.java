package org.llm4people.hermes.thread;

import org.junit.Test;
import org.llm4people.hermes.HermesConfig;

import static org.junit.Assert.assertEquals;

/** Pure-JVM regression tests verifying DiscussionPhoto honors raised caller byte ceilings. */
public final class DiscussionPhotoLimitTest {
    @Test
    public void effectiveSourceLimitHonorsRaisedLimitsAndDefaultsNonPositive() {
        long twentyFourMb = 24L * 1024 * 1024;
        long eightyMb = 80L * 1024 * 1024;
        assertEquals(twentyFourMb, DiscussionPhoto.effectiveSourceLimit(twentyFourMb));
        assertEquals(eightyMb, DiscussionPhoto.effectiveSourceLimit(eightyMb));
        assertEquals(HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES, DiscussionPhoto.effectiveSourceLimit(0L));
        assertEquals(HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES, DiscussionPhoto.effectiveSourceLimit(-1L));
    }
}
