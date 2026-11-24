package org.llm4people.hermes.presence;

import org.junit.Test;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.os.PermissionsPanel;
import org.llm4people.hermes.push.PushPolicy;
import org.llm4people.hermes.settings.AppearanceSettingsPanel;
import org.llm4people.hermes.templates.TemplatesPanel;
import org.llm4people.hermes.update.UpdatePanel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DeepAndroidActionsNavigateTest {
    private static final class MemoryBacking implements AppCustomizationStore.Backing {
        String stored;
        @Override public String read() { return stored; }
        @Override public void write(String value) { stored = value; }
        @Override public void remove() { stored = null; }
    }

    @Test
    public void navigateTargetsSsotCoversAllCanonicalDestinationsAndRejectsUnknown() {
        List<String> required = List.of(
                "discussions", "notes_tasks", "tasks", "apps", "settings", "appearance",
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

        for (String direct : List.of("notes_tasks", "apps", "settings", "appearance",
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
                "",
                "   ")) {
            org.junit.Assert.assertFalse("Expected rejection for deep link: " + bad,
                    DeepAndroidActions.isAllowedDeepLink(bad));
        }
        org.junit.Assert.assertFalse(DeepAndroidActions.isAllowedDeepLink(null));
    }
}
