package org.llm4people.hermes.panel;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ReminderScheduleTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 22, 10, 0, 0, 0, PARIS);

    @Test
    public void friendlyPresetsGenerateValidGatewaySchedulesAndPlainFrenchSummaries() {
        ReminderSchedule once = ReminderSchedule.defaultOnce(NOW)
                .withDate(LocalDate.of(2026, 11, 25))
                .withTime(LocalTime.of(9, 15));
        assertFalse(once.isRecurring());
        assertEquals("2026-11-25T09:15+01:00", once.toGatewaySchedule(PARIS));
        assertNull(once.validationError(NOW, PARIS));
        assertTrue(once.humanSummary(Locale.FRENCH).contains("09 h 15"));

        ReminderSchedule daily = once.withPreset(ReminderSchedule.Preset.DAILY).withTime(LocalTime.of(8, 30));
        assertTrue(daily.isRecurring());
        assertEquals("30 8 * * *", daily.toGatewaySchedule(PARIS));
        assertEquals("Chaque jour à 08 h 30", daily.humanSummary(Locale.FRENCH));
        assertNull(daily.validationError(NOW, PARIS));

        ReminderSchedule weekdays = once.withPreset(ReminderSchedule.Preset.WEEKDAYS).withTime(LocalTime.of(9, 0));
        assertEquals("0 9 * * 1-5", weekdays.toGatewaySchedule(PARIS));
        assertEquals("Du lundi au vendredi à 09 h 00", weekdays.humanSummary(Locale.FRENCH));

        ReminderSchedule weekly = once.withPreset(ReminderSchedule.Preset.WEEKLY)
                .withWeeklyDay(DayOfWeek.SUNDAY)
                .withTime(LocalTime.of(18, 45));
        assertEquals("45 18 * * 0", weekly.toGatewaySchedule(PARIS));
        assertEquals("Chaque dimanche à 18 h 45", weekly.humanSummary(Locale.FRENCH));

        ReminderSchedule customDays = new ReminderSchedule(
                ReminderSchedule.Preset.CUSTOM,
                LocalDate.of(2026, 11, 25),
                LocalTime.of(14, 5),
                DayOfWeek.MONDAY,
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                false,
                30,
                "");
        assertEquals("5 14 * * 1,3,5", customDays.toGatewaySchedule(PARIS));
        assertEquals("Lun, Mer, Ven à 14 h 05", customDays.humanSummary(Locale.FRENCH));
        assertNull(customDays.validationError(NOW, PARIS));

        ReminderSchedule customInterval = customDays.withCustomIntervalMode(true).withIntervalMinutes(45);
        assertEquals("45m", customInterval.toGatewaySchedule(PARIS));
        assertEquals("Toutes les 45 minutes", customInterval.humanSummary(Locale.FRENCH));
        assertNull(customInterval.validationError(NOW, PARIS));
    }

    @Test
    public void parsesSavedServerSchedulesBackIntoFriendlyPresetsOrAdvancedMode() {
        ReminderSchedule pOnce = ReminderSchedule.parse("2027-01-10T11:30:00+01:00", PARIS, NOW);
        assertEquals(ReminderSchedule.Preset.ONCE, pOnce.preset);
        assertEquals(LocalDate.of(2027, 1, 10), pOnce.date);
        assertEquals(LocalTime.of(11, 30), pOnce.time);

        ReminderSchedule pDaily = ReminderSchedule.parse("0 7 * * *", PARIS, NOW);
        assertEquals(ReminderSchedule.Preset.DAILY, pDaily.preset);
        assertEquals(LocalTime.of(7, 0), pDaily.time);

        ReminderSchedule pWeekdays = ReminderSchedule.parse("15 9 * * 1-5", PARIS, NOW);
        assertEquals(ReminderSchedule.Preset.WEEKDAYS, pWeekdays.preset);
        assertEquals(LocalTime.of(9, 15), pWeekdays.time);

        ReminderSchedule pWeeklySun7 = ReminderSchedule.parse("0 19 * * 7", PARIS, NOW);
        assertEquals(ReminderSchedule.Preset.WEEKLY, pWeeklySun7.preset);
        assertEquals(DayOfWeek.SUNDAY, pWeeklySun7.weeklyDay);

        ReminderSchedule pMulti = ReminderSchedule.parse("30 16 * * 2,4,0", PARIS, NOW);
        assertEquals(ReminderSchedule.Preset.CUSTOM, pMulti.preset);
        assertFalse(pMulti.customIntervalMode);
        assertEquals(EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY), pMulti.customDays);

        ReminderSchedule pHours = ReminderSchedule.parse("2h", PARIS, NOW);
        assertEquals(ReminderSchedule.Preset.CUSTOM, pHours.preset);
        assertTrue(pHours.customIntervalMode);
        assertEquals(120, pHours.intervalMinutes);
        assertEquals("Toutes les 2 heures", pHours.humanSummary(Locale.FRENCH));

        ReminderSchedule pAdvanced = ReminderSchedule.parse("*/15 9-17 * * 1-5", PARIS, NOW);
        assertEquals(ReminderSchedule.Preset.ADVANCED, pAdvanced.preset);
        assertEquals("*/15 9-17 * * 1-5", pAdvanced.toGatewaySchedule(PARIS));
        assertNull(pAdvanced.validationError(NOW, PARIS));
    }

    @Test
    public void rejectsPastOneShotEmptyDaysInvalidIntervalAndMalformedCron() {
        ReminderSchedule past = ReminderSchedule.defaultOnce(NOW)
                .withDate(LocalDate.of(2026, 11, 22))
                .withTime(LocalTime.of(9, 0));
        assertEquals("past", past.validationError(NOW, PARIS));

        ReminderSchedule noDays = new ReminderSchedule(
                ReminderSchedule.Preset.CUSTOM, LocalDate.of(2026, 11, 25), LocalTime.of(9, 0),
                DayOfWeek.MONDAY, EnumSet.noneOf(DayOfWeek.class), false, 30, "");
        assertEquals("days", noDays.validationError(NOW, PARIS));

        assertEquals("interval", noDays.withCustomIntervalMode(true).withIntervalMinutes(0).validationError(NOW, PARIS));
        assertEquals("interval", noDays.withCustomIntervalMode(true).withIntervalMinutes(20000).validationError(NOW, PARIS));

        for (String badExpr : new String[]{"", "   ", "0 9 * *", "0 9 * * * *", "0m", "0 9 * * 1\nrm -rf", "bad cron"}) {
            assertFalse("Accepted bad expression: " + badExpr, ReminderSchedule.isValidCronOrInterval(badExpr));
            assertEquals("expression", noDays.withAdvancedExpression(badExpr).validationError(NOW, PARIS));
        }
    }
}
