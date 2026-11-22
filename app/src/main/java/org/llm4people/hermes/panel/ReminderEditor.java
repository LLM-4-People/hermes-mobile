package org.llm4people.hermes.panel;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

final class ReminderEditor {
    private final SessionPanel panel;
    private final ReminderPanel owner;
    private final JSONObject job;
    private final ZoneId zone = ZoneId.systemDefault();
    private ZonedDateTime selected = ZonedDateTime.now(zone).plusHours(1).withSecond(0).withNano(0);
    private boolean recurring;
    private boolean dateChanged;
    private EditText title, prompt, expression;
    private LinearLayout scheduleControls;
    private TextView error;
    private Button save;
    private LinearLayout status;
    private String expressionText = "";

    ReminderEditor(SessionPanel panel, ReminderPanel owner, JSONObject job) {
        this.panel = panel; this.owner = owner; this.job = job;
        if (job == null) return;
        String schedule = PanelData.schedule(job);
        try { selected = OffsetDateTime.parse(schedule).atZoneSameInstant(zone); }
        catch (java.time.DateTimeException unparsed) { recurring = true; expressionText = schedule; }
    }

    void show() {
        LinearLayout page = panel.page("reminders", job == null ? R.string.panel_new_reminder : R.string.panel_reminder_edit,
                () -> { if (job == null) owner.show(); else owner.details(job); });
        title = field(page, R.string.panel_name, true, job == null ? "" : PanelData.text(job, "name"));
        prompt = field(page, R.string.panel_prompt, false, job == null ? "" : PanelData.text(job, "prompt"));
        panel.section(page, R.string.panel_schedule);
        scheduleControls = Ui.column(panel.activity);
        page.addView(scheduleControls);
        renderSchedule();
        error = panel.note(page, "");
        error.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        save = panel.action(page, R.string.panel_save, this::save);
        status = Ui.column(panel.activity);
        page.addView(status);
    }

    private EditText field(LinearLayout page, int name, boolean single, String value) {
        TextView label = panel.note(page, panel.t(name));
        EditText field = new EditText(panel.activity);
        field.setId(android.view.View.generateViewId());
        label.setLabelFor(field.getId());
        field.setSingleLine(single);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (single ? 0 : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        field.setText(value);
        page.addView(field);
        return field;
    }

    private void renderSchedule() {
        scheduleControls.removeAllViews();
        if (recurring) {
            expression = field(scheduleControls, R.string.panel_expression, true, expressionText);
            expression.setHint(R.string.panel_expression_hint);
        } else {
            DateTimeFormatter date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.FRENCH);
            DateTimeFormatter time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.FRENCH);
            Button day = Ui.button(panel.activity, selected.format(date), v -> panel.present(new DatePickerDialog(
                    panel.activity, (picker, year, month, number) -> {
                        selected = ZonedDateTime.of(java.time.LocalDate.of(year, month + 1, number),
                                selected.toLocalTime(), zone);
                        dateChanged = true;
                        renderSchedule();
                    }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth())));
            day.setContentDescription(panel.t(R.string.panel_date) + ", " + day.getText());
            scheduleControls.addView(day);
            Button hour = Ui.button(panel.activity, selected.format(time), v -> panel.present(new TimePickerDialog(
                    panel.activity, (picker, h, m) -> {
                        selected = selected.withHour(h).withMinute(m);
                        dateChanged = true;
                        renderSchedule();
                    }, selected.getHour(), selected.getMinute(), true)));
            hour.setContentDescription(panel.t(R.string.panel_time) + ", " + hour.getText());
            scheduleControls.addView(hour);
        }
        String zoneName = job != null && recurring && !PanelData.text(job, "timezone").isEmpty()
                ? PanelData.text(job, "timezone") : zone.getId();
        panel.note(scheduleControls, panel.t(R.string.panel_zone, zoneName));
        panel.action(scheduleControls, recurring ? R.string.panel_once : R.string.panel_recurrence, () -> {
            if (recurring) expressionText = expression.getText().toString();
            recurring = !recurring;
            dateChanged = true;
            renderSchedule();
        });
    }

    private void save() {
        String name = title.getText().toString().trim();
        String instruction = prompt.getText().toString().trim();
        if (name.isEmpty() || instruction.isEmpty()) { error.setText(R.string.panel_required); return; }
        JSONObject changes = PanelData.put(PanelData.put(new JSONObject(), "name", name), "prompt", instruction);
        String schedule = recurring ? expression.getText().toString().trim() : selected.toOffsetDateTime().toString();
        boolean changed = job == null || dateChanged || (recurring && !schedule.equals(PanelData.schedule(job)));
        if (changed) {
            if (schedule.isEmpty()) { expression.setError(panel.t(R.string.panel_expression_hint)); return; }
            if (!recurring && !selected.isAfter(ZonedDateTime.now(zone))) { error.setText(R.string.panel_date_past); return; }
            PanelData.put(changes, "schedule", schedule);
            if (job == null || !recurring || PanelData.text(job, "timezone").isEmpty())
                PanelData.put(changes, "timezone", zone.getId());
        }
        if (job == null) PanelData.put(changes, "deliver", "local");
        error.setText("");
        owner.submit(job, changes, status, save);
    }
}
