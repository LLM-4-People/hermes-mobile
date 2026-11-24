package org.llm4people.hermes.hub;

import org.junit.Test;
import org.llm4people.hermes.artifact.LiveUiPatch;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

        Map<String, String> dirty = new LinkedHashMap<>();
        dirty.put(null, "Ignored");
        dirty.put("   ", "Ignored");
        dirty.put("page/raw", "   ");
        LiveUiPatch.CustomTab blankLabel = new LiveUiPatch.CustomTab("t_dup", "Dup", "page/raw");
        List<AppsLauncherModel.ScreenEntry> cleaned = AppsLauncherModel.mergeScreens(
                dirty, Arrays.asList(null, blankLabel));
        assertEquals(1, cleaned.size());
        assertEquals("page/raw", cleaned.get(0).pageId());
        assertEquals("Dup", cleaned.get(0).title());
        assertTrue(cleaned.get(0).pinnedTab());

        List<AppsLauncherModel.ScreenEntry> fallbackTitle = AppsLauncherModel.mergeScreens(dirty, null);
        assertEquals(1, fallbackTitle.size());
        assertEquals("page/raw", fallbackTitle.get(0).title());
        assertFalse(fallbackTitle.get(0).pinnedTab());

        try {
            fallbackTitle.add(new AppsLauncherModel.ScreenEntry("page/x", "X", false));
            fail("mergeScreens must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {}
    }
}
