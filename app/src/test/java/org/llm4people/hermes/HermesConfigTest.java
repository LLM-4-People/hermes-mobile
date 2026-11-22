package org.llm4people.hermes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class HermesConfigTest {
    @Test
    public void defaultsMatchServerArtifactLimitAndEnforceCeiling() {
        HermesConfig cfg = HermesConfig.defaults();
        assertEquals(16L * 1024 * 1024, cfg.artifactDefaultMaxBytes);
        assertEquals(64L * 1024 * 1024, cfg.artifactHardMaxBytes);
        assertEquals(8L * 1024 * 1024, cfg.resolveArtifactLimit(8L * 1024 * 1024));
        assertEquals(32L * 1024 * 1024, cfg.resolveArtifactLimit(Integer.valueOf(32 * 1024 * 1024)));
        assertEquals(cfg.artifactHardMaxBytes, cfg.resolveArtifactLimit(128L * 1024 * 1024));
    }

    @Test
    public void invalidAdvertisedArtifactLimitsFallBackToDefault() {
        HermesConfig cfg = HermesConfig.defaults();
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit(0L));
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit(-5L));
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit((Object) null));
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit("16777216"));
        assertEquals(cfg.artifactDefaultMaxBytes, cfg.resolveArtifactLimit(Boolean.TRUE));
    }

    @Test
    public void constructorRejectsNonPositiveOrInvertedBounds() {
        try {
            new HermesConfig(0, 1000, 1000, 1000, 1000, 500, 1024, 2048, 10, 10, 1);
            fail("Expected rejection for zero connect timeout");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new HermesConfig(1000, 1000, 1000, 1000, 1000, 2000, 1024, 2048, 10, 10, 1);
            fail("Expected rejection when fused grace exceeds nearby timeout");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new HermesConfig(1000, 1000, 1000, 1000, 1000, 500, 4096, 2048, 10, 10, 1);
            fail("Expected rejection when hard artifact limit is below default");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
