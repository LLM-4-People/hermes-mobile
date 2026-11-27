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
