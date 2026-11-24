package org.llm4people.hermes.presence;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.hub.AppsLauncherPanel;
import org.llm4people.hermes.hub.NotesTasksPanel;
import org.llm4people.hermes.os.PermissionsPanel;
import org.llm4people.hermes.push.PushPolicy;
import org.llm4people.hermes.settings.AppearanceSettingsPanel;
import org.llm4people.hermes.settings.SettingsHubPanel;
import org.llm4people.hermes.templates.TemplatesPanel;
import org.llm4people.hermes.update.UpdatePanel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DeepAndroidActionsNavigateTest {
    @Before
    public void setUpDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void tearDownDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    private static final class MemoryBacking implements AppCustomizationStore.Backing {
        String stored;
        @Override public String read() { return stored; }
        @Override public void write(String value) { stored = value; }
        @Override public void remove() { stored = null; }
    }

    @Test
    public void navigateTargetsSsotCoversAllCanonicalDestinationsAndRejectsUnknown() {
        List<String> required = List.of(
                "discussions", NotesTasksPanel.ROUTE_ID, "tasks", AppsLauncherPanel.ROUTE_ID,
                SettingsHubPanel.ROUTE_ID, "appearance",
                AppearanceSettingsPanel.ROUTE_ID,
                PermissionsPanel.ROUTE_ID,
                UpdatePanel.ROUTE_ID,
                AmbientAccessPanel.ROUTE_ID,
                PresenceSettingsPanel.ROUTE_ID,
                ConnectionDoctorPanel.ROUTE_ID,
                TemplatesPanel.ROUTE_ID,
                "search", "command_palette", "thread", "memory", "notes", "shared_with_me", "tab");

        for (String target : required) {
            assertTrue("NAVIGATE_TARGETS missing " + target, DeepAndroidActions.NAVIGATE_TARGETS.contains(target));
            assertNotNull("resolveNavigateDestination returned null for " + target,
                    DeepAndroidActions.resolveNavigateDestination(target));
        }

        for (String target : DeepAndroidActions.NAVIGATE_TARGETS) {
            assertNotNull("Every entry in NAVIGATE_TARGETS must resolve: " + target,
                    DeepAndroidActions.resolveNavigateDestination(target));
        }

        assertNull(DeepAndroidActions.resolveNavigateDestination(null));
        assertNull(DeepAndroidActions.resolveNavigateDestination(""));
        assertNull(DeepAndroidActions.resolveNavigateDestination("unknown_screen"));
    }

    @Test
    public void appNavigateExecutesCanonicalRoutesAndEnforcesRequiredIds() {
        AtomicReference<String> lastTarget = new AtomicReference<>();
        DeepAndroidActions actions = new DeepAndroidActions(
                new AppPresenceTracker(),
                new ContinuousLocationTracker(),
                new AppCustomizationStore(new MemoryBacking()),
                new DeepAndroidActions.NudgeRateLimiter(),
                new DeepAndroidActions.HostBridge() {
                    @Override public boolean navigate(String targetKind, String targetId) {
                        lastTarget.set(targetKind + "|" + targetId);
                        return true;
                    }
                });

        PresencePolicy policy = PresencePolicy.defaults();
        PushPolicy push = PushPolicy.defaults();
        long now = 1_000_000L;

        for (String direct : List.of("notes_tasks", "apps", SettingsHubPanel.ROUTE_ID, "appearance",
                AppearanceSettingsPanel.ROUTE_ID, PermissionsPanel.ROUTE_ID,
                UpdatePanel.ROUTE_ID, AmbientAccessPanel.ROUTE_ID,
                PresenceSettingsPanel.ROUTE_ID, ConnectionDoctorPanel.ROUTE_ID,
                TemplatesPanel.ROUTE_ID, "search", "command_palette")) {
            DeepAndroidActions.Result res = actions.execute(
                    "app_navigate", Map.of("target", direct), policy, push, false, now, 600);
            assertTrue("Expected EXECUTED for " + direct, res.ok());
            assertEquals(direct + "|", lastTarget.get());
        }

        // tab requires route_id
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                actions.execute("app_navigate", Map.of("target", "tab"), policy, push, false, now, 600).status());
        assertTrue(actions.execute("app_navigate",
                Map.of("target", "tab", "route_id", "notes_tasks"), policy, push, false, now, 600).ok());
        assertEquals("tab|notes_tasks", lastTarget.get());

        // Unsafe route_id rejected
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS,
                actions.execute("app_navigate",
                        Map.of("target", "tab", "route_id", "../etc/passwd;rm"), policy, push, false, now, 600).status());
    }

    @Test
    public void allowedDeepLinkDelegatesHttpsToSafeLinksAndRejectsDeceptiveOrBlockedUrls() {
        // Positive HTTPS (including uppercase scheme) and non-HTTP allowlisted schemes
        assertTrue(DeepAndroidActions.isAllowedDeepLink("https://example.com"));
        assertTrue(DeepAndroidActions.isAllowedDeepLink("https://example.com/path?q=1"));
        assertTrue(DeepAndroidActions.isAllowedDeepLink("HTTPS://EXAMPLE.COM/docs"));
        assertTrue(DeepAndroidActions.isAllowedDeepLink("geo:45.5017,-73.5673"));
        assertTrue(DeepAndroidActions.isAllowedDeepLink("mailto:help@example.com"));
        assertTrue(DeepAndroidActions.isAllowedDeepLink("tel:+15145550199"));

        // Negative HTTPS phishing / malformed / hostless / whitespace / backslash inputs
        for (String bad : List.of(
                "https://trusted.example.com@evil.example.net/login",
                "https://user:pass@example.com/secret",
                "https://example.com\\evil",
                "https://example.com/path with spaces",
                "https://",
                "https:///only-path",
                "https://example.com:0/badport",
                "https://..invalid/path",
                "http://example.com/insecure",
                "file:///etc/passwd",
                "content://com.android.contacts/data",
                "javascript:alert(1)",
                "intent://scan/#Intent;end",
                "https://example.com/a\nb",
                "https://example.com/\u034F",
                "https://example.com/\u202E",
                "geo:45.5\u2028,-73.5",
                "tel:+1\u2029555",
                "tel:\uFE0F",
                "tel:\u00A0",
                "mailto:\u3164",
                "",
                "   ")) {
            assertFalse("Expected rejection for deep link: " + bad,
                    DeepAndroidActions.isAllowedDeepLink(bad));
        }
        assertFalse(DeepAndroidActions.isAllowedDeepLink(null));
    }

    @Test
    public void androidSetTimerSaturatesOverflowAndRejectsNonFiniteOrInvalidDurations() {
        AtomicInteger observedSeconds = new AtomicInteger(-1);
        DeepAndroidActions actions = new DeepAndroidActions(
                new AppPresenceTracker(),
                new ContinuousLocationTracker(),
                new AppCustomizationStore(new MemoryBacking()),
                new DeepAndroidActions.NudgeRateLimiter(),
                new DeepAndroidActions.HostBridge() {
                    @Override public boolean setTimer(int durationSeconds, String label) {
                        observedSeconds.set(durationSeconds);
                        return true;
                    }
                });

        PresencePolicy policy = PresencePolicy.defaults();
        PushPolicy push = PushPolicy.defaults();
        long now = 1_000_000L;

        // 64-bit overflow (1L << 32) + 60 previously wrapped to 60; must saturate and fail range check.
        DeepAndroidActions.Result overflow = actions.execute(
                "android_set_timer", Map.of("duration_seconds", (1L << 32) + 60L),
                policy, push, true, now, 600);
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS, overflow.status());
        assertEquals("timer_duration_out_of_range", overflow.reasonCode());

        DeepAndroidActions.Result underflow = actions.execute(
                "android_set_timer", Map.of("duration_seconds", -4_294_967_200L),
                policy, push, true, now, 600);
        assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS, underflow.status());
        assertEquals("timer_duration_out_of_range", underflow.reasonCode());

        // Non-finite numbers and non-Number inputs must be rejected as invalid_timer_duration.
        for (Object invalid : new Object[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "60", null}) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("duration_seconds", invalid);
            DeepAndroidActions.Result res = actions.execute(
                    "android_set_timer", params, policy, push, true, now, 600);
            assertEquals(DeepAndroidActions.Status.REJECTED_INVALID_PARAMS, res.status());
            assertEquals("invalid_timer_duration", res.reasonCode());
        }
        assertEquals(-1, observedSeconds.get());

        // Valid timer duration executes cleanly.
        DeepAndroidActions.Result ok = actions.execute(
                "android_set_timer", Map.of("duration_seconds", 60, "label", "Tea"),
                policy, push, true, now, 600);
        assertTrue(ok.ok());
        assertEquals(60, observedSeconds.get());
    }
}
