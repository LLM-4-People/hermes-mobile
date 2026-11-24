package org.llm4people.hermes.ambient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.WindowManager;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.RouteRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public final class AmbientIntelligenceTest extends InstrumentationTestCase {
    public void testRouteRegistrationPanelMountSecureDialogsAndOptInBounds() throws Throwable {
        Context context = getInstrumentation().getTargetContext();

        RouteRegistry registry = new RouteRegistry();
        AtomicBoolean opened = new AtomicBoolean(false);
        AmbientAccessPanel.registerRoute(registry, context, () -> opened.set(true));
        List<RouteRegistry.Destination> settings = registry.destinationsIn(RouteRegistry.Section.SETTINGS, Set.of());
        assertEquals(1, settings.size());
        assertEquals(AmbientAccessPanel.ROUTE_ID, settings.get(0).id());
        assertTrue(registry.dispatch(AmbientAccessPanel.ROUTE_ID));
        assertTrue(opened.get());

        // Opt-in readers stay strictly empty when disabled.
        assertTrue(CalendarContextReader.readUpcoming(context, false, 7).isEmpty());

        // Filter excludes hard-blocked banking/password/self apps from selectable list.
        List<AmbientAccessPanel.AppEntry> filtered = AmbientAccessPanel.filterSelectableApps(List.of(
                new AmbientAccessPanel.AppEntry("org.thoughtcrime.securesms", "Signal"),
                new AmbientAccessPanel.AppEntry("com.x8bit.bitwarden", "Bitwarden"),
                new AmbientAccessPanel.AppEntry("com.desjardins.mobile", "Desjardins"),
                new AmbientAccessPanel.AppEntry("org.llm4people.hermes", "Hermes Mobile")
        ), "");
        assertEquals(1, filtered.size());
        assertEquals("org.thoughtcrime.securesms", filtered.get(0).packageName());

        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        AtomicReference<HermesNotificationListener.ReplyOutcome> outcome = new AtomicReference<>();
        AtomicReference<HermesNotificationListener.ReplyOutcome> revokedOutcome = new AtomicReference<>();
        AtomicReference<AmbientAccessPanel> panelRef = new AtomicReference<>();
        AtomicReference<AmbientAccessPanel> closedPanelRef = new AtomicReference<>();
        AtomicReference<AlertDialog> replyDialogRef = new AtomicReference<>();
        AtomicReference<AlertDialog> mediaDialogRef = new AtomicReference<>();
        AtomicReference<AlertDialog> revokedDialogRef = new AtomicReference<>();
        try {
            runTestOnUiThread(() -> {
                Map<String, String> backing = new HashMap<>();
                NotificationAllowlist allowlist = NotificationAllowlist.forStore(new NotificationAllowlist.Store() {
                    @Override public String read(String key) { return backing.get(key); }
                    @Override public void write(String key, String value) { backing.put(key, value); }
                    @Override public void remove(String key) { backing.remove(key); }
                });
                AmbientAccessPanel panel = new AmbientAccessPanel(activity, allowlist, text -> {});
                panelRef.set(panel);
                View root = panel.buildView();
                assertNotNull(root);
                activity.setContentView(root);

                AmbientNotification sample = new AmbientNotification(
                        "k1", "org.thoughtcrime.securesms", "Signal", "Marie",
                        "Marie", "On déjeune ensemble ?", System.currentTimeMillis(), true, "Répondre");
                AlertDialog replyDialog = HermesNotificationListener.showQuickReplyConfirmation(
                        activity, sample, "Avec plaisir !", () -> true, outcome::set);
                replyDialogRef.set(replyDialog);
                assertTrue((replyDialog.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                replyDialog.cancel();
                replyDialogRef.set(null);

                MediaContextBridge.NowPlaying track = new MediaContextBridge.NowPlaying(
                        "com.example.music", "Music", "Clair de Lune", "Debussy", "",
                        MediaContextBridge.PlaybackKind.PLAYING, 0, 120000);
                AlertDialog mediaDialog = MediaContextBridge.showControlConfirmation(
                        activity, () -> false, track, MediaContextBridge.ControlAction.PAUSE, null);
                mediaDialogRef.set(mediaDialog);
                assertTrue((mediaDialog.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                mediaDialog.dismiss();
                mediaDialogRef.set(null);

                // Revoked live opt-in fails closed when confirm is invoked on an open quick-reply dialog.
                // AlertDialog dispatches the positive click through its Handler, so assert after idle sync.
                AlertDialog revokedDialog = HermesNotificationListener.showQuickReplyConfirmation(
                        activity, sample, "Bonjour", () -> false, revokedOutcome::set);
                revokedDialogRef.set(revokedDialog);
                revokedDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

                panel.dismissDialogs();
                panel.close();
                panelRef.set(null);

                // Closed encrypted profile store must never crash AmbientAccessPanel buildView or refreshAll.
                NotificationAllowlist closedStoreAllowlist = NotificationAllowlist.forStore(new NotificationAllowlist.Store() {
                    @Override public String read(String key) { throw new IllegalStateException("Profile closed"); }
                    @Override public void write(String key, String value) { throw new IllegalStateException("Profile closed"); }
                    @Override public void remove(String key) { throw new IllegalStateException("Profile closed"); }
                });
                AmbientAccessPanel closedPanel = new AmbientAccessPanel(activity, closedStoreAllowlist, text -> {});
                closedPanelRef.set(closedPanel);
                View closedRoot = closedPanel.buildView();
                assertNotNull(closedRoot);
                closedPanel.refreshAll();
                closedPanel.close();
                closedPanelRef.set(null);

                HermesNotificationListener.clearSession();
                assertTrue(HermesNotificationListener.snapshot().isEmpty());
            });
            getInstrumentation().waitForIdleSync();
            assertEquals(HermesNotificationListener.ReplyOutcome.CANCELLED, outcome.get());
            assertEquals(HermesNotificationListener.ReplyOutcome.FAILED, revokedOutcome.get());
        } finally {
            try {
                runTestOnUiThread(() -> {
                    dismissIfShowing(replyDialogRef.getAndSet(null));
                    dismissIfShowing(mediaDialogRef.getAndSet(null));
                    dismissIfShowing(revokedDialogRef.getAndSet(null));
                    AmbientAccessPanel openPanel = panelRef.getAndSet(null);
                    if (openPanel != null) {
                        openPanel.dismissDialogs();
                        openPanel.close();
                    }
                    AmbientAccessPanel openClosedPanel = closedPanelRef.getAndSet(null);
                    if (openClosedPanel != null) {
                        openClosedPanel.dismissDialogs();
                        openClosedPanel.close();
                    }
                    HermesNotificationListener.clearSession();
                });
            } finally {
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }

    private static void dismissIfShowing(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}
