package org.llm4people.hermes.presence;

import org.junit.Test;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.customization.InteractiveCardView;
import org.llm4people.hermes.push.PushPolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Deterministic host-JVM tests for Track 9B:
 * fail-closed PresencePolicy, AppPresenceTracker (profileEpoch + read-without-reply),
 * ContinuousLocationTracker (coarse quantization + moving state + zero-retention wipe),
 * AppCustomization WCAG AA contrast validator, AppCustomizationStore Undo/Reset/wipe,
 * InteractiveCardView.Spec validation, and all 9 DeepAndroidActions.
 */
public final class PresenceAndCustomizationTest {
    private static final class MemoryBacking implements AppCustomizationStore.Backing {
        String stored;
        @Override public String read() { return stored; }
        @Override public void write(String value) { stored = value; }
        @Override public void remove() { stored = null; }
    }

    @Test
    public void presencePolicyDefaultsFailClosedAndRejectsCorruptRecords() {
        PresencePolicy def = PresencePolicy.defaults();
        assertFalse(def.shareAppPresence);
        assertFalse(def.shareReadWithoutReply);
        assertFalse(def.shareDraftPreview);
        assertFalse(def.continuousLocationEnabled);
        assertFalse(def.preciseLocation);
        assertFalse(def.locationPaused);
        assertFalse(def.proactiveNudgesEnabled);
        assertFalse(def.allowsLiveLocation());
        assertFalse(def.anySharingEnabled());
        assertEquals(PresencePolicy.DEFAULT_READ_GRACE_SEC, def.readWithoutReplyGraceSec);
        assertEquals(PresencePolicy.DEFAULT_MAX_NUDGES_PER_HOUR, def.maxNudgesPerHour);

        // Dependent flags fail closed when parent flag is OFF.
        PresencePolicy dep = def.withDraftPreview(true).withPreciseLocation(true).withLocationPaused(true);
        assertFalse(dep.shareDraftPreview);
        assertFalse(dep.preciseLocation);
        assertFalse(dep.locationPaused);

        // Encode/decode round-trip preserves opt-ins and clamps bounds.
        PresencePolicy optedIn = def.withAppPresence(true)
                .withReadWithoutReply(true)
                .withContinuousLocation(true)
                .withPreciseLocation(true)
                .withProactiveNudges(true)
                .withReadGraceSec(1)     // clamped to 5
                .withMaxNudgesPerHour(99); // clamped to 10
        assertEquals(PresencePolicy.MIN_READ_GRACE_SEC, optedIn.readWithoutReplyGraceSec);
        assertEquals(PresencePolicy.MAX_MAX_NUDGES_PER_HOUR, optedIn.maxNudgesPerHour);
        assertEquals(optedIn, PresencePolicy.decode(optedIn.encode()));

        // Corrupt records fail closed to all-OFF defaults.
        assertEquals(def, PresencePolicy.decode("v2|1|1|1|1|1|0|1|30|3"));
        assertEquals(def, PresencePolicy.decode("corrupt-payload"));
        assertEquals(def, PresencePolicy.decode("v1|1|2|0|0|0|0|0|30|3"));
    }

