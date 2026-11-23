package org.llm4people.hermes.os;

import org.junit.Test;
import org.llm4people.hermes.R;

import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

public class LiveRunNotificationTest {
    private static LiveRunNotification.TextLookup frenchLookup() {
        return mapLookup(Map.ofEntries(
                Map.entry(R.string.live_run_working_title, "Hermes travaille…"),
                Map.entry(R.string.live_run_working_body, "Ouvrez l’application pour suivre la réponse."),
                Map.entry(R.string.live_run_decision_title, "Décision requise"),
                Map.entry(R.string.live_run_decision_body, "Hermes attend votre réponse dans l’application."),
                Map.entry(R.string.live_run_chip_action, "Action"),
                Map.entry(R.string.live_run_chip_sending, "Envoi"),
                Map.entry(R.string.live_run_chip_working, "En cours"),
                Map.entry(R.string.live_run_chip_step_of, "Étape %1$d/%2$d"),
                Map.entry(R.string.live_run_chip_step, "Étape %1$d"),
                Map.entry(R.string.live_run_multi_decision_title, "%1$d tâches · action requise"),
                Map.entry(R.string.live_run_multi_body, "Ouvrez l’application pour suivre vos tâches en cours."),
                Map.entry(R.string.live_run_multi_chip_action, "Action (%1$d/%2$d)")
        ), Map.of(
                R.plurals.live_run_multi_title, "%1$d tâches en cours",
                R.plurals.live_run_multi_chip, "%1$d tâches"
        ));
    }

    private static LiveRunNotification.TextLookup englishLookup() {
        return mapLookup(Map.ofEntries(
                Map.entry(R.string.live_run_working_title, "Hermes is working…"),
                Map.entry(R.string.live_run_working_body, "Open the app to follow the reply."),
                Map.entry(R.string.live_run_decision_title, "Decision needed"),
                Map.entry(R.string.live_run_decision_body, "Hermes is waiting for your reply in the app."),
                Map.entry(R.string.live_run_chip_action, "Action"),
                Map.entry(R.string.live_run_chip_sending, "Sending"),
                Map.entry(R.string.live_run_chip_working, "Working"),
                Map.entry(R.string.live_run_chip_step_of, "Step %1$d/%2$d"),
                Map.entry(R.string.live_run_chip_step, "Step %1$d"),
                Map.entry(R.string.live_run_multi_decision_title, "%1$d tasks · action needed"),
                Map.entry(R.string.live_run_multi_body, "Open the app to follow your active tasks."),
                Map.entry(R.string.live_run_multi_chip_action, "Action (%1$d/%2$d)")
        ), Map.of(
                R.plurals.live_run_multi_title, "%1$d active tasks",
                R.plurals.live_run_multi_chip, "%1$d tasks"
        ));
    }

    private static LiveRunNotification.TextLookup mapLookup(Map<Integer, String> strings, Map<Integer, String> plurals) {
        return new LiveRunNotification.TextLookup() {
            @Override public String string(int resId, Object... args) {
                String pattern = strings.get(resId);
                assertNotNull("Missing string resource " + resId, pattern);
                return args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
            }
            @Override public String quantity(int pluralsId, int quantity, Object... args) {
                String pattern = plurals.get(pluralsId);
                assertNotNull("Missing plural resource " + pluralsId, pattern);
                return String.format(Locale.ROOT, pattern, args);
            }
        };
    }

    @Test public void idleAndNullSnapshotsProduceNoNotificationPresentation() {
        assertNull(LiveRunNotification.present((LiveRunNotification.Snapshot) null, frenchLookup()));
        assertNull(LiveRunNotification.present(LiveRunNotification.Snapshot.idle(), frenchLookup()));
        assertNull(LiveRunNotification.present((android.content.Context) null, LiveRunNotification.Snapshot.idle()));
        assertFalse(LiveRunNotification.Snapshot.idle().active());
    }

    @Test public void presentationsResolveLocalizedFrenchAndEnglishResourcesWithoutPrivateData() {
        LiveRunNotification.Snapshot startingSnap =
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.STARTING, 0, 0, false, false);
        LiveRunNotification.Presentation startingFr = LiveRunNotification.present(startingSnap, frenchLookup());
        assertNotNull(startingFr);
        assertEquals("Hermes travaille…", startingFr.title());
        assertEquals("Envoi", startingFr.shortCriticalText());
        assertTrue(startingFr.indeterminate());

        LiveRunNotification.Presentation startingEn = LiveRunNotification.present(startingSnap, englishLookup());
        assertNotNull(startingEn);
        assertEquals("Hermes is working…", startingEn.title());
        assertEquals("Sending", startingEn.shortCriticalText());

