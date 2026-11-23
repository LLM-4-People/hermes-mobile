package org.llm4people.hermes.profile;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class UnlockBackoffTest {
    private static final class FakeBacking implements UnlockBackoff.Backing {
        final Map<String, String> data = new HashMap<>();
        @Override public String get(String key) { return data.get(key); }
        @Override public void put(String key, String value) { data.put(key, value); }
        @Override public void remove(String key) { data.remove(key); }
    }

    private static final class FakeClock implements UnlockBackoff.Clock {
        long elapsed = 10_000L;
        long wall = 1_700_000_000_000L;
        int boot = 7;
        @Override public long elapsedMs() { return elapsed; }
        @Override public long wallMs() { return wall; }
        @Override public int bootCount() { return boot; }
    }

    @Test
    public void exponentialScheduleCapsAtFiveMinutesAndSurvivesRestart() {
        FakeBacking backing = new FakeBacking();
        FakeClock clock = new FakeClock();
        UnlockBackoff backoff = new UnlockBackoff(backing, clock);

        // Attempts 1-2 have zero delay for casual typos.
        assertEquals(0L, backoff.recordFailure("alice"));
        assertEquals(0L, backoff.remainingMs("alice"));
        assertEquals(0L, backoff.recordFailure("alice"));
        assertEquals(0L, backoff.remainingMs("alice"));

        // Attempt 3 -> 5s, 4 -> 15s, 5 -> 30s, 6 -> 60s, 7 -> 120s, 8+ -> 300s cap.
        long[] expected = {5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L};
        for (long delay : expected) {
            assertEquals(delay, backoff.recordFailure("alice"));
            // Simulating process/Activity restart: brand-new UnlockBackoff over same backing cannot bypass cooldown!
            UnlockBackoff restarted = new UnlockBackoff(backing, clock);
            assertEquals(delay, restarted.remainingMs("alice"));
            // Advance monotonic clock past cooldown to permit next failure probe.
            clock.elapsed += delay + 1L;
            clock.wall += delay + 1L;
            assertEquals(0L, restarted.remainingMs("alice"));
        }
    }

    @Test
    public void isolatedPerProfileSurvivesRebootAndClearsOnValidUnlock() {
        FakeBacking backing = new FakeBacking();
        FakeClock clock = new FakeClock();
        UnlockBackoff backoff = new UnlockBackoff(backing, clock);

        backoff.recordFailure("alice");
        backoff.recordFailure("alice");
        assertEquals(5_000L, backoff.recordFailure("alice"));

        // Profile B is completely unaffected by Profile A's failures.
        assertEquals(0L, backoff.remainingMs("bob"));

        // Simulated reboot: bootCount changes, elapsed resets to 100ms, wall clock advanced 2s -> 3s remaining.
        clock.boot = 8;
        clock.elapsed = 100L;
        clock.wall += 2_000L;
        assertEquals(3_000L, backoff.remainingMs("alice"));

        // Wild wall clock skew backward is clamped to MAX_COOLDOWN_MS (never infinite lockout).
        clock.wall -= 86_400_000L;
        assertEquals(UnlockBackoff.MAX_COOLDOWN_MS, backoff.remainingMs("alice"));

        // Valid unlock clears failure history & cooldown immediately.
        backoff.reset("alice");
        assertEquals(0L, backoff.remainingMs("alice"));
        assertEquals(0, backoff.failureCount("alice"));
    }

    @Test
    public void corruptStoredRecordFailsClosedAndFormattingIsPlain() {
        FakeBacking backing = new FakeBacking();
        FakeClock clock = new FakeClock();
        backing.put("backoff.alice", "corrupt|payload");
        UnlockBackoff backoff = new UnlockBackoff(backing, clock);
        assertTrue(backoff.remainingMs("alice") > 0L);

        // Tampered high-failure record with zeroed timestamps must fail closed, never return 0ms.
        backing.put("backoff.bob", "10|0|0|7");
        assertTrue(backoff.remainingMs("bob") > 0L);
        assertEquals(UnlockBackoff.FREE_ATTEMPTS + 1, backoff.failureCount("bob"));

        assertEquals("1 s", UnlockBackoff.formatRemaining(100L));
        assertEquals("5 s", UnlockBackoff.formatRemaining(5_000L));
        assertEquals("1 min", UnlockBackoff.formatRemaining(60_000L));
        assertEquals("2 min 10 s", UnlockBackoff.formatRemaining(130_000L));
    }

    @Test
    public void classCastCorruptPrefAndClockRollbackAfterRebootFailClosed() {
        FakeClock clock = new FakeClock();
        UnlockBackoff.Backing throwingBacking = new UnlockBackoff.Backing() {
            @Override public String get(String key) { throw new ClassCastException("non-string stored"); }
            @Override public void put(String key, String value) {}
            @Override public void remove(String key) {}
        };
        UnlockBackoff corruptBackoff = new UnlockBackoff(throwingBacking, clock);
        assertTrue("ClassCastException in prefs must fail closed", corruptBackoff.remainingMs("alice") > 0L);
        assertEquals(UnlockBackoff.FREE_ATTEMPTS + 1, corruptBackoff.failureCount("alice"));

        FakeBacking backing = new FakeBacking();
        UnlockBackoff backoff = new UnlockBackoff(backing, clock);
        backoff.recordFailure("alice");
        backoff.recordFailure("alice");
        assertEquals(5_000L, backoff.recordFailure("alice"));

        // Different boot count with wall clock rolled back to 0 or negative must fail closed (never 0ms)
        clock.boot = 9;
        clock.wall = 0L;
        assertEquals(5_000L, backoff.remainingMs("alice"));
        clock.wall = -500L;
        assertEquals(5_000L, backoff.remainingMs("alice"));

        // Same boot with invalid negative elapsed clock and zero wall clock must also fail closed
        clock.boot = 7;
        clock.elapsed = -1L;
        clock.wall = 0L;
        assertEquals(5_000L, backoff.remainingMs("alice"));
    }
}
