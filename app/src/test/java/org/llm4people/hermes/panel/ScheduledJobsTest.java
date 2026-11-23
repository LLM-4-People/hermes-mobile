package org.llm4people.hermes.panel;

import org.junit.Test;

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
        assertEquals("2m 05s", ScheduledJobModel.formatDuration(parsed.get(0).durationSeconds()));
        assertEquals("failed", parsed.get(1).status());
        assertEquals(14L, parsed.get(1).durationSeconds());
        assertEquals("14s", ScheduledJobModel.formatDuration(parsed.get(1).durationSeconds()));
        assertEquals("running", parsed.get(2).status());
        assertTrue(parsed.get(2).active());
        assertEquals(-1L, parsed.get(2).durationSeconds());
        assertEquals("", ScheduledJobModel.formatDuration(-1L));
        assertEquals("1h 04m", ScheduledJobModel.formatDuration(3840L));

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
        assertEquals("Chaque jour à 08 h 30", daily.humanSummary(Locale.FRENCH));
        assertEquals("Every day at 08:30", daily.humanSummary(Locale.ENGLISH));

        ReminderSchedule weekdays = daily.withPreset(ReminderSchedule.Preset.WEEKDAYS).withTime(LocalTime.of(9, 0));
        assertEquals("Du lundi au vendredi à 09 h 00", weekdays.humanSummary(Locale.FRENCH));
        assertEquals("Weekdays at 09:00", weekdays.humanSummary(Locale.ENGLISH));

        ReminderSchedule weekly = daily.withPreset(ReminderSchedule.Preset.WEEKLY)
                .withWeeklyDay(DayOfWeek.SUNDAY).withTime(LocalTime.of(18, 45));
        assertEquals("Chaque dimanche à 18 h 45", weekly.humanSummary(Locale.FRENCH));
        assertEquals("Every Sunday at 18:45", weekly.humanSummary(Locale.ENGLISH));

        ReminderSchedule customDays = daily.withPreset(ReminderSchedule.Preset.CUSTOM);
        assertEquals("Lun, Mer à 08 h 30", customDays.humanSummary(Locale.FRENCH));
        assertEquals("Mon, Wed at 08:30", customDays.humanSummary(Locale.ENGLISH));

        ReminderSchedule customInterval = customDays.withCustomIntervalMode(true).withIntervalMinutes(45);
        assertEquals("Toutes les 45 minutes", customInterval.humanSummary(Locale.FRENCH));
        assertEquals("Every 45 minutes", customInterval.humanSummary(Locale.ENGLISH));

        String enTime = ReminderPanel.formatTime("2026-11-25T09:00:00Z", ZoneId.of("UTC"), Locale.US);
        String frTime = ReminderPanel.formatTime("2026-11-25T09:00:00Z", ZoneId.of("UTC"), Locale.FRANCE);
        assertFalse(enTime.isEmpty());
        assertFalse(frTime.isEmpty());
        assertNotEquals(enTime, frTime);

        assertEquals("signal", ScheduledJobModel.deliveryChannelLabel("signal:+15551234567"));
        assertEquals("local", ScheduledJobModel.deliveryChannelLabel("local"));
        assertEquals("", ScheduledJobModel.deliveryChannelLabel("   "));
    }
}
