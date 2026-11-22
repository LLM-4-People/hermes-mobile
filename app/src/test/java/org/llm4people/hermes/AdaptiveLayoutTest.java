package org.llm4people.hermes;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdaptiveLayoutTest {
    @Test public void phoneAndSplitScreenStaySinglePaneWhileTabletPairsDiscussions() {
        AdaptiveLayout.ConversationGeometry phone = AdaptiveLayout.conversationPanes(360, 780, 1.0f, true, false, null);
        assertFalse(phone.dual());
        assertFalse(phone.sidebarVisible());
        assertTrue(phone.detailVisible());

        AdaptiveLayout.ConversationGeometry landscapePhone = AdaptiveLayout.conversationPanes(780, 360, 1.0f, true, false, null);
        assertFalse(landscapePhone.dual());
        assertFalse(landscapePhone.sidebarVisible());
        assertTrue(landscapePhone.detailVisible());

        AdaptiveLayout.ConversationGeometry splitHalf = AdaptiveLayout.conversationPanes(600, 800, 1.0f, true, false, null);
        assertFalse(splitHalf.dual());
        assertFalse(splitHalf.sidebarVisible());
        assertTrue(splitHalf.detailVisible());

        AdaptiveLayout.ConversationGeometry portraitTablet = AdaptiveLayout.conversationPanes(800, 1200, 1.0f, true, false, null);
        assertTrue(portraitTablet.dual());
        assertTrue(portraitTablet.sidebarVisible());
        assertTrue(portraitTablet.detailVisible());
        assertEquals(267, portraitTablet.railDp());

        AdaptiveLayout.ConversationGeometry landscapeTablet = AdaptiveLayout.conversationPanes(1200, 800, 1.0f, true, false, null);
        assertTrue(landscapeTablet.dual());
        assertTrue(landscapeTablet.sidebarVisible());
        assertEquals(320, landscapeTablet.railDp());
    }

    @Test public void openInstrumentAutoHidesSidebarWhenRoomIsTightAndRespectsManualToggle() {
        AdaptiveLayout.ConversationGeometry portraitWithTool = AdaptiveLayout.conversationPanes(
                800, 1200, 1.0f, true, true, null);
        assertTrue(portraitWithTool.dual());
        assertFalse(portraitWithTool.sidebarVisible());
        assertTrue(portraitWithTool.detailVisible());

        AdaptiveLayout.ConversationGeometry portraitForcedOpen = AdaptiveLayout.conversationPanes(
                800, 1200, 1.0f, true, true, Boolean.TRUE);
        assertTrue(portraitForcedOpen.dual());
        assertTrue(portraitForcedOpen.sidebarVisible());

        AdaptiveLayout.ConversationGeometry landscapeWithTool = AdaptiveLayout.conversationPanes(
                1200, 800, 1.0f, true, true, null);
        assertTrue(landscapeWithTool.dual());
        assertTrue(landscapeWithTool.sidebarVisible());

        AdaptiveLayout.ConversationGeometry landscapeForcedClosed = AdaptiveLayout.conversationPanes(
                1200, 800, 1.0f, true, false, Boolean.FALSE);
        assertTrue(landscapeForcedClosed.dual());
        assertFalse(landscapeForcedClosed.sidebarVisible());
    }

    @Test public void unselectedInboxShowsSidebarAndShowsEmptyDetailOnlyOnTablet() {
        AdaptiveLayout.ConversationGeometry phoneInbox = AdaptiveLayout.conversationPanes(360, 640, 1.0f, false, false, null);
        assertFalse(phoneInbox.dual());
        assertTrue(phoneInbox.sidebarVisible());
        assertFalse(phoneInbox.detailVisible());

        AdaptiveLayout.ConversationGeometry tabletInbox = AdaptiveLayout.conversationPanes(1000, 720, 1.0f, false, false, null);
        assertTrue(tabletInbox.dual());
        assertTrue(tabletInbox.sidebarVisible());
        assertTrue(tabletInbox.detailVisible());
    }

    @Test public void largeFontScaleCollapsesPortraitTabletToSinglePaneAndStacksNarrowPanels() {
        AdaptiveLayout.ConversationGeometry portraitLarge = AdaptiveLayout.conversationPanes(
                800, 1200, 1.5f, true, false, null);
        assertFalse(portraitLarge.dual());
        assertFalse(portraitLarge.sidebarVisible());

        AdaptiveLayout.ConversationGeometry landscapeLarge = AdaptiveLayout.conversationPanes(
                1200, 800, 1.5f, true, false, null);
        assertTrue(landscapeLarge.dual());
        assertEquals(348, landscapeLarge.railDp());

        AdaptiveLayout.PanelGeometry portraitPanel = AdaptiveLayout.panel(800, 1.5f, true, false);
        assertFalse(portraitPanel.paired());
        assertEquals(680, portraitPanel.sheetWidthDp());

        AdaptiveLayout.PanelGeometry landscapePanel = AdaptiveLayout.panel(1200, 1.5f, true, false);
        assertTrue(landscapePanel.paired());
        assertEquals(1040, landscapePanel.sheetWidthDp());

        AdaptiveLayout.PanelGeometry singleColumn = AdaptiveLayout.panel(1200, 1.5f, false, false);
        assertFalse(singleColumn.paired());
        assertEquals(680, singleColumn.sheetWidthDp());

        AdaptiveLayout.PanelGeometry wideSplitPicker = AdaptiveLayout.panel(1200, 1.0f, false, true);
        assertFalse(wideSplitPicker.paired());
        assertEquals(1040, wideSplitPicker.sheetWidthDp());
    }

    @Test public void splitThresholdsAndCenteredGuttersHandleNormalAndImproperInputs() {
        assertFalse(AdaptiveLayout.splitInstrument(639.9f));
        assertTrue(AdaptiveLayout.splitInstrument(640f));
        assertFalse(AdaptiveLayout.splitNearbyGuide(619.9f));
        assertTrue(AdaptiveLayout.splitNearbyGuide(620f));

        assertEquals(22, AdaptiveLayout.centeredGutterDp(360, 520, 22));
        assertEquals(340, AdaptiveLayout.centeredGutterDp(1200, 520, 22));
        assertEquals(260, AdaptiveLayout.centeredGutterDp(1200, 680, 22));

        // Negative, zero, NaN and infinite inputs must degrade safely without throwing.
        assertFalse(AdaptiveLayout.splitInstrument(Float.NaN));
        assertFalse(AdaptiveLayout.splitInstrument(-800f));
        assertFalse(AdaptiveLayout.splitNearbyGuide(Float.POSITIVE_INFINITY));
        assertEquals(22, AdaptiveLayout.centeredGutterDp(Float.NaN, 520, 22));
        assertEquals(0, AdaptiveLayout.centeredGutterDp(-100, -50, -10));

        AdaptiveLayout.ConversationGeometry invalid = AdaptiveLayout.conversationPanes(
                Float.NaN, -100, Float.NEGATIVE_INFINITY, true, false, null);
        assertFalse(invalid.dual());
        assertFalse(invalid.sidebarVisible());
        assertTrue(invalid.detailVisible());

        AdaptiveLayout.PanelGeometry invalidPanel = AdaptiveLayout.panel(Float.NaN, -2f, true, false);
        assertFalse(invalidPanel.paired());
        assertEquals(0, invalidPanel.sheetWidthDp());
    }
}
