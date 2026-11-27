package org.llm4people.hermes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
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
    public void registersCapabilitiesDispatchesAndPreservesOrder() {
        RouteRegistry registry = new RouteRegistry();
        AtomicInteger hits = new AtomicInteger();
        registry.register(new RouteRegistry.Destination(
                "discussions", RouteRegistry.Section.DISCUSSIONS, "Discussions", "Vos échanges", 0, true, null,
                hits::incrementAndGet));
        registry.register(new RouteRegistry.Destination(
                "memory", RouteRegistry.Section.SETTINGS, "Mémoire", "Souvenirs", 0, true, "memory_api",
                () -> hits.addAndGet(10)));

        assertTrue(registry.dispatch("discussions"));
        assertTrue(registry.dispatch("memory"));
        assertEquals(11, hits.get());

        assertFalse(registry.dispatch("missing_route"));
        assertTrue(Diagnostics.exportRedacted().contains("route.unknown_action"));
    }

    @Test
    public void rejectsDuplicateOrMalformedIdentifiers() {
        RouteRegistry registry = new RouteRegistry();
        RouteRegistry.Destination dest = new RouteRegistry.Destination(
                "apps", RouteRegistry.Section.APPS, "Apps", "", 0, true, null, () -> {});
        registry.register(dest);

        try {
            registry.register(dest);
            fail("Expected duplicate destination rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
        try {
            new RouteRegistry.Destination("INVALID ID!", RouteRegistry.Section.APPS, "X", "", 0, false, null, () -> {});
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
    }

    @Test
    public void sectionTaxonomyMatchesModernFourTabShell() {
        assertEquals(
                List.of(
                        RouteRegistry.Section.DISCUSSIONS,
                        RouteRegistry.Section.NOTES_TASKS,
                        RouteRegistry.Section.APPS,
                        RouteRegistry.Section.SETTINGS),
                List.of(RouteRegistry.Section.values()));
    }
}
