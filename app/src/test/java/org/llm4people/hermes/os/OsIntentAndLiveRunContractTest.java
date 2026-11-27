package org.llm4people.hermes.os;

import org.junit.Test;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.presence.DeepAndroidActions;
import org.llm4people.hermes.presence.PresenceSettingsPanel;
import org.llm4people.hermes.push.DistributorReceiver;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/** Pure-JVM regression tests for Round-36 PR A OS intent, permission, and WorkspaceParts SSoT contracts. */
public final class OsIntentAndLiveRunContractTest {

    @Test
    public void exportedReceiversAndEntryIntentHandleNullFailClosed() {
        new DistributorReceiver().onReceive(null, null);
        new HermesWidgetProvider().onReceive(null, null);
        assertNull(OsEntryIntent.parseRequest(null));
    }

    @Test
    public void nudgeChannelEnabledFailsClosedOnNullAndLocationUnavailableMapsFailClosed() throws Exception {
        assertFalse(DeepAndroidActions.isNudgeChannelEnabled(null));
        assertFalse(DeepAndroidActions.areNudgesReady(null));
        assertEquals(
                PresenceSettingsPanel.ToggleDecision.UNAVAILABLE,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.UNAVAILABLE));
        assertEquals(
                PresenceSettingsPanel.ToggleDecision.OPEN_SETTINGS,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.PERMANENTLY_DENIED));

        // R37.3.3: reconcilePermissions strips revoked nudges and location before capability advertisement.
        org.llm4people.hermes.presence.PresencePolicy allOn = org.llm4people.hermes.presence.PresencePolicy.defaults()
                .withAppPresence(true)
                .withContinuousLocation(true)
                .withPreciseLocation(true)
                .withProactiveNudges(true);
        org.llm4people.hermes.presence.PresencePolicy reconciled = PresenceSettingsPanel.reconcilePermissions(
                allOn, false, false, false);
        assertFalse(reconciled.continuousLocationEnabled);
        assertFalse(reconciled.preciseLocation);
        assertFalse(reconciled.proactiveNudgesEnabled);

        // R37.3.4: PermissionCenter.areNotificationsEnabled fails closed on null Context / RuntimeException.
        Method areNotificationsEnabled = PermissionCenter.class.getDeclaredMethod(
                "areNotificationsEnabled", android.content.Context.class);
        areNotificationsEnabled.setAccessible(true);
        assertFalse((Boolean) areNotificationsEnabled.invoke(null, new Object[]{null}));

        // R37.3.1: HermesWidgetProvider.refresh(context) preserves runActive instead of resetting it to false.
        java.lang.reflect.Field runActiveField = HermesWidgetProvider.class.getDeclaredField("runActive");
        runActiveField.setAccessible(true);
        boolean previous = runActiveField.getBoolean(null);
        try {
            runActiveField.setBoolean(null, true);
            HermesWidgetProvider.refresh(null);
            org.junit.Assert.assertTrue("refresh(context) must preserve runActive", runActiveField.getBoolean(null));
        } finally {
            runActiveField.setBoolean(null, previous);
        }

        // R38.3.2: HermesWidgetPinning.requestPinShortcut with WIDGET_ACTION + null/invalid widgetId -> INVALID_SPEC.
        android.content.Context stubContext = new android.content.ContextWrapper(null) {
            @Override public android.content.pm.PackageManager getPackageManager() { return null; }
            @Override public Object getSystemService(String name) { throw new RuntimeException("boom"); }
            @Override public String getSystemServiceName(Class<?> serviceClass) { throw new RuntimeException("boom"); }
        };
        assertEquals(
                HermesWidgetPinning.PinResult.INVALID_SPEC,
                HermesWidgetPinning.requestPinShortcut(
                        stubContext, "valid_shortcut", "Label", OsEntryIntent.Target.WIDGET_ACTION, null));
        assertEquals(
                HermesWidgetPinning.PinResult.INVALID_SPEC,
                HermesWidgetPinning.requestPinShortcut(
                        stubContext, "valid_shortcut", "Label", OsEntryIntent.Target.WIDGET_ACTION, "bad id!"));

        // R38.3.3: PermissionCenter.isPermissionDeclared fails closed when PackageManager is null.
        assertFalse(PermissionCenter.isPermissionDeclared(stubContext, "android.permission.POST_NOTIFICATIONS"));

        // R38.3.4 & R39.8.4: DeepAndroidActions.isNudgeChannelEnabled catches RuntimeException, logs warning, returns false.
        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertFalse(DeepAndroidActions.isNudgeChannelEnabled(stubContext));
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected deep_action.nudge_channel_check_failed, got: " + diag,
                    diag.contains("deep_action.nudge_channel_check_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        // R39.3.4: ApkUpdateInstaller.canRequestPackageInstalls fails closed on null Context, null PM, and throwing PM.
        assertFalse(org.llm4people.hermes.update.ApkUpdateInstaller.canRequestPackageInstalls(null));
        assertFalse(org.llm4people.hermes.update.ApkUpdateInstaller.canRequestPackageInstalls(stubContext));
        android.content.Context throwingPmContext = new android.content.ContextWrapper(null) {
            @Override public android.content.pm.PackageManager getPackageManager() {
                throw new RuntimeException("pm ipc failed");
            }
        };
        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertFalse(org.llm4people.hermes.update.ApkUpdateInstaller.canRequestPackageInstalls(throwingPmContext));
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected update.install_permission_check_failed, got: " + diag,
                    diag.contains("update.install_permission_check_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        // R39.3.3: LiveRunNotification.allowed and cancelNotificationOnly fail closed on null / throwing NotificationManager.
        Method allowedMethod = LiveRunNotification.class.getDeclaredMethod("allowed", android.content.Context.class);
        allowedMethod.setAccessible(true);
        Method cancelOnlyMethod = LiveRunNotification.class.getDeclaredMethod(
                "cancelNotificationOnly", android.content.Context.class);
        cancelOnlyMethod.setAccessible(true);
        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertFalse((Boolean) allowedMethod.invoke(null, new Object[]{null}));
            assertFalse((Boolean) allowedMethod.invoke(null, stubContext));
            cancelOnlyMethod.invoke(null, stubContext);
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected live_run.allowed_check_failed, got: " + diag,
                    diag.contains("live_run.allowed_check_failed"));
            org.junit.Assert.assertTrue("Expected live_run.cancel_failed, got: " + diag,
                    diag.contains("live_run.cancel_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }
    }

    @Test
    public void r40IpcAndNudgeGuardsFailClosed() throws Exception {
        android.content.Context nullPmCtx = new android.content.ContextWrapper(null) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public android.content.pm.PackageManager getPackageManager() { return null; }
        };
        android.content.Context throwingCtx = new android.content.ContextWrapper(null) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public android.content.pm.PackageManager getPackageManager() {
                throw new RuntimeException("pm ipc failed");
            }
            @Override public Object getSystemService(String name) {
                throw new RuntimeException("service ipc failed");
            }
            @Override public String getSystemServiceName(Class<?> serviceClass) {
                throw new RuntimeException("service name ipc failed");
            }
        };

        // R40.8.2 & R41.8.2: DeepAndroidActions.androidOsBridge().postLocalNudge fails closed on throwing NotificationManager.
        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertFalse(DeepAndroidActions.androidOsBridge(throwingCtx, "profile-1", null, null)
                    .postLocalNudge("Title", "Body"));
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected deep_action.nudge_notify_failed, got: " + diag,
                    diag.contains("deep_action.nudge_notify_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        // R40.3.1 & R41.8.2: PushNotices IPC guards fail closed with per-case Diagnostics assertions.
        Class<?> pushNotices = Class.forName("org.llm4people.hermes.push.PushNotices");
        Method pushAllowed = pushNotices.getDeclaredMethod("allowed", android.content.Context.class);
        Method pushTapIntent = pushNotices.getDeclaredMethod(
                "tapIntent", android.content.Context.class, String.class, String.class);
        Method pushChannel = pushNotices.getDeclaredMethod("channel", android.content.Context.class);
        Method pushCancel = pushNotices.getDeclaredMethod("cancel", android.content.Context.class, String.class);
        pushAllowed.setAccessible(true);
        pushTapIntent.setAccessible(true);
        pushChannel.setAccessible(true);
        pushCancel.setAccessible(true);

        // Null PackageManager paths must return null with zero warnings.
        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertFalse((Boolean) pushAllowed.invoke(null, new Object[]{null}));
            assertNull(pushTapIntent.invoke(null, null, "p1", "ev1"));
            assertNull(pushTapIntent.invoke(null, nullPmCtx, "p1", "ev1"));
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            assertFalse("Expected zero push warnings on null PackageManager, got: " + diag,
                    diag.contains("push."));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertFalse((Boolean) pushAllowed.invoke(null, throwingCtx));
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected push.allowed_check_failed, got: " + diag,
                    diag.contains("push.allowed_check_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertNull(pushTapIntent.invoke(null, throwingCtx, "p1", "ev1"));
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected push.tap_intent_failed, got: " + diag,
                    diag.contains("push.tap_intent_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        org.llm4people.hermes.Diagnostics.clear();
        try {
            pushChannel.invoke(null, throwingCtx);
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected push.channel_create_failed, got: " + diag,
                    diag.contains("push.channel_create_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        org.llm4people.hermes.Diagnostics.clear();
        try {
            pushCancel.invoke(null, throwingCtx, "p1");
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected push.notice_cancel_failed, got: " + diag,
                    diag.contains("push.notice_cancel_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        // R40.3.2 & R41.8.2: PushSyncService.ensureScheduled fails closed on null & throwing JobScheduler.
        Class<?> pushSyncService = Class.forName("org.llm4people.hermes.push.PushSyncService");
        Method ensureScheduled = pushSyncService.getDeclaredMethod("ensureScheduled", android.content.Context.class);
        ensureScheduled.setAccessible(true);
        org.llm4people.hermes.Diagnostics.clear();
        try {
            ensureScheduled.invoke(null, new Object[]{null});
            String nullDiag = org.llm4people.hermes.Diagnostics.exportRedacted();
            assertFalse("Expected zero warnings on null Context, got: " + nullDiag,
                    nullDiag.contains("push.job_scheduler_unavailable"));
            ensureScheduled.invoke(null, throwingCtx);
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected push.job_scheduler_unavailable, got: " + diag,
                    diag.contains("push.job_scheduler_unavailable"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        // R41.8.1: BiometricVault availability & supportsDeviceCredential fail closed across sdkInt 28/29/30.
        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertEquals(BiometricVault.Availability.UNAVAILABLE, BiometricVault.availability(null, 30));
            assertEquals(BiometricVault.Availability.UNAVAILABLE, BiometricVault.availability(throwingCtx, 28));
            assertFalse(BiometricVault.supportsDeviceCredential(null, 30));
            assertFalse(BiometricVault.supportsDeviceCredential(throwingCtx, 29));
            String belowMinDiag = org.llm4people.hermes.Diagnostics.exportRedacted();
            assertFalse("Expected no warning below min SDK, got: " + belowMinDiag,
                    belowMinDiag.contains("biometric.availability_check_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        for (int sdk : new int[]{29, 30}) {
            org.llm4people.hermes.Diagnostics.clear();
            try {
                assertEquals(BiometricVault.Availability.UNAVAILABLE, BiometricVault.availability(throwingCtx, sdk));
                String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
                org.junit.Assert.assertTrue("Expected biometric.availability_check_failed on sdk=" + sdk + ", got: " + diag,
                        diag.contains("biometric.availability_check_failed"));
            } finally {
                org.llm4people.hermes.Diagnostics.clear();
            }
        }

        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertFalse(BiometricVault.supportsDeviceCredential(throwingCtx, 30));
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected biometric.availability_check_failed on supportsDeviceCredential sdk=30, got: " + diag,
                    diag.contains("biometric.availability_check_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }

        // R40.3.5 & R41.8.2: DistributorIdentity.current & matches fail closed with zero warnings on null PackageManager.
        Class<?> distributorIdentity = Class.forName("org.llm4people.hermes.push.DistributorIdentity");
        Method currentIdentity = distributorIdentity.getDeclaredMethod(
                "current", android.content.Context.class, String.class);
        Method matchesIdentity = distributorIdentity.getDeclaredMethod(
                "matches", android.content.Context.class, String.class, String.class);
        currentIdentity.setAccessible(true);
        matchesIdentity.setAccessible(true);
        org.llm4people.hermes.Diagnostics.clear();
        try {
            assertNull(currentIdentity.invoke(null, null, "org.example.push"));
            assertNull(currentIdentity.invoke(null, nullPmCtx, "org.example.push"));
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            assertFalse("Expected zero warnings on null PackageManager, got: " + diag,
                    diag.contains("push."));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }
        assertNull(currentIdentity.invoke(null, throwingCtx, "org.example.push"));
        assertFalse((Boolean) matchesIdentity.invoke(null, throwingCtx, "org.example.push", "sha256:abc"));

        // R41.3.2 & R41.3.4: HermesShortcuts.reportUsed & DistributorPackageReceiver fail closed.
        org.llm4people.hermes.Diagnostics.clear();
        try {
            HermesShortcuts.reportUsed(throwingCtx, OsEntryIntent.Target.DICTATE);
            String diag = org.llm4people.hermes.Diagnostics.exportRedacted();
            org.junit.Assert.assertTrue("Expected os.shortcut_report_failed, got: " + diag,
                    diag.contains("os.shortcut_report_failed"));
        } finally {
            org.llm4people.hermes.Diagnostics.clear();
        }
        new org.llm4people.hermes.push.DistributorPackageReceiver().onReceive(null, null);
        assertEquals(org.llm4people.hermes.AppLanguage.Mode.AUTO, org.llm4people.hermes.AppLanguage.readMode(null));
    }

    @Test
    public void workspacePartsDelegatesJsonQuotingToJsonValues() throws Exception {
        Class<?> workspaceParts = Class.forName("org.llm4people.hermes.artifact.WorkspaceParts");
        for (Method m : workspaceParts.getDeclaredMethods()) {
            assertFalse("Duplicate appendQuoted must be removed in favor of JsonValues.quote",
                    "appendQuoted".equals(m.getName()));
        }
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\u0000\"", JsonValues.quote("a\"b\\c\n\r\t\u0000"));
    }
}
