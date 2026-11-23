package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.push.MobilePush;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Profile-scoped Scheduled Jobs overview, live execution history, and immediate trigger controls.
 */
final class ReminderPanel {
    static final String JOBS = "/api/jobs";
    private final SessionPanel panel;
    private ScheduledJobModel.Filter activeFilter = ScheduledJobModel.Filter.ALL;

    ReminderPanel(SessionPanel panel) { this.panel = panel; }

    /** Active UI locale from the localized Activity context (never hardcoded to a single language). */
    static Locale activeLocale(Context context) {
        if (context != null && context.getResources() != null
                && context.getResources().getConfiguration() != null) {
            android.os.LocaleList locales = context.getResources().getConfiguration().getLocales();
            if (locales != null && !locales.isEmpty() && locales.get(0) != null) {
                return locales.get(0);
            }
        }
        return Locale.getDefault();
    }

    void show() {
        LinearLayout page = panel.page("reminders", R.string.panel_reminders, null);
        Locale locale = activeLocale(panel.activity);
        ZoneId zone = ZoneId.systemDefault();

        // Active profile pill + summary header
        TextView profileBadge = Ui.text(panel.activity, panel.account.label, 14);
        profileBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        profileBadge.setTextColor(panel.activity.getColor(R.color.accent));
        profileBadge.setPaddingRelative(Ui.dp(panel.activity, 12), Ui.dp(panel.activity, 5),
                Ui.dp(panel.activity, 12), Ui.dp(panel.activity, 5));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(Ui.dp(panel.activity, 999));
        badgeBg.setColor(panel.activity.getColor(R.color.accent_surface));
        profileBadge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = Ui.dp(panel.activity, 6);
        page.addView(profileBadge, badgeLp);

        LinearLayout topActions = Ui.row(panel.activity);
        Button createBtn = Ui.button(panel.activity, panel.t(R.string.panel_new_reminder), v -> edit(null));
        enforceTouch(createBtn);
        Button refreshBtn = Ui.button(panel.activity, panel.t(R.string.panel_refresh), v -> show());
        enforceTouch(refreshBtn);
        topActions.addView(createBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        refLp.setMarginStart(Ui.dp(panel.activity, 8));
        topActions.addView(refreshBtn, refLp);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        topLp.topMargin = Ui.dp(panel.activity, 10);
        page.addView(topActions, topLp);

        LinearLayout jobsSection = Ui.column(panel.activity);
        page.addView(jobsSection, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        page.addView(Ui.divider(panel.activity));
        panel.note(page, panel.t(MobilePush.isEnabled(panel.activity, panel.account.id)
                ? R.string.panel_push_on : R.string.panel_push_off));
        panel.action(page, R.string.panel_push, () -> new MobilePush(panel.activity).settings(panel.account));
        panel.note(page, panel.t(R.string.panel_push_status));

        panel.load(jobsSection, () -> panel.api.get(JOBS + "?include_disabled=true").getJSONArray("jobs"),
                jobs -> renderJobsOverview(jobsSection, jobs, zone, locale), this::show);
    }

    private void renderJobsOverview(LinearLayout container, JSONArray jobs, ZoneId zone, Locale locale) {
        container.removeAllViews();
        ScheduledJobModel.Summary summary = ScheduledJobModel.summarizeJobs(jobs);

        TextView summaryView = panel.note(container, panel.t(R.string.panel_jobs_summary,
                summary.active(), summary.paused(), summary.attention()));
        summaryView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summaryView.setTextColor(panel.activity.getColor(R.color.ink));

        if (summary.total() == 0) {
            panel.note(container, panel.t(R.string.panel_no_reminder));
            return;
        }

        // Filter chips bar
        HorizontalScrollView filterScroll = new HorizontalScrollView(panel.activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipsRow = Ui.row(panel.activity);
        addFilterChip(chipsRow, ScheduledJobModel.Filter.ALL,
                panel.t(R.string.panel_jobs_filter_all, summary.total()), container, jobs, zone, locale);
        addFilterChip(chipsRow, ScheduledJobModel.Filter.ACTIVE,
                panel.t(R.string.panel_jobs_filter_active, summary.active()), container, jobs, zone, locale);
        addFilterChip(chipsRow, ScheduledJobModel.Filter.PAUSED,
                panel.t(R.string.panel_jobs_filter_paused, summary.paused()), container, jobs, zone, locale);
        addFilterChip(chipsRow, ScheduledJobModel.Filter.ATTENTION,
                panel.t(R.string.panel_jobs_filter_attention, summary.attention()), container, jobs, zone, locale);
        filterScroll.addView(chipsRow);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = Ui.dp(panel.activity, 6);
        scrollLp.bottomMargin = Ui.dp(panel.activity, 8);
        container.addView(filterScroll, scrollLp);

        int shown = 0;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job == null || PanelData.text(job, "id").isEmpty()) continue;
            if (!ScheduledJobModel.matchesFilter(PanelData.jobState(job), activeFilter)) continue;
            container.addView(buildJobCard(job, zone, locale), cardMargins());
            shown++;
        }
        if (shown == 0) {
            panel.note(container, panel.t(R.string.panel_jobs_filter_empty));
        }
    }

    private void addFilterChip(
            LinearLayout row,
            ScheduledJobModel.Filter filter,
            String label,
            LinearLayout container,
            JSONArray jobs,
            ZoneId zone,
            Locale locale) {
        boolean selected = activeFilter == filter;
        Button chip = new Button(panel.activity, null, android.R.attr.borderlessButtonStyle);
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(13);
        chip.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        enforceTouch(chip);
        Ui.pad(chip, 14, 6);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(panel.activity, 999));
        bg.setColor(panel.activity.getColor(selected ? R.color.accent_surface : R.color.surface));
        bg.setStroke(Math.max(1, Ui.dp(panel.activity, 1)),
                panel.activity.getColor(selected ? R.color.accent : R.color.line));
        chip.setBackground(bg);
        chip.setTextColor(panel.activity.getColor(selected ? R.color.accent : R.color.ink));
        chip.setSelected(selected);
        chip.setOnClickListener(v -> {
            activeFilter = filter;
            renderJobsOverview(container, jobs, zone, locale);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(panel.activity, 8));
        row.addView(chip, lp);
    }

    private LinearLayout buildJobCard(JSONObject job, ZoneId zone, Locale locale) {
        LinearLayout card = Ui.column(panel.activity);
        Ui.pad(card, 14, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(panel.activity, 12));
        bg.setColor(panel.activity.getColor(R.color.surface));
        bg.setStroke(Math.max(1, Ui.dp(panel.activity, 1)), panel.activity.getColor(R.color.line));
        card.setBackground(bg);

        String jobState = PanelData.jobState(job);
        String name = PanelData.text(job, "name");

        LinearLayout header = Ui.row(panel.activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button titleBtn = new Button(panel.activity, null, android.R.attr.borderlessButtonStyle);
        titleBtn.setText(name);
        titleBtn.setAllCaps(false);
        titleBtn.setTextSize(16);
        titleBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleBtn.setTextColor(panel.activity.getColor(R.color.ink));
        titleBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        titleBtn.setPaddingRelative(0, Ui.dp(panel.activity, 4), Ui.dp(panel.activity, 8), Ui.dp(panel.activity, 4));
        enforceTouch(titleBtn);
        titleBtn.setOnClickListener(v -> details(job));
        header.addView(titleBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(statusPill(jobState));
        card.addView(header);

        String scheduleText = humanSchedule(job, zone, locale);
        if (!scheduleText.isEmpty()) {
            TextView schedView = Ui.text(panel.activity, scheduleText, 14);
            schedView.setTextColor(panel.activity.getColor(R.color.ink));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(panel.activity, 6);
            card.addView(schedView, lp);
        }

        String channel = ScheduledJobModel.deliveryChannelLabel(PanelData.text(job, "deliver"));
        if (!channel.isEmpty()) {
            TextView deliverView = Ui.text(panel.activity, panel.t(R.string.panel_jobs_delivery, channel), 12);
            deliverView.setTextColor(panel.activity.getColor(R.color.muted));
            card.addView(deliverView);
        }

        String next = PanelData.text(job, "next_run_at");
        if (!next.isEmpty()) {
            TextView nextView = Ui.text(panel.activity,
                    panel.t(R.string.panel_next, formatTime(next, zone, locale)), 13);
            nextView.setTextColor(panel.activity.getColor(R.color.muted));
            card.addView(nextView);
        }

        String lastRun = PanelData.text(job, "last_run_at");
        if (!lastRun.isEmpty()) {
            TextView lastView = Ui.text(panel.activity,
                    panel.t(R.string.panel_jobs_last_run, formatTime(lastRun, zone, locale)), 12);
            lastView.setTextColor(panel.activity.getColor(R.color.muted));
            card.addView(lastView);
        }

        String lastError = PanelData.text(job, "last_error");
        if (!lastError.isEmpty()) {
            TextView errView = Ui.text(panel.activity, panel.t(R.string.panel_last_job_error, lastError), 12);
            errView.setTextColor(panel.activity.getColor(R.color.danger));
            card.addView(errView);
        }

        // Quick 1-tap inline actions
        HorizontalScrollView actionsScroll = new HorizontalScrollView(panel.activity);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = Ui.row(panel.activity);

        Button runNowBtn = Ui.button(panel.activity, panel.t(R.string.panel_jobs_run_now), null);
        enforceTouch(runNowBtn);
        runNowBtn.setOnClickListener(v -> triggerJobNow(job, runNowBtn, null));
        actions.addView(runNowBtn);

        if (!"completed".equals(jobState)) {
            boolean enabled = job.optBoolean("enabled", true);
            Button toggleBtn = Ui.button(panel.activity,
                    panel.t(enabled ? R.string.panel_pause : R.string.panel_resume),
                    v -> mutate(() -> panel.api.post(path(job) + (enabled ? "/pause" : "/resume"), new JSONObject())));
            enforceTouch(toggleBtn);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tLp.setMarginStart(Ui.dp(panel.activity, 6));
            actions.addView(toggleBtn, tLp);
        }

        Button detailsBtn = Ui.button(panel.activity, panel.t(R.string.panel_jobs_details), v -> details(job));
        enforceTouch(detailsBtn);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.setMarginStart(Ui.dp(panel.activity, 6));
        actions.addView(detailsBtn, dLp);

        actionsScroll.addView(actions);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = Ui.dp(panel.activity, 8);
        card.addView(actionsScroll, actLp);
        return card;
    }

    private TextView statusPill(String jobState) {
        TextView pill = Ui.text(panel.activity, stateLabel(jobState), 12);
        pill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pill.setSingleLine(true);
        pill.setPaddingRelative(Ui.dp(panel.activity, 10), Ui.dp(panel.activity, 4),
                Ui.dp(panel.activity, 10), Ui.dp(panel.activity, 4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(panel.activity, 999));
        switch (jobState) {
            case "scheduled" -> {
                bg.setColor(panel.activity.getColor(R.color.accent_surface));
                pill.setTextColor(panel.activity.getColor(R.color.accent));
            }
            case "paused" -> {
                bg.setColor(panel.activity.getColor(R.color.warning_surface));
                pill.setTextColor(panel.activity.getColor(R.color.warning));
            }
            case "error" -> {
                bg.setColor(panel.activity.getColor(R.color.danger_surface));
                pill.setTextColor(panel.activity.getColor(R.color.danger));
            }
            default -> {
                bg.setColor(panel.activity.getColor(R.color.paper));
                bg.setStroke(Math.max(1, Ui.dp(panel.activity, 1)), panel.activity.getColor(R.color.line));
                pill.setTextColor(panel.activity.getColor(R.color.muted));
            }
        }
        pill.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(panel.activity, 8));
        pill.setLayoutParams(lp);
        return pill;
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

    private String stateLabel(String jobState) {
        return switch (jobState) {
            case "completed" -> panel.t(R.string.panel_completed);
            case "error" -> panel.t(R.string.panel_job_error);
            case "scheduled" -> panel.t(R.string.panel_scheduled);
            case "paused" -> panel.t(R.string.panel_paused);
            default -> panel.t(R.string.panel_unknown);
        };
    }

    static String scheduleLabel(JSONObject job, ZoneId zone) {
        return scheduleLabel(job, zone, Locale.FRENCH);
    }

    static String scheduleLabel(JSONObject job, ZoneId zone, Locale locale) {
        String value = PanelData.schedule(job);
        JSONObject schedule = job.optJSONObject("schedule");
        if (value.isEmpty() || (schedule != null && !"once".equals(PanelData.text(schedule, "kind")))) return value;
        return formatTime(value, zone, locale);
    }

    /** Human-readable localized schedule label falling back gracefully to raw cron/interval. */
    static String humanSchedule(JSONObject job, ZoneId zone, Locale locale) {
        String raw = PanelData.schedule(job);
        if (raw.isEmpty()) return "";
        ReminderSchedule parsed = ReminderSchedule.parse(raw, zone, ZonedDateTime.now(zone));
        return parsed.humanSummary(locale);
    }

    /** Formats an ISO-8601 timestamp or epoch seconds cleanly in the active locale. */
    static String formatTime(String raw, ZoneId zone, Locale locale) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Locale effective = locale == null ? Locale.getDefault() : locale;
        DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(effective);
        try {
            return OffsetDateTime.parse(raw.trim()).atZoneSameInstant(zone).format(fmt);
        } catch (java.time.DateTimeException ignored) {
            long epoch = ScheduledJobModel.parseEpochSeconds(raw);
            if (epoch > 0L) {
                return Instant.ofEpochSecond(epoch).atZone(zone).format(fmt);
            }
            return raw;
        }
    }

    static String formatEpochTime(long epochSeconds, ZoneId zone, Locale locale) {
        if (epochSeconds <= 0L) return "";
        Locale effective = locale == null ? Locale.getDefault() : locale;
        DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(effective);
        return Instant.ofEpochSecond(epochSeconds).atZone(zone).format(fmt);
    }

    void details(JSONObject job) {
        LinearLayout page = panel.page("reminders", R.string.panel_reminders, this::show);
        Locale locale = activeLocale(panel.activity);
        ZoneId zone = ZoneId.systemDefault();
        String name = PanelData.text(job, "name");
        String jobState = PanelData.jobState(job);

        LinearLayout header = Ui.row(panel.activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = Ui.text(panel.activity, name, 19);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(statusPill(jobState));
        page.addView(header);

        String prompt = PanelData.text(job, "prompt");
        if (!prompt.isEmpty()) panel.note(page, prompt);
        panel.note(page, panel.t(R.string.panel_job_state, stateLabel(jobState)));
        String lastError = PanelData.text(job, "last_error");
        if (!lastError.isEmpty()) {
            TextView errView = panel.note(page, panel.t(R.string.panel_last_job_error, lastError));
            errView.setTextColor(panel.activity.getColor(R.color.danger));
        }
        String friendlySchedule = humanSchedule(job, zone, locale);
        String rawSchedule = scheduleLabel(job, zone, locale);
        panel.note(page, panel.t(R.string.panel_saved_schedule,
                friendlySchedule.isEmpty() ? rawSchedule : friendlySchedule));
        String channel = ScheduledJobModel.deliveryChannelLabel(PanelData.text(job, "deliver"));
        if (!channel.isEmpty()) {
            panel.note(page, panel.t(R.string.panel_jobs_delivery, channel));
        }
        String next = PanelData.text(job, "next_run_at");
        if (!next.isEmpty()) panel.note(page, panel.t(R.string.panel_next, formatTime(next, zone, locale)));
        String lastRun = PanelData.text(job, "last_run_at");
        if (!lastRun.isEmpty()) panel.note(page, panel.t(R.string.panel_jobs_last_run, formatTime(lastRun, zone, locale)));

        String path = path(job);
        LinearLayout runsSection = Ui.column(panel.activity);
        Button runNowBtn = panel.action(page, R.string.panel_jobs_run_now, () -> {});
        runNowBtn.setOnClickListener(v -> triggerJobNow(job, runNowBtn, () -> loadRecentRuns(runsSection, job, zone, locale)));

        panel.action(page, R.string.panel_edit, () -> edit(job));
        if (!"completed".equals(PanelData.text(job, "state"))) {
            boolean enabled = job.optBoolean("enabled", true);
            panel.action(page, enabled ? R.string.panel_pause : R.string.panel_resume,
                    () -> mutate(() -> panel.api.post(path + (enabled ? "/pause" : "/resume"), new JSONObject())));
        }
        panel.action(page, R.string.panel_delete, () -> panel.present(new AlertDialog.Builder(panel.activity)
                .setMessage(panel.t(R.string.panel_confirm_delete, name))
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_delete, (d, which) -> mutate(() -> {
                    panel.api.delete(path); return null;
                })).create()));

        panel.section(page, R.string.panel_jobs_recent_runs);
        page.addView(runsSection);
        loadRecentRuns(runsSection, job, zone, locale);
    }

    private void triggerJobNow(JSONObject job, Button button, Runnable onTriggered) {
        String jobId = PanelData.text(job, "id");
        if (jobId.isEmpty()) return;
        button.setEnabled(false);
        String original = button.getText().toString();
        button.setText(panel.t(R.string.panel_jobs_running_now));
        LinearLayout statusSlot = Ui.column(panel.activity);
        panel.request(statusSlot, () -> panel.api.post(ScheduledJobModel.triggerPath(jobId), new JSONObject()),
                ok -> {
                    button.setEnabled(true);
                    button.setText(original);
                    Diagnostics.event("jobs", "triggered_now");
                    Toast.makeText(panel.activity, R.string.panel_jobs_triggered, Toast.LENGTH_SHORT).show();
                    if (onTriggered != null) onTriggered.run();
                },
                error -> {
                    button.setEnabled(true);
                    button.setText(original);
                    Diagnostics.warning("jobs", "trigger_failed");
                    Toast.makeText(panel.activity, R.string.panel_jobs_trigger_failed, Toast.LENGTH_LONG).show();
                });
    }

    private void loadRecentRuns(LinearLayout container, JSONObject job, ZoneId zone, Locale locale) {
        String jobId = PanelData.text(job, "id");
        if (jobId.isEmpty()) return;
        container.removeAllViews();
        panel.request(container,
                () -> ScheduledJobModel.parseRuns(panel.api.get(
                        ScheduledJobModel.runsPath(jobId, ScheduledJobModel.DEFAULT_RUNS_LIMIT))),
                runs -> renderRecentRuns(container, runs, zone, locale),
                error -> {
                    // Gracefully degrade on older servers where /api/jobs/{id}/runs is not yet deployed.
                    if (error instanceof HermesApi.HttpError http && http.status == 404) {
                        panel.note(container, panel.t(R.string.panel_jobs_no_runs));
                    } else {
                        panel.failure(container, error, () -> loadRecentRuns(container, job, zone, locale));
                    }
                });
    }

    private void renderRecentRuns(
            LinearLayout container,
            List<ScheduledJobModel.JobRun> runs,
            ZoneId zone,
            Locale locale) {
        container.removeAllViews();
        if (runs == null || runs.isEmpty()) {
            panel.note(container, panel.t(R.string.panel_jobs_no_runs));
            return;
        }
        for (ScheduledJobModel.JobRun run : runs) {
            LinearLayout row = Ui.column(panel.activity);
            Ui.pad(row, 12, 10);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(panel.activity, 10));
            bg.setColor(panel.activity.getColor(R.color.surface));
            bg.setStroke(Math.max(1, Ui.dp(panel.activity, 1)), panel.activity.getColor(R.color.line));
            row.setBackground(bg);

            String when = formatEpochTime(run.startedAtEpochSec(), zone, locale);
            String dur = ScheduledJobModel.formatDuration(run.durationSeconds());
            StringBuilder line = new StringBuilder(runStatusLabel(run.status()));
            if (!when.isEmpty()) line.append(" · ").append(when);
            if (!dur.isEmpty()) line.append(" · ").append(dur);
            TextView header = Ui.text(panel.activity, line.toString(), 13);
            header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            header.setTextColor(panel.activity.getColor(
                    "failed".equals(run.status()) ? R.color.danger
                            : "running".equals(run.status()) ? R.color.accent : R.color.ink));
            row.addView(header);

            String detail = !run.preview().isEmpty() ? run.preview() : run.title();
            if (!detail.isEmpty()) {
                TextView detailView = Ui.text(panel.activity, detail, 13);
                detailView.setTextColor(panel.activity.getColor(R.color.muted));
                row.addView(detailView);
            }
            container.addView(row, cardMargins());
        }
    }

    private String runStatusLabel(String status) {
        return switch (status) {
            case "running" -> panel.t(R.string.panel_jobs_run_status_running);
            case "completed" -> panel.t(R.string.panel_jobs_run_status_completed);
            case "failed" -> panel.t(R.string.panel_jobs_run_status_failed);
            case "cancelled" -> panel.t(R.string.panel_jobs_run_status_cancelled);
            case "missed" -> panel.t(R.string.panel_jobs_run_status_missed);
            default -> panel.t(R.string.panel_unknown);
        };
    }

    private void enforceTouch(Button button) {
        int touchMin = Ui.dp(panel.activity, 48);
        button.setMinHeight(touchMin);
        button.setMinimumHeight(touchMin);
    }

    private LinearLayout.LayoutParams cardMargins() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(panel.activity, 8);
        return lp;
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
