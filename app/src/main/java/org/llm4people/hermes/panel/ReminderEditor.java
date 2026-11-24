package org.llm4people.hermes.panel;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
        error.setTextColor(panel.activity.getColor(R.color.danger));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        save = Ui.filledButton(panel.activity, panel.t(R.string.panel_save), v -> save());
        schedulePane.addView(save, topMargin(14));
        status = Ui.column(panel.activity);
        schedulePane.addView(status, topMargin(8));
    }

    private EditText field(LinearLayout page, int name, boolean single, String value) {
        TextView label = panel.note(page, panel.t(name));
        label.setTextColor(panel.activity.getColor(R.color.ink));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        EditText field = new EditText(panel.activity);
        field.setId(View.generateViewId());
        label.setLabelFor(field.getId());
        field.setSingleLine(single);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (single ? 0 : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        field.setTextSize(16);
        field.setTextColor(panel.activity.getColor(R.color.ink));
        field.setHintTextColor(panel.activity.getColor(R.color.muted));
        field.setBackgroundResource(R.drawable.profile_input);
        field.setBackgroundTintList(null);
        Ui.pad(field, 12, 10);
        field.setMinHeight(Ui.dp(panel.activity, 48));
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setImeOptions((single ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_NONE)
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setText(value);
        page.addView(field, topMargin(6));
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

        scheduleControls.addView(Ui.divider(panel.activity), topMargin(12));

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

        // Localized live summary inside a calm tonal card so users always see what they scheduled.
        LinearLayout previewCard = Ui.tonalCard(panel.activity);
        TextView preview = Ui.text(panel.activity,
                panel.t(R.string.panel_schedule_preview, schedule.humanSummary(panel.activity)), 14);
        preview.setTextColor(panel.activity.getColor(R.color.ink));
        preview.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        previewCard.addView(preview);

        String zoneName = job != null && schedule.isRecurring() && !PanelData.text(job, "timezone").isEmpty()
                ? PanelData.text(job, "timezone") : zone.getId();
        TextView zoneView = Ui.text(panel.activity, panel.t(R.string.panel_zone, zoneName), 13);
        zoneView.setTextColor(panel.activity.getColor(R.color.muted));
        previewCard.addView(zoneView, topMargin(4));
        scheduleControls.addView(previewCard, topMargin(12));

        // Optional collapsible advanced mode tucked cleanly away from casual users.
        scheduleControls.addView(Ui.divider(panel.activity), topMargin(12));
        Button advancedBtn = Ui.outlinedButton(panel.activity,
                panel.t(advancedOpen ? R.string.panel_advanced_toggle_hide : R.string.panel_advanced_toggle_show),
                v -> {
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
        scheduleControls.addView(advancedBtn, topMargin(10));

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

    private Button selectableChip(String label, boolean selected, boolean alignStart, View.OnClickListener listener) {
        Button chip = new Button(panel.activity, null, android.R.attr.borderlessButtonStyle);
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(14);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setStateListAnimator(null);
        chip.setGravity((alignStart ? Gravity.START : Gravity.CENTER_HORIZONTAL) | Gravity.CENTER_VERTICAL);
        int touchMin = Ui.dp(panel.activity, 48);
        chip.setMinHeight(touchMin);
        chip.setMinimumHeight(touchMin);
        Ui.pad(chip, 14, 10);
        GradientDrawable fill = Ui.shape(panel.activity, Ui.BUTTON_CORNER_DP,
                selected ? R.color.accent_surface : R.color.surface,
                selected ? R.color.accent : R.color.line,
                selected ? 2 : 1);
        chip.setBackground(Ui.roundedRipple(panel.activity, Ui.BUTTON_CORNER_DP, fill));
        chip.setTextColor(panel.activity.getColor(selected ? R.color.accent : R.color.ink));
        chip.setSelected(selected);
        chip.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(selected);
            }
        });
        chip.setOnClickListener(listener);
        return chip;
    }

    private HorizontalScrollView chipScrollRow(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(panel.activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private void addChipWithGap(LinearLayout row, Button chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(panel.activity, 8));
        row.addView(chip, lp);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(panel.activity, dp);
        return lp;
    }

    private void showError(int messageRes) {
        error.setText(messageRes);
        error.setVisibility(View.VISIBLE);
    }

    private void clearError() {
        error.setText("");
        error.setVisibility(View.GONE);
    }

    private void addPresetButton(ReminderSchedule.Preset preset, int labelRes) {
        boolean selected = !advancedOpen && schedule.preset == preset;
        Button btn = selectableChip(panel.t(labelRes), selected, true, v -> {
            advancedOpen = false;
            schedule = schedule.withPreset(preset);
            scheduleChanged = true;
            renderSchedule();
        });
        scheduleControls.addView(btn, topMargin(8));
    }

    private void addDatePickerButton() {
        DateTimeFormatter dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(ReminderPanel.activeLocale(panel.activity));
        Button day = Ui.outlinedButton(panel.activity, schedule.date.format(dateFmt), v -> panel.present(new DatePickerDialog(
                panel.activity, (picker, year, month, number) -> {
                    schedule = schedule.withDate(LocalDate.of(year, month + 1, number));
                    scheduleChanged = true;
                    renderSchedule();
                }, schedule.date.getYear(), schedule.date.getMonthValue() - 1, schedule.date.getDayOfMonth())));
        day.setContentDescription(panel.t(R.string.panel_date) + ", " + day.getText());
        scheduleControls.addView(day, topMargin(10));
    }

    private void addTimePickerButton() {
        Locale locale = ReminderPanel.activeLocale(panel.activity);
        DateTimeFormatter timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
        Button hour = Ui.outlinedButton(panel.activity, schedule.time.format(timeFmt), v -> panel.present(new TimePickerDialog(
                panel.activity, (picker, h, m) -> {
                    schedule = schedule.withTime(LocalTime.of(h, m));
                    scheduleChanged = true;
                    renderSchedule();
                }, schedule.time.getHour(), schedule.time.getMinute(), true)));
        hour.setContentDescription(panel.t(R.string.panel_time) + ", " + hour.getText());
        scheduleControls.addView(hour, topMargin(8));
    }

    private void addSingleDaySelector() {
        Locale locale = ReminderPanel.activeLocale(panel.activity);
        panel.note(scheduleControls, panel.t(R.string.panel_custom_days));
        LinearLayout row = Ui.row(panel.activity);
        for (DayOfWeek day : ReminderSchedule.WEEK_ORDER) {
            boolean chosen = schedule.weeklyDay == day;
            Button chip = selectableChip(ReminderSchedule.shortDay(day, locale), chosen, false, v -> {
                schedule = schedule.withWeeklyDay(day);
                scheduleChanged = true;
                renderSchedule();
            });
            chip.setContentDescription(ReminderSchedule.fullDay(day, locale));
            addChipWithGap(row, chip);
        }
        scheduleControls.addView(chipScrollRow(row), topMargin(8));
    }

    private void addCustomControls() {
        LinearLayout modeRow = Ui.row(panel.activity);
        Button daysMode = selectableChip(panel.t(R.string.panel_custom_mode_days),
                !schedule.customIntervalMode, false, v -> {
                    schedule = schedule.withCustomIntervalMode(false);
                    scheduleChanged = true;
                    renderSchedule();
                });
        Button intervalMode = selectableChip(panel.t(R.string.panel_custom_mode_interval),
                schedule.customIntervalMode, false, v -> {
                    schedule = schedule.withCustomIntervalMode(true);
                    scheduleChanged = true;
                    renderSchedule();
                });
        addChipWithGap(modeRow, daysMode);
        addChipWithGap(modeRow, intervalMode);
        scheduleControls.addView(chipScrollRow(modeRow), topMargin(10));

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
            LinearLayout row = Ui.row(panel.activity);
            for (DayOfWeek day : ReminderSchedule.WEEK_ORDER) {
                boolean checked = schedule.customDays.contains(day);
                Button chip = selectableChip(ReminderSchedule.shortDay(day, locale), checked, false, v -> {
                    schedule = schedule.toggleCustomDay(day);
                    scheduleChanged = true;
                    renderSchedule();
                });
                chip.setContentDescription(ReminderSchedule.fullDay(day, locale));
                addChipWithGap(row, chip);
            }
            scheduleControls.addView(chipScrollRow(row), topMargin(8));
            addTimePickerButton();
        }
    }

    private void save() {
        String name = title.getText().toString().trim();
        String instruction = prompt.getText().toString().trim();
        if (name.isEmpty() || instruction.isEmpty()) {
            showError(R.string.panel_required);
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
                    case "past" -> showError(R.string.panel_date_past);
                    case "days" -> showError(R.string.panel_custom_days_required);
                    case "interval" -> showError(R.string.panel_custom_interval_invalid);
                    default -> {
                        showError(R.string.panel_expression_invalid);
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
        clearError();
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
