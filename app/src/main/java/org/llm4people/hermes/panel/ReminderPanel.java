package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
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
public final class ReminderPanel {
    public static final String ROUTE_ID = "reminders";
    static final String JOBS = "/api/jobs";
    private final SessionPanel panel;
    private ScheduledJobModel.Filter activeFilter = ScheduledJobModel.Filter.ALL;
    private String lastEmptyAnnouncement;

    ReminderPanel(SessionPanel panel) { this.panel = panel; }

    /** Active UI locale from the app language SSoT (never hardcoded to a single language). */
    static Locale activeLocale(Context context) {
        return context == null ? Locale.getDefault() : AppLanguage.resolvedLocale(context);
    }

    void show() {
        LinearLayout page = panel.page(ROUTE_ID, R.string.panel_reminders, null);
        Locale locale = activeLocale(panel.activity);
        ZoneId zone = ZoneId.systemDefault();

        // Active profile pill + summary header
        TextView profileBadge = Ui.pill(panel.activity, panel.account.label, R.color.accent_surface, R.color.accent);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = Ui.dp(panel.activity, 6);
        page.addView(profileBadge, badgeLp);

        LinearLayout topActions = Ui.row(panel.activity);
        Button createBtn = Ui.filledButton(panel.activity, panel.t(R.string.panel_new_reminder), v -> edit(null));
        Button refreshBtn = Ui.outlinedButton(panel.activity, panel.t(R.string.panel_refresh), v -> show());
        topActions.addView(createBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        refLp.setMarginStart(Ui.dp(panel.activity, 8));
        topActions.addView(refreshBtn, refLp);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        topLp.topMargin = Ui.dp(panel.activity, 10);
        page.addView(topActions, topLp);

        TextView listFeedback = createFeedbackView();
        page.addView(listFeedback, cardMargins());

        LinearLayout jobsSection = Ui.column(panel.activity);
        page.addView(jobsSection, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addDivider(page, 10, 4);
        panel.note(page, panel.t(MobilePush.isEnabled(panel.activity, panel.account.id)
                ? R.string.panel_push_on : R.string.panel_push_off));
        Button pushBtn = Ui.outlinedButton(panel.activity, panel.t(R.string.panel_push),
                v -> panel.openNotifications());
        page.addView(pushBtn, cardMargins());
        panel.note(page, panel.t(R.string.panel_push_status));

        panel.load(jobsSection, () -> panel.api.get(JOBS + "?include_disabled=true").getJSONArray("jobs"),
                jobs -> renderJobsOverview(jobsSection, jobs, zone, locale, listFeedback), this::show);
    }

    private void renderJobsOverview(LinearLayout container, JSONArray jobs, ZoneId zone, Locale locale,
                                    TextView feedbackView) {
        container.removeAllViews();
        ScheduledJobModel.Summary summary = ScheduledJobModel.summarizeJobs(jobs);

        TextView summaryView = panel.note(container, panel.t(R.string.panel_jobs_summary,
                summary.active(), summary.paused(), summary.attention()));
        summaryView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summaryView.setTextColor(panel.activity.getColor(R.color.ink));

        if (summary.total() == 0) {
            lastEmptyAnnouncement = null;
            panel.note(container, panel.t(R.string.panel_no_reminder));
            return;
        }

        // Filter chips bar
        HorizontalScrollView filterScroll = new HorizontalScrollView(panel.activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipsRow = Ui.row(panel.activity);
        addFilterChip(chipsRow, ScheduledJobModel.Filter.ALL,
                panel.t(R.string.panel_jobs_filter_all, summary.total()), container, jobs, zone, locale, feedbackView);
        addFilterChip(chipsRow, ScheduledJobModel.Filter.ACTIVE,
                panel.t(R.string.panel_jobs_filter_active, summary.active()), container, jobs, zone, locale, feedbackView);
        addFilterChip(chipsRow, ScheduledJobModel.Filter.PAUSED,
                panel.t(R.string.panel_jobs_filter_paused, summary.paused()), container, jobs, zone, locale, feedbackView);
        addFilterChip(chipsRow, ScheduledJobModel.Filter.ATTENTION,
                panel.t(R.string.panel_jobs_filter_attention, summary.attention()), container, jobs, zone, locale, feedbackView);
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
            container.addView(buildJobCard(job, zone, locale, feedbackView), cardMargins());
            shown++;
        }
        if (shown == 0) {
            TextView empty = panel.note(container, panel.t(R.string.panel_jobs_filter_empty));
            String emptyKey = "jobs_filter_empty:" + activeFilter.name();
            if (!emptyKey.equals(lastEmptyAnnouncement)) {
                empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                lastEmptyAnnouncement = emptyKey;
            }
        } else {
            lastEmptyAnnouncement = null;
        }
    }

    private void addFilterChip(
            LinearLayout row,
            ScheduledJobModel.Filter filter,
            String label,
            LinearLayout container,
            JSONArray jobs,
            ZoneId zone,
            Locale locale,
            TextView feedbackView) {
        boolean selected = activeFilter == filter;
        Button chip = Ui.choiceChip(panel.activity, label, v -> {
            activeFilter = filter;
            renderJobsOverview(container, jobs, zone, locale, feedbackView);
        });
        chip.setTextSize(13);
        Ui.styleChoiceChip(chip, selected);
        Ui.pad(chip, 14, 6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.setMarginStart(Ui.dp(panel.activity, 8));
        row.addView(chip, lp);
    }

    private LinearLayout buildJobCard(JSONObject job, ZoneId zone, Locale locale, TextView feedbackView) {
        LinearLayout card = Ui.card(panel.activity);

        String jobState = PanelData.jobState(job);
        String name = PanelData.text(job, "name");

        LinearLayout header = Ui.row(panel.activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button titleBtn = new Button(panel.activity, null, android.R.attr.borderlessButtonStyle);
        titleBtn.setText(name);
        titleBtn.setAllCaps(false);
        titleBtn.setTextSize(16);
        titleBtn.setTypeface(Ui.mediumTypeface());
        titleBtn.setTextColor(panel.activity.getColor(R.color.ink));
        titleBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        titleBtn.setStateListAnimator(null);
        titleBtn.setBackground(Ui.roundedRipple(panel.activity, Ui.BUTTON_CORNER_DP, null));
        titleBtn.setPaddingRelative(Ui.dp(panel.activity, 4), Ui.dp(panel.activity, 6),
                Ui.dp(panel.activity, 8), Ui.dp(panel.activity, 6));
        int touchMin = Ui.dp(panel.activity, 48);
        titleBtn.setMinHeight(touchMin);
        titleBtn.setMinimumHeight(touchMin);
        titleBtn.setOnClickListener(v -> details(job));
        header.addView(titleBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(statusPill(jobState));
        card.addView(header);

        String scheduleText = humanSchedule(panel.activity, job, zone);
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

        Button runNowBtn = Ui.tonalButton(panel.activity, panel.t(R.string.panel_jobs_run_now), null);
        runNowBtn.setOnClickListener(v -> triggerJobNow(job, runNowBtn, feedbackView, null));
        actions.addView(runNowBtn);

        if (!"completed".equals(jobState)) {
            boolean enabled = job.optBoolean("enabled", true);
            Button toggleBtn = Ui.outlinedButton(panel.activity,
                    panel.t(enabled ? R.string.panel_pause : R.string.panel_resume),
                    v -> mutate(() -> panel.api.post(path(job) + (enabled ? "/pause" : "/resume"), new JSONObject())));
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tLp.setMarginStart(Ui.dp(panel.activity, 8));
            actions.addView(toggleBtn, tLp);
        }

        Button detailsBtn = Ui.outlinedButton(panel.activity, panel.t(R.string.panel_jobs_details), v -> details(job));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.setMarginStart(Ui.dp(panel.activity, 8));
        actions.addView(detailsBtn, dLp);

        actionsScroll.addView(actions);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = Ui.dp(panel.activity, 10);
        card.addView(actionsScroll, actLp);
        return card;
    }

    private TextView statusPill(String jobState) {
        TextView pill = switch (jobState) {
            case "scheduled" -> Ui.pill(panel.activity, stateLabel(jobState),
                    R.color.accent_surface, R.color.accent);
            case "paused" -> Ui.pill(panel.activity, stateLabel(jobState),
                    R.color.warning_surface, R.color.warning);
            case "error" -> Ui.pill(panel.activity, stateLabel(jobState),
                    R.color.danger_surface, R.color.danger);
            default -> {
                TextView neutral = Ui.pill(panel.activity, stateLabel(jobState),
                        R.color.surface_container, R.color.muted);
                neutral.setBackground(Ui.shape(panel.activity, 999,
                        R.color.surface_container, R.color.line, 1));
                yield neutral;
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(panel.activity, 8));
        pill.setLayoutParams(lp);
        return pill;
    }

    void open(String jobId) {
        LinearLayout page = panel.page(ROUTE_ID, R.string.panel_reminders, this::show);
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

    static String scheduleLabel(JSONObject job, ZoneId zone, Locale locale) {
        String value = PanelData.schedule(job);
        JSONObject schedule = job.optJSONObject("schedule");
        if (value.isEmpty() || (schedule != null && !"once".equals(PanelData.text(schedule, "kind")))) return value;
        return formatTime(value, zone, locale);
    }

    /** Human-readable localized schedule label falling back gracefully to raw cron/interval. */
    static String humanSchedule(Context context, JSONObject job, ZoneId zone) {
        String raw = PanelData.schedule(job);
        if (raw.isEmpty()) return "";
        ReminderSchedule parsed = ReminderSchedule.parse(raw, zone, ZonedDateTime.now(zone));
        return parsed.humanSummary(context);
    }

    /** Formats an ISO-8601 timestamp or epoch seconds cleanly in the active locale. */
    static String formatTime(String raw, ZoneId zone, Locale locale) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Locale effective = locale == null ? Locale.getDefault() : locale;
        DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(effective);
        try {
            return OffsetDateTime.parse(raw.trim()).atZoneSameInstant(zone).format(fmt);
        } catch (java.time.DateTimeException ignored) {
            long epoch = org.llm4people.hermes.EpochTimestamps.parseEpochSeconds(raw);
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
        LinearLayout page = panel.page(ROUTE_ID, R.string.panel_reminders, this::show);
        Locale locale = activeLocale(panel.activity);
        ZoneId zone = ZoneId.systemDefault();
        String name = PanelData.text(job, "name");
        String jobState = PanelData.jobState(job);

        LinearLayout header = Ui.row(panel.activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = Ui.text(panel.activity, name, 19);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setAccessibilityHeading(true);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(statusPill(jobState));
        page.addView(header);

        LinearLayout metaCard = Ui.card(panel.activity);
        String prompt = PanelData.text(job, "prompt");
        if (!prompt.isEmpty()) {
            TextView promptView = Ui.text(panel.activity, prompt, 15);
            promptView.setTextColor(panel.activity.getColor(R.color.ink));
            metaCard.addView(promptView);
            addDivider(metaCard, 10, 4);
        }
        addMetaLine(metaCard, panel.t(R.string.panel_job_state, stateLabel(jobState)), R.color.ink);
        String lastError = PanelData.text(job, "last_error");
        if (!lastError.isEmpty()) {
            addMetaLine(metaCard, panel.t(R.string.panel_last_job_error, lastError), R.color.danger);
        }
        String friendlySchedule = humanSchedule(panel.activity, job, zone);
        String rawSchedule = scheduleLabel(job, zone, locale);
        addMetaLine(metaCard, panel.t(R.string.panel_saved_schedule,
                friendlySchedule.isEmpty() ? rawSchedule : friendlySchedule), R.color.ink);
        String channel = ScheduledJobModel.deliveryChannelLabel(PanelData.text(job, "deliver"));
        if (!channel.isEmpty()) {
            addMetaLine(metaCard, panel.t(R.string.panel_jobs_delivery, channel), R.color.muted);
        }
        String next = PanelData.text(job, "next_run_at");
        if (!next.isEmpty()) {
            addMetaLine(metaCard, panel.t(R.string.panel_next, formatTime(next, zone, locale)), R.color.muted);
        }
        String lastRun = PanelData.text(job, "last_run_at");
        if (!lastRun.isEmpty()) {
            addMetaLine(metaCard, panel.t(R.string.panel_jobs_last_run, formatTime(lastRun, zone, locale)), R.color.muted);
        }
        page.addView(metaCard, cardMargins());

        TextView detailFeedback = createFeedbackView();
        page.addView(detailFeedback, cardMargins());

        String path = path(job);
        LinearLayout runsSection = Ui.column(panel.activity);
        Button runNowBtn = Ui.filledButton(panel.activity, panel.t(R.string.panel_jobs_run_now), null);
        runNowBtn.setOnClickListener(v -> triggerJobNow(job, runNowBtn, detailFeedback,
                () -> loadRecentRuns(runsSection, job, zone, locale)));
        page.addView(runNowBtn, cardMargins());

        Button editBtn = Ui.outlinedButton(panel.activity, panel.t(R.string.panel_edit), v -> edit(job));
        page.addView(editBtn, cardMargins());
        if (!"completed".equals(PanelData.text(job, "state"))) {
            boolean enabled = job.optBoolean("enabled", true);
            Button pauseBtn = Ui.outlinedButton(panel.activity,
                    panel.t(enabled ? R.string.panel_pause : R.string.panel_resume),
                    v -> mutate(() -> panel.api.post(path + (enabled ? "/pause" : "/resume"), new JSONObject())));
            page.addView(pauseBtn, cardMargins());
        }
        Button deleteBtn = Ui.outlinedButton(panel.activity, panel.t(R.string.panel_delete),
                v -> panel.present(new AlertDialog.Builder(panel.activity)
                        .setMessage(panel.t(R.string.panel_confirm_delete, name))
                        .setNegativeButton(R.string.panel_cancel, null)
                        .setPositiveButton(R.string.panel_delete, (d, which) -> mutate(() -> {
                            panel.api.delete(path); return null;
                        })).create()));
        deleteBtn.setTextColor(Ui.dangerInk(panel.activity));
        page.addView(deleteBtn, cardMargins());

        panel.section(page, R.string.panel_jobs_recent_runs);
        page.addView(runsSection);
        loadRecentRuns(runsSection, job, zone, locale);
    }

    private void addMetaLine(LinearLayout card, String text, int colorRes) {
        TextView view = Ui.text(panel.activity, text, 14);
        view.setTextColor(panel.activity.getColor(colorRes));
        if (card.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(panel.activity, 6);
            card.addView(view, lp);
        } else {
            card.addView(view);
        }
    }

    private TextView createFeedbackView() {
        TextView view = Ui.text(panel.activity, "", 13);
        view.setTypeface(Ui.mediumTypeface());
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        view.setVisibility(View.GONE);
        return view;
    }

    private void triggerJobNow(JSONObject job, Button button, TextView feedbackView, Runnable onTriggered) {
        String jobId = PanelData.text(job, "id");
        if (jobId.isEmpty()) return;
        button.setEnabled(false);
        String original = button.getText().toString();
        button.setText(panel.t(R.string.panel_jobs_running_now));
        if (feedbackView != null) {
            feedbackView.setText("");
            feedbackView.setVisibility(View.GONE);
        }
        LinearLayout statusSlot = Ui.column(panel.activity);
        panel.request(statusSlot, () -> panel.api.post(ScheduledJobModel.triggerPath(jobId), new JSONObject()),
                ok -> {
                    button.setEnabled(true);
                    button.setText(original);
                    Diagnostics.event("jobs", "triggered_now");
                    if (feedbackView != null) {
                        feedbackView.setTextColor(panel.activity.getColor(R.color.accent));
                        feedbackView.setText(R.string.panel_jobs_triggered);
                        feedbackView.setVisibility(View.VISIBLE);
                    }
                    if (onTriggered != null) onTriggered.run();
                },
                error -> {
                    button.setEnabled(true);
                    button.setText(original);
                    Diagnostics.warning("jobs", "trigger_failed");
                    if (feedbackView != null) {
                        feedbackView.setTextColor(panel.activity.getColor(R.color.danger));
                        feedbackView.setText(R.string.panel_jobs_trigger_failed);
                        feedbackView.setVisibility(View.VISIBLE);
                    }
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
            LinearLayout row = Ui.tonalCard(panel.activity);

            String when = formatEpochTime(run.startedAtEpochSec(), zone, locale);
            String dur = ScheduledJobModel.formatDuration(
                    run.durationSeconds(), org.llm4people.hermes.AppLanguage.resolvedLocale(panel.activity));
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

    private LinearLayout.LayoutParams cardMargins() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(panel.activity, 8);
        return lp;
    }

    private static String path(JSONObject job) { return JOBS + "/" + HermesApi.segment(PanelData.text(job, "id")); }

    private void addDivider(LinearLayout parent, int topDp, int bottomDp) {
        View divider = Ui.divider(panel.activity);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        lp.topMargin = Ui.dp(panel.activity, topDp);
        lp.bottomMargin = Ui.dp(panel.activity, bottomDp);
        parent.addView(divider, lp);
    }

    void create() { edit(null); }

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
        LinearLayout page = panel.page(ROUTE_ID, R.string.panel_reminders, this::show);
        panel.request(page, operation, ignored -> show(), error -> {
            panel.failure(page, error, null);
            panel.note(page, panel.t(R.string.panel_check_mutation));
            panel.action(page, R.string.panel_view_reminders, this::show);
        });
    }
}
