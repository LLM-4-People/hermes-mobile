package org.llm4people.hermes.panel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.RouteRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BrainCapabilitiesAndRoutesTest {
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
    public void capabilityDegradationAndWorkspaceAliasNormalization() {
        BrainCapabilities legacy = BrainCapabilities.empty();
        assertFalse(legacy.memoryApi());
        assertFalse(legacy.sessionSearch());
        assertFalse(legacy.skillStudio());
        assertFalse(legacy.workspaceFiles());
        assertFalse(legacy.profileDefaults());
        assertFalse(legacy.sessionForkAtTurn());
        assertFalse(legacy.sessionExport());
        assertFalse(legacy.smartNotes());
        assertFalse(legacy.universalSharing());
        assertFalse(legacy.habitSuggestions());

        BrainCapabilities fromBrowserFlag = BrainCapabilities.fromFlags(Map.of(
                "workspace_browser", true,
                "memory_api", true,
                "skill_studio", false,
                "universal_sharing", true));
        assertTrue(fromBrowserFlag.workspaceFiles());
        assertTrue(fromBrowserFlag.supports(BrainCapabilities.WORKSPACE_FILES));
        assertTrue(fromBrowserFlag.supports(BrainCapabilities.WORKSPACE_BROWSER));
        assertTrue(fromBrowserFlag.memoryApi());
        assertFalse(fromBrowserFlag.skillStudio());
        assertTrue(fromBrowserFlag.universalSharing());
        assertTrue(fromBrowserFlag.noteSharing());
    }

    @Test
    public void registersCerveauDestinationsAndThreadActionsIntoRouteRegistry() {
        List<String> calls = new ArrayList<>();
        RouteRegistry registry = BrainStudioRoutes.register(new RouteRegistry(), resId -> "res:" + resId, new BrainStudioRoutes.Handlers() {
            @Override public void openMemory() { calls.add("memory"); }
            @Override public void openNotes() { calls.add("notes"); }
            @Override public void openSharedWithMe() { calls.add("shared"); }
            @Override public void openGlobalSearch() { calls.add("search"); }
            @Override public void openSkillStudio() { calls.add("skills"); }
            @Override public void openWorkspaceFiles() { calls.add("files"); }
            @Override public void openProfileDefaults() { calls.add("profile"); }
            @Override public void togglePinThread() { calls.add("pin"); }
            @Override public void forkThread() { calls.add("fork"); }
            @Override public void exportThread() { calls.add("export"); }
            @Override public void shareThread() { calls.add("share"); }
        });

        // Legacy gateway with empty capabilities still exposes Skills under SETTINGS (degrades to read-only).
        List<RouteRegistry.Destination> legacySettings =
                registry.destinationsIn(RouteRegistry.Section.SETTINGS, BrainCapabilities.empty().asSet());
        assertEquals(1, legacySettings.size());
        assertEquals(BrainStudioRoutes.ROUTE_SKILLS, legacySettings.get(0).id());
        assertTrue(registry.destinationsIn(RouteRegistry.Section.NOTES_TASKS, BrainCapabilities.empty().asSet()).isEmpty());

        // Upgraded gateway unlocks Notes & Shared under NOTES_TASKS and Memory/Search/Skills/Files/Profile under SETTINGS.
        BrainCapabilities full = BrainCapabilities.fromFlags(Map.of(
                BrainCapabilities.MEMORY_API, true,
                BrainCapabilities.SMART_NOTES, true,
                BrainCapabilities.UNIVERSAL_SHARING, true,
                BrainCapabilities.SESSION_SEARCH, true,
                BrainCapabilities.WORKSPACE_BROWSER, true,
                BrainCapabilities.PROFILE_DEFAULTS, true));
        List<RouteRegistry.Destination> notesTasksRoutes =
                registry.destinationsIn(RouteRegistry.Section.NOTES_TASKS, full.asSet());
        assertEquals(2, notesTasksRoutes.size());
        assertEquals(BrainStudioRoutes.ROUTE_NOTES, notesTasksRoutes.get(0).id());
        assertEquals(BrainStudioRoutes.ROUTE_SHARED_WITH_ME, notesTasksRoutes.get(1).id());

        List<RouteRegistry.Destination> settingsRoutes =
                registry.destinationsIn(RouteRegistry.Section.SETTINGS, full.asSet());
        assertEquals(5, settingsRoutes.size());
        assertTrue(settingsRoutes.stream().anyMatch(d -> BrainStudioRoutes.ROUTE_PROFILE_DEFAULTS.equals(d.id())));
        assertTrue(settingsRoutes.stream().anyMatch(d -> BrainStudioRoutes.ROUTE_MEMORY.equals(d.id())));
        assertTrue(settingsRoutes.stream().anyMatch(d -> BrainStudioRoutes.ROUTE_SEARCH.equals(d.id())));
        assertTrue(settingsRoutes.stream().anyMatch(d -> BrainStudioRoutes.ROUTE_SKILLS.equals(d.id())));
        assertTrue(settingsRoutes.stream().anyMatch(d -> BrainStudioRoutes.ROUTE_WORKSPACE_FILES.equals(d.id())));

        assertTrue(registry.dispatch(BrainStudioRoutes.ROUTE_MEMORY));
        assertTrue(registry.dispatch(BrainStudioRoutes.ROUTE_NOTES));
        assertTrue(registry.dispatch(BrainStudioRoutes.ROUTE_SHARED_WITH_ME));
        assertTrue(registry.dispatch(BrainStudioRoutes.ROUTE_SEARCH));
        assertTrue(registry.dispatch(BrainStudioRoutes.ROUTE_SKILLS));
        assertTrue(registry.dispatch(BrainStudioRoutes.ROUTE_WORKSPACE_FILES));
        assertTrue(registry.dispatch(BrainStudioRoutes.ROUTE_PROFILE_DEFAULTS));
        assertTrue(registry.dispatch(BrainStudioRoutes.ACTION_PIN_THREAD));
        assertTrue(registry.dispatch(BrainStudioRoutes.ACTION_FORK_THREAD));
        assertTrue(registry.dispatch(BrainStudioRoutes.ACTION_EXPORT_THREAD));
        assertTrue(registry.dispatch(BrainStudioRoutes.ACTION_SHARE_THREAD));
        assertEquals(List.of("memory", "notes", "shared", "search", "skills", "files", "profile",
                "pin", "fork", "export", "share"), calls);

        try {
            BrainStudioRoutes.register(registry, resId -> "res:" + resId, new BrainStudioRoutes.Handlers() {
                @Override public void openMemory() {}
                @Override public void openNotes() {}
                @Override public void openSharedWithMe() {}
                @Override public void openGlobalSearch() {}
                @Override public void openSkillStudio() {}
                @Override public void openWorkspaceFiles() {}
                @Override public void openProfileDefaults() {}
                @Override public void togglePinThread() {}
                @Override public void forkThread() {}
                @Override public void exportThread() {}
                @Override public void shareThread() {}
            });
            fail("Expected duplicate route rejection");
        } catch (IllegalStateException expected) {
            // Duplicate registration must fail closed.
        }
    }
}
