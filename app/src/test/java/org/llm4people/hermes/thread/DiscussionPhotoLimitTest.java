package org.llm4people.hermes.thread;

import org.junit.Test;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.camera.CapturedMedia;

import static org.junit.Assert.assertEquals;

/** Pure-JVM regression tests verifying DiscussionPhoto shares CapturedMedia's payload & byte ceilings. */
public final class DiscussionPhotoLimitTest {
    @Test
    public void effectiveSourceLimitHonorsRaisedLimitsAndDefaultsNonPositive() {
        long twentyFourMb = 24L * 1024 * 1024;
        long eightyMb = 80L * 1024 * 1024;
        assertEquals(CapturedMedia.IMAGE_PAYLOAD_LIMIT, DiscussionPhoto.PAYLOAD_LIMIT);
        assertEquals(twentyFourMb, CapturedMedia.effectiveByteLimit(twentyFourMb));
        assertEquals(eightyMb, CapturedMedia.effectiveByteLimit(eightyMb));
        assertEquals(HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES, CapturedMedia.effectiveByteLimit(0L));
        assertEquals(HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES, CapturedMedia.effectiveByteLimit(-1L));
    }
}