        LiveRunNotification.Snapshot steppedSnap =
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.TOOL_RUNNING, 2, 4, false, true);
        LiveRunNotification.Presentation steppedFr = LiveRunNotification.present(steppedSnap, frenchLookup());
        assertNotNull(steppedFr);
        assertEquals("Étape 2/4", steppedFr.shortCriticalText());
        assertFalse(steppedFr.indeterminate());
        assertTrue(steppedFr.progress() > 0 && steppedFr.progress() < steppedFr.max());

        LiveRunNotification.Presentation steppedEn = LiveRunNotification.present(steppedSnap, englishLookup());
        assertNotNull(steppedEn);
        assertEquals("Step 2/4", steppedEn.shortCriticalText());

        LiveRunNotification.Snapshot decisionSnap =
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.AWAITING_DECISION, 3, 4, true, true);
        LiveRunNotification.Presentation decisionFr = LiveRunNotification.present(decisionSnap, frenchLookup());
        assertNotNull(decisionFr);
        assertEquals("Décision requise", decisionFr.title());
        assertEquals("Action", decisionFr.shortCriticalText());
        assertFalse(decisionFr.indeterminate());

        LiveRunNotification.Presentation decisionEn = LiveRunNotification.present(decisionSnap, englishLookup());
        assertNotNull(decisionEn);
        assertEquals("Decision needed", decisionEn.title());
    }

    @Test public void outOfRangeStepCountsClampSafely() {
        LiveRunNotification.Presentation clamped = LiveRunNotification.present(
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.TOOL_RUNNING, 500, 3, false, true),
                frenchLookup());
        assertNotNull(clamped);
        assertEquals("Étape 3/3", clamped.shortCriticalText());

        LiveRunNotification.Presentation negative = LiveRunNotification.present(
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.WORKING, -5, -2, false, true),
                frenchLookup());
        assertNotNull(negative);
        assertEquals("En cours", negative.shortCriticalText());
        assertTrue(negative.indeterminate());
    }

    @Test public void consecutiveEqualSnapshotsAreDeduplicatedWhileStateChangesEmitImmediately() {
        LiveRunNotification.Snapshot working = new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.WORKING, 0, 0, false, true);
        LiveRunNotification.Snapshot workingCopy = new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.WORKING, 0, 0, false, true);
        LiveRunNotification.Snapshot step1 = new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.TOOL_RUNNING, 1, 0, false, true);
        LiveRunNotification.Snapshot decision = new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.AWAITING_DECISION, 1, 0, true, true);

        assertFalse(LiveRunNotification.shouldEmit(null, LiveRunNotification.Snapshot.idle()));
        assertTrue(LiveRunNotification.shouldEmit(LiveRunNotification.Snapshot.idle(), working));
        assertFalse("Repeated 80ms SSE delta frames must not spam notify()",
                LiveRunNotification.shouldEmit(working, workingCopy));
        assertTrue(LiveRunNotification.shouldEmit(working, step1));
        assertTrue(LiveRunNotification.shouldEmit(step1, decision));
        assertTrue(LiveRunNotification.shouldEmit(decision, LiveRunNotification.Snapshot.idle()));
        assertFalse(LiveRunNotification.shouldEmit(LiveRunNotification.Snapshot.idle(), null));
    }

    @Test public void multiRunPresentationsShowCountsAndDecisionSummaryWithoutPrivateText() {
        LiveRunNotification.Snapshot threeRunning = new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.WORKING, 0, 0, false, true, 3, 0);
        LiveRunNotification.Presentation multi = LiveRunNotification.present(threeRunning, frenchLookup());
        assertNotNull(multi);
        assertEquals("3 tâches en cours", multi.title());
        assertEquals("3 tâches", multi.shortCriticalText());
        assertTrue(multi.indeterminate());

        LiveRunNotification.Presentation multiEn = LiveRunNotification.present(threeRunning, englishLookup());
        assertNotNull(multiEn);
        assertEquals("3 active tasks", multiEn.title());
        assertEquals("3 tasks", multiEn.shortCriticalText());

        LiveRunNotification.Snapshot fourWithTwoDecisions = new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.AWAITING_DECISION, 4, 0, true, true, 4, 2);
        LiveRunNotification.Presentation multiDecision = LiveRunNotification.present(fourWithTwoDecisions, frenchLookup());
        assertNotNull(multiDecision);
        assertEquals("4 tâches · action requise", multiDecision.title());
        assertEquals("Action (2/4)", multiDecision.shortCriticalText());
        assertFalse(multiDecision.indeterminate());

        LiveRunNotification.Snapshot twoRunning = new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.WORKING, 0, 0, false, true, 2, 0);
        assertTrue(LiveRunNotification.shouldEmit(twoRunning, threeRunning));

        LiveRunNotification.Presentation clamped = LiveRunNotification.present(
                new LiveRunNotification.Snapshot(LiveRunNotification.Phase.WORKING, 0, 0, false, true, 999, 0),
                frenchLookup());
        assertNotNull(clamped);
        assertEquals("99 tâches", clamped.shortCriticalText());
    }

    @Test public void actionLabelsResolveChannelOpenAndStopFromLocalizedStringLookup() {
        Map<Integer, String> en = Map.of(
                R.string.live_run_channel_name, "Active work",
                R.string.live_run_action_open, "Open",
                R.string.live_run_action_stop, "Stop");
        LiveRunNotification.ActionLabels labels = LiveRunNotification.actionLabels(en::get);
        assertEquals("Active work", labels.channelName());
        assertEquals("Open", labels.openLabel());
        assertEquals("Stop", labels.stopLabel());
        assertNull(LiveRunNotification.localizedContext(null));
    }
}
