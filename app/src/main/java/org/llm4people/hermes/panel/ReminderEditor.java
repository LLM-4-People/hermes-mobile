package org.llm4people.hermes.panel;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * Everyday-friendly native reminder editor.
 * Casual users pick simple presets paired with native Android DatePickerDialog & TimePickerDialog;
 * raw cron/interval expressions stay tucked cleanly inside an optional advanced disclosure.
 */
final class ReminderEditor {
    private final SessionPanel panel;
    private final ReminderPanel owner;
    private final JSONObject job;
    private final ZoneId zone = ZoneId.systemDefault();
    private ReminderSchedule schedule;
    private boolean advancedOpen;
    private boolean scheduleChanged;
    private EditText title, prompt, expression, intervalField;
    private LinearLayout scheduleControls;
    private TextView error;
    private Button save;
    private LinearLayout status;

    ReminderEditor(SessionPanel panel, ReminderPanel owner, JSONObject job) {
        this.panel = panel;
        this.owner = owner;
        this.job = job;
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (job == null) {
            schedule = ReminderSchedule.defaultOnce(now);
        } else {
            schedule = ReminderSchedule.parse(PanelData.schedule(job), zone, now);
            advancedOpen = schedule.preset == ReminderSchedule.Preset.ADVANCED;
        }
    }

    void show() {
        LinearLayout page = panel.page("reminders",
                job == null ? R.string.panel_new_reminder : R.string.panel_reminder_edit,
                () -> { if (job == null) owner.show(); else owner.details(job); });
        PanelFrame.Sections editor = panel.sections(page);
        title = field(editor.first, R.string.panel_name, true, job == null ? "" : PanelData.text(job, "name"));
        prompt = field(editor.first, R.string.panel_prompt, false, job == null ? "" : PanelData.text(job, "prompt"));
        LinearLayout schedulePane = editor.second;
        panel.section(schedulePane, R.string.panel_schedule);
        scheduleControls = Ui.column(panel.activity);
        schedulePane.addView(scheduleControls);
        renderSchedule();
        error = panel.note(schedulePane, "");
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        save = panel.action(schedulePane, R.string.panel_save, this::save);
        status = Ui.column(panel.activity);
        schedulePane.addView(status);
    }

