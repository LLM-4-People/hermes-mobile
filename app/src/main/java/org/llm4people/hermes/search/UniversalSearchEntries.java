package org.llm4people.hermes.search;

import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.panel.UniversalShareModel;
import org.llm4people.hermes.settings.SettingsHubModel;
import org.llm4people.hermes.templates.TemplatesPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-JVM SSoT converting live app sources (recent chats, {@link SettingsHubModel} sections,
 * and extra workspace tools) into {@link UniversalSearchModel.LocalEntry} items and resolving
 * shared-result routing without duplicating catalogs.
 */
public final class UniversalSearchEntries {
    private static final Set<String> TOOL_ACTION_IDS = Set.of(
            BrainStudioRoutes.ROUTE_MEMORY,
            BrainStudioRoutes.ROUTE_SKILLS,
            BrainStudioRoutes.ROUTE_WORKSPACE_FILES,
            TemplatesPanel.ROUTE_ID,
            "stats",
            ConnectionDoctorPanel.ROUTE_ID
    );

    public record ChatRef(String id, String title, String preview) {}

    public record ToolRef(String actionId, String title, String subtitle, String keywords,
                          UniversalSearchModel.Category category) {}

    public enum SharedDestination {
        OPEN_SHARED_THREADS,
        OPEN_SHARED_NOTES,
        OPEN_SHARED_HUB
    }

    private UniversalSearchEntries() {}

    /**
     * Build the deduplicated local search catalog from recent chats, {@link SettingsHubModel}
     * sections (reused as SSoT), and any additional quick tools/hub entries.
     */
    public static List<UniversalSearchModel.LocalEntry> build(
            List<ChatRef> recentChats,
            List<SettingsHubModel.SectionSpec> settingsSections,
            List<ToolRef> extraEntries) {
        List<UniversalSearchModel.LocalEntry> out = new ArrayList<>();
        Set<String> seenThreads = new LinkedHashSet<>();
        Set<String> seenActions = new LinkedHashSet<>();

        if (recentChats != null) {
            for (ChatRef chat : recentChats) {
                if (chat == null || safe(chat.id()).isEmpty() || safe(chat.title()).isEmpty()) continue;
                if (seenThreads.add(chat.id().trim())) {
                    out.add(UniversalSearchModel.LocalEntry.chat(
                            chat.id(), chat.title(), safe(chat.preview()), true));
                }
            }
        }

        if (settingsSections != null) {
            for (SettingsHubModel.SectionSpec section : settingsSections) {
                if (section == null) continue;
                String sectionTitle = safe(section.title());
                for (SettingsHubModel.ItemSpec item : section.items()) {
                    if (item == null || item.actionId().isEmpty() || item.title().isEmpty()) continue;
                    if (!seenActions.add(item.actionId())) continue;
                    String keywords = sectionTitle.isEmpty()
                            ? item.keywords()
                            : (item.keywords().isEmpty() ? sectionTitle : sectionTitle + " " + item.keywords());
                    if (TOOL_ACTION_IDS.contains(item.actionId())) {
                        out.add(UniversalSearchModel.LocalEntry.tool(
                                item.actionId(), item.title(), item.subtitle(), keywords));
                    } else {
                        out.add(UniversalSearchModel.LocalEntry.setting(
                                item.actionId(), item.title(), item.subtitle(), keywords));
                    }
                }
            }
        }

        if (extraEntries != null) {
            for (ToolRef extra : extraEntries) {
                if (extra == null || safe(extra.actionId()).isEmpty() || safe(extra.title()).isEmpty()) continue;
                String action = extra.actionId().trim();
                if (!seenActions.add(action)) continue;
                UniversalSearchModel.Category cat = extra.category() == UniversalSearchModel.Category.SETTINGS
                        ? UniversalSearchModel.Category.SETTINGS
                        : UniversalSearchModel.Category.TOOLS;
                out.add(cat == UniversalSearchModel.Category.SETTINGS
                        ? UniversalSearchModel.LocalEntry.setting(action, extra.title(), safe(extra.subtitle()), safe(extra.keywords()))
                        : UniversalSearchModel.LocalEntry.tool(action, extra.title(), safe(extra.subtitle()), safe(extra.keywords())));
            }
        }

        return Collections.unmodifiableList(out);
    }

    /** Route a tapped shared search result cleanly to its native destination. */
    public static SharedDestination routeSharedItem(UniversalSearchModel.Result item) {
        if (item == null) return SharedDestination.OPEN_SHARED_HUB;
        return switch (item.resourceKind()) {
            case UniversalShareModel.KIND_SESSION -> SharedDestination.OPEN_SHARED_THREADS;
            case UniversalShareModel.KIND_NOTE -> SharedDestination.OPEN_SHARED_NOTES;
            default -> SharedDestination.OPEN_SHARED_HUB;
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
