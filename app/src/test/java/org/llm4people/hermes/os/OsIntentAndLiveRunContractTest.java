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
    public void nudgeChannelEnabledFailsClosedOnNullAndLocationUnavailableMapsFailClosed() {
        assertFalse(DeepAndroidActions.isNudgeChannelEnabled(null));
        assertEquals(
                PresenceSettingsPanel.ToggleDecision.UNAVAILABLE,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.UNAVAILABLE));
        assertEquals(
                PresenceSettingsPanel.ToggleDecision.OPEN_SETTINGS,
                PresenceSettingsPanel.decidePermissionToggle(true, PermissionCenter.State.PERMANENTLY_DENIED));
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
