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
        assertEquals(LockGracePolicy.Window.MINUTES_1, LockGracePolicy.Window.fromKey("1m"));
        assertEquals(LockGracePolicy.Window.MINUTES_2, LockGracePolicy.Window.fromKey("2m"));
        assertEquals(LockGracePolicy.Window.MINUTES_5, LockGracePolicy.Window.fromKey("5m"));
        assertEquals(LockGracePolicy.Window.MINUTES_15, LockGracePolicy.Window.fromKey("15m"));
        assertEquals(LockGracePolicy.Window.MINUTES_30, LockGracePolicy.Window.fromKey("30m"));
        assertEquals(LockGracePolicy.Window.SCREEN_LOCK, LockGracePolicy.Window.fromKey("screen_lock"));
    }

    @Test public void expandedTimeoutsScreenLockAndInternalTransitionsStayFailClosed() {
        assertEquals(LockGracePolicy.Window.MINUTES_5, LockGracePolicy.DEFAULT_FOR_NEW_PROFILE);
        assertEquals(60_000L, LockGracePolicy.Window.MINUTES_1.millis);
        assertEquals(900_000L, LockGracePolicy.Window.MINUTES_15.millis);
        assertEquals(1_800_000L, LockGracePolicy.Window.MINUTES_30.millis);
        assertTrue(LockGracePolicy.Window.SCREEN_LOCK.locksOnScreenOffOnly());
        assertFalse(LockGracePolicy.Window.MINUTES_5.locksOnScreenOffOnly());
        assertEquals(6, LockGracePolicy.selectableWindows().length);

        // Internal child-activity transitions (QR scanner, photo/file pickers) never auto-lock while screen stays on
        assertFalse(LockGracePolicy.shouldLockOnReturn(
                LockGracePolicy.Window.IMMEDIATE, 1_000L, 10_000L, false, false, true));

        // Screen off / device lock ALWAYS locks immediately even during internal transition or SCREEN_LOCK mode
        assertTrue(LockGracePolicy.shouldLockOnReturn(
                LockGracePolicy.Window.SCREEN_LOCK, 1_000L, 2_000L, false, true, false));
        assertTrue(LockGracePolicy.shouldLockOnReturn(
                LockGracePolicy.Window.MINUTES_30, 1_000L, 2_000L, false, true, true));

        // SCREEN_LOCK stays unlocked across normal app switches when screen stays unlocked
        assertFalse(LockGracePolicy.shouldLockOnReturn(
                LockGracePolicy.Window.SCREEN_LOCK, 1_000L, 999_999L, false, false, false));

        // Manual lock always locks even in SCREEN_LOCK or internal transition
        assertTrue(LockGracePolicy.shouldLockOnReturn(
                LockGracePolicy.Window.SCREEN_LOCK, 1_000L, 1_001L, true, false, true));

        // 15m and 30m honor exact millisecond boundaries
        assertFalse(LockGracePolicy.shouldLockOnReturn(
                LockGracePolicy.Window.MINUTES_15, 1_000L, 900_999L, false, false, false));
        assertTrue(LockGracePolicy.shouldLockOnReturn(
                LockGracePolicy.Window.MINUTES_15, 1_000L, 901_000L, false, false, false));
    }

    @Test public void internalTransitionTimeoutsStayBoundedAndFailClosed() {
        assertEquals(LockGracePolicy.INTERNAL_TRANSITION_MIN_MS,
                LockGracePolicy.internalTransitionTimeoutMillis(null));
        assertEquals(LockGracePolicy.INTERNAL_TRANSITION_MIN_MS,
                LockGracePolicy.internalTransitionTimeoutMillis(LockGracePolicy.Window.IMMEDIATE));
        assertEquals(LockGracePolicy.INTERNAL_TRANSITION_MIN_MS,
                LockGracePolicy.internalTransitionTimeoutMillis(LockGracePolicy.Window.SECONDS_30));
        assertEquals(LockGracePolicy.INTERNAL_TRANSITION_MIN_MS,
                LockGracePolicy.internalTransitionTimeoutMillis(LockGracePolicy.Window.MINUTES_1));
        assertEquals(120_000L,
                LockGracePolicy.internalTransitionTimeoutMillis(LockGracePolicy.Window.MINUTES_2));
        assertEquals(LockGracePolicy.INTERNAL_TRANSITION_MAX_MS,
                LockGracePolicy.internalTransitionTimeoutMillis(LockGracePolicy.Window.MINUTES_5));
        assertEquals(LockGracePolicy.INTERNAL_TRANSITION_MAX_MS,
                LockGracePolicy.internalTransitionTimeoutMillis(LockGracePolicy.Window.MINUTES_30));
        assertEquals(LockGracePolicy.INTERNAL_TRANSITION_MAX_MS,
                LockGracePolicy.internalTransitionTimeoutMillis(LockGracePolicy.Window.SCREEN_LOCK));

        // Within cap stays valid; exact boundary, negative, or clock-backwards fail closed
        assertFalse(LockGracePolicy.isInternalTransitionExpired(1_000L, 60_999L, 60_000L));
        assertTrue(LockGracePolicy.isInternalTransitionExpired(1_000L, 61_000L, 60_000L));
        assertTrue(LockGracePolicy.isInternalTransitionExpired(-1L, 2_000L, 60_000L));
        assertTrue(LockGracePolicy.isInternalTransitionExpired(5_000L, 4_999L, 60_000L));
        assertTrue(LockGracePolicy.isInternalTransitionExpired(1_000L, 1_001L, 0L));
    }
}