    private EditText field(LinearLayout page, int name, boolean single, String value) {
        TextView label = panel.note(page, panel.t(name));
        EditText field = new EditText(panel.activity);
        field.setId(View.generateViewId());
        label.setLabelFor(field.getId());
        field.setSingleLine(single);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (single ? 0 : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        field.setMinHeight(Ui.dp(panel.activity, 48));
        field.setText(value);
        page.addView(field);
        return field;
    }

    private void renderSchedule() {
        scheduleControls.removeAllViews();
        expression = null;
        intervalField = null;

        panel.note(scheduleControls, panel.t(R.string.panel_preset_heading));
        addPresetButton(ReminderSchedule.Preset.ONCE, R.string.panel_preset_once);
        addPresetButton(ReminderSchedule.Preset.DAILY, R.string.panel_preset_daily);
        addPresetButton(ReminderSchedule.Preset.WEEKDAYS, R.string.panel_preset_weekdays);
        addPresetButton(ReminderSchedule.Preset.WEEKLY, R.string.panel_preset_weekly);
        addPresetButton(ReminderSchedule.Preset.CUSTOM, R.string.panel_preset_custom);

        scheduleControls.addView(Ui.divider(panel.activity));

        switch (schedule.preset) {
            case ONCE -> {
                addDatePickerButton();
                addTimePickerButton();
            }
            case DAILY, WEEKDAYS -> addTimePickerButton();
            case WEEKLY -> {
                addSingleDaySelector();
                addTimePickerButton();
            }
            case CUSTOM -> addCustomControls();
            case ADVANCED -> {
                // Rendered inside the optional advanced disclosure section below.
            }
        }

        // Localized live summary so casual users always see what they scheduled in their active language.
        TextView preview = panel.note(scheduleControls,
                panel.t(R.string.panel_schedule_preview, schedule.humanSummary(panel.activity)));
        preview.setTextColor(panel.activity.getColor(R.color.ink));
        preview.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        String zoneName = job != null && schedule.isRecurring() && !PanelData.text(job, "timezone").isEmpty()
                ? PanelData.text(job, "timezone") : zone.getId();
        panel.note(scheduleControls, panel.t(R.string.panel_zone, zoneName));

        // Optional collapsible advanced mode tucked cleanly away from casual users.
        scheduleControls.addView(Ui.divider(panel.activity));
        panel.action(scheduleControls,
                advancedOpen ? R.string.panel_advanced_toggle_hide : R.string.panel_advanced_toggle_show,
                () -> {
                    if (advancedOpen) {
                        advancedOpen = false;
                        if (schedule.preset == ReminderSchedule.Preset.ADVANCED) {
                            schedule = schedule.withPreset(ReminderSchedule.Preset.DAILY);
                            scheduleChanged = true;
                        }
                    } else {
                        advancedOpen = true;
                        String seed = schedule.preset == ReminderSchedule.Preset.ADVANCED
                                ? schedule.advancedExpression
                                : (schedule.preset == ReminderSchedule.Preset.ONCE
                                        ? ""
                                        : schedule.toGatewaySchedule(zone));
                        schedule = schedule.withAdvancedExpression(seed);
                        scheduleChanged = true;
                    }
                    renderSchedule();
                });

        if (advancedOpen) {
            panel.note(scheduleControls, panel.t(R.string.panel_advanced_hint));
            expression = field(scheduleControls, R.string.panel_expression, true, schedule.advancedExpression);
            expression.setHint(R.string.panel_expression_hint);
            expression.addTextChangedListener(new SimpleWatcher(text -> {
                schedule = schedule.withAdvancedExpression(text);
                scheduleChanged = true;
            }));
        }
    }

    private void addPresetButton(ReminderSchedule.Preset preset, int labelRes) {
        boolean selected = !advancedOpen && schedule.preset == preset;
        String label = (selected ? "● " : "○ ") + panel.t(labelRes);
        Button btn = Ui.button(panel.activity, label, v -> {
            advancedOpen = false;
            schedule = schedule.withPreset(preset);
            scheduleChanged = true;
            renderSchedule();
        });
        btn.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        btn.setSelected(selected);
        if (selected) btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        scheduleControls.addView(btn);
    }

    private void addDatePickerButton() {
        DateTimeFormatter dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(ReminderPanel.activeLocale(panel.activity));
        Button day = Ui.button(panel.activity, schedule.date.format(dateFmt), v -> panel.present(new DatePickerDialog(
                panel.activity, (picker, year, month, number) -> {
                    schedule = schedule.withDate(LocalDate.of(year, month + 1, number));
                    scheduleChanged = true;
                    renderSchedule();
                }, schedule.date.getYear(), schedule.date.getMonthValue() - 1, schedule.date.getDayOfMonth())));
        day.setContentDescription(panel.t(R.string.panel_date) + ", " + day.getText());
        scheduleControls.addView(day);
    }

    private void addTimePickerButton() {
        Locale locale = ReminderPanel.activeLocale(panel.activity);
        DateTimeFormatter timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
        Button hour = Ui.button(panel.activity, schedule.time.format(timeFmt), v -> panel.present(new TimePickerDialog(
                panel.activity, (picker, h, m) -> {
                    schedule = schedule.withTime(LocalTime.of(h, m));
                    scheduleChanged = true;
                    renderSchedule();
                }, schedule.time.getHour(), schedule.time.getMinute(), true)));
        hour.setContentDescription(panel.t(R.string.panel_time) + ", " + hour.getText());
        scheduleControls.addView(hour);
    }

    private void addSingleDaySelector() {
        Locale locale = ReminderPanel.activeLocale(panel.activity);
        panel.note(scheduleControls, panel.t(R.string.panel_custom_days));
        LinearLayout row1 = Ui.row(panel.activity);
        LinearLayout row2 = Ui.row(panel.activity);
        for (int i = 0; i < ReminderSchedule.WEEK_ORDER.length; i++) {
            DayOfWeek day = ReminderSchedule.WEEK_ORDER[i];
            boolean chosen = schedule.weeklyDay == day;
            Button chip = Ui.button(panel.activity,
                    (chosen ? "[" : "") + ReminderSchedule.shortDay(day, locale) + (chosen ? "]" : ""),
                    v -> {
                        schedule = schedule.withWeeklyDay(day);
                        scheduleChanged = true;
                        renderSchedule();
                    });
            chip.setContentDescription(ReminderSchedule.fullDay(day, locale));
            chip.setSelected(chosen);
            if (chosen) chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            (i < 4 ? row1 : row2).addView(chip);
        }
        scheduleControls.addView(row1);
        scheduleControls.addView(row2);
    }

    private void addCustomControls() {
        LinearLayout modeRow = Ui.row(panel.activity);
        Button daysMode = Ui.button(panel.activity,
                (!schedule.customIntervalMode ? "● " : "○ ") + panel.t(R.string.panel_custom_mode_days),
                v -> {
                    schedule = schedule.withCustomIntervalMode(false);
                    scheduleChanged = true;
                    renderSchedule();
                });
        Button intervalMode = Ui.button(panel.activity,
                (schedule.customIntervalMode ? "● " : "○ ") + panel.t(R.string.panel_custom_mode_interval),
                v -> {
                    schedule = schedule.withCustomIntervalMode(true);
                    scheduleChanged = true;
                    renderSchedule();
                });
        modeRow.addView(daysMode);
        modeRow.addView(intervalMode);
        scheduleControls.addView(modeRow);

        if (schedule.customIntervalMode) {
            intervalField = field(scheduleControls, R.string.panel_custom_interval_label, true,
                    schedule.intervalMinutes > 0 ? Integer.toString(schedule.intervalMinutes) : "");
            intervalField.setInputType(InputType.TYPE_CLASS_NUMBER);
            intervalField.setHint(R.string.panel_custom_interval_hint);
            intervalField.addTextChangedListener(new SimpleWatcher(text -> {
                schedule = schedule.withIntervalMinutes(parseInterval(text));
                scheduleChanged = true;
            }));
        } else {
            Locale locale = ReminderPanel.activeLocale(panel.activity);
            panel.note(scheduleControls, panel.t(R.string.panel_custom_days));
            LinearLayout row1 = Ui.row(panel.activity);
            LinearLayout row2 = Ui.row(panel.activity);
            for (int i = 0; i < ReminderSchedule.WEEK_ORDER.length; i++) {
                DayOfWeek day = ReminderSchedule.WEEK_ORDER[i];
                boolean checked = schedule.customDays.contains(day);
                Button chip = Ui.button(panel.activity,
                        (checked ? "✓ " : "") + ReminderSchedule.shortDay(day, locale),
                        v -> {
                            schedule = schedule.toggleCustomDay(day);
                            scheduleChanged = true;
                            renderSchedule();
                        });
                chip.setContentDescription(ReminderSchedule.fullDay(day, locale));
                chip.setSelected(checked);
                if (checked) chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                (i < 4 ? row1 : row2).addView(chip);
            }
            scheduleControls.addView(row1);
            scheduleControls.addView(row2);
            addTimePickerButton();
        }
    }

    private void save() {
        String name = title.getText().toString().trim();
        String instruction = prompt.getText().toString().trim();
        if (name.isEmpty() || instruction.isEmpty()) {
            error.setText(R.string.panel_required);
            return;
        }
        if (expression != null) {
            schedule = schedule.withAdvancedExpression(expression.getText().toString());
        }
        if (intervalField != null) {
            schedule = schedule.withIntervalMinutes(parseInterval(intervalField.getText().toString()));
        }

        String wireSchedule = schedule.toGatewaySchedule(zone);
        boolean changed = job == null || scheduleChanged || !wireSchedule.equals(PanelData.schedule(job));
        JSONObject changes = PanelData.put(PanelData.put(new JSONObject(), "name", name), "prompt", instruction);
        if (changed) {
            String validation = schedule.validationError(ZonedDateTime.now(zone), zone);
            if (validation != null) {
                switch (validation) {
                    case "past" -> error.setText(R.string.panel_date_past);
                    case "days" -> error.setText(R.string.panel_custom_days_required);
                    case "interval" -> error.setText(R.string.panel_custom_interval_invalid);
                    default -> {
                        error.setText(R.string.panel_expression_invalid);
                        if (expression != null) expression.setError(panel.t(R.string.panel_expression_hint));
                    }
                }
                return;
            }
            PanelData.put(changes, "schedule", wireSchedule);
            if (job == null || !schedule.isRecurring() || PanelData.text(job, "timezone").isEmpty()) {
                PanelData.put(changes, "timezone", zone.getId());
            }
        }
        if (job == null) PanelData.put(changes, "deliver", "local");
        error.setText("");
        owner.submit(job, changes, status, save);
    }

    private static int parseInterval(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return 0;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException invalid) {
            Diagnostics.debug("panel", "interval_parse_invalid");
            return 0;
        }
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final java.util.function.Consumer<String> onChange;
        SimpleWatcher(java.util.function.Consumer<String> onChange) { this.onChange = onChange; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { onChange.accept(s == null ? "" : s.toString()); }
    }
}
