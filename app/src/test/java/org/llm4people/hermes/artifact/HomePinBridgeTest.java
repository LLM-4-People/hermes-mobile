package org.llm4people.hermes.artifact;

import org.junit.Test;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.push.DeliveryRouter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Deterministic JVM unit tests verifying PR #14 <-> PR #17 <-> PR #18 <-> PR #19 cross-feature wiring. */
public final class HomePinBridgeTest {

    @Test
    public void convertsLiveUiWidgetSpecIntoOsWidgetSpecWithCheckedItemsAndBoundLimit() throws Exception {
        LiveUiPatch.WidgetSpec live = new LiveUiPatch.WidgetSpec(
                "morning_plan",
                "Ma journée",
                "3 priorités",
                "checklist",
                List.of(
                        new LiveUiPatch.WidgetItem("Appeler le dentiste", "09h", Boolean.TRUE, "Rappelle-moi d’appeler le dentiste"),
                        new LiveUiPatch.WidgetItem("Envoyer le devis", "11h", Boolean.FALSE, ""),
                        new LiveUiPatch.WidgetItem("Course 3", "", null, ""),
                        new LiveUiPatch.WidgetItem("Course 4", "", null, ""),
                        new LiveUiPatch.WidgetItem("Course 5", "", null, "")
                ),
                "#1E5B78", null, true);

        HermesWidgetSpec osSpec = HomePinBridge.toOsWidgetSpec(live, "profile-a");
        assertNotNull(osSpec);
        assertEquals("morning_plan", osSpec.widgetId);
        assertEquals("Ma journée", osSpec.title);
        assertEquals("3 priorités", osSpec.subtitle);
        assertEquals(HermesWidgetSpec.Template.CHECKLIST, osSpec.template);
        assertEquals("#1E5B78", osSpec.accentHex);
        assertTrue(osSpec.homeScreenSafe);
        assertEquals(HermesWidgetSpec.MAX_ITEMS, osSpec.items.size());
        assertEquals("Appeler le dentiste", osSpec.items.get(0).label());
        assertTrue(osSpec.items.get(0).checked());
        assertEquals("09h", osSpec.items.get(0).value());
        assertEquals("Rappelle-moi d’appeler le dentiste", osSpec.items.get(0).actionDraft());
        assertEquals("Envoyer le devis", osSpec.items.get(1).label());
        assertFalse(osSpec.items.get(1).checked());

        // Lossless codec round-trip into HermesWidgetStore format.
        HermesWidgetSpec decoded = HermesWidgetSpec.decode(osSpec.encode());
        assertEquals(osSpec.widgetId, decoded.widgetId);
        assertEquals(osSpec.title, decoded.title);
        assertEquals(osSpec.items.size(), decoded.items.size());
        assertTrue(decoded.items.get(0).checked());
        assertFalse(decoded.items.get(1).checked());
    }

    @Test
    public void rejectsNullLiveUiWidgetSpecAndValidatesSurfaceShortcutIds() {
        assertNull(HomePinBridge.toOsWidgetSpec(null, "profile-a"));
        assertNull(HomePinBridge.parseDeliveryWidgetSpec(null));
        assertTrue(HomePinBridge.isValidSurfaceId("page/tableau-budget"));
        assertFalse(HomePinBridge.isValidSurfaceId(null));
        assertFalse(HomePinBridge.isValidSurfaceId(""));
        assertFalse(HomePinBridge.isValidSurfaceId("home"));
        assertFalse(HomePinBridge.isValidSurfaceId("page/../secrets"));
    }

    @Test
    public void surfaceTitleRejectsSecretPrefixAndShortShortcutLabelNeverSplitsSurrogatePair() {
        String secretTitle = "Daily ops status summary - sk-1234567890abcdef";
        assertEquals("", HomePinBridge.safeSurfaceTitle(secretTitle));
        assertEquals("", HomePinBridge.safeShortShortcutLabel(secretTitle));
        assertEquals("Daily ops status", HomePinBridge.safeSurfaceTitle("  Daily ops status  "));

        // 26 UTF-16 code units with 📊 (surrogate pair) spanning indices 24..25 must not split the surrogate pair.
        String emojiAtBoundary = "a".repeat(24) + "\uD83D\uDCCA";
        assertEquals(26, emojiAtBoundary.length());
        String shortLabel = HomePinBridge.safeShortShortcutLabel(emojiAtBoundary);
        assertEquals("a".repeat(24), shortLabel);
        assertFalse(Character.isHighSurrogate(shortLabel.charAt(shortLabel.length() - 1)));
    }

