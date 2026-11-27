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

    @Test public void widgetProviderStatusTextIsPrivacySafe() {
        assertEquals(org.llm4people.hermes.R.string.widget_status_private, HermesWidgetProvider.statusTextRes(false));
        assertEquals(org.llm4people.hermes.R.string.widget_status_working, HermesWidgetProvider.statusTextRes(true));
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
                false, null);
        HermesWidgetSpec redacted = privateSpec.redactedForHomeScreen(null);
        assertEquals("Hermes", redacted.title);
        assertFalse(redacted.subtitle.contains("salary"));
        assertTrue(redacted.items.isEmpty());
        assertNull(redacted.accentHex);

        // Secret-like tokens must be stripped even if homeScreenSafe is true.
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("Bearer abcdefgh12345678", 48));
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("sk-1234567890abcdef", 48));
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("eyJhbGciOiJIUzI1NiJ9.payload", 48));
        assertEquals("Bonjour", HermesWidgetSpec.sanitizeHomeText("Bon\u202Ejour\n", 48));
        // R20.4.2: Standalone invisible fillers & unattached variation selectors never yield phantom widget text.
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("\u3164".repeat(48), 48));
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("\uFE0F".repeat(48), 48));
        assertEquals("", HermesWidgetSpec.sanitizeHomeText("\u034F\u180F\u00A0", 48));
        assertEquals("☕\uFE0F Pause", HermesWidgetSpec.sanitizeHomeText("\u3164\uFE0F☕\uFE0F\uFE0F Pause", 48));
        // Draft actions must also reject secret-like tokens and invisible-only text while preserving newlines.
        assertEquals("", HermesWidgetSpec.sanitizeDraft("Bearer abcdefgh12345678"));
        assertEquals("", HermesWidgetSpec.sanitizeDraft("sk-1234567890abcdef"));
        assertEquals("", HermesWidgetSpec.sanitizeDraft("ghp_1234567890abcdef1234567890"));
        assertEquals("", HermesWidgetSpec.sanitizeDraft("\u3164\u034F\uFE0F\u2028\u2029"));
        assertEquals("Ligne 1\nLigne 2 ☕\uFE0F", HermesWidgetSpec.sanitizeDraft("\u3164Ligne 1\n\uFE0FLigne\u034F 2 ☕\uFE0F\uFE0F\u2028"));
        assertEquals("Prépare ma journée", HermesWidgetSpec.sanitizeDraft("Prépare ma journée"));

        // Invalid IDs, blank titles, and unsupported templates must fail cleanly.
        try {
            new HermesWidgetSpec("../bad", "Titre", "", HermesWidgetSpec.Template.STATUS, List.of(), null, true, null);
            fail("Invalid widget ID accepted");
        } catch (IllegalArgumentException expected) {}

        assertNull(HermesWidgetSpec.Template.parse("script"));
        try {
            HermesWidgetSpec.decode("v1\nb2s\nT2s\n\nscript\n1\n\n0");
            fail("Invalid widget template accepted");
        } catch (IllegalArgumentException expected) {}

        // Lossless v2 encode/decode roundtrip preserves ownerProfileId and redaction invariants,
        // while legacy v1 payloads remain readable.
        HermesWidgetSpec owned = privateSpec.withOwner("profile-alpha");
        HermesWidgetSpec decoded = HermesWidgetSpec.decode(owned.encode());
        assertEquals("daily_brief", decoded.widgetId);
        assertEquals("profile-alpha", decoded.ownerProfileId);
        assertFalse(decoded.homeScreenSafe);
        assertEquals("Hermes", decoded.redactedForHomeScreen(null).title);

        String legacyV1 = "v1\n" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("legacy_w".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n"
                + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Ancien".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n\nstatus\n1\n\n0";
        HermesWidgetSpec decodedV1 = HermesWidgetSpec.decode(legacyV1);
        assertEquals("legacy_w", decodedV1.widgetId);
        assertNull(decodedV1.ownerProfileId);
    }

    @Test public void widgetStoreIsolatesProfilesAndPurgesForgottenProfile() {
        MemoryBacking backing = new MemoryBacking();
        HermesWidgetStore store = new HermesWidgetStore(backing);

        HermesWidgetSpec aliceWidget = new HermesWidgetSpec(
                "alice_brief",
                "Alice",
                "Résumé",
                HermesWidgetSpec.Template.QUICK_ACTIONS,
                List.of(new HermesWidgetSpec.Item("Ouvrir", "", "Brouillon confidentiel Alice")),
                null,
                true,
                "profile-alice");
        HermesWidgetSpec bobWidget = new HermesWidgetSpec(
                "bob_brief",
                "Bob",
                "Résumé",
                HermesWidgetSpec.Template.QUICK_ACTIONS,
                List.of(new HermesWidgetSpec.Item("Ouvrir", "", "Brouillon Bob")),
                null,
                true,
                "profile-bob");
        store.upsertSpec(aliceWidget, "profile-alice");
        store.upsertSpec(bobWidget, "profile-bob");
        store.bindInstance(201, "alice_brief");
        store.bindInstance(202, "bob_brief");

        assertEquals("profile-alice", store.ownerForWidget("alice_brief"));
        assertEquals("Brouillon confidentiel Alice", store.draftForAction("alice_brief", 0, "profile-alice"));
        // Fail closed when another profile (or no profile) is active!
        assertNull(store.draftForAction("alice_brief", 0, "profile-bob"));
        assertNull(store.draftForAction("alice_brief", 0, null));

        // Purging Alice removes Alice's spec and bindings while keeping Bob intact.
        assertEquals(1, store.purgeProfile("profile-alice"));
        assertNull(store.spec("alice_brief"));
        assertNull(store.specForInstance(201));
        assertNotNull(store.spec("bob_brief"));
        assertNotNull(store.specForInstance(202));
        assertEquals("Brouillon Bob", store.draftForAction("bob_brief", 0, "profile-bob"));
    }

    @Test public void widgetStoreHandlesMultiInstanceBindingLiveUpdateRemovalAndCorruptRows() throws Exception {
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
                true, null);
        store.upsertSpec(v1);
        store.savePendingPin("tok-1", "kpi_morning");
        assertEquals("kpi_morning", store.consumePendingPin("tok-1"));
        assertNull(store.consumePendingPin("tok-1")); // one-shot

        store.bindInstance(101, "kpi_morning");
        store.bindInstance(102, "kpi_morning");
        assertEquals(2, store.instancesForWidget("kpi_morning").size());
        assertEquals("Matinée", store.specForInstance(101).title);
        assertEquals("#225C50", store.specForInstance(101).accentHex);
        assertEquals("Montre mes rappels du jour", store.draftForAction("kpi_morning", 0, null));
        assertNull(store.draftForAction("kpi_morning", 9, null));

        // Live update propagates to all bound instances.
        HermesWidgetSpec v2 = new HermesWidgetSpec(
                "kpi_morning",
                "Après-midi",
                "Mis à jour",
                HermesWidgetSpec.Template.CHECKLIST,
                Collections.singletonList(new HermesWidgetSpec.Item("Appeler Marie", "14h", "")),
                null,
                true, null);
        store.upsertSpec(v2);
        assertEquals("Après-midi", store.specForInstance(101).title);
        assertEquals("Après-midi", store.specForInstance(102).title);
        assertEquals("Appeler Marie · 14h", v2.formattedItemLine(v2.items.get(0)));
        assertFalse(v2.items.get(0).checked());

        // Checklist checked state round-trips through encode/decode without Unicode box/check prefixes.
        HermesWidgetSpec checklistSpec = new HermesWidgetSpec(
                "tasks_today", "Tâches", "", HermesWidgetSpec.Template.CHECKLIST,
                Arrays.asList(
                        new HermesWidgetSpec.Item("Envoyer le rapport", "", "", true),
                        new HermesWidgetSpec.Item("Appeler Paul", "15h", "", false)),
                "#1B5E50", true, "profile-a");
        HermesWidgetSpec decodedChecklist = HermesWidgetSpec.decode(checklistSpec.encode());
        assertEquals(2, decodedChecklist.items.size());
        assertTrue(decodedChecklist.items.get(0).checked());
        assertFalse(decodedChecklist.items.get(1).checked());
        assertEquals("Envoyer le rapport", decodedChecklist.formattedItemLine(decodedChecklist.items.get(0)));
        assertEquals("Appeler Paul · 15h", decodedChecklist.formattedItemLine(decodedChecklist.items.get(1)));

        // Legacy 3-column v2 records decode cleanly with checked=false, while invalid flag rejects.
        String legacyThreeCol = checklistSpec.encode().replace("\t1", "").replace("\t0", "");
        assertFalse(HermesWidgetSpec.decode(legacyThreeCol).items.get(0).checked());
        try {
            HermesWidgetSpec.decode(checklistSpec.encode().replace("\t1", "\t2"));
            fail("Expected invalid checked flag rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        // Quick actions render clean labels without Unicode "› " prefix (native drawable is attached in provider).
        HermesWidgetSpec quickSpec = new HermesWidgetSpec(
                "quick_nav", "Actions", "", HermesWidgetSpec.Template.QUICK_ACTIONS,
                Arrays.asList(
                        new HermesWidgetSpec.Item("Ouvrir", "", "Ouvre le résumé"),
                        new HermesWidgetSpec.Item("Rappels", "2", "Montre les rappels")),
                "#1B5E50", true, "profile-a");
        assertEquals("Ouvrir", quickSpec.formattedItemLine(quickSpec.items.get(0)));
        assertEquals("Rappels · 2", quickSpec.formattedItemLine(quickSpec.items.get(1)));
        assertFalse(quickSpec.formattedItemLine(quickSpec.items.get(0)).startsWith("›"));

        // HomePinBridge preserves checked boolean cleanly without prepending "✓ ".
        org.llm4people.hermes.artifact.LiveUiPatch.WidgetSpec liveSpec =
                new org.llm4people.hermes.artifact.LiveUiPatch.WidgetSpec(
                        "chk_1", "Courses", "", "checklist",
                        Arrays.asList(
                                new org.llm4people.hermes.artifact.LiveUiPatch.WidgetItem("Lait", "", Boolean.TRUE, ""),
                                new org.llm4people.hermes.artifact.LiveUiPatch.WidgetItem("Pain", "", Boolean.FALSE, "")),
                        "#1B5E50", null, true);
        HermesWidgetSpec converted = org.llm4people.hermes.artifact.HomePinBridge.toOsWidgetSpec(liveSpec, "profile-a");
        assertNotNull(converted);
        assertEquals("Lait", converted.items.get(0).label());
        assertTrue(converted.items.get(0).checked());
        assertEquals("Pain", converted.items.get(1).label());
        assertFalse(converted.items.get(1).checked());

        // Unbinding one instance keeps the other intact.
        store.unbindInstance(101);
        assertNull(store.specForInstance(101));
        assertNotNull(store.specForInstance(102));

        // Removing the spec unbinds remaining instances.
        assertTrue(store.removeSpec("kpi_morning", null));
        assertFalse(store.removeSpec("kpi_morning", null));
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


    private static HermesWidgetSpec brief(String widgetId, String title, String draft) {
        return new HermesWidgetSpec(widgetId, title, "Résumé", HermesWidgetSpec.Template.QUICK_ACTIONS,
                List.of(new HermesWidgetSpec.Item("Ouvrir", "", draft)), null, true, null);
    }

    @Test public void widgetStoreRefusesCrossProfileOverwriteOwnerStripAndRemoval() {
        HermesWidgetStore store = new HermesWidgetStore(new MemoryBacking());
        assertTrue(store.upsertSpec(brief("daily_brief", "Alice", "Brouillon Alice"), "profile-alice"));
        store.bindInstance(301, "daily_brief");

        // Another profile with a colliding ID, or an unowned write, can neither overwrite nor strip ownership.
        assertFalse(store.upsertSpec(brief("daily_brief", "Bob", "Brouillon Bob"), "profile-bob"));
        assertFalse(store.upsertSpec(brief("daily_brief", "Sans profil", "Brouillon anonyme")));
        assertEquals("profile-alice", store.ownerForWidget("daily_brief"));
        assertEquals("Alice", store.spec("daily_brief").title);
        assertEquals("Brouillon Alice", store.draftForAction("daily_brief", 0, "profile-alice"));
        assertNull(store.draftForAction("daily_brief", 0, "profile-bob"));

        // Removal is owner-guarded and keeps the bound launcher instance intact.
        assertFalse(store.removeSpec("daily_brief", "profile-bob"));
        assertFalse(store.removeSpec("daily_brief", null));
        assertNotNull(store.specForInstance(301));

        // The owner keeps full control.
        assertTrue(store.upsertSpec(brief("daily_brief", "Alice v2", "Brouillon Alice"), "profile-alice"));
        assertEquals("Alice v2", store.spec("daily_brief").title);
        assertTrue(store.removeSpec("daily_brief", "profile-alice"));
        assertNull(store.specForInstance(301));

        // A legacy unowned spec may be claimed (then it is protected like any other).
        assertTrue(store.upsertSpec(brief("legacy_brief", "Ancien", "")));
        assertTrue(store.upsertSpec(brief("legacy_brief", "Ancien", ""), "profile-bob"));
        assertEquals("profile-bob", store.ownerForWidget("legacy_brief"));
        assertFalse(store.upsertSpec(brief("legacy_brief", "Ancien", ""), "profile-alice"));
        assertEquals(1, store.purgeProfile("profile-bob"));
        assertNull(store.spec("legacy_brief"));
    }

    @Test public void quickActionsWidgetIdIsProfileScopedStableAndOpaque() {
        String alice = "0f8fad5b-d9cb-469f-a165-70867728950e";
        String bob = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
        String aliceId = HermesWidgetStore.profileScopedId("hermes_quick_actions", alice);
        String bobId = HermesWidgetStore.profileScopedId("hermes_quick_actions", bob);
        assertNotEquals(aliceId, bobId);
        assertEquals(aliceId, HermesWidgetStore.profileScopedId("hermes_quick_actions", alice));
        assertTrue(aliceId.startsWith("hermes_quick_actions_"));
        assertTrue(HermesWidgetSpec.validWidgetId(aliceId));
        assertFalse(aliceId.contains(alice));
        assertEquals("hermes_quick_actions", HermesWidgetStore.profileScopedId("hermes_quick_actions", null));
        try {
            HermesWidgetStore.profileScopedId("../escape", alice);
            fail("Invalid base widget id accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
