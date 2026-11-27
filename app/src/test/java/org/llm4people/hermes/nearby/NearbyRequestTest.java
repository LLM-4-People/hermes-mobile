package org.llm4people.hermes.nearby;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public final class NearbyRequestTest {
    private static final CoarseArea CELL = CoarseArea.from(0, 0, 10, 1, 1);

    private static final class Platform implements NearbyRequest.Source {
        final List<String> providers = new ArrayList<>();
        final List<CoarseArea> deliveries = new ArrayList<>();
        final Map<Long, Runnable> timers = new HashMap<>();
        final NearbyRequest request = new NearbyRequest(this, deliveries::add, org.llm4people.hermes.HermesConfig.DEFAULT_NEARBY_TIMEOUT_MS, org.llm4people.hermes.HermesConfig.DEFAULT_NEARBY_FUSED_GRACE_MS);
        boolean available = true;
        long time;
        int closes;
        Consumer<CoarseArea> fused;
        Consumer<CoarseArea> network;

        public boolean available() { return available; }
        public long elapsedMillis() { return time; }
        public void after(long delay, Runnable action) { timers.put(delay, action); }
        public void read(boolean useFused, Consumer<CoarseArea> callback) {
            providers.add(useFused ? "fused" : "network");
            if (useFused) fused = callback;
            else network = callback;
        }
        public void close() { closes++; }
    }

    @Test public void fusedFirstFallsBackImmediatelyOrAfterBriefWaitAndClosesOnlyOnce() {
        for (boolean failsImmediately : new boolean[]{true, false}) {
            Platform phone = new Platform();
            phone.request.start(true);
            assertEquals(List.of("fused"), phone.providers);
            if (failsImmediately) phone.fused.accept(null);
            phone.time = 4_000;
            phone.timers.get(4_000L).run();
            assertEquals(List.of("fused", "network"), phone.providers);
            phone.network.accept(CELL);
            phone.fused.accept(CELL);
            phone.network.accept(null);
            phone.timers.get(12_000L).run();
            phone.request.cancel();
            assertEquals(List.of(CELL), phone.deliveries);
            assertEquals(1, phone.closes);
        }
    }

    @Test public void fusedCanFinishAloneOrWhileNetworkFallbackIsUnavailable() {
        for (boolean delayed : new boolean[]{true, false}) {
            Platform phone = new Platform();
            phone.request.start(true);
            if (delayed) {
                phone.timers.get(4_000L).run();
                phone.network.accept(null);
                assertTrue(phone.deliveries.isEmpty());
            }
            phone.fused.accept(CELL);
            phone.timers.get(4_000L).run();
            assertEquals(delayed ? List.of("fused", "network") : List.of("fused"), phone.providers);
            assertEquals(List.of(CELL), phone.deliveries);
        }
    }

    @Test public void cancelledOwnerNeverReceivesAnOldSampleOrStartsAnotherProvider() {
        Platform phone = new Platform();
        phone.request.start(true);
        phone.request.cancel();
        phone.fused.accept(CELL);
        phone.timers.get(4_000L).run();
        phone.timers.get(12_000L).run();
        assertEquals(List.of("fused"), phone.providers);
        assertTrue(phone.deliveries.isEmpty());
        assertEquals(1, phone.closes);

        phone = new Platform();
        phone.request.cancel();
        phone.request.start(false);
        assertTrue(phone.providers.isEmpty());
        assertTrue(phone.deliveries.isEmpty());
    }

    @Test public void permissionLossAndElapsedDeadlineRejectEvenAValidDelayedSample() {
        for (boolean revoked : new boolean[]{true, false}) {
            Platform phone = new Platform();
            phone.request.start(false);
            assertEquals(List.of("network"), phone.providers);
            if (revoked) phone.available = false;
            else phone.time = 12_000;
            phone.network.accept(CELL);
            phone.timers.get(12_000L).run();
            assertEquals(1, phone.deliveries.size());
            assertNull(phone.deliveries.get(0));
            assertEquals(1, phone.closes);
        }
    }

    @Test public void noPermissionNoProvidersAndPlatformSilenceEachReportOneFailure() {
        Platform denied = new Platform();
        denied.available = false;
        denied.request.start(true);
        assertTrue(denied.providers.isEmpty());
        assertNull(denied.deliveries.get(0));

        Platform disabled = new Platform();
        disabled.request.start(true);
        disabled.fused.accept(null);
        disabled.network.accept(null);
        assertEquals(1, disabled.deliveries.size());
        assertNull(disabled.deliveries.get(0));

        Platform silent = new Platform();
        silent.request.start(true);
        silent.timers.get(12_000L).run();
        silent.fused.accept(CELL);
        assertEquals(1, silent.deliveries.size());
        assertNull(silent.deliveries.get(0));
    }

    @Test public void respectsConfiguredTimeoutAndGraceBounds() {
        Platform phone = new Platform();
        NearbyRequest custom = new NearbyRequest(phone, phone.deliveries::add, 6_000, 1_500);
        custom.start(true);
        assertTrue(phone.timers.containsKey(6_000L));
        assertTrue(phone.timers.containsKey(1_500L));
        phone.time = 1_500;
        phone.timers.get(1_500L).run();
        assertEquals(List.of("fused", "network"), phone.providers);
        phone.time = 6_000;
        phone.network.accept(CELL);
        assertEquals(1, phone.deliveries.size());
        assertNull(phone.deliveries.get(0));

        try {
            new NearbyRequest(phone, phone.deliveries::add, 1_000, 2_000);
            fail("Expected ordered timeout/grace check");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
