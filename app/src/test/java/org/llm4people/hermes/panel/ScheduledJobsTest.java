package org.llm4people.hermes.panel;

import org.junit.Test;
import org.llm4people.hermes.R;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

public final class ScheduledJobsTest {
    @Test public void summarizesCountsAndFiltersJobsDeterministically() {
        ScheduledJobModel.Summary summary = ScheduledJobModel.summarizeStates(
                List.of("scheduled", "scheduled", "paused", "error", "completed"));
        assertEquals(5, summary.total());
        assertEquals(2, summary.active());
        assertEquals(1, summary.paused());
        assertEquals(1, summary.errors());
        assertEquals(1, summary.completed());
        assertEquals(2, summary.attention());

        assertTrue(ScheduledJobModel.matchesFilter("scheduled", ScheduledJobModel.Filter.ALL));
        assertTrue(ScheduledJobModel.matchesFilter("scheduled", ScheduledJobModel.Filter.ACTIVE));
        assertFalse(ScheduledJobModel.matchesFilter("paused", ScheduledJobModel.Filter.ACTIVE));
        assertTrue(ScheduledJobModel.matchesFilter("paused", ScheduledJobModel.Filter.PAUSED));
        assertTrue(ScheduledJobModel.matchesFilter("error", ScheduledJobModel.Filter.ATTENTION));
        assertTrue(ScheduledJobModel.matchesFilter("completed", ScheduledJobModel.Filter.ATTENTION));
        assertFalse(ScheduledJobModel.matchesFilter("scheduled", ScheduledJobModel.Filter.ATTENTION));

        assertEquals(0, ScheduledJobModel.summarizeStates(null).total());
    }

    @Test public void buildsCanonicalTriggerAndRunsPathsWithValidation() {
        assertEquals("/api/jobs/job-42/run", ScheduledJobModel.triggerPath("job-42"));
        assertEquals("/api/jobs/job-42/runs?limit=10", ScheduledJobModel.runsPath("job-42", 10));
        assertEquals("/api/jobs/job-42/runs?limit=50", ScheduledJobModel.runsPath("job-42", 999));
        assertEquals("/api/jobs/job-42/runs?limit=1", ScheduledJobModel.runsPath("job-42", 0));

        try {
            ScheduledJobModel.triggerPath("   ");
            fail("Expected blank job id rejection");
        } catch (IllegalArgumentException expected) {}
        try {
            ScheduledJobModel.runsPath(null, 10);
            fail("Expected null job id rejection");
        } catch (IllegalArgumentException expected) {}
    }

    @Test public void parsesRecentRunsHandlesNumericAndIsoTimestampsAndFormatsDurations() {
        Map<String, Object> run1 = new LinkedHashMap<>();
        run1.put("id", "run-1");
        run1.put("status", "completed");
        run1.put("started_at", 1_760_000_000.0);
        run1.put("ended_at", 1_760_000_125.0);
        run1.put("title", "Morning brief");
        run1.put("preview", "Summary delivered.");
        run1.put("is_active", false);

        Map<String, Object> run2 = new LinkedHashMap<>();
        run2.put("id", "run-2");
        run2.put("status", "failed");
        run2.put("started_at", "2026-11-22T09:00:00Z");
        run2.put("ended_at", "2026-11-22T09:00:14Z");
        run2.put("title", "FAILED: timeout");
        run2.put("is_active", false);

        Map<String, Object> run3 = new LinkedHashMap<>();
        run3.put("id", "run-3");
        run3.put("status", "unknown");
        run3.put("started_at", 1_760_001_000L);
        run3.put("is_active", true);

        Map<String, Object> response = Map.of("runs", List.of(run1, run2, run3));
        List<ScheduledJobModel.JobRun> parsed = ScheduledJobModel.parseRunsMap(response);
        assertEquals(3, parsed.size());
        assertEquals("completed", parsed.get(0).status());
        assertEquals(125L, parsed.get(0).durationSeconds());
        assertEquals("2m 05s", ScheduledJobModel.formatDuration(parsed.get(0).durationSeconds(), Locale.ROOT));
        assertEquals("failed", parsed.get(1).status());
        assertEquals(14L, parsed.get(1).durationSeconds());
        assertEquals("14s", ScheduledJobModel.formatDuration(parsed.get(1).durationSeconds(), Locale.ROOT));
        assertEquals("running", parsed.get(2).status());
        assertTrue(parsed.get(2).active());
        assertEquals(-1L, parsed.get(2).durationSeconds());
        assertEquals("", ScheduledJobModel.formatDuration(-1L, Locale.ROOT));
        assertEquals("1h 04m", ScheduledJobModel.formatDuration(3840L, Locale.ROOT));
        assertEquals("0s", ScheduledJobModel.formatDuration(0L, Locale.FRENCH));
        assertEquals("2m 05s", ScheduledJobModel.formatDuration(125L, Locale.FRENCH));
        assertEquals("1h 04m", ScheduledJobModel.formatDuration(3840L, Locale.ENGLISH));
        assertEquals("14s", ScheduledJobModel.formatDuration(14L, null));
        assertEquals("", ScheduledJobModel.formatDuration(-5L, Locale.FRENCH));

        assertTrue(ScheduledJobModel.parseRunsMap(null).isEmpty());
        assertTrue(ScheduledJobModel.parseRunsMap(Map.of()).isEmpty());
    }

