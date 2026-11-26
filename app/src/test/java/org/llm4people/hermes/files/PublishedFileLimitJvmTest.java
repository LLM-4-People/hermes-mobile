package org.llm4people.hermes.files;

import org.junit.Test;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.camera.CapturedMedia;

import static org.junit.Assert.assertEquals;

/** Pure-JVM regression tests verifying CapturedMedia.effectiveByteLimit honors caller-resolved positive byte limits. */
public final class PublishedFileLimitJvmTest {
    @Test
    public void honorsRaisedCallerResolvedLimitsAbove16And64MiBAndDefaultsNonPositive() {
        long twentyFourMb = 24L * 1024 * 1024;
        long eightyMb = 80L * 1024 * 1024;
        assertEquals(twentyFourMb, CapturedMedia.effectiveByteLimit(twentyFourMb));
        assertEquals(eightyMb, CapturedMedia.effectiveByteLimit(eightyMb));
        assertEquals(1024L * 1024L, CapturedMedia.effectiveByteLimit(1024L * 1024L));
        assertEquals(HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES, CapturedMedia.effectiveByteLimit(0L));
        assertEquals(HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES, CapturedMedia.effectiveByteLimit(-1L));
    }
}
