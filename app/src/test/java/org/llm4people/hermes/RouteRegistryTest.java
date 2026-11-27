package org.llm4people.hermes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RouteRegistryTest {
    @Before
    public void setUp() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void tearDown() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void registersAndDispatchesActionsCleanly() {
        RouteRegistry registry = new RouteRegistry();
        AtomicInteger hits = new AtomicInteger();
        registry.registerAction("discussions", hits::incrementAndGet);
        registry.registerAction("brain/memory", () -> hits.addAndGet(10));

        assertTrue(registry.dispatch("discussions"));
        assertTrue(registry.dispatch("brain/memory"));
        assertEquals(11, hits.get());

        assertFalse(registry.dispatch(null));
        assertFalse(registry.dispatch("missing_route"));
        assertTrue(Diagnostics.exportRedacted().contains("route.unknown_action"));
    }

    @Test
    public void rejectsDuplicateOrMalformedIdentifiers() {
        RouteRegistry registry = new RouteRegistry();
        registry.registerAction("apps", () -> {});

        try {
            registry.registerAction("apps", () -> {});
            fail("Expected duplicate action rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
        try {
            registry.registerAction("INVALID ID!", () -> {});
            fail("Expected invalid route id rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            registry.registerAction("bad action", () -> {});
            fail("Expected invalid action id rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            registry.registerAction("valid_id", null);
            fail("Expected null handler rejection");
        } catch (NullPointerException expected) {
            // ok
        }
    }
}
