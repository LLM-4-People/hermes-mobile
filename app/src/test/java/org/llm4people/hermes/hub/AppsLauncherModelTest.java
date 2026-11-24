package org.llm4people.hermes.hub;

import org.junit.Test;
import org.llm4people.hermes.artifact.LiveUiPatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppsLauncherModelTest {

    @Test
    public void mergeScreensDeduplicatesByPageIdAndOrdersPinnedTabsFirst() throws Exception {
        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("page/tracker", "Daily tracker");
        saved.put("page/budget", "Budget");

        LiveUiPatch.CustomTab tab = new LiveUiPatch.CustomTab("t_tracker", "Tracker tab", "page/tracker");
        List<AppsLauncherModel.ScreenEntry> merged = AppsLauncherModel.mergeScreens(saved, List.of(tab));

        assertEquals(2, merged.size());
        assertEquals("page/tracker", merged.get(0).pageId());
        assertEquals("Tracker tab", merged.get(0).title());
        assertTrue(merged.get(0).pinnedTab());
        assertEquals("page/budget", merged.get(1).pageId());
        assertEquals("Budget", merged.get(1).title());
        assertFalse(merged.get(1).pinnedTab());

        assertTrue(AppsLauncherModel.mergeScreens(null, null).isEmpty());
    }
}
