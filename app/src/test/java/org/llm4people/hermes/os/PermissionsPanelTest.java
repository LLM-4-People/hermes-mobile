package org.llm4people.hermes.os;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for {@link PermissionsPanel} stale denial/blocked feedback clearing (R15.3.3). */
public final class PermissionsPanelTest {

    @Test
    public void shouldClearStaleFeedbackReturnsTrueOnlyWhenPendingKeyBecomesGranted() {
        Map<PermissionCenter.Key, PermissionCenter.State> states = new EnumMap<>(PermissionCenter.Key.class);
        states.put(PermissionCenter.Key.CAMERA, PermissionCenter.State.GRANTED);
        states.put(PermissionCenter.Key.MICROPHONE, PermissionCenter.State.PERMANENTLY_DENIED);
        states.put(PermissionCenter.Key.LOCATION, PermissionCenter.State.DENIED);
        states.put(PermissionCenter.Key.NOTIFICATIONS, PermissionCenter.State.NOT_REQUESTED);
        states.put(PermissionCenter.Key.CALENDAR, PermissionCenter.State.UNAVAILABLE);

        // Positive: pending key is now GRANTED -> clear stale error banner.
        assertTrue(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, states));

        // Negative: pending key is still denied/blocked/off/unavailable -> keep feedback visible.
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.MICROPHONE, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.LOCATION, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.NOTIFICATIONS, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CALENDAR, states));

        // Negative: null/missing/empty inputs are safe no-ops.
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(null, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, null));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, Collections.emptyMap()));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.NOTIFICATION_ACCESS, states));
    }
}
