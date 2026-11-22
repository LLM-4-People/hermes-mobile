package org.llm4people.hermes.panel;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, deterministic schedule model for everyday-friendly reminders.
 * Converts friendly presets + native Date/Time selections into valid Hermes gateway schedules
 * (ISO-8601 one-shot timestamps, 5-field cron expressions, or interval durations) and parses
 * saved server schedules back into friendly presets when editing.
 */
public final class ReminderSchedule {
    public static final int MIN_INTERVAL_MINUTES = 1;
    public static final int MAX_INTERVAL_MINUTES = 7 * 24 * 60; // 10,080 minutes (7 days)
    public static final int MAX_EXPRESSION_CHARS = 128;

    private static final Pattern INTERVAL = Pattern.compile("^([1-9][0-9]{0,4})([mhd])$");
    private static final Pattern CRON_FIELD = Pattern.compile("^[0-9*,/-]{1,32}$");
    private static final Pattern SIMPLE_CRON = Pattern.compile("^([0-5]?[0-9])\\s+([01]?[0-9]|2[0-3])\\s+\\*\\s+\\*\\s+([0-7,*\\-]+)$");

    public static final DayOfWeek[] WEEK_ORDER = {
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
    };

    public enum Preset {
        ONCE,
        DAILY,
        WEEKDAYS,
        WEEKLY,
        CUSTOM,
        ADVANCED
    }

    public final Preset preset;
    public final LocalDate date;
    public final LocalTime time;
    public final DayOfWeek weeklyDay;
    public final EnumSet<DayOfWeek> customDays;
    public final boolean customIntervalMode;
    public final int intervalMinutes;
    public final String advancedExpression;

    public ReminderSchedule(
            Preset preset,
            LocalDate date,
            LocalTime time,
            DayOfWeek weeklyDay,
            Set<DayOfWeek> customDays,
            boolean customIntervalMode,
            int intervalMinutes,
            String advancedExpression) {
        this.preset = Objects.requireNonNull(preset, "preset");
        this.date = Objects.requireNonNull(date, "date");
        this.time = Objects.requireNonNull(time, "time").withSecond(0).withNano(0);
        this.weeklyDay = weeklyDay == null ? DayOfWeek.MONDAY : weeklyDay;
        this.customDays = (customDays == null || customDays.isEmpty())
                ? EnumSet.noneOf(DayOfWeek.class)
                : EnumSet.copyOf(customDays);
        this.customIntervalMode = customIntervalMode;
        this.intervalMinutes = intervalMinutes;
        this.advancedExpression = advancedExpression == null ? "" : advancedExpression.trim();
    }

    public static ReminderSchedule defaultOnce(ZonedDateTime now) {
        ZonedDateTime next = now.plusHours(1).withSecond(0).withNano(0);
        return new ReminderSchedule(
                Preset.ONCE,
                next.toLocalDate(),
                next.toLocalTime(),
                next.getDayOfWeek(),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                false,
                60,
                "");
    }

    /** Parse a saved gateway schedule back into the friendliest matching preset. */
    public static ReminderSchedule parse(String savedSchedule, ZoneId zone, ZonedDateTime fallbackNow) {
        ReminderSchedule base = defaultOnce(fallbackNow);
        if (savedSchedule == null || savedSchedule.trim().isEmpty()) return base;
        String trimmed = savedSchedule.trim();

        // 1. One-shot ISO-8601 timestamp
        try {
            ZonedDateTime parsed = OffsetDateTime.parse(trimmed).atZoneSameInstant(zone);
            return new ReminderSchedule(Preset.ONCE, parsed.toLocalDate(), parsed.toLocalTime(),
                    parsed.getDayOfWeek(), base.customDays, false, base.intervalMinutes, "");
        } catch (DateTimeException ignored) {
            // Not an ISO timestamp; continue parsing recurring forms.
        }

        // 2. Simple interval ("30m", "2h")
        Matcher intervalMatch = INTERVAL.matcher(trimmed);
        if (intervalMatch.matches()) {
            int qty = Integer.parseInt(intervalMatch.group(1));
            String unit = intervalMatch.group(2);
            int mins = switch (unit) {
                case "m" -> qty;
                case "h" -> qty * 60;
                case "d" -> qty * 1440;
                default -> -1;
            };
            if (mins >= MIN_INTERVAL_MINUTES && mins <= MAX_INTERVAL_MINUTES) {
                return new ReminderSchedule(Preset.CUSTOM, base.date, base.time, base.weeklyDay,
                        base.customDays, true, mins, trimmed);
            }
        }

        // 3. Common 5-field cron expressions ("M H * * DOW")
        Matcher cronMatch = SIMPLE_CRON.matcher(trimmed);
        if (cronMatch.matches()) {
            int minute = Integer.parseInt(cronMatch.group(1));
            int hour = Integer.parseInt(cronMatch.group(2));
            String dow = cronMatch.group(3);
            LocalTime at = LocalTime.of(hour, minute);
            if ("*".equals(dow)) {
                return new ReminderSchedule(Preset.DAILY, base.date, at, base.weeklyDay,
                        base.customDays, false, base.intervalMinutes, trimmed);
            }
            if ("1-5".equals(dow)) {
                return new ReminderSchedule(Preset.WEEKDAYS, base.date, at, base.weeklyDay,
                        base.customDays, false, base.intervalMinutes, trimmed);
            }
            DayOfWeek single = parseSingleCronDay(dow);
            if (single != null) {
                return new ReminderSchedule(Preset.WEEKLY, base.date, at, single,
                        EnumSet.of(single), false, base.intervalMinutes, trimmed);
            }
            EnumSet<DayOfWeek> multi = parseMultiCronDays(dow);
            if (multi != null && !multi.isEmpty()) {
                return new ReminderSchedule(Preset.CUSTOM, base.date, at, multi.iterator().next(),
                        multi, false, base.intervalMinutes, trimmed);
            }
        }

        // 4. Complex expression stays preserved verbatim in optional advanced mode.
        return new ReminderSchedule(Preset.ADVANCED, base.date, base.time, base.weeklyDay,
                base.customDays, false, base.intervalMinutes, trimmed);
    }