    @Test
    public void appPresenceTrackerEnforcesEpochFencingDraftPrivacyAndReadWithoutReply() {
        AppPresenceTracker tracker = new AppPresenceTracker();
        long epoch1 = tracker.bindProfile("profile-a", 10L);
        assertTrue(tracker.updateScreen(epoch1, "thread", "sess-1"));
        assertTrue(tracker.updateActiveRunCount(epoch1, 2));
        assertTrue(tracker.updateDraft(epoch1, "Mon brouillon privé"));

        // Stale epoch is ignored cleanly.
        assertFalse(tracker.updateScreen(epoch1 - 1, "atelier", "sess-other"));

        // Fail-closed: when policy is all OFF, wire context is null!
        long t0 = 1_000_000L;
        assertNull(tracker.toWireContextJson(PresencePolicy.defaults(), null, t0));

        // With app presence ON (draft preview OFF), draft_chars is sent, draft text is NEVER leaked!
        PresencePolicy screenOnly = PresencePolicy.defaults().withAppPresence(true);
        String json = tracker.toWireContextJson(screenOnly, null, t0);
        assertNotNull(json);
        assertTrue(json.contains("\"current_screen\":\"thread\""));
        assertTrue(json.contains("\"active_run_count\":2"));
        assertTrue(json.contains("\"draft_chars\":19"));
        assertFalse("Draft text must never leak unless explicitly opted in", json.contains("brouillon"));

        // Secret-like draft text is scrubbed even when draft preview is explicitly opted in.
        tracker.updateDraft(epoch1, "Bearer sk-supersecret1234567890");
        String withSecret = tracker.toWireContextJson(screenOnly.withDraftPreview(true), null, t0);
        assertFalse(withSecret.contains("sk-supersecret"));

        // Read-without-reply lifecycle: delivered -> read -> grace window -> reply clears immediately.
        PresencePolicy rwrPolicy = PresencePolicy.defaults()
                .withReadWithoutReply(true)
                .withReadGraceSec(30);
        tracker.recordAssistantMessageDelivered(epoch1, "sess-1", "msg-42");
        tracker.markAssistantMessageRead(epoch1, "sess-1", "msg-42", t0);

        // Before grace window (20s < 30s): not yet flagged.
        assertFalse(tracker.evaluateReadWithoutReply(rwrPolicy, t0 + 20_000L).inAppUnreadReply());

        // After grace window (35s >= 30s): flagged!
        AppPresenceTracker.ReadWithoutReplyState flagged = tracker.evaluateReadWithoutReply(rwrPolicy, t0 + 35_000L);
        assertTrue(flagged.inAppUnreadReply());
        assertEquals("sess-1", flagged.sessionId());
        assertEquals("msg-42", flagged.messageId());
        assertEquals(35L, flagged.readAgeSec());

        // Opted-in notification read/dismissed without reply also surfaces after grace and clears on reply.
        tracker.recordNotificationReadWithoutReply(epoch1, "notif-1", "Messages", t0);
        AppPresenceTracker.ReadWithoutReplyState withNotif = tracker.evaluateReadWithoutReply(rwrPolicy, t0 + 40_000L);
        assertEquals(1, withNotif.dismissedNotifications().size());
        assertEquals("Messages", withNotif.dismissedNotifications().get(0).appLabel());

        tracker.recordNotificationReplied(epoch1, "notif-1");
        assertTrue(tracker.evaluateReadWithoutReply(rwrPolicy, t0 + 45_000L).dismissedNotifications().isEmpty());

        // User reply immediately clears in-app read-without-reply!
        tracker.recordUserReplySent(epoch1, "sess-1");
        assertFalse(tracker.evaluateReadWithoutReply(rwrPolicy, t0 + 50_000L).inAppUnreadReply());

        // Lock wipes all state and bumps epoch so old callbacks cannot write back.
        tracker.clearOnLock();
        assertNull(tracker.toWireContextJson(rwrPolicy.withAppPresence(true), null, t0 + 60_000L));
        assertFalse(tracker.updateScreen(epoch1, "atelier", "sess-1"));
    }

