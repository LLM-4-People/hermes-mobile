package org.llm4people.hermes.panel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** Pure-JVM behavioral regression test for SessionPanel notification settings callback dispatch (R7.8.5). */
public final class SessionPanelNotificationsTest {

    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void resetDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void openNotificationsIsNullSafeBeforeAndAfterClearAndInvokesRegisteredCallback() {
        SessionPanel panel = new SessionPanel(null, null, null);
        // Before registration: null-safe no-op.
        panel.openNotifications();

        AtomicInteger calls = new AtomicInteger();
        panel.setOpenNotifications(calls::incrementAndGet);
        panel.openNotifications();
        panel.openNotifications();
        assertEquals(2, calls.get());

        // Clearing handler restores null-safe no-op without invoking previous listener.
        panel.setOpenNotifications(null);
        panel.openNotifications();
        assertEquals(2, calls.get());
    }
}
