package org.llm4people.hermes.panel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.Button;

import org.json.JSONObject;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class ReminderEditorScheduleTest extends InstrumentationTestCase {
    public void testFriendlyPresetsDisplayWithoutRawCronUntilAdvancedExpanded() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SessionPanel panel = new SessionPanel(activity, null, null);
        try {
            JSONObject weekdayJob = new JSONObject()
                    .put("id", "job-1")
                    .put("name", "Briefing")
                    .put("prompt", "Résume ma journée")
                    .put("schedule", new JSONObject().put("kind", "cron").put("expr", "0 9 * * 1-5"));
            runTestOnUiThread(() -> new ReminderEditor(panel, new ReminderPanel(panel), weekdayJob).show());
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                List<View> windows = WindowInspector.getGlobalWindowViews();
                View top = windows.get(windows.size() - 1);
                assertTrue(hasText(top, "Une seule fois"));
                assertTrue(hasText(top, "Chaque jour"));
                assertTrue(hasText(top, "Jours de semaine"));
                assertTrue(hasText(top, "Chaque semaine"));
                assertTrue(hasText(top, "Personnalisé"));
                assertTrue(hasText(top, "Du lundi au vendredi à 09 h 00"));
                // Raw cron expression input stays tucked away until user opens "Expression avancée".
                assertFalse(hasText(top, "Expression horaire Hermes"));
                Button adv = findButton(top, "Expression avancée");
                assertNotNull(adv);
                adv.performClick();
                assertTrue(hasText(top, "Expression horaire Hermes"));
            });
            // Native schedule builder produces valid gateway schedule string.
            ReminderSchedule parsed = ReminderSchedule.parse("0 9 * * 1-5", ZoneId.of("Europe/Paris"),
                    ZonedDateTime.now(ZoneId.of("Europe/Paris")));
            assertEquals("0 9 * * 1-5", parsed.toGatewaySchedule(ZoneId.of("Europe/Paris")));
        } finally {
            runTestOnUiThread(() -> {
                panel.close();
                activity.finish();
            });
        }
    }

    private static boolean hasText(View root, String text) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        return !matches.isEmpty();
    }

    private static Button findButton(View root, String text) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : matches) {
            if (v instanceof Button b) return b;
        }
        return null;
    }
}