    @Test
    public void continuousLocationTrackerRespectsCoarseQuantizationMovementAndZeroRetention() {
        ContinuousLocationTracker tracker = new ContinuousLocationTracker();
        PresencePolicy off = PresencePolicy.defaults();
        PresencePolicy coarseOn = off.withContinuousLocation(true);
        PresencePolicy paused = coarseOn.withLocationPaused(true);
        PresencePolicy preciseOn = coarseOn.withPreciseLocation(true);

        assertEquals(ContinuousLocationTracker.Indicator.OFF, ContinuousLocationTracker.indicatorFor(off));
        assertEquals(ContinuousLocationTracker.Indicator.ACTIVE_COARSE, ContinuousLocationTracker.indicatorFor(coarseOn));
        assertEquals(ContinuousLocationTracker.Indicator.PAUSED, ContinuousLocationTracker.indicatorFor(paused));
        assertEquals(ContinuousLocationTracker.Indicator.ACTIVE_PRECISE, ContinuousLocationTracker.indicatorFor(preciseOn));

        long t0 = 5_000_000L;
        ContinuousLocationTracker.Snapshot snap = tracker.recordFix(
                45.50169, -73.56725, 18.0f, 0.0f, "Montréal", t0, false);
        assertEquals(45.50, snap.lat(), 0.0001);
        assertEquals(-73.57, snap.lon(), 0.0001);
        assertTrue("Coarse mode must enforce >= 500m accuracy floor", snap.accuracyM() >= 500.0f);
        assertEquals(ContinuousLocationTracker.MovingState.STATIONARY, snap.movingState());

        // Fail-closed: OFF or PAUSED returns null even if a fix was recorded.
        assertNull(tracker.currentSnapshot(off, t0 + 1_000L));
        assertNull(tracker.currentSnapshot(paused, t0 + 1_000L));
        assertNotNull(tracker.currentSnapshot(coarseOn, t0 + 1_000L));

        // Stale fix (> 30 min) is suppressed automatically.
        assertNull(tracker.currentSnapshot(coarseOn, t0 + ContinuousLocationTracker.STALE_AFTER_MS + 1_000L));

        // Wire JSON emits opted_in:true inside continuous_location for Track 9A contract parity.
        AppPresenceTracker presence = new AppPresenceTracker();
        presence.bindProfile("p-loc", 1L);
        String locWire = presence.toWireContextJson(coarseOn, snap, t0 + 5_000L);
        assertNotNull(locWire);
        assertTrue(locWire.contains("\"opted_in\":true"));
        assertTrue(locWire.contains("\"precision\":\"coarse\""));

        // Movement classification covers stationary / walking / transit.
        assertEquals(ContinuousLocationTracker.MovingState.STATIONARY,
                ContinuousLocationTracker.classifyMovingState(0.1f, 0.0, 10_000L));
        assertEquals(ContinuousLocationTracker.MovingState.WALKING,
                ContinuousLocationTracker.classifyMovingState(1.4f, 0.0, 10_000L));
        assertEquals(ContinuousLocationTracker.MovingState.TRANSIT,
                ContinuousLocationTracker.classifyMovingState(12.5f, 0.0, 10_000L));
        assertEquals(ContinuousLocationTracker.MovingState.WALKING,
                ContinuousLocationTracker.classifyMovingState(-1.0f, 150.0, 100_000L));

        // Zero retention after stopAndWipe.
        tracker.stopAndWipe();
        assertNull(tracker.currentSnapshot(coarseOn, t0 + 2_000L));
        assertNull(tracker.currentSnapshot(preciseOn, t0 + 2_000L));

        // Invalid coordinates rejected.
        try {
            tracker.recordFix(95.0, 0.0, 10.0f, 0.0f, "", t0, true);
            fail("Expected out-of-range latitude rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void appCustomizationEnforcesWcagAaContrastUndoResetAndWipe() {
        // Every curated preset meets WCAG AA >= 4.5:1 on both light and dark surfaces.
        for (AppCustomization.PalettePreset preset : AppCustomization.PalettePreset.values()) {
            if (preset == AppCustomization.PalettePreset.CUSTOM) continue;
            int lightRgb = AppCustomization.parseRgb(preset.lightAccentHex);
            int darkRgb = AppCustomization.parseRgb(preset.darkAccentHex);
            assertTrue("Preset " + preset + " light contrast < 4.5",
                    AppCustomization.meetsWcagAa(lightRgb, AppCustomization.LIGHT_SURFACE_RGB));
            assertTrue("Preset " + preset + " dark contrast < 4.5",
                    AppCustomization.meetsWcagAa(darkRgb, AppCustomization.DARK_SURFACE_RGB));
        }

        // High-contrast custom hex accepted; low-contrast / yellow / malformed rejected.
        assertTrue(AppCustomization.isAccessibleAccentHex("#1B4D3E"));
        assertFalse(AppCustomization.isAccessibleAccentHex("#FFFF00"));
        assertFalse(AppCustomization.isAccessibleAccentHex("#CCCCCC"));
        assertFalse(AppCustomization.isAccessibleAccentHex("#888888"));
        assertFalse(AppCustomization.isAccessibleAccentHex("not-a-color"));

        // Dark lift guarantees WCAG AA on dark surface even for a deep custom hex.
        AppCustomization custom = AppCustomization.defaults()
                .withPalette(AppCustomization.PalettePreset.CUSTOM, "#1B4D3E")
                .withDensity(AppCustomization.Density.SPACIOUS)
                .withFontScaleDelta(0.10f)
                .withStartupTab(AppCustomization.StartupTab.ATELIER)
                .withCompanionGreeting("Bonjour Jacob")
                .withComposerShortcuts(List.of(
                        new AppCustomization.ComposerShortcut("plan", "Plan du jour", "Aide-moi à planifier ma journée")));
        assertTrue(AppCustomization.meetsWcagAa(
                custom.darkAccentArgb() & 0xFFFFFF, AppCustomization.DARK_SURFACE_RGB));

        // Secret-like content rejected in greeting & shortcuts.
        try {
            AppCustomization.defaults().withCompanionGreeting("Token sk-1234567890abcdef");
            fail("Expected secret rejection in greeting");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        // Store Apply -> Undo -> Reset -> Undo -> Reload -> Corrupt -> ClearAll.
        MemoryBacking backing = new MemoryBacking();
        AppCustomizationStore store = new AppCustomizationStore(backing);
        assertEquals(AppCustomization.defaults(), store.current());
        assertFalse(store.canUndo());

        store.apply(custom);
        assertEquals(custom, store.current());
        assertTrue(store.canUndo());

        // Reload from encrypted-equivalent backing preserves current & Undo stack!
        AppCustomizationStore reloaded = new AppCustomizationStore(backing);
        assertEquals(custom, reloaded.current());
        assertTrue(reloaded.canUndo());

        reloaded.resetToDefault();
        assertEquals(AppCustomization.defaults(), reloaded.current());
        assertEquals(2, reloaded.undoDepth());

        reloaded.undo();
        assertEquals(custom, reloaded.current());

        reloaded.undo();
        assertEquals(AppCustomization.defaults(), reloaded.current());
        assertFalse(reloaded.canUndo());

        // Bounded undo stack never exceeds MAX_UNDO_DEPTH.
        for (int i = 0; i < 15; i++) {
            reloaded.apply(AppCustomization.defaults().withCompanionGreeting("Msg " + i));
        }
        assertEquals(AppCustomizationStore.MAX_UNDO_DEPTH, reloaded.undoDepth());

        // Wipe / clearAll removes persisted state completely.
        reloaded.clearAll();
        assertNull(backing.stored);
        assertEquals(AppCustomization.defaults(), reloaded.current());

        // Corrupt vault fails closed to defaults.
        backing.stored = "corrupt-data";
        AppCustomizationStore recovered = new AppCustomizationStore(backing);
        assertEquals(AppCustomization.defaults(), recovered.current());
    }

    @Test
    public void deepAndroidActionsEnforceConsentQuietHoursRateLimitAndSchemeAllowlist() {
        AppPresenceTracker presence = new AppPresenceTracker();
        long epoch = presence.bindProfile("p1", 1L);
        presence.updateScreen(epoch, "discussions", "sess-9");

        ContinuousLocationTracker location = new ContinuousLocationTracker();
        AppCustomizationStore customStore = new AppCustomizationStore(new MemoryBacking());
        DeepAndroidActions.NudgeRateLimiter limiter = new DeepAndroidActions.NudgeRateLimiter();

        AtomicInteger nudgesPosted = new AtomicInteger();
        AtomicReference<String> lastNav = new AtomicReference<>();
        AtomicReference<Integer> lastTimer = new AtomicReference<>();
        AtomicReference<String> lastOpenedUrl = new AtomicReference<>();
        AtomicInteger syncCount = new AtomicInteger();

        DeepAndroidActions.HostBridge bridge = new DeepAndroidActions.HostBridge() {
            @Override public boolean navigate(String kind, String id) { lastNav.set(kind + ":" + id); return true; }
            @Override public boolean setTimer(int sec, String label) { lastTimer.set(sec); return true; }
            @Override public boolean openAppOrLink(String pkg, String url) { lastOpenedUrl.set(pkg + "|" + url); return true; }
            @Override public boolean syncShortcutsAndWidgets(String t, String s, boolean safe) { syncCount.incrementAndGet(); return true; }
            @Override public boolean postLocalNudge(String title, String body) { nudgesPosted.incrementAndGet(); return true; }
        };
        DeepAndroidActions actions = new DeepAndroidActions(presence, location, customStore, limiter, bridge);

        long now = 10_000_000L;
        PresencePolicy off = PresencePolicy.defaults();
        PushPolicy quietNight = PushPolicy.defaults().withQuietHours(true, 22 * 60, 7 * 60, true);

        // 0. Unknown action rejected.
        assertEquals(DeepAndroidActions.Status.REJECTED_UNKNOWN_ACTION,
                actions.execute("evil_action", Map.of(), off, quietNight, true, now, 12 * 60).status());

        // 1. app_navigate matches Track 9A canonical target/session_id/template_id/route_id params.
        assertTrue(actions.execute("app_navigate",
                Map.of("target", "atelier"), off, quietNight, false, now, 12 * 60).ok());
        assertEquals("atelier:", lastNav.get());
        assertTrue(actions.execute("app_navigate",
                Map.of("target", "thread", "session_id", "sess-42"), off, quietNight, false, now, 12 * 60).ok());
        assertEquals("thread:sess-42", lastNav.get());
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                actions.execute("app_navigate",
                        Map.of("target", "thread"), off, quietNight, false, now, 12 * 60).status());
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                actions.execute("app_navigate",
                        Map.of("target", "shell_exec", "route_id", "rm"), off, quietNight, true, now, 12 * 60).status());

        // 2. app_read_state fails closed when presence is OFF, succeeds when opted in.
        assertEquals(DeepAndroidActions.Status.DENIED_NOT_OPTED_IN,
                actions.execute("app_read_state", Map.of(), off, quietNight, true, now, 12 * 60).status());
        DeepAndroidActions.Result readOk = actions.execute(
                "app_read_state", Map.of(), off.withAppPresence(true), quietNight, false, now, 12 * 60);
        assertTrue(readOk.ok());
        assertTrue(readOk.payloadJson().contains("\"current_screen\":\"discussions\""));

        // 3. app_customize requires confirmation, enforces WCAG AA, uses Track 9A canonical params, supports undo & reset.
        assertEquals(DeepAndroidActions.Status.DENIED_CONFIRMATION_REQUIRED,
                actions.execute("app_customize", Map.of("operation", "apply", "accent", "slate"), off, quietNight, false, now, 12 * 60).status());
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                actions.execute("app_customize", Map.of("operation", "apply", "accent_hex", "#FFFF00"), off, quietNight, true, now, 12 * 60).status());
        assertTrue(actions.execute("app_customize", Map.of(
                "operation", "apply",
                "accent", "indigo",
                "density", "compact",
                "font_scale_step", 2,
                "greeting", "Salut",
                "quick_shortcuts", List.of(Map.of("label", "Bilan", "draft", "Fais un bilan"))
        ), off, quietNight, true, now, 12 * 60).ok());
        assertEquals(AppCustomization.PalettePreset.INDIGO, customStore.current().palette);
        assertEquals(0.10f, customStore.current().fontScaleDelta, 0.001f);
        assertEquals("Salut", customStore.current().companionGreeting);
        assertEquals(1, customStore.current().composerShortcuts.size());
        assertTrue(actions.execute("app_customize", Map.of("operation", "undo"), off, quietNight, true, now, 12 * 60).ok());
        assertEquals(AppCustomization.PalettePreset.FOREST, customStore.current().palette);

        // 4. app_create_template requires confirmation and validates Track 9A draft_prompt.
        assertEquals(DeepAndroidActions.Status.DENIED_CONFIRMATION_REQUIRED,
                actions.execute("app_create_template",
                        Map.of("title", "Résumé", "draft_prompt", "Résume ceci"), off, quietNight, false, now, 12 * 60).status());
        assertTrue(actions.execute("app_create_template",
                Map.of("title", "Résumé", "draft_prompt", "Résume ceci"), off, quietNight, true, now, 12 * 60).ok());

        // 5. android_set_timer enforces confirmation and 1..86400s bounds.
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                actions.execute("android_set_timer", Map.of("duration_seconds", 0), off, quietNight, true, now, 12 * 60).status());
        assertTrue(actions.execute("android_set_timer",
                Map.of("duration_seconds", 300, "label", "Thé"), off, quietNight, true, now, 12 * 60).ok());
        assertEquals(Integer.valueOf(300), lastTimer.get());