    public boolean isRecurring() {
        return preset != Preset.ONCE;
    }

    public ZonedDateTime zonedDateTime(ZoneId zone) {
        return ZonedDateTime.of(date, time, zone);
    }

    public ReminderSchedule withPreset(Preset nextPreset) {
        return new ReminderSchedule(nextPreset, date, time, weeklyDay, customDays,
                customIntervalMode, intervalMinutes, advancedExpression);
    }

    public ReminderSchedule withDate(LocalDate nextDate) {
        return new ReminderSchedule(preset, nextDate, time, weeklyDay, customDays,
                customIntervalMode, intervalMinutes, advancedExpression);
    }

    public ReminderSchedule withTime(LocalTime nextTime) {
        return new ReminderSchedule(preset, date, nextTime, weeklyDay, customDays,
                customIntervalMode, intervalMinutes, advancedExpression);
    }

    public ReminderSchedule withWeeklyDay(DayOfWeek day) {
        return new ReminderSchedule(preset, date, time, day, customDays,
                customIntervalMode, intervalMinutes, advancedExpression);
    }

    public ReminderSchedule toggleCustomDay(DayOfWeek day) {
        EnumSet<DayOfWeek> next = EnumSet.copyOf(customDays);
        if (next.contains(day)) next.remove(day);
        else next.add(day);
        return new ReminderSchedule(preset, date, time, weeklyDay, next,
                customIntervalMode, intervalMinutes, advancedExpression);
    }

    public ReminderSchedule withCustomIntervalMode(boolean intervalMode) {
        return new ReminderSchedule(preset, date, time, weeklyDay, customDays,
                intervalMode, intervalMinutes, advancedExpression);
    }

    public ReminderSchedule withIntervalMinutes(int minutes) {
        return new ReminderSchedule(preset, date, time, weeklyDay, customDays,
                customIntervalMode, minutes, advancedExpression);
    }

    public ReminderSchedule withAdvancedExpression(String expr) {
        return new ReminderSchedule(Preset.ADVANCED, date, time, weeklyDay, customDays,
                customIntervalMode, intervalMinutes, expr);
    }

    /** Produce the exact valid schedule string expected by Hermes Gateway (/api/jobs). */
    public String toGatewaySchedule(ZoneId zone) {
        return switch (preset) {
            case ONCE -> zonedDateTime(zone).toOffsetDateTime().toString();
            case DAILY -> time.getMinute() + " " + time.getHour() + " * * *";
            case WEEKDAYS -> time.getMinute() + " " + time.getHour() + " * * 1-5";
            case WEEKLY -> time.getMinute() + " " + time.getHour() + " * * " + toCronDay(weeklyDay);
            case CUSTOM -> customIntervalMode
                    ? intervalMinutes + "m"
                    : time.getMinute() + " " + time.getHour() + " * * " + formatCustomDays(customDays);
            case ADVANCED -> advancedExpression;
        };
    }

