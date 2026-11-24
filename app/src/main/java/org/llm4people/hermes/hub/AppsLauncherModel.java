package org.llm4people.hermes.hub;

import org.llm4people.hermes.artifact.LiveUiPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-JVM Single Source of Truth for the living Apps launcher screen.
 * Merges saved custom screens with pinned Live-UI custom tabs without duplication.
 */
public final class AppsLauncherModel {
    public record ScreenEntry(String pageId, String title, boolean pinnedTab) {
        public ScreenEntry {
            pageId = pageId == null ? "" : pageId.trim();
            title = title == null ? "" : title.trim();
        }
    }

    private AppsLauncherModel() {}

    /** Merge custom Live-UI tabs (pinned first) with saved custom page titles, deduplicating by pageId. */
    public static List<ScreenEntry> mergeScreens(
            Map<String, String> savedPages,
            List<LiveUiPatch.CustomTab> customTabs) {
        Map<String, ScreenEntry> ordered = new LinkedHashMap<>();
        if (customTabs != null) {
            for (LiveUiPatch.CustomTab tab : customTabs) {
                if (tab == null || tab.page == null || tab.page.trim().isEmpty()) continue;
                String id = tab.page.trim();
                String label = tab.label != null && !tab.label.trim().isEmpty() ? tab.label.trim() : id;
                ordered.putIfAbsent(id, new ScreenEntry(id, label, true));
            }
        }
        if (savedPages != null) {
            for (Map.Entry<String, String> entry : savedPages.entrySet()) {
                String id = entry.getKey() == null ? "" : entry.getKey().trim();
                if (id.isEmpty()) continue;
                String title = entry.getValue() == null || entry.getValue().trim().isEmpty()
                        ? id : entry.getValue().trim();
                ordered.putIfAbsent(id, new ScreenEntry(id, title, false));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(ordered.values()));
    }
}
