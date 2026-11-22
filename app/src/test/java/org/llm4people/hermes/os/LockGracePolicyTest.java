package org.llm4people.hermes.os;

import org.junit.Test;

import static org.junit.Assert.*;

public class LockGracePolicyTest {
    @Test public void immediateDefaultAlwaysLocksAndGraceWindowsHonorExactExpiry() {
        assertEquals(0L, LockGracePolicy.Window.IMMEDIATE.millis);
        assertEquals(30_000L, LockGracePolicy.Window.SECONDS_30.millis);
        assertEquals(120_000L, LockGracePolicy.Window.MINUTES_2.millis);
        assertEquals(300_000L, LockGracePolicy.Window.MINUTES_5.millis);

        // Immediate (0s) locks immediately even with 0ms elapsed.
        assertTrue(LockGracePolicy.shouldLockOnReturn(1_000L, 1_000L, 0L, false));

        // Within 30s grace window stays unlocked.
        assertFalse(LockGracePolicy.shouldLockOnReturn(1_000L, 30_999L, 30_000L, false));
        assertEquals(1L, LockGracePolicy.remainingMillis(1_000L, 30_999L, 30_000L));

        // At or past exact expiry locks strictly.
        assertTrue(LockGracePolicy.shouldLockOnReturn(1_000L, 31_000L, 30_000L, false));
        assertTrue(LockGracePolicy.shouldLockOnReturn(1_000L, 45_000L, 30_000L, false));
        assertEquals(0L, LockGracePolicy.remainingMillis(1_000L, 31_000L, 30_000L));
    }

    @Test public void manualLockAlwaysOverridesAnyGraceWindow() {
        assertTrue(LockGracePolicy.shouldLockOnReturn(1_000L, 1_001L, 300_000L, true));
        assertTrue(LockGracePolicy.shouldLockOnReturn(1_000L, 1_000L, 120_000L, true));
    }

    @Test public void invalidTimestampsAndCorruptKeysFailClosedToImmediateLock() {
        assertTrue(LockGracePolicy.shouldLockOnReturn(-1L, 5_000L, 300_000L, false));
        assertTrue(LockGracePolicy.shouldLockOnReturn(10_000L, 9_999L, 300_000L, false));
        assertTrue(LockGracePolicy.shouldLockOnReturn(1_000L, 2_000L, -5_000L, false));
        assertEquals(0L, LockGracePolicy.remainingMillis(10_000L, 9_000L, 300_000L));

        assertEquals(LockGracePolicy.Window.IMMEDIATE, LockGracePolicy.Window.fromKey(null));
        assertEquals(LockGracePolicy.Window.IMMEDIATE, LockGracePolicy.Window.fromKey(""));
        assertEquals(LockGracePolicy.Window.IMMEDIATE, LockGracePolicy.Window.fromKey("99h"));
        assertEquals(LockGracePolicy.Window.SECONDS_30, LockGracePolicy.Window.fromKey("30s"));
        assertEquals(LockGracePolicy.Window.MINUTES_2, LockGracePolicy.Window.fromKey("2m"));
        assertEquals(LockGracePolicy.Window.MINUTES_5, LockGracePolicy.Window.fromKey("5m"));
    }
}
