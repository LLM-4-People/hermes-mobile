package org.llm4people.hermes.share;

import org.junit.Test;
import org.llm4people.hermes.R;
import org.llm4people.hermes.ambient.HermesNotificationListener;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.presence.PresencePolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeviceBridgeCapabilitiesTest {
    private static final Set<String> FORK_PR18_ALLOWLIST = Set.of(
            "camera", "location_coarse", "nearby_osm",
            "calendar_draft", "alarm_timer", "maps_handoff",
            "share_sheet", "clipboard_read",
            "dial_draft", "email_draft", "contacts_pick", "ui_surfaces_v2",
            "notifications_digest", "notification_reply", "media_control", "calendar_read");

    @Test
    public void advertisedCapabilitiesStrictlyMatchForkPr15Allowlist() {
        List<String> full = DeviceBridgeFlow.advertisedCapabilities(true, true, true, true);
        for (String cap : full) {
            assertTrue("Non-allowlisted capability would trigger HTTP 400: " + cap,
                    FORK_PR18_ALLOWLIST.contains(cap));
        }
        assertTrue(full.contains("camera"));
        assertTrue(full.contains("location_coarse"));
        assertTrue(full.contains("nearby_osm"));
        assertTrue(full.contains("alarm_timer"));
        assertTrue(full.contains("maps_handoff"));
        assertTrue(full.contains("calendar_draft"));
        assertTrue(full.contains("dial_draft"));
        assertTrue(full.contains("email_draft"));
        assertTrue(full.contains("contacts_pick"));
        assertTrue(full.contains("ui_surfaces_v2"));
        assertFalse(full.contains("map_navigation"));
        assertFalse(full.contains("clipboard_write"));
        assertFalse(full.contains("sms_draft"));
        assertFalse(full.contains("contact_draft"));

        List<String> minimal = DeviceBridgeFlow.advertisedCapabilities(false, false, false, false);
        assertFalse(minimal.contains("camera"));
        assertFalse(minimal.contains("location_coarse"));
        assertFalse(minimal.contains("nearby_osm"));
        assertFalse(minimal.contains("alarm_timer"));
        assertFalse(minimal.contains("maps_handoff"));

        List<String> executable = DeviceBridgeFlow.executableCapabilities(true, true, true);
        for (String cap : executable) {
            assertTrue("Executable capability must be in Fork PR #18 allowlist: " + cap,
                    FORK_PR18_ALLOWLIST.contains(cap));
        }
        assertTrue(executable.contains("calendar_draft"));
        assertTrue(executable.contains("alarm_timer"));
        assertTrue(executable.contains("maps_handoff"));
        assertTrue(executable.contains("share_sheet"));
        assertTrue(executable.contains("clipboard_read"));
        assertTrue(executable.contains("dial_draft"));
        assertTrue(executable.contains("email_draft"));
        // Unbacked or disabled ambient capabilities must NOT be advertised by default.
        assertFalse(executable.contains("camera"));
        assertFalse(executable.contains("location_coarse"));
        assertFalse(executable.contains("nearby_osm"));
        assertFalse(executable.contains("contacts_pick"));
        assertFalse(executable.contains("ui_surfaces_v2"));
        assertFalse(executable.contains("notifications_digest"));
        assertFalse(executable.contains("notification_reply"));
        assertFalse(executable.contains("media_control"));
        assertFalse(executable.contains("calendar_read"));

        List<String> restricted = DeviceBridgeFlow.executableCapabilities(false, false, false);
        assertFalse(restricted.contains("maps_handoff"));
        assertFalse(restricted.contains("alarm_timer"));
        assertFalse(restricted.contains("clipboard_read"));
    }

    @Test
    public void ambientCapabilitiesAdvertiseStrictlyWhenBothOptInAndOsPermissionAreActive() {
        NotificationAllowlist.Settings allOn = new NotificationAllowlist.Settings(true, true, true, true, true);
        // Fail closed when OS listener & calendar permission are missing.
        assertTrue(DeviceBridgeFlow.ambientExecutableCapabilities(allOn, false, false, true).isEmpty());
        // Fail closed when opt-in is OFF even if OS listener & calendar permission are granted.
        assertTrue(DeviceBridgeFlow.ambientExecutableCapabilities(
                NotificationAllowlist.Settings.ALL_OFF, true, true, true).isEmpty());
        // Fail closed for notifications when allowlist is empty.
        List<String> noApps = DeviceBridgeFlow.ambientExecutableCapabilities(allOn, true, true, false);
        assertFalse(noApps.contains("notifications_digest"));
        assertFalse(noApps.contains("notification_reply"));
        assertTrue(noApps.contains("media_control"));
        assertTrue(noApps.contains("calendar_read"));
        // Full opt-in + OS permissions + non-empty allowlist advertises all 4 ambient capabilities.
        List<String> ready = DeviceBridgeFlow.executableCapabilities(true, true, true, allOn, true, true, true);
        assertTrue(ready.contains("notifications_digest"));
        assertTrue(ready.contains("notification_reply"));
        assertTrue(ready.contains("media_control"));
        assertTrue(ready.contains("calendar_read"));
        for (String cap : ready) {
            assertTrue("Ambient capability must stay in Fork PR #18 allowlist: " + cap,
                    FORK_PR18_ALLOWLIST.contains(cap));
        }
    }

    @Test
    public void localizedTitlesAndDefaultReasonsExistForEverySupportedAction() {
        for (String action : DeviceBridgeFlow.SUPPORTED_ACTIONS) {
            assertTrue("Missing title resource for " + action, DeviceBridgeFlow.titleRes(action) != 0);
            assertTrue("Missing reason resource for " + action, DeviceBridgeFlow.defaultReasonRes(action) != 0);
        }
        assertEquals(org.llm4people.hermes.R.string.share_bridge_title_default, DeviceBridgeFlow.titleRes("unknown_action"));
        assertEquals(org.llm4people.hermes.R.string.share_bridge_reason_default, DeviceBridgeFlow.defaultReasonRes("unknown_action"));
    }

    @Test
    public void mediaStatusCommandAndCalendarDaysAheadParametersResolveCleanly() {
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand((String) null));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand(""));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand("   "));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand("status"));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand("STATUS"));
        assertFalse(DeviceBridgeFlow.isMediaStatusCommand("pause"));
        assertFalse(DeviceBridgeFlow.isMediaStatusCommand("next"));

        assertEquals(7, DeviceBridgeFlow.resolveCalendarDays(null, null));
        assertEquals(14, DeviceBridgeFlow.resolveCalendarDays(14, null));
        assertEquals(3, DeviceBridgeFlow.resolveCalendarDays(null, 3));
        assertEquals(1, DeviceBridgeFlow.resolveCalendarDays(1, 10));
    }

    @Test
    public void deepCapabilitiesGatePresenceAndNudgesFailClosedAndStayInSupportedActions() {
        List<String> defCaps = DeviceBridgeFlow.deepExecutableCapabilities(PresencePolicy.defaults());
        assertTrue(defCaps.contains("app_navigate"));
        assertTrue(defCaps.contains("app_customize"));
        assertTrue(defCaps.contains("app_create_template"));
        assertTrue(defCaps.contains("android_sync_shortcuts"));
        assertFalse("app_read_state requires shareAppPresence opt-in", defCaps.contains("app_read_state"));
        assertFalse("android_proactive_nudge requires proactiveNudgesEnabled opt-in",
                defCaps.contains("android_proactive_nudge"));

        PresencePolicy optedIn = PresencePolicy.defaults()
                .withAppPresence(true)
                .withProactiveNudges(true);
        List<String> fullCaps = DeviceBridgeFlow.deepExecutableCapabilities(optedIn);
        assertTrue(fullCaps.contains("app_read_state"));
        assertTrue(fullCaps.contains("android_proactive_nudge"));
        for (String cap : fullCaps) {
            assertTrue("Deep capability must be registered in SUPPORTED_ACTIONS: " + cap,
                    DeviceBridgeFlow.SUPPORTED_ACTIONS.contains(cap));
        }
    }

    @Test
    public void calendarDraftWithoutEndDefaultsToOneHourOnlyForValidOffsetStarts() {
        assertEquals("2026-11-23T11:00:00Z", DeviceBridgeFlow.defaultCalendarEnd("2026-11-23T10:00:00Z"));
        assertEquals("2026-11-24T00:30:00-05:00", DeviceBridgeFlow.defaultCalendarEnd(" 2026-11-23T23:30:00-05:00 "));
        // No offset, free text, or blank starts stay without an end so DeviceAction fails closed.
        for (String invalid : new String[]{null, "", "   ", "demain 10h", "2026-11-23T10:00:00", "2026-13-40T10:00:00Z"}) {
            assertEquals(null, DeviceBridgeFlow.defaultCalendarEnd(invalid));
        }
    }

    @Test
    public void sanitizesReasonAndBuildsTargetPreviewsForAllSixConfirmRequiredDeepActions() {
        // R17.2.2: sanitizeReason strips bidi/CGJ/controls and truncates safely at surrogate boundary.
        assertEquals("", DeviceBridgeFlow.sanitizeReason("  \u202E\u034F\n\t "));
        assertEquals("Ouvrir carte", DeviceBridgeFlow.sanitizeReason("Ou\u202E\u034Fvrir\n\u00A0carte"));
        String reasonBoundary = "a".repeat(298) + "\uD83D\uDE00tail";
        String sanitizedReason = DeviceBridgeFlow.sanitizeReason(reasonBoundary);
        assertTrue(sanitizedReason.length() <= 300);
        assertTrue(sanitizedReason.endsWith("…"));
        assertFalse(Character.isHighSurrogate(sanitizedReason.charAt(sanitizedReason.length() - 2)));

        // 1. android_open_app
        List<DeviceBridgeFlow.PreviewLine> openApp = DeviceBridgeFlow.previewLinesForParams("android_open_app",
                Map.of("deep_link", "https://example.org/doc\u202E", "package_name", "org.example.app"));
        assertEquals(2, openApp.size());
        assertEquals(R.string.share_bridge_preview_link, openApp.get(0).labelRes());
        assertEquals("https://example.org/doc", openApp.get(0).value());
        assertEquals(R.string.share_bridge_preview_app, openApp.get(1).labelRes());
        assertEquals("org.example.app", openApp.get(1).value());

        // 2. android_share_sheet
        List<DeviceBridgeFlow.PreviewLine> share = DeviceBridgeFlow.previewLinesForParams("android_share_sheet",
                Map.of("subject", "Bilan\u034F", "text", "Résumé\ncomplet"));
        assertEquals(2, share.size());
        assertEquals(R.string.share_bridge_preview_subject, share.get(0).labelRes());
        assertEquals("Bilan", share.get(0).value());
        assertEquals(R.string.share_bridge_preview_message, share.get(1).labelRes());
        assertEquals("Résumé complet", share.get(1).value());

        // 3. android_set_timer
        List<DeviceBridgeFlow.PreviewLine> timer = DeviceBridgeFlow.previewLinesForParams("android_set_timer",
                Map.of("duration_seconds", 330, "label", "Thé\u202E"));
        assertEquals(2, timer.size());
        assertEquals(R.string.share_bridge_preview_duration, timer.get(0).labelRes());
        assertEquals("5min 30s", timer.get(0).value());
        assertEquals(R.string.share_bridge_preview_label, timer.get(1).labelRes());
        assertEquals("Thé", timer.get(1).value());

        // 4. app_create_template
        List<DeviceBridgeFlow.PreviewLine> template = DeviceBridgeFlow.previewLinesForParams("app_create_template",
                Map.of("title", "Résumé matinal", "draft_prompt", "Résume mes tâches\ndu jour."));
        assertEquals(2, template.size());
        assertEquals(R.string.share_bridge_preview_title, template.get(0).labelRes());
        assertEquals("Résumé matinal", template.get(0).value());
        assertEquals(R.string.share_bridge_preview_draft, template.get(1).labelRes());
        assertEquals("Résume mes tâches du jour.", template.get(1).value());

        // 5. android_sync_shortcuts
        List<DeviceBridgeFlow.PreviewLine> shortcuts = DeviceBridgeFlow.previewLinesForParams("android_sync_shortcuts",
                Map.of("tile_label", "Hermes", "widget_summary", "3 rappels actifs"));
        assertEquals(2, shortcuts.size());
        assertEquals(R.string.share_bridge_preview_label, shortcuts.get(0).labelRes());
        assertEquals("Hermes", shortcuts.get(0).value());
        assertEquals(R.string.share_bridge_preview_summary, shortcuts.get(1).labelRes());
        assertEquals("3 rappels actifs", shortcuts.get(1).value());

        // 6. app_customize: localized labels & enum values with zero raw wire tokens.
        Map<String, Object> customParams = new LinkedHashMap<>();
        customParams.put("operation", "apply");
        customParams.put("theme", "dark");
        customParams.put("accent", "slate");
        customParams.put("accent_hex", "#1B4D89");
        customParams.put("density", "compact");
        customParams.put("font_scale_step", 2);
        customParams.put("startup_tab", "notes_tasks");
        customParams.put("greeting", "Bonjour\u202E");
        customParams.put("quick_shortcuts", List.of("a", "b"));
        List<DeviceBridgeFlow.PreviewLine> customize =
                DeviceBridgeFlow.previewLinesForParams("app_customize", customParams);
        assertEquals(8, customize.size());
        assertEquals(DeviceBridgeFlow.PreviewLine.resValue(R.string.share_bridge_preview_theme, R.string.appearance_theme_dark),
                customize.get(0));
        assertEquals(DeviceBridgeFlow.PreviewLine.resValue(R.string.share_bridge_preview_accent, R.string.appearance_palette_slate),
                customize.get(1));
        assertEquals(DeviceBridgeFlow.PreviewLine.text(R.string.share_bridge_preview_accent_color, "#1B4D89"),
                customize.get(2));
        assertEquals(DeviceBridgeFlow.PreviewLine.resValue(R.string.share_bridge_preview_density, R.string.appearance_density_compact),
                customize.get(3));
        assertEquals(DeviceBridgeFlow.PreviewLine.text(R.string.share_bridge_preview_text_size, "110%"),
                customize.get(4));
        assertEquals(DeviceBridgeFlow.PreviewLine.resValue(R.string.share_bridge_preview_startup_tab, R.string.nav_notes_tasks),
                customize.get(5));
        assertEquals(DeviceBridgeFlow.PreviewLine.text(R.string.share_bridge_preview_greeting, "Bonjour"),
                customize.get(6));
        assertEquals(DeviceBridgeFlow.PreviewLine.text(R.string.share_bridge_preview_shortcuts, "2"),
                customize.get(7));

        List<DeviceBridgeFlow.PreviewLine> customizeReset =
                DeviceBridgeFlow.previewLinesForParams("app_customize", Map.of("operation", "reset"));
        assertEquals(List.of(DeviceBridgeFlow.PreviewLine.message(R.string.share_bridge_preview_customize_reset)),
                customizeReset);

        List<DeviceBridgeFlow.PreviewLine> customizeUndo =
                DeviceBridgeFlow.previewLinesForParams("app_customize", Map.of("operation", "undo"));
        assertEquals(List.of(DeviceBridgeFlow.PreviewLine.message(R.string.share_bridge_preview_customize_undo)),
                customizeUndo);

        // Negative / empty / unknown action returns empty preview safely.
        assertTrue(DeviceBridgeFlow.previewLinesForParams("unknown", Map.of("a", "b")).isEmpty());
        assertTrue(DeviceBridgeFlow.previewLinesForParams("android_open_app", null).isEmpty());
        assertTrue(DeviceBridgeFlow.previewLinesForParams("android_open_app", Map.of("deep_link", " \u202E ")).isEmpty());
    }

    @Test
    public void previewBoundaryAndNegativeInputsAndNotificationReplyDraftSanitization() {
        // Hour-scale and short timer formatting
        assertEquals(List.of(DeviceBridgeFlow.PreviewLine.text(R.string.share_bridge_preview_duration, "1h")),
                DeviceBridgeFlow.previewLinesForParams("android_set_timer", Map.of("duration_seconds", 3600)));
        assertEquals(List.of(DeviceBridgeFlow.PreviewLine.text(R.string.share_bridge_preview_duration, "1h 1min 5s")),
                DeviceBridgeFlow.previewLinesForParams("android_set_timer", Map.of("duration_seconds", 3665)));
        assertEquals(List.of(DeviceBridgeFlow.PreviewLine.text(R.string.share_bridge_preview_duration, "45s")),
                DeviceBridgeFlow.previewLinesForParams("android_set_timer", Map.of("duration_seconds", 45)));

        // Non-positive and non-numeric timer durations are omitted fail-closed
        assertTrue(DeviceBridgeFlow.previewLinesForParams("android_set_timer", Map.of("duration_seconds", 0)).isEmpty());
        assertTrue(DeviceBridgeFlow.previewLinesForParams("android_set_timer", Map.of("duration_seconds", -10)).isEmpty());
        assertTrue(DeviceBridgeFlow.previewLinesForParams("android_set_timer", Map.of("duration_seconds", "bad")).isEmpty());

        // Empty / unknown-only params and out-of-range font_scale_step / non-String values
        assertTrue(DeviceBridgeFlow.previewLinesForParams("app_customize", Map.of()).isEmpty());
        assertTrue(DeviceBridgeFlow.previewLinesForParams("app_customize",
                Map.of("operation", "apply", "unknown_key", "ignored")).isEmpty());
        assertTrue(DeviceBridgeFlow.previewLinesForParams("app_customize",
                Map.of("operation", "apply", "font_scale_step", 99)).isEmpty());
        assertTrue(DeviceBridgeFlow.previewLinesForParams("app_customize",
                Map.of("operation", "apply", "font_scale_step", -9)).isEmpty());
        assertTrue(DeviceBridgeFlow.previewLinesForParams("android_open_app",
                Map.of("deep_link", 12345, "package_name", Boolean.TRUE)).isEmpty());

        // Accent preset alias & unrecognized value still use localized label resource (never "accent=...")
        assertEquals(List.of(DeviceBridgeFlow.PreviewLine.resValue(R.string.share_bridge_preview_accent, R.string.appearance_palette_plum)),
                DeviceBridgeFlow.previewLinesForParams("app_customize", Map.of("accent_preset", "plum")));
        assertEquals(List.of(DeviceBridgeFlow.PreviewLine.text(R.string.share_bridge_preview_accent, "custom_raw")),
                DeviceBridgeFlow.previewLinesForParams("app_customize", Map.of("accent", "custom_raw")));

        // R18.2.3: Reply draft sanitization matches NotificationPrivacyGuard before confirmation display.
        assertEquals("", HermesNotificationListener.sanitizeReplyDraft(null));
        assertEquals("", HermesNotificationListener.sanitizeReplyDraft("  \u202E\u034F\r\n\t "));
        assertEquals("On arrive dans 5 min",
                HermesNotificationListener.sanitizeReplyDraft("On\u202E\u034F\uDB40\uDC20 arrive\u0000\ndans\u00A05\uD83D min"));
        String longDraft = HermesNotificationListener.sanitizeReplyDraft("a".repeat(HermesNotificationListener.MAX_REPLY_CHARS + 50));
        assertEquals(HermesNotificationListener.MAX_REPLY_CHARS, longDraft.length());
        assertTrue(longDraft.endsWith("…"));
    }
}
