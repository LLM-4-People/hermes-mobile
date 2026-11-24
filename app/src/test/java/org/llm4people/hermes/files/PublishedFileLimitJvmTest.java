package org.llm4people.hermes.files;

import org.junit.Test;
import org.llm4people.hermes.HermesConfig;

import static org.junit.Assert.assertEquals;

/** Pure-JVM regression tests verifying PublishedFile honors caller-resolved positive byte limits. */
public final class PublishedFileLimitJvmTest {
    @Test
    public void honorsRaisedCallerResolvedLimitsAbove16And64MiBAndDefaultsNonPositive() {
        long twentyFourMb = 24L * 1024 * 1024;
        long eightyMb = 80L * 1024 * 1024;
        assertEquals(twentyFourMb, PublishedFile.effectiveBound(twentyFourMb));
        assertEquals(eightyMb, PublishedFile.effectiveBound(eightyMb));
        assertEquals(1024L * 1024L, PublishedFile.effectiveBound(1024L * 1024L));
        assertEquals(HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES, PublishedFile.effectiveBound(0L));
        assertEquals(HermesConfig.DEFAULT_ARTIFACT_MAX_BYTES, PublishedFile.effectiveBound(-1L));
    }
}