    @Test public void formatsSchedulesAndTimestampsInBothEnglishAndFrenchWithoutHardcoding() {
        ReminderSchedule daily = new ReminderSchedule(
                ReminderSchedule.Preset.DAILY,
                LocalDate.of(2026, 11, 25),
                LocalTime.of(8, 30),
                DayOfWeek.MONDAY,
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                false,
                30,
                "");
        assertEquals(R.string.ws_sched_daily, daily.summarySpec(Locale.FRENCH, "08 h 30").resId());
        assertEquals(R.string.ws_sched_daily, daily.summarySpec(Locale.ENGLISH, "08:30").resId());

        ReminderSchedule weekdays = daily.withPreset(ReminderSchedule.Preset.WEEKDAYS).withTime(LocalTime.of(9, 0));
        assertEquals(R.string.ws_sched_weekdays, weekdays.summarySpec(Locale.FRENCH, "09 h 00").resId());
        assertEquals(R.string.ws_sched_weekdays, weekdays.summarySpec(Locale.ENGLISH, "09:00").resId());

        ReminderSchedule weekly = daily.withPreset(ReminderSchedule.Preset.WEEKLY)
                .withWeeklyDay(DayOfWeek.SUNDAY).withTime(LocalTime.of(18, 45));
        assertEquals("dimanche", weekly.summarySpec(Locale.FRENCH, "18 h 45").args()[0]);
        assertEquals("Sunday", weekly.summarySpec(Locale.ENGLISH, "18:45").args()[0]);

        ReminderSchedule customDays = daily.withPreset(ReminderSchedule.Preset.CUSTOM);
        assertEquals(R.string.ws_sched_custom_days, customDays.summarySpec(Locale.FRENCH, "08 h 30").resId());
        assertEquals(R.string.ws_sched_custom_days, customDays.summarySpec(Locale.ENGLISH, "08:30").resId());

        ReminderSchedule customInterval = customDays.withCustomIntervalMode(true).withIntervalMinutes(45);
        assertEquals(R.string.ws_sched_interval_mins, customInterval.summarySpec(Locale.FRENCH, null).resId());
        assertEquals(45, customInterval.summarySpec(Locale.ENGLISH, null).args()[0]);

        String enTime = ReminderPanel.formatTime("2026-11-25T09:00:00Z", ZoneId.of("UTC"), Locale.US);
        String frTime = ReminderPanel.formatTime("2026-11-25T09:00:00Z", ZoneId.of("UTC"), Locale.FRANCE);
        assertFalse(enTime.isEmpty());
        assertFalse(frTime.isEmpty());
        assertNotEquals(enTime, frTime);

        String torontoFr = ReminderPanel.formatTime(
                "2027-01-01T01:15:07.123456+02:00", ZoneId.of("America/Toronto"), Locale.FRENCH);
        assertTrue(torontoFr.contains("2026"));

        assertEquals("signal", ScheduledJobModel.deliveryChannelLabel("signal:+15551234567"));
        assertEquals("local", ScheduledJobModel.deliveryChannelLabel("local"));
        assertEquals("", ScheduledJobModel.deliveryChannelLabel("   "));
    }
}
