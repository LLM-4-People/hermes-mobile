package org.llm4people.hermes.os;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for {@link PermissionsPanel} bidirectional stale-feedback clearing (R16.3.2). */
public final class PermissionsPanelTest {

    @Test
    public void shouldClearStaleFeedbackHandlesBothDeniedToGrantedAndGrantedToRevokedTransitions() {
        Map<PermissionCenter.Key, PermissionCenter.State> states = new EnumMap<>(PermissionCenter.Key.class);
        states.put(PermissionCenter.Key.CAMERA, PermissionCenter.State.GRANTED);
        states.put(PermissionCenter.Key.MICROPHONE, PermissionCenter.State.PERMANENTLY_DENIED);
        states.put(PermissionCenter.Key.LOCATION, PermissionCenter.State.DENIED);
        states.put(PermissionCenter.Key.NOTIFICATIONS, PermissionCenter.State.NOT_REQUESTED);
        states.put(PermissionCenter.Key.CALENDAR, PermissionCenter.State.UNAVAILABLE);

        // Direction 1: denied/blocked banner clears once permission becomes GRANTED, stays otherwise.
        assertTrue(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, false, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.MICROPHONE, false, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.LOCATION, false, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.NOTIFICATIONS, false, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CALENDAR, false, states));

        // Direction 2: granted banner clears once permission is revoked/unavailable, stays while GRANTED.
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, true, states));
        assertTrue(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.MICROPHONE, true, states));
        assertTrue(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.LOCATION, true, states));
        assertTrue(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.NOTIFICATIONS, true, states));
        assertTrue(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CALENDAR, true, states));

        // Negative: null/missing/empty inputs are safe no-ops in both directions.
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(null, false, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(null, true, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, false, null));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, true, null));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, false, Collections.emptyMap()));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.CAMERA, true, Collections.emptyMap()));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.NOTIFICATION_ACCESS, false, states));
        assertFalse(PermissionsPanel.shouldClearStaleFeedback(PermissionCenter.Key.NOTIFICATION_ACCESS, true, states));
    }

    @Test
    public void feedbackResForPermissionOutcomeMatchesLiveState() {
        org.junit.Assert.assertEquals(
                org.llm4people.hermes.R.string.perm_feedback_granted,
                PermissionsPanel.feedbackResForPermissionOutcome(PermissionCenter.State.GRANTED));
        org.junit.Assert.assertEquals(
                org.llm4people.hermes.R.string.perm_feedback_blocked,
                PermissionsPanel.feedbackResForPermissionOutcome(PermissionCenter.State.PERMANENTLY_DENIED));
        org.junit.Assert.assertEquals(
                org.llm4people.hermes.R.string.perm_feedback_denied,
                PermissionsPanel.feedbackResForPermissionOutcome(PermissionCenter.State.DENIED));
        org.junit.Assert.assertEquals(
                org.llm4people.hermes.R.string.perm_feedback_denied,
                PermissionsPanel.feedbackResForPermissionOutcome(PermissionCenter.State.NOT_REQUESTED));
        org.junit.Assert.assertEquals(
                org.llm4people.hermes.R.string.perm_feedback_denied,
                PermissionsPanel.feedbackResForPermissionOutcome(PermissionCenter.State.UNAVAILABLE));
        org.junit.Assert.assertEquals(
                org.llm4people.hermes.R.string.perm_feedback_denied,
                PermissionsPanel.feedbackResForPermissionOutcome(null));
    }
}
