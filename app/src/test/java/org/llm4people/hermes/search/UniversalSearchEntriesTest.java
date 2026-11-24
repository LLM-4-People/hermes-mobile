package org.llm4people.hermes.search;

import org.junit.Test;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.panel.UniversalShareModel;
import org.llm4people.hermes.settings.AppearanceSettingsPanel;
import org.llm4people.hermes.settings.SettingsHubModel;
import org.llm4people.hermes.templates.TemplatesPanel;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class UniversalSearchEntriesTest {
    @Test
    public void buildsDeduplicatedCatalogFromChatsSettingsHubAndExtraTools() {
        List<UniversalSearchEntries.ChatRef> chats = List.of(
                new UniversalSearchEntries.ChatRef("t-1", "Budget Q4", "Preview"),
                new UniversalSearchEntries.ChatRef("t-1", "Duplicate chat ignored", ""),
                new UniversalSearchEntries.ChatRef("", "Blank id ignored", "")
        );
        List<SettingsHubModel.SectionSpec> sections = List.of(
                new SettingsHubModel.SectionSpec("appearance", "Appearance & Language", List.of(
                        new SettingsHubModel.ItemSpec(
                                AppearanceSettingsPanel.ROUTE_ID, 0, "Appearance", "System · Forest", "theme dark"),
                        new SettingsHubModel.ItemSpec(
                                "language", 0, "App language", "English", "lang")
                )),
                new SettingsHubModel.SectionSpec("assistant", "Assistant & AI", List.of(
                        new SettingsHubModel.ItemSpec(
                                BrainStudioRoutes.ROUTE_MEMORY, 0, "Memory", "What Hermes remembers", "facts"),
                        new SettingsHubModel.ItemSpec(
                                TemplatesPanel.ROUTE_ID, 0, "Templates", "Reusable prompts", "workflows"),
                        new SettingsHubModel.ItemSpec(
                                ConnectionDoctorPanel.ROUTE_ID, 0, "Connection Doctor", "Health checks", "doctor")
                ))
        );
        List<UniversalSearchEntries.ToolRef> extra = List.of(
                new UniversalSearchEntries.ToolRef(
                        "command_palette", "Command palette", "Quick jump", "ctrl+k",
                        UniversalSearchModel.Category.TOOLS),
                // Duplicate of already-indexed SettingsHub item must not be duplicated.
                new UniversalSearchEntries.ToolRef(
                        "language", "Duplicate language", "", "",
                        UniversalSearchModel.Category.SETTINGS)
        );

        List<UniversalSearchModel.LocalEntry> entries = UniversalSearchEntries.build(chats, sections, extra);
        assertEquals(7, entries.size());

        // Recent chats map to CHATS and are marked recent.
        assertEquals(UniversalSearchModel.Category.CHATS, entries.get(0).category());
        assertEquals("t-1", entries.get(0).targetId());
        assertTrue(entries.get(0).recent());

        // Settings items preserve section title in keywords and map to SETTINGS.
        UniversalSearchModel.LocalEntry appearance = entries.stream()
                .filter(e -> AppearanceSettingsPanel.ROUTE_ID.equals(e.actionId()))
                .findFirst().orElseThrow();
        assertEquals(UniversalSearchModel.Category.SETTINGS, appearance.category());
        assertTrue(appearance.keywords().contains("Appearance & Language"));

        // Power tools from SettingsHub map to TOOLS so browsing Tools surfaces them cleanly.
        for (String toolAction : List.of(
                BrainStudioRoutes.ROUTE_MEMORY, TemplatesPanel.ROUTE_ID,
                ConnectionDoctorPanel.ROUTE_ID, "command_palette")) {
            UniversalSearchModel.LocalEntry entry = entries.stream()
                    .filter(e -> toolAction.equals(e.actionId()))
                    .findFirst().orElseThrow();
            assertEquals(toolAction + " should map to TOOLS", UniversalSearchModel.Category.TOOLS, entry.category());
        }

        // Null/empty inputs fail closed to empty list.
        assertTrue(UniversalSearchEntries.build(null, null, null).isEmpty());
    }

    @Test
    public void routesSharedResultsByResourceKind() {
        UniversalSearchModel.Result sharedSession = new UniversalSearchModel.Result(
                "shared:s-1", UniversalSearchModel.Category.SHARED, "Sprint", "alice",
                "sess-1", "", "s-1", UniversalShareModel.KIND_SESSION, "alice", "read", true, 35, List.of());
        UniversalSearchModel.Result sharedNote = new UniversalSearchModel.Result(
                "shared:s-2", UniversalSearchModel.Category.SHARED, "Notes", "bob",
                "note-1", "", "s-2", UniversalShareModel.KIND_NOTE, "bob", "edit", true, 35, List.of());
        UniversalSearchModel.Result sharedSkill = new UniversalSearchModel.Result(
                "shared:s-3", UniversalSearchModel.Category.SHARED, "Skill", "carol",
                "skill-1", "", "s-3", UniversalShareModel.KIND_SKILL, "carol", "read", true, 35, List.of());

        assertEquals(UniversalSearchEntries.SharedDestination.OPEN_SHARED_THREADS,
                UniversalSearchEntries.routeSharedItem(sharedSession));
        assertEquals(UniversalSearchEntries.SharedDestination.OPEN_SHARED_NOTES,
                UniversalSearchEntries.routeSharedItem(sharedNote));
        assertEquals(UniversalSearchEntries.SharedDestination.OPEN_SHARED_HUB,
                UniversalSearchEntries.routeSharedItem(sharedSkill));
        for (String hubKind : List.of(
                UniversalShareModel.KIND_TOOL,
                UniversalShareModel.KIND_SURFACE,
                UniversalShareModel.KIND_WIDGET,
                UniversalShareModel.KIND_ARTIFACT,
                "",
                "unknown")) {
            UniversalSearchModel.Result item = new UniversalSearchModel.Result(
                    "shared:x-" + hubKind, UniversalSearchModel.Category.SHARED, "Item", "owner",
                    "res-x", "", "x", hubKind, "owner", "read", true, 30, List.of());
            assertEquals("Kind " + hubKind + " should route to shared hub",
                    UniversalSearchEntries.SharedDestination.OPEN_SHARED_HUB,
                    UniversalSearchEntries.routeSharedItem(item));
        }
        assertEquals(UniversalSearchEntries.SharedDestination.OPEN_SHARED_HUB,
                UniversalSearchEntries.routeSharedItem(null));
    }

    @Test
    public void skipsNullAndBlankEntriesWithoutThrowing() {
        java.util.ArrayList<UniversalSearchEntries.ChatRef> chats = new java.util.ArrayList<>();
        chats.add(null);
        chats.add(new UniversalSearchEntries.ChatRef("t-blank-title", "   ", "Preview"));
        chats.add(new UniversalSearchEntries.ChatRef("t-valid", "Valid", "Sub"));

        java.util.ArrayList<UniversalSearchEntries.ToolRef> tools = new java.util.ArrayList<>();
        tools.add(null);
        tools.add(new UniversalSearchEntries.ToolRef("", "No id", "", "", UniversalSearchModel.Category.TOOLS));
        tools.add(new UniversalSearchEntries.ToolRef("id-1", "   ", "", "", UniversalSearchModel.Category.TOOLS));
        tools.add(new UniversalSearchEntries.ToolRef("id-2", "Doctor", "Check", "net", UniversalSearchModel.Category.TOOLS));

        List<UniversalSearchModel.LocalEntry> built = UniversalSearchEntries.build(chats, List.of(), tools);
        assertEquals(2, built.size());
        assertEquals("t-valid", built.get(0).targetId());
        assertEquals("id-2", built.get(1).actionId());
    }
}
