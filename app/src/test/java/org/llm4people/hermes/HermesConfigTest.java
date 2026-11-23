package org.llm4people.hermes;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
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
        assertEquals("LLM-4-People/hermes-mobile", cfg.updateRepo);
        assertEquals("https://api.github.com", cfg.updateApiBase);
        assertEquals(52_428_800L, cfg.updateMaxApkBytes);
        assertEquals(86_400_000L, cfg.updateCheckIntervalMs);
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

    @Test
    public void loadCachedMemoizesPerContextKeyAndResetsCleanly() {
        HermesConfig.clearCacheForTest();
        try {
            Object appA = new Object();
            Object appB = new Object();
            AtomicInteger loads = new AtomicInteger();

            HermesConfig first = HermesConfig.loadCached(appA, () -> {
                loads.incrementAndGet();
                return new HermesConfig(5000, 10000, 1024, 1024, 4000, 1000, 4096, 8192, 50, 20, 3);
            });
            for (int i = 0; i < 100; i++) {
                HermesConfig hit = HermesConfig.loadCached(appA, () -> {
                    loads.incrementAndGet();
                    return HermesConfig.defaults();
                });
                assertSame(first, hit);
            }
            assertEquals("Memoized load must invoke resource loader exactly once", 1, loads.get());

            HermesConfig second = HermesConfig.loadCached(appB, () -> {
                loads.incrementAndGet();
                return new HermesConfig(6000, 12000, 2048, 2048, 5000, 1000, 4096, 8192, 50, 20, 3);
            });
            assertNotSame(first, second);
            assertEquals(2, loads.get());

            HermesConfig.clearCacheForTest();
            HermesConfig reloaded = HermesConfig.loadCached(appA, () -> {
                loads.incrementAndGet();
                return new HermesConfig(7000, 14000, 2048, 2048, 5000, 1000, 4096, 8192, 50, 20, 3);
            });
            assertEquals(3, loads.get());
            assertEquals(7000, reloaded.httpConnectTimeoutMs);

            assertSame(HermesConfig.defaults(), HermesConfig.loadCached(null, () -> first));
            assertSame(HermesConfig.defaults(), HermesConfig.loadCached(appA, null));
            assertSame(HermesConfig.defaults(), HermesConfig.load(null));
        } finally {
            HermesConfig.clearCacheForTest();
        }
    }
}
