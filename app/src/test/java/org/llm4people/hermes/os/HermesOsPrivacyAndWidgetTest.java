package org.llm4people.hermes.os;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class HermesOsPrivacyAndWidgetTest {
    private static final class MemoryBacking implements HermesWidgetStore.Backing {
        final Map<String, String> data = new LinkedHashMap<>();
        @Override public String get(String key) { return data.get(key); }
        @Override public void put(String key, String value) { data.put(key, value); }
        @Override public void remove(String key) { data.remove(key); }
        @Override public Map<String, String> all() { return new LinkedHashMap<>(data); }
    }

    @Test public void dynamicShortcutsStayOpaqueBoundedAndPrivacySafe() {
        List<HermesShortcuts.Descriptor> list = HermesShortcuts.descriptors();
        assertEquals(4, list.size());
        for (HermesShortcuts.Descriptor item : list) {
            assertTrue(item.id().startsWith("hermes_"));
            assertFalse(item.defaultShortLabel().isEmpty());
            assertNotNull(item.target());
        }
        assertEquals("Assistant privé", HermesWidgetProvider.statusText(false));
        assertEquals("Travail en cours", HermesWidgetProvider.statusText(true));
    }

    @Test public void osEntryTargetAllowlistRejectsArbitraryValues() {
        assertEquals(OsEntryIntent.Target.ASK, OsEntryIntent.Target.parse("ask"));
        assertEquals(OsEntryIntent.Target.DICTATE, OsEntryIntent.Target.parse("dictate"));
        assertEquals(OsEntryIntent.Target.WIDGET_ACTION, OsEntryIntent.Target.parse("widget_action"));
        assertNull(OsEntryIntent.Target.parse(null));
        assertNull(OsEntryIntent.Target.parse(""));
        assertNull(OsEntryIntent.Target.parse("shell_exec"));
        assertNull(OsEntryIntent.Target.parse("javascript:alert(1)"));
    }

    @Test public void widgetSpecEnforcesHomeScreenSafePrivacyAndSecretRedaction() {
        HermesWidgetSpec privateSpec = new HermesWidgetSpec(
                "daily_brief",
                "Secret thread title",
                "Private salary note",
                HermesWidgetSpec.Template.BRIEFING,
                List.of(new HermesWidgetSpec.Item("Confidential", "1234", "Send secret")),
                "#225C50",
                false);
        HermesWidgetSpec redacted = privateSpec.redactedForHomeScreen();
        assertEquals("Hermes", redacted.title);
        assertFalse(redacted.subtitle.contains("salary"));
        assertTrue(redacted.items.isEmpty());
        assertNull(redacted.accentHex);

        // Secret-like tokens must be stripped even if homeScreenSafe is true.
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("Bearer abcdefgh12345678", 48));
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("sk-1234567890abcdef", 48));
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("eyJhbGciOiJIUzI1NiJ9.payload", 48));
        assertEquals("Bonjour", HermesWidgetSpec.sanitizeHomeText("Bon\u202Ejour\n", 48));

        // Invalid IDs, blank titles, and unsupported templates must fail cleanly.
        try {
            new HermesWidgetSpec("../bad", "Titre", "", HermesWidgetSpec.Template.STATUS, List.of(), null, true);
            fail("Invalid widget ID accepted");
        } catch (IllegalArgumentException expected) {}

        assertNull(HermesWidgetSpec.Template.parse("script"));
        try {
            HermesWidgetSpec.decode("v1\nb2s\nT2s\n\nscript\n1\n\n0");
            fail("Invalid widget template accepted");
        } catch (IllegalArgumentException expected) {}

        // Lossless encode/decode roundtrip preserves fields and redaction invariants.
        HermesWidgetSpec decoded = HermesWidgetSpec.decode(privateSpec.encode());
        assertEquals("daily_brief", decoded.widgetId);
        assertFalse(decoded.homeScreenSafe);
        assertEquals("Hermes", decoded.redactedForHomeScreen().title);
    }

    @Test public void widgetStoreHandlesMultiInstanceBindingLiveUpdateRemovalAndCorruptRows() {
        MemoryBacking backing = new MemoryBacking();
        HermesWidgetStore store = new HermesWidgetStore(backing);

        HermesWidgetSpec v1 = new HermesWidgetSpec(
                "kpi_morning",
                "Matinée",
                "Résumé du jour",
                HermesWidgetSpec.Template.METRIC,
                Arrays.asList(
                        new HermesWidgetSpec.Item("Rappels", "2", "Montre mes rappels du jour"),
                        new HermesWidgetSpec.Item("Météo", "18 °C", "Donne la météo du jour")
                ),
                "#225c50",
                true);
        store.upsertSpec(v1);
        store.savePendingPin("tok-1", "kpi_morning");
        assertEquals("kpi_morning", store.consumePendingPin("tok-1"));
        assertNull(store.consumePendingPin("tok-1")); // one-shot

        store.bindInstance(101, "kpi_morning");
        store.bindInstance(102, "kpi_morning");
        assertEquals(2, store.instancesForWidget("kpi_morning").size());
        assertEquals("Matinée", store.specForInstance(101).title);
        assertEquals("#225C50", store.specForInstance(101).accentHex);
        assertEquals("Montre mes rappels du jour", store.draftForAction("kpi_morning", 0));
        assertNull(store.draftForAction("kpi_morning", 9));

        // Live update propagates to all bound instances.
        HermesWidgetSpec v2 = new HermesWidgetSpec(
                "kpi_morning",
                "Après-midi",
                "Mis à jour",
                HermesWidgetSpec.Template.CHECKLIST,
                Collections.singletonList(new HermesWidgetSpec.Item("Appeler Marie", "14h", "")),
                null,
                true);
        store.upsertSpec(v2);
        assertEquals("Après-midi", store.specForInstance(101).title);
        assertEquals("Après-midi", store.specForInstance(102).title);
        assertEquals("☐ Appeler Marie · 14h", v2.formattedItemLine(v2.items.get(0)));

        // Unbinding one instance keeps the other intact.
        store.unbindInstance(101);
        assertNull(store.specForInstance(101));
        assertNotNull(store.specForInstance(102));

        // Removing the spec unbinds remaining instances.
        assertTrue(store.removeSpec("kpi_morning"));
        assertFalse(store.removeSpec("kpi_morning"));
        assertNull(store.specForInstance(102));
        assertTrue(store.instancesForWidget("kpi_morning").isEmpty());

        // Corrupt JSON degrades safely to null without throwing.
        backing.put("spec.broken_widget", "{not-json");
        assertNull(store.spec("broken_widget"));
        assertTrue(store.allSpecs().isEmpty());

        // Invalid appWidgetId must be rejected.
        try {
            store.bindInstance(-1, "kpi_morning");
            fail("Invalid appWidgetId accepted");
        } catch (IllegalArgumentException expected) {}
    }
}
