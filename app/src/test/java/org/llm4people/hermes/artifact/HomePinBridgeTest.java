package org.llm4people.hermes.artifact;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.push.DeliveryRouter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
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
                "#1E5B78",
                true,
                false);

        HermesWidgetSpec osSpec = HomePinBridge.toOsWidgetSpec(live);
        assertNotNull(osSpec);
        assertEquals("morning_plan", osSpec.widgetId);
        assertEquals("Ma journée", osSpec.title);
        assertEquals("3 priorités", osSpec.subtitle);
        assertEquals(HermesWidgetSpec.Template.CHECKLIST, osSpec.template);
        assertEquals("#1E5B78", osSpec.accentHex);
        assertTrue(osSpec.homeScreenSafe);
        assertEquals(HermesWidgetSpec.MAX_ITEMS, osSpec.items.size());
        assertEquals("✓ Appeler le dentiste", osSpec.items.get(0).label());
        assertEquals("09h", osSpec.items.get(0).value());
        assertEquals("Rappelle-moi d’appeler le dentiste", osSpec.items.get(0).actionDraft());
        assertEquals("Envoyer le devis", osSpec.items.get(1).label());
    }

    @Test
    public void rejectsNullLiveUiWidgetSpec() {
        assertNull(HomePinBridge.toOsWidgetSpec(null));
    }

    @Test
    public void parsesHomeScreenSafeDeliveryWidgetUpdateAndRejectsUnsafeOrSecretPayloads() throws Exception {
        JSONObject valid = new JSONObject()
                .put("widget", new JSONObject()
                        .put("widget_id", "daily_brief")
                        .put("title", "Résumé du matin")
                        .put("subtitle", "Calme et prêt")
                        .put("template", "briefing")
                        .put("home_screen_safe", true)
                        .put("items", new JSONArray()
                                .put(new JSONObject().put("label", "Météo").put("value", "18°C"))));

        HermesWidgetSpec parsed = HomePinBridge.parseDeliveryWidgetSpec(valid);
        assertNotNull(parsed);
        assertEquals("daily_brief", parsed.widgetId);
        assertEquals(HermesWidgetSpec.Template.BRIEFING, parsed.template);
        assertEquals(1, parsed.items.size());

        JSONObject unsafe = new JSONObject()
                .put("widget_id", "private_notes")
                .put("title", "Secret")
                .put("template", "status")
                .put("home_screen_safe", false);
        assertNull(HomePinBridge.parseDeliveryWidgetSpec(unsafe));

        JSONObject missingFlag = new JSONObject()
                .put("widget_id", "missing_opt_in")
                .put("title", "Implicit")
                .put("template", "status");
        assertNull(HomePinBridge.parseDeliveryWidgetSpec(missingFlag));

        JSONObject secretTitle = new JSONObject()
                .put("widget_id", "leak_test")
                .put("title", "Bearer eyJhbGciOiJIUzI1NiJ9.secret")
                .put("template", "status")
                .put("home_screen_safe", true);
        assertNull(HomePinBridge.parseDeliveryWidgetSpec(secretTitle));
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
        assertTrue(registry.dispatch("activite/livraisons"));
        assertTrue(registry.dispatch("activite/preferences_notifications"));
        assertEquals(1, memoryOpened.get());
        assertEquals(1, deliveriesOpened.get());
        assertEquals(1, policyOpened.get());
    }
}