    @Test
    public void widgetRefreshGuardSparklineContrastAndSyntaxGutterScaleWithSp() {
        HermesWidgetSpec aliceWidget = new HermesWidgetSpec(
                "w_alice", "Alice", "", HermesWidgetSpec.Template.STATUS, List.of(), null, true, "alice");
        HermesWidgetSpec unownedWidget = new HermesWidgetSpec(
                "w_shared", "Shared", "", HermesWidgetSpec.Template.STATUS, List.of(), null, true, null);
        assertTrue(HomePinBridge.canRefreshWidget(aliceWidget, "alice"));
        assertFalse(HomePinBridge.canRefreshWidget(aliceWidget, "bob"));
        assertFalse(HomePinBridge.canRefreshWidget(aliceWidget, null));
        assertFalse(HomePinBridge.canRefreshWidget(null, "alice"));
        assertTrue(HomePinBridge.canRefreshWidget(unownedWidget, "bob"));

        for (boolean night : new boolean[]{false, true}) {
            ThemePalette palette = ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), night);
            int muted = SparklineView.resolveMutedBarColor(palette);
            assertTrue("Sparkline muted bar contrast must be >= 3.0:1 (night=" + night + ")",
                    ThemePalette.contrastRatio(muted, palette.surface) >= 3.0);
        }

        int normalGutter = SyntaxHighlighter.computeGutterWidthPx(3, 2.0f, 2.0f);
        int largeFontGutter = SyntaxHighlighter.computeGutterWidthPx(3, 4.0f, 2.0f);
        assertTrue("Gutter width must grow when fontScale/sp increases", largeFontGutter > normalGutter);

        // DeviceAction rejects strippable invisibles (e.g. Hangul filler U+3164) in displayable fields.
        assertFalse(org.llm4people.hermes.PlainTextSanitizer.hasVisibleText("\u3164"));
        assertTrue(org.llm4people.hermes.PlainTextSanitizer.isStrippableInvisible(0x3164));
    }

    @Test
    public void unifiedRouteRegistryRegistersBrainStudioAndActivityDeliveryRoutesTogether() {
        AtomicInteger deliveriesOpened = new AtomicInteger();
        AtomicInteger policyOpened = new AtomicInteger();
        AtomicInteger memoryOpened = new AtomicInteger();

        BrainStudioRoutes.Handlers brain = new BrainStudioRoutes.Handlers() {
            @Override public void openMemory() { memoryOpened.incrementAndGet(); }
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
        };

        RouteRegistry registry = BrainStudioRoutes.register(new RouteRegistry(), brain);
        DeliveryRouter.registerRoutes(registry, deliveriesOpened::incrementAndGet, policyOpened::incrementAndGet);

        assertTrue(registry.dispatch(BrainStudioRoutes.ROUTE_MEMORY));
        assertTrue(registry.dispatch(DeliveryRouter.ROUTE_DELIVERIES));
        assertTrue(registry.dispatch(DeliveryRouter.ROUTE_PUSH_POLICY));
        assertEquals(1, memoryOpened.get());
        assertEquals(1, deliveriesOpened.get());
        assertEquals(1, policyOpened.get());
    }

    @Test public void widgetIdsUpTo64CharsAndDigitLeadingAreValidAndDeadBroadcastRemoved() throws Exception {
        // R37.3.2 / R37.4.1: 64-char and digit-leading widget IDs stay valid without a "widget-" prefix.
        String id64 = "a".repeat(64);
        String digitLeading = "123_daily_status";
        for (String wid : new String[]{id64, digitLeading}) {
            assertTrue("Expected validWidgetId for " + wid,
                    org.llm4people.hermes.os.HermesWidgetSpec.validWidgetId(wid));
            LiveUiPatch.WidgetSpec spec = new LiveUiPatch.WidgetSpec(
                    wid, "Status", "Ready", "status", java.util.List.of(), null, null, true);
            org.llm4people.hermes.os.HermesWidgetSpec osSpec = HomePinBridge.toOsWidgetSpec(spec, "profile-a");
            assertNotNull("Expected toOsWidgetSpec to succeed for " + wid, osSpec);
            assertEquals(wid, osSpec.widgetId);
            assertEquals(wid, HomePinBridge.widgetShortcutId(osSpec));
            assertTrue(HomePinBridge.hasValidShortcutTarget(null, wid));
            assertTrue(osSpec.widgetId.length() <= 64);
        }

        // R38.8.2: positive & negative shortcut target / null-spec / null-activity guards.
        assertNull(HomePinBridge.widgetShortcutId(null));
        assertTrue(HomePinBridge.hasValidShortcutTarget("page/demo-panel", null));
        assertFalse(HomePinBridge.hasValidShortcutTarget(null, null));
        assertFalse(HomePinBridge.hasValidShortcutTarget("", ""));
        assertFalse(HomePinBridge.hasValidShortcutTarget("invalid-surface", "bad widget id!"));
        assertFalse(HomePinBridge.hasValidShortcutTarget("invalid-surface", "a".repeat(65)));
        assertFalse(HomePinBridge.requestPinShortcut(null, "w1", "Status", null, "w1"));

        // R37.4.2: dead ACTION_WIDGET_UPDATED and broadcastWidgetUpdated must be absent.
        for (java.lang.reflect.Field f : HomePinBridge.class.getDeclaredFields()) {
            assertFalse("Dead ACTION_WIDGET_UPDATED must be removed",
                    "ACTION_WIDGET_UPDATED".equals(f.getName()));
        }
        for (java.lang.reflect.Method m : HomePinBridge.class.getDeclaredMethods()) {
            assertFalse("Dead broadcastWidgetUpdated must be removed",
                    "broadcastWidgetUpdated".equals(m.getName()));
        }
    }
}