        // 6. android_share_sheet enforces confirmation & non-empty text.
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                actions.execute("android_share_sheet", Map.of("text", ""), off, quietNight, true, now, 12 * 60).status());
        assertTrue(actions.execute("android_share_sheet",
                Map.of("text", "Résumé de la réunion"), off, quietNight, true, now, 12 * 60).ok());

        // 7. android_open_app blocks dangerous schemes (file/content/javascript/intent) and uses Track 9A deep_link.
        for (String blocked : List.of("file:///etc/passwd", "content://sms/inbox", "javascript:alert(1)", "intent:#Intent;end")) {
            assertEquals("Scheme must be blocked: " + blocked, DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                    actions.execute("android_open_app", Map.of("deep_link", blocked), off, quietNight, true, now, 12 * 60).status());
        }
        assertTrue(actions.execute("android_open_app",
                Map.of("deep_link", "https://example.org/docs"), off, quietNight, true, now, 12 * 60).ok());
        assertEquals("|https://example.org/docs", lastOpenedUrl.get());

        // 8. android_sync_shortcuts requires confirmation, uses Track 9A tile_label/shortcuts/widget_summary, and rejects secrets.
        assertEquals(DeepAndroidActions.Status.DENIED_CONFIRMATION_REQUIRED,
                actions.execute("android_sync_shortcuts",
                        Map.of("tile_label", "Hermes", "widget_summary", "Ma journée"), off, quietNight, false, now, 12 * 60).status());
        assertTrue(actions.execute("android_sync_shortcuts", Map.of(
                "tile_label", "Hermes",
                "widget_summary", "Ma journée",
                "shortcuts", List.of(Map.of("id", "ask", "label", "Demander", "target", "discussions"))
        ), off, quietNight, true, now, 12 * 60).ok());
        assertEquals(1, syncCount.get());
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                actions.execute("android_sync_shortcuts",
                        Map.of("widget_summary", "Bearer ghp_1234567890abcdef"), off, quietNight, true, now, 12 * 60).status());

        // 9. android_proactive_nudge enforces opt-in, quiet hours, and hourly rate cap!
        Map<String, Object> nudgeParams = Map.of("title", "Petit rappel", "body", "Votre train part dans 20 min.");
        // Opted out -> denied.
        assertEquals(DeepAndroidActions.Status.DENIED_NOT_OPTED_IN,
                actions.execute("android_proactive_nudge", nudgeParams, off, quietNight, false, now, 14 * 60).status());

        PresencePolicy nudgesOn = off.withProactiveNudges(true).withMaxNudgesPerHour(2);
        // Quiet hours (23:00) -> suppressed.
        assertEquals(DeepAndroidActions.Status.SUPPRESSED_QUIET_HOURS,
                actions.execute("android_proactive_nudge", nudgeParams, nudgesOn, quietNight, false, now, 23 * 60).status());

        // Daytime (14:00): first 2 pass, 3rd within same hour rate-limited, 4th after 61 min passes!
        assertTrue(actions.execute("android_proactive_nudge", nudgeParams, nudgesOn, quietNight, false, now, 14 * 60).ok());
        assertTrue(actions.execute("android_proactive_nudge", nudgeParams, nudgesOn, quietNight, false, now + 60_000L, 14 * 60).ok());
        assertEquals(DeepAndroidActions.Status.SUPPRESSED_RATE_LIMIT,
                actions.execute("android_proactive_nudge", nudgeParams, nudgesOn, quietNight, false, now + 120_000L, 14 * 60).status());
        assertEquals(2, nudgesPosted.get());

        assertTrue(actions.execute("android_proactive_nudge", nudgeParams, nudgesOn, quietNight,
                false, now + 3_700_000L, 15 * 60).ok());
        assertEquals(3, nudgesPosted.get());
    }

    @Test
    public void interactiveCardSpecValidatesKindsAndRejectsMalformedOrSecretPayloads() {
        // Canonical Track 9A quick_pick card (id + items + draft)
        Map<String, Object> card = new HashMap<>();
        card.put("id", "card_1");
        card.put("kind", "quick_pick");
        card.put("title", "Choisissez une suite");
        card.put("items", List.of(
                Map.of("id", "c1", "label", "Résumer", "draft", "Résume en 3 points")));
        InteractiveCardView.Spec spec = InteractiveCardView.Spec.fromMap(card);
        assertEquals("card_1", spec.cardId);
        assertEquals(InteractiveCardView.Kind.QUICK_PICKS, spec.kind);
        assertEquals(1, spec.quickPicks.size());
        assertEquals("Résume en 3 points", spec.quickPicks.get(0).draftText());

        // Canonical Track 9A toggle_confirm card (id + items + enabled)
        InteractiveCardView.Spec toggleSpec = InteractiveCardView.Spec.fromMap(Map.of(
                "id", "card_t",
                "kind", "toggle_confirm",
                "title", "Options",
                "items", List.of(Map.of("id", "t1", "label", "Concis", "description", "Court", "enabled", true))));
        assertEquals(InteractiveCardView.Kind.TOGGLES, toggleSpec.kind);
        assertTrue(toggleSpec.toggles.get(0).checked());

        // Canonical Track 9A customization_preview card (accent_hex, font_scale_step, greeting)
        InteractiveCardView.Spec okPreview = InteractiveCardView.Spec.fromMap(Map.of(
                "id", "card_ok",
                "kind", "customization_preview",
                "title", "Nouvel habillage",
                "customization", Map.of(
                        "accent_hex", "#1B4D3E",
                        "density", "spacious",
                        "font_scale_step", 2,
                        "greeting", "Bonjour")));
        assertEquals(AppCustomization.PalettePreset.CUSTOM, okPreview.proposedCustomization.palette);
        assertEquals("#1B4D3E", okPreview.proposedCustomization.customAccentHex);
        assertEquals(0.10f, okPreview.proposedCustomization.fontScaleDelta, 0.001f);
        assertEquals("Bonjour", okPreview.proposedCustomization.companionGreeting);

        // WCAG 2.1 threshold (0.04045): 10/255 = 0.039215... uses linear branch c / 12.92.
        double expectedLinear10 = (10.0 / 255.0) / 12.92;
        assertEquals(expectedLinear10, AppCustomization.relativeLuminance(0x0A0A0A), 1e-9);

        // Preview card validates WCAG AA custom accent.
        Map<String, Object> badPreview = Map.of(
                "id", "card_2",
                "kind", "customization_preview",
                "title", "Aperçu",
                "customization", Map.of("accent_hex", "#FFFF00"));
        try {
            InteractiveCardView.Spec.fromMap(badPreview);
            fail("Expected WCAG AA rejection on interactive customization preview");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