    /** Validate before submitting to the gateway. Returns null if valid, or a validation error code. */
    public String validationError(ZonedDateTime now, ZoneId zone) {
        return switch (preset) {
            case ONCE -> zonedDateTime(zone).isAfter(now) ? null : "past";
            case DAILY, WEEKDAYS, WEEKLY -> null;
            case CUSTOM -> {
                if (customIntervalMode) {
                    yield (intervalMinutes >= MIN_INTERVAL_MINUTES && intervalMinutes <= MAX_INTERVAL_MINUTES)
                            ? null : "interval";
                }
                yield customDays.isEmpty() ? "days" : null;
            }
            case ADVANCED -> isValidCronOrInterval(advancedExpression) ? null : "expression";
        };
    }

    /** Warm, jargon-free French summary shown to everyday users. */
    public String humanSummary(Locale locale) {
        Locale effective = locale == null ? Locale.FRENCH : locale;
        String hhmm = String.format(effective, "%02d h %02d", time.getHour(), time.getMinute());
        return switch (preset) {
            case ONCE -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(effective).format(date)
                    + " à " + hhmm;
            case DAILY -> "Chaque jour à " + hhmm;
            case WEEKDAYS -> "Du lundi au vendredi à " + hhmm;
            case WEEKLY -> "Chaque " + fullDayFr(weeklyDay).toLowerCase(Locale.FRENCH) + " à " + hhmm;
            case CUSTOM -> {
                if (customIntervalMode) {
                    yield formatIntervalFr(intervalMinutes);
                }
                if (customDays.isEmpty()) yield "Aucun jour sélectionné";
                StringBuilder sb = new StringBuilder();
                for (DayOfWeek day : WEEK_ORDER) {
                    if (!customDays.contains(day)) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(shortDayFr(day));
                }
                yield sb + " à " + hhmm;
            }
            case ADVANCED -> advancedExpression.isEmpty() ? "Expression personnalisée" : advancedExpression;
        };
    }

    public static boolean isValidCronOrInterval(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_EXPRESSION_CHARS) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < 32 || ch == 127) return false;
        }
        Matcher intervalMatch = INTERVAL.matcher(trimmed);
        if (intervalMatch.matches()) {
            int qty = Integer.parseInt(intervalMatch.group(1));
            return qty > 0;
        }
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) return false;
        for (String field : fields) {
            if (!CRON_FIELD.matcher(field).matches()) return false;
        }
        return true;
    }

    public static String shortDayFr(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "Lun";
            case TUESDAY -> "Mar";
            case WEDNESDAY -> "Mer";
            case THURSDAY -> "Jeu";
            case FRIDAY -> "Ven";
            case SATURDAY -> "Sam";
            case SUNDAY -> "Dim";
        };
    }

    public static String fullDayFr(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "Lundi";
            case TUESDAY -> "Mardi";
            case WEDNESDAY -> "Mercredi";
            case THURSDAY -> "Jeudi";
            case FRIDAY -> "Vendredi";
            case SATURDAY -> "Samedi";
            case SUNDAY -> "Dimanche";
        };
    }

    private static String formatIntervalFr(int minutes) {
        if (minutes <= 0) return "Intervalle invalide";
        if (minutes == 1) return "Toutes les minutes";
        if (minutes < 60) return "Toutes les " + minutes + " minutes";
        int hours = minutes / 60;
        int rem = minutes % 60;
        if (rem == 0) {
            return hours == 1 ? "Toutes les heures" : "Toutes les " + hours + " heures";
        }
        return String.format(Locale.FRENCH, "Toutes les %d h %02d", hours, rem);
    }

    static int toCronDay(DayOfWeek day) {
        return day == DayOfWeek.SUNDAY ? 0 : day.getValue();
    }

    private static DayOfWeek parseSingleCronDay(String token) {
        return switch (token) {
            case "0", "7" -> DayOfWeek.SUNDAY;
            case "1" -> DayOfWeek.MONDAY;
            case "2" -> DayOfWeek.TUESDAY;
            case "3" -> DayOfWeek.WEDNESDAY;
            case "4" -> DayOfWeek.THURSDAY;
            case "5" -> DayOfWeek.FRIDAY;
            case "6" -> DayOfWeek.SATURDAY;
            default -> null;
        };
    }

    private static EnumSet<DayOfWeek> parseMultiCronDays(String dow) {
        if (!dow.contains(",")) return null;
        EnumSet<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        for (String part : dow.split(",")) {
            DayOfWeek parsed = parseSingleCronDay(part);
            if (parsed == null) return null;
            set.add(parsed);
        }
        return set;
    }

    private static String formatCustomDays(Set<DayOfWeek> days) {
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek day : WEEK_ORDER) {
            if (!days.contains(day)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(toCronDay(day));
        }
        return sb.toString();
    }
}
