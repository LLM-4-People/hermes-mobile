package org.llm4people.hermes.panel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.Button;

import org.json.JSONObject;
import org.llm4people.hermes.R;

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
                assertTrue(hasText(top, activity.getString(R.string.panel_preset_once)));
                assertTrue(hasText(top, activity.getString(R.string.panel_preset_daily)));
                assertTrue(hasText(top, activity.getString(R.string.panel_preset_weekdays)));
                assertTrue(hasText(top, activity.getString(R.string.panel_preset_weekly)));
                assertTrue(hasText(top, activity.getString(R.string.panel_preset_custom)));
                String expectedSummary = ReminderSchedule.parse("0 9 * * 1-5", ZoneId.systemDefault(),
                        ZonedDateTime.now(ZoneId.systemDefault())).humanSummary(ReminderPanel.activeLocale(activity));
                assertTrue(hasText(top, expectedSummary));
                // Raw cron expression input stays tucked away until user opens advanced schedule.
                assertFalse(hasText(top, activity.getString(R.string.panel_expression)));
                Button adv = findButton(top, activity.getString(R.string.panel_advanced_toggle_show));
                assertNotNull(adv);
                adv.performClick();
                assertTrue(hasText(top, activity.getString(R.string.panel_expression)));
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
