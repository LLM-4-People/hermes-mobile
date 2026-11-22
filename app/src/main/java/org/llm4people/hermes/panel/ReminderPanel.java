package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.widget.LinearLayout;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.push.MobilePush;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.concurrent.Callable;

final class ReminderPanel {
    static final String JOBS = "/api/jobs";
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.FRENCH);
    private final SessionPanel panel;

    ReminderPanel(SessionPanel panel) { this.panel = panel; }

    void show() {
        LinearLayout page = panel.page("reminders", R.string.panel_reminders, null);
        panel.note(page, panel.account.label);
        panel.action(page, R.string.panel_new_reminder, () -> edit(null));
        panel.note(page, panel.t(MobilePush.isEnabled(panel.activity, panel.account.id)
                ? R.string.panel_push_on : R.string.panel_push_off));
        panel.action(page, R.string.panel_push, () -> new MobilePush(panel.activity).settings(panel.account));
        panel.note(page, panel.t(R.string.panel_push_status));
        panel.load(page, () -> panel.api.get(JOBS + "?include_disabled=true").getJSONArray("jobs"), jobs -> {
            if (jobs.length() == 0) panel.note(page, panel.t(R.string.panel_no_reminder));
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.optJSONObject(i);
                if (job == null || PanelData.text(job, "id").isEmpty()) continue;
                page.addView(Ui.divider(panel.activity));
                String name = PanelData.text(job, "name");
                page.addView(Ui.button(panel.activity, name, v -> details(job)));
                panel.note(page, panel.t(R.string.panel_job_state, state(job)));
                if (!PanelData.text(job, "next_run_at").isEmpty()) {
                    panel.note(page, panel.t(R.string.panel_next, localTime(PanelData.text(job, "next_run_at"), ZoneId.systemDefault())));
                }
            }
        }, this::show);
    }

    void open(String jobId) {
        LinearLayout page = panel.page("reminders", R.string.panel_reminders, this::show);
        if (jobId == null || jobId.isEmpty()) {
            panel.note(page, panel.t(R.string.panel_missing_job));
            panel.action(page, R.string.panel_view_reminders, this::show);
            return;
        }
        panel.request(page, () -> {
            JSONObject job = panel.api.get(JOBS + "/" + HermesApi.segment(jobId)).getJSONObject("job");
            if (!jobId.equals(PanelData.text(job, "id"))) throw new org.json.JSONException("Mismatched job");
            return job;
        }, this::details, error -> {
            if (error instanceof HermesApi.HttpError && ((HermesApi.HttpError) error).status == 404) {
                panel.note(page, panel.t(R.string.panel_missing_job));
                panel.action(page, R.string.panel_view_reminders, this::show);
            } else panel.failure(page, error, () -> open(jobId));
        });
    }

    private String state(JSONObject job) {
        switch (PanelData.jobState(job)) {
            case "completed": return panel.t(R.string.panel_completed);
            case "error": return panel.t(R.string.panel_job_error);
            case "scheduled": return panel.t(R.string.panel_scheduled);
            case "paused": return panel.t(R.string.panel_paused);
            default: return panel.t(R.string.panel_unknown);
        }
    }

    static String scheduleLabel(JSONObject job, ZoneId zone) {
        String value = PanelData.schedule(job);
        JSONObject schedule = job.optJSONObject("schedule");
        if (value.isEmpty() || (schedule != null && !"once".equals(PanelData.text(schedule, "kind")))) return value;
        return localTime(value, zone);
    }

    private static String localTime(String iso, ZoneId zone) {
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DISPLAY_TIME);
        } catch (java.time.DateTimeException malformed) { return iso; }
    }

    void details(JSONObject job) {
        LinearLayout page = panel.page("reminders", R.string.panel_reminders, this::show);
        String name = PanelData.text(job, "name");
        panel.note(page, name);
        String prompt = PanelData.text(job, "prompt");
        if (!prompt.isEmpty()) panel.note(page, prompt);
        panel.note(page, panel.t(R.string.panel_job_state, state(job)));
        String lastError = PanelData.text(job, "last_error");
        if (!lastError.isEmpty()) panel.note(page, panel.t(R.string.panel_last_job_error, lastError));
        panel.note(page, panel.t(R.string.panel_saved_schedule, scheduleLabel(job, ZoneId.systemDefault())));
        String next = PanelData.text(job, "next_run_at");
        if (!next.isEmpty()) panel.note(page, panel.t(R.string.panel_next, localTime(next, ZoneId.systemDefault())));
        String path = path(job);
        panel.action(page, R.string.panel_edit, () -> edit(job));
        if (!"completed".equals(PanelData.text(job, "state"))) {
            boolean enabled = job.optBoolean("enabled");
            panel.action(page, enabled ? R.string.panel_pause : R.string.panel_resume,
                    () -> mutate(() -> panel.api.post(path + (enabled ? "/pause" : "/resume"), new JSONObject())));
        }
        panel.action(page, R.string.panel_delete, () -> panel.present(new AlertDialog.Builder(panel.activity)
                .setMessage(panel.t(R.string.panel_confirm_delete, name))
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_delete, (d, which) -> mutate(() -> {
                    panel.api.delete(path); return null;
                })).create()));
    }

    private static String path(JSONObject job) { return JOBS + "/" + HermesApi.segment(PanelData.text(job, "id")); }

    private void edit(JSONObject job) { new ReminderEditor(panel, this, job).show(); }

    void submit(JSONObject job, JSONObject changes, LinearLayout status, android.widget.Button save) {
        save.setEnabled(false);
        status.removeAllViews();
        panel.request(status, () -> job == null ? panel.api.post(JOBS, changes) : panel.api.patch(path(job), changes),
                ignored -> show(), error -> {
                    panel.failure(status, error, null);
                    if (error instanceof HermesApi.HttpError && ((HermesApi.HttpError) error).status == 400) {
                        panel.note(status, panel.t(R.string.panel_check_fields));
                        save.setEnabled(true);
                    } else {
                        panel.note(status, panel.t(R.string.panel_check_mutation));
                        panel.action(status, R.string.panel_view_reminders, this::show);
                    }
                });
    }

    private void mutate(Callable<JSONObject> operation) {
        // A POST can commit on the server even when its response is lost. Reload the list instead of retrying it.
        LinearLayout page = panel.page("reminders", R.string.panel_reminders, this::show);
        panel.request(page, operation, ignored -> show(), error -> {
            panel.failure(page, error, null);
            panel.note(page, panel.t(R.string.panel_check_mutation));
            panel.action(page, R.string.panel_view_reminders, this::show);
        });
    }
}
