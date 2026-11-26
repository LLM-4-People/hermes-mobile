package org.llm4people.hermes.push;

import android.database.Cursor;
import org.junit.Test;
import org.llm4people.hermes.os.PermissionCenter;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for PushSettings notification recovery and blocked-settings resume flow. */
public final class PushSettingsTest {
    private static PushStore.Device fakeDevice(String state, int repair) {
        List<String> columns = List.of(
                "profile", "installation", "endpoint", "state", "secret",
                "distributor", "signer", "vapid", "subscription", "subscription_version",
                "failure", "repair", "attempts", "retry_at", "established",
                "app", "api_key", "project", "sender");
        Map<String, Object> values = Map.ofEntries(
                Map.entry("profile", "p1"),
                Map.entry("installation", "inst-1"),
                Map.entry("endpoint", "https://push.example"),
                Map.entry("state", state),
                Map.entry("secret", new byte[]{1}),
                Map.entry("distributor", "org.unifiedpush.example"),
                Map.entry("signer", "abc"),
                Map.entry("vapid", "vapid"),
                Map.entry("subscription", new byte[]{2}),
                Map.entry("subscription_version", "v1"),
                Map.entry("failure", ""),
                Map.entry("repair", repair),
                Map.entry("attempts", 0),
                Map.entry("retry_at", 0L),
                Map.entry("established", 1),
                Map.entry("app", ""),
                Map.entry("api_key", ""),
                Map.entry("project", ""),
                Map.entry("sender", ""));
        Cursor cursor = (Cursor) Proxy.newProxyInstance(
                Cursor.class.getClassLoader(),
                new Class<?>[]{Cursor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getColumnIndexOrThrow".equals(name)) {
                        return columns.indexOf(args[0]);
                    }
                    int idx = (Integer) args[0];
                    Object val = values.get(columns.get(idx));
                    return switch (name) {
                        case "getString" -> (String) val;
                        case "getBlob" -> (byte[]) val;
                        case "getInt" -> ((Number) val).intValue();
                        case "getLong" -> ((Number) val).longValue();
                        default -> null;
                    };
                });
        return new PushStore.Device(cursor);
    }

    @Test
    public void recoveryOnlyRequestsRuntimePermissionOnApi33PlusWhenNotGrantedAndNotPermanentlyDenied() {
        // API 33+: NOT_REQUESTED / DENIED can still prompt at runtime.
        assertTrue(PushSettings.shouldRequestRuntimePermissionInRecovery(33, PermissionCenter.State.NOT_REQUESTED));
        assertTrue(PushSettings.shouldRequestRuntimePermissionInRecovery(34, PermissionCenter.State.DENIED));

        // API 33+ with GRANTED (muted notification channel / IMPORTANCE_NONE) must open Android Settings
        // instead of looping back into begin() -> showNotificationPermissionRecovery().
        assertFalse(PushSettings.shouldRequestRuntimePermissionInRecovery(33, PermissionCenter.State.GRANTED));
        assertFalse(PushSettings.shouldRequestRuntimePermissionInRecovery(35, PermissionCenter.State.GRANTED));

        // PERMANENTLY_DENIED, UNAVAILABLE, null, or pre-API 33 must open Android Notification Settings.
        assertFalse(PushSettings.shouldRequestRuntimePermissionInRecovery(33, PermissionCenter.State.PERMANENTLY_DENIED));
        assertFalse(PushSettings.shouldRequestRuntimePermissionInRecovery(33, PermissionCenter.State.UNAVAILABLE));
        assertFalse(PushSettings.shouldRequestRuntimePermissionInRecovery(33, null));
        assertFalse(PushSettings.shouldRequestRuntimePermissionInRecovery(32, PermissionCenter.State.NOT_REQUESTED));
        assertFalse(PushSettings.shouldRequestRuntimePermissionInRecovery(32, PermissionCenter.State.DENIED));
        assertFalse(PushSettings.shouldRequestRuntimePermissionInRecovery(32, PermissionCenter.State.GRANTED));
    }

    @Test
    public void blockedSettingsDialogIdentificationMatchesPositiveAndroidSettingsBranch() {
        PushStore.Device active = fakeDevice(PushStore.ACTIVE, PushStore.NO_REPAIR);
        PushStore.Device repairing = fakeDevice(PushStore.PENDING, PushStore.REPAIR_ACTION);
        PushStore.Device pendingNoRepair = fakeDevice(PushStore.PENDING, PushStore.NO_REPAIR);
        PushStore.Device removing = fakeDevice(PushStore.REMOVING, PushStore.NO_REPAIR);

        assertTrue(PushSettings.isBlockedSettingsDialog(active, false));
        assertTrue(PushSettings.isBlockedSettingsDialog(repairing, false));
        assertFalse(PushSettings.isBlockedSettingsDialog(active, true));
        assertFalse(PushSettings.isBlockedSettingsDialog(repairing, true));
        assertFalse(PushSettings.isBlockedSettingsDialog(pendingNoRepair, false));
        assertFalse(PushSettings.isBlockedSettingsDialog(removing, false));
        assertFalse(PushSettings.isBlockedSettingsDialog(null, false));
    }

    @Test
    public void resumeActionAdvancesRecoveryAndRefreshesBlockedSettingsOnlyWhileDialogStaysOpen() {
        // Recovery dialog kept open while user enables notifications in Android Settings -> auto-advance.
        assertEquals(PushSettings.ResumeAction.DISCOVER_TRANSPORT,
                PushSettings.resolveResumeAction(true, true, false, false, true));
        // Still blocked after returning from Settings -> stay on recovery dialog.
        assertEquals(PushSettings.ResumeAction.NONE,
                PushSettings.resolveResumeAction(true, true, false, false, false));
        // Dismissed dialog cannot resume (proves why Settings button must keep dialog open).
        assertEquals(PushSettings.ResumeAction.NONE,
                PushSettings.resolveResumeAction(false, true, false, false, true));

        // Main settings dialog kept open while user toggles notifications -> auto-refresh.
        assertEquals(PushSettings.ResumeAction.REFRESH_SETTINGS,
                PushSettings.resolveResumeAction(true, false, true, false, true));
        assertEquals(PushSettings.ResumeAction.REFRESH_SETTINGS,
                PushSettings.resolveResumeAction(true, false, true, true, false));
        // Unchanged state or dismissed dialog -> no-op.
        assertEquals(PushSettings.ResumeAction.NONE,
                PushSettings.resolveResumeAction(true, false, true, true, true));
        assertEquals(PushSettings.ResumeAction.NONE,
                PushSettings.resolveResumeAction(false, false, true, false, true));
    }
}
