package org.llm4people.hermes.doctor;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.PermissionsPanel;
import org.llm4people.hermes.profile.Account;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plain-language live Connection Doctor panel mounted under Settings ({@link #ROUTE_ID}).
 * Runs 6 parallel non-blocking health probes, renders high-contrast day/night status cards
 * with 1-tap fix actions, and exposes a strictly redacted "Copy safe report" behind
 * "Technical details".
 */
public final class ConnectionDoctorPanel {
    public static final String ROUTE_ID = "connection_doctor";

    /** Optional 1-tap fix callbacks wired by the host Activity / navigation shell. */
    public interface FixHandlers {
        default void openPushSettings() {}
        default void openAppUpdates() {}
        default void openAmbientAccess() {}
        default void openLanguageSettings() {}
        default void openPermissions() {}
    }

    private final Activity activity;
    private final ConnectionDoctor doctor;
    private final FixHandlers fixHandlers;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-doctor-panel");
        t.setDaemon(true);
        return t;
    });

    private TextView summaryTitle;
    private TextView summaryCounts;
    private TextView feedback;
    private ProgressBar progressBar;
    private Button rerunButton;
    private TextView permissionsSummary;
    private LinearLayout cardsContainer;
    private Button technicalToggle;
    private LinearLayout technicalBox;
    private TextView technicalHost;
    private TextView technicalVersion;
    private TextView technicalLanguage;
    private TextView technicalReportPreview;
    private Button copyReportButton;

    private DoctorCheck.Report latestReport;
    private boolean technicalExpanded;
    private boolean busy;
    private boolean closed;

    public ConnectionDoctorPanel(Activity activity, Account account, HermesApi api, FixHandlers fixHandlers) {
        this(activity, ConnectionDoctor.forAndroid(Objects.requireNonNull(activity, "activity"), account, api),
                fixHandlers);
    }

    public ConnectionDoctorPanel(Activity activity, ConnectionDoctor doctor, FixHandlers fixHandlers) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.doctor = Objects.requireNonNull(doctor, "doctor");
        this.fixHandlers = fixHandlers != null ? fixHandlers : new FixHandlers() {};
    }

    /** Register this panel in {@link RouteRegistry} under {@link RouteRegistry.Section#SETTINGS}. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Context context, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        return registry.register(new RouteRegistry.Destination(
                ROUTE_ID,
                RouteRegistry.Section.SETTINGS,
                context.getString(R.string.doctor_route_label),
                context.getString(R.string.doctor_route_hint),
                0,
                false,
                null,
                open));
    }

    /** Build the scrollable paper/ink/teal TalkBack-accessible Connection Doctor view. */
    public View buildView() {
        LinearLayout body = Ui.column(activity);
        ScrollView scroll = Ui.centeredScroll(activity, body, AdaptiveLayout.READING_COLUMN_MAX_DP, 20, 16, 24);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));

        TextView subtitle = Ui.text(activity, activity.getString(R.string.doctor_panel_subtitle), 15);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        subtitle.setPaddingRelative(0, 0, 0, Ui.dp(activity, 14));
        body.addView(subtitle);

        LinearLayout summaryCard = Ui.card(activity);
        summaryTitle = Ui.text(activity, activity.getString(R.string.doctor_status_running), 18);
        summaryTitle.setTypeface(Ui.mediumTypeface());
        summaryTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        summaryCard.addView(summaryTitle);

        summaryCounts = Ui.text(activity, "", 14);
        summaryCounts.setTextColor(activity.getColor(R.color.muted));
        summaryCounts.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        summaryCounts.setVisibility(View.GONE);
        summaryCard.addView(summaryCounts);

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, -2);
        progressLp.topMargin = Ui.dp(activity, 10);
        summaryCard.addView(progressBar, progressLp);

        rerunButton = Ui.tonalButton(activity, activity.getString(R.string.doctor_rerun_action), v -> runChecks());
        LinearLayout.LayoutParams rerunLp = new LinearLayout.LayoutParams(-2, -2);
        rerunLp.topMargin = Ui.dp(activity, 10);
        summaryCard.addView(rerunButton, rerunLp);

        body.addView(summaryCard, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout permissionsCard = Ui.card(activity);
        TextView permTitle = Ui.text(activity, activity.getString(R.string.doctor_permissions_card_title), 16);
        permTitle.setTypeface(Ui.mediumTypeface());
        permissionsCard.addView(permTitle);

        permissionsSummary = Ui.text(activity, PermissionsPanel.summaryLine(activity), 14);
        permissionsSummary.setTextColor(activity.getColor(R.color.muted));
        permissionsSummary.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        permissionsCard.addView(permissionsSummary);

        Button openPerms = Ui.outlinedButton(activity, activity.getString(R.string.doctor_fix_permissions),
                v -> fixHandlers.openPermissions());
        openPerms.setTag("doctor:open_permissions");
        LinearLayout.LayoutParams openPermsLp = new LinearLayout.LayoutParams(-2, -2);
        openPermsLp.topMargin = Ui.dp(activity, 10);
        permissionsCard.addView(openPerms, openPermsLp);

        LinearLayout.LayoutParams permCardLp = new LinearLayout.LayoutParams(-1, -2);
        permCardLp.topMargin = Ui.dp(activity, 12);
        body.addView(permissionsCard, permCardLp);

        feedback = Ui.text(activity, "", 14);
        feedback.setTextColor(activity.getColor(R.color.accent));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setPaddingRelative(0, Ui.dp(activity, 10), 0, 0);
        feedback.setVisibility(View.GONE);
        body.addView(feedback);

        cardsContainer = Ui.column(activity);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(-1, -2);
        cardsLp.topMargin = Ui.dp(activity, 12);
        body.addView(cardsContainer, cardsLp);

        buildTechnicalSection(body);

        runChecks();
        return scroll;
    }

    /** Run all 6 health checks off the UI thread (debounced against rapid double-taps). */
    public void runChecks() {
        if (closed || busy) return;
        busy = true;
        if (rerunButton != null) {
            rerunButton.setEnabled(false);
            rerunButton.setText(R.string.doctor_checking_action);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        if (latestReport == null) {
            setSummaryTitle(R.string.doctor_status_running, R.color.ink);
        }
        runner.execute(() -> {
            DoctorCheck.Report report = null;
            try {
                report = doctor.runAll();
            } catch (RuntimeException error) {
                Diagnostics.warning("doctor", "run_all_failed", error);
            }
            final DoctorCheck.Report completed = report;
            postUi(() -> {
                busy = false;
                if (rerunButton != null) {
                    rerunButton.setEnabled(true);
                    rerunButton.setText(R.string.doctor_rerun_action);
                }
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                if (completed != null) {
                    latestReport = completed;
                    if (feedback != null) feedback.setVisibility(View.GONE);
                    renderReport(completed);
                } else {
                    renderRunFailure();
                }
            });
        });
    }

    private void renderRunFailure() {
        setSummaryTitle(R.string.doctor_status_problem, R.color.danger);
        if (summaryCounts != null && latestReport == null) {
            summaryCounts.setVisibility(View.GONE);
        }
        showFeedback(activity.getString(R.string.doctor_check_server_unreachable), true);
    }

    private void setSummaryTitle(int resId, int colorRes) {
        if (summaryTitle == null) return;
        summaryTitle.setTextColor(activity.getColor(colorRes));
        String next = activity.getString(resId);
        if (!next.contentEquals(summaryTitle.getText())) {
            summaryTitle.setText(next);
        }
    }

    /** Return the latest redacted diagnostic report, or null if not finished yet. */
    public DoctorCheck.Report latestReport() {
        return latestReport;
    }

    /** Refresh the live device-permissions summary card (e.g. on Activity resume from Android Settings). */
    public void refresh() {
        if (closed) return;
        if (permissionsSummary != null) {
            permissionsSummary.setText(PermissionsPanel.summaryLine(activity));
        }
    }

    /** Stop background probes and prevent stale UI callbacks. */
    public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        runner.shutdownNow();
        doctor.close();
    }

    private void buildTechnicalSection(LinearLayout body) {
        technicalToggle = Ui.button(activity, activity.getString(R.string.doctor_technical_show), v -> {
            technicalExpanded = !technicalExpanded;
            technicalBox.setVisibility(technicalExpanded ? View.VISIBLE : View.GONE);
            technicalToggle.setActivated(technicalExpanded);
            technicalToggle.setText(technicalExpanded
                    ? R.string.doctor_technical_hide
                    : R.string.doctor_technical_show);
        });
        technicalToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_prompt_disclosure, 0, 0, 0);
        technicalToggle.setCompoundDrawablePadding(Ui.dp(activity, 8));
        technicalToggle.setCompoundDrawableTintList(ColorStateList.valueOf(activity.getColor(R.color.accent)));
        technicalToggle.setActivated(false);
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(-2, -2);
        toggleLp.topMargin = Ui.dp(activity, 14);
        body.addView(technicalToggle, toggleLp);

        technicalBox = Ui.tonalCard(activity);
        technicalBox.setVisibility(View.GONE);

        TextView privacyNote = Ui.text(activity, activity.getString(R.string.doctor_technical_privacy_note), 13);
        privacyNote.setTextColor(activity.getColor(R.color.muted));
        technicalBox.addView(privacyNote);

        technicalHost = Ui.text(activity, "", 14);
        technicalHost.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);
        technicalBox.addView(technicalHost);

        technicalVersion = Ui.text(activity, "", 14);
        technicalVersion.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        technicalBox.addView(technicalVersion);

        technicalLanguage = Ui.text(activity, "", 14);
        technicalLanguage.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        technicalBox.addView(technicalLanguage);

        technicalReportPreview = Ui.text(activity, "", 12);
        technicalReportPreview.setTypeface(Typeface.MONOSPACE);
        technicalReportPreview.setTextColor(activity.getColor(R.color.muted));
        technicalReportPreview.setPaddingRelative(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 10));
        technicalBox.addView(technicalReportPreview);

        copyReportButton = Ui.outlinedButton(activity, activity.getString(R.string.doctor_copy_report_action),
                v -> copySafeReport());
        technicalBox.addView(copyReportButton, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-1, -2);
        boxLp.topMargin = Ui.dp(activity, 8);
        body.addView(technicalBox, boxLp);
    }

    private void renderReport(DoctorCheck.Report report) {
        DoctorCheck.Status overall = report.overallStatus();
        int summaryRes = switch (overall) {
            case OK -> R.string.doctor_status_all_ok;
            case WARN -> R.string.doctor_status_attention;
            case ERROR -> R.string.doctor_status_problem;
        };
        setSummaryTitle(summaryRes, statusInkColorRes(overall));

        summaryCounts.setText(activity.getString(R.string.doctor_summary_counts,
                report.count(DoctorCheck.Status.OK),
                report.count(DoctorCheck.Status.WARN),
                report.count(DoctorCheck.Status.ERROR)));
        summaryCounts.setVisibility(View.VISIBLE);

        if (permissionsSummary != null) {
            permissionsSummary.setText(PermissionsPanel.summaryLine(activity));
        }
        cardsContainer.removeAllViews();
        for (DoctorCheck.Item item : report.items()) {
            LinearLayout card = buildCheckCard(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(activity, 10);
            cardsContainer.addView(card, lp);
        }

        technicalHost.setText(activity.getString(R.string.doctor_technical_host, report.sanitizedHost()));
        technicalVersion.setText(activity.getString(R.string.doctor_technical_version, report.appVersion()));
        technicalLanguage.setText(activity.getString(R.string.doctor_technical_language, report.languageTag()));
        technicalReportPreview.setText(report.toSafeReportText());
    }

    private LinearLayout buildCheckCard(DoctorCheck.Item item) {
        LinearLayout card = Ui.card(activity);

        TextView badge = Ui.pill(activity, badgeText(item.status()),
                statusSurfaceColorRes(item.status()), statusInkColorRes(item.status()));
        card.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView title = Ui.text(activity, checkTitle(item.id()), 16);
        title.setTypeface(Ui.mediumTypeface());
        title.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);
        card.addView(title);

        TextView detail = Ui.text(activity, checkDescription(item), 14);
        detail.setTextColor(activity.getColor(R.color.muted));
        detail.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        card.addView(detail);

        if (item.fixAction() != DoctorCheck.FixAction.NONE) {
            Button fix = Ui.tonalButton(activity, fixLabel(item.fixAction()), v -> dispatchFix(item.fixAction()));
            LinearLayout.LayoutParams fixLp = new LinearLayout.LayoutParams(-2, -2);
            fixLp.topMargin = Ui.dp(activity, 10);
            card.addView(fix, fixLp);
        }

        return card;
    }

    private void dispatchFix(DoctorCheck.FixAction action) {
        if (closed || action == null) return;
        Diagnostics.event("doctor", "fix_" + action.name().toLowerCase(java.util.Locale.ROOT));
        switch (action) {
            case RETRY -> runChecks();
            case OPEN_PUSH -> fixHandlers.openPushSettings();
            case OPEN_UPDATES -> fixHandlers.openAppUpdates();
            case OPEN_AMBIENT -> fixHandlers.openAmbientAccess();
            case OPEN_LANGUAGE -> fixHandlers.openLanguageSettings();
            case NONE -> {}
        }
    }

    private void copySafeReport() {
        if (closed || latestReport == null) return;
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            showFeedback(activity.getString(R.string.doctor_report_copy_failed), true);
            return;
        }
        try {
            String safeText = latestReport.toSafeReportText();
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    activity.getString(R.string.doctor_panel_title), safeText));
            Diagnostics.event("doctor", "safe_report_copied");
            showFeedback(activity.getString(R.string.doctor_report_copied), false);
        } catch (RuntimeException failed) {
            Diagnostics.warning("doctor", "safe_report_copy_failed");
            showFeedback(activity.getString(R.string.doctor_report_copy_failed), true);
        }
    }

    private void showFeedback(String message, boolean error) {
        if (feedback == null) return;
        feedback.setText(message);
        feedback.setTextColor(activity.getColor(error ? R.color.danger : R.color.accent));
        feedback.setVisibility(View.VISIBLE);
    }

    private static int statusInkColorRes(DoctorCheck.Status status) {
        return switch (status) {
            case OK -> R.color.accent;
            case WARN -> R.color.warning;
            case ERROR -> R.color.danger;
        };
    }

    private static int statusSurfaceColorRes(DoctorCheck.Status status) {
        return switch (status) {
            case OK -> R.color.accent_surface;
            case WARN -> R.color.warning_surface;
            case ERROR -> R.color.danger_surface;
        };
    }

    private String badgeText(DoctorCheck.Status status) {
        return switch (status) {
            case OK -> activity.getString(R.string.doctor_badge_ok);
            case WARN -> activity.getString(R.string.doctor_badge_warn);
            case ERROR -> activity.getString(R.string.doctor_badge_error);
        };
    }

    private String checkTitle(DoctorCheck.CheckId id) {
        return switch (id) {
            case SERVER -> activity.getString(R.string.doctor_check_server_title);
            case CAPABILITIES -> activity.getString(R.string.doctor_check_capabilities_title);
            case PUSH -> activity.getString(R.string.doctor_check_push_title);
            case UPDATES -> activity.getString(R.string.doctor_check_updates_title);
            case AMBIENT -> activity.getString(R.string.doctor_check_ambient_title);
            case LANGUAGE -> activity.getString(R.string.doctor_check_language_title);
        };
    }

    private String checkDescription(DoctorCheck.Item item) {
        return switch (item.detailCode()) {
            case SERVER_OK -> activity.getString(R.string.doctor_check_server_ok, item.latencyMs());
            case SERVER_SLOW -> activity.getString(R.string.doctor_check_server_slow, item.latencyMs());
            case SERVER_AUTH_ERROR -> activity.getString(R.string.doctor_check_server_auth_error);
            case SERVER_UNREACHABLE -> activity.getString(R.string.doctor_check_server_unreachable);
            case SERVER_MISSING -> activity.getString(R.string.doctor_check_server_missing);
            case CAPABILITIES_FULL -> activity.getString(
                    R.string.doctor_check_capabilities_ok, item.capabilityCount(), item.expectedCapabilities());
            case CAPABILITIES_PARTIAL -> activity.getString(
                    R.string.doctor_check_capabilities_partial, item.capabilityCount(), item.expectedCapabilities());
            case CAPABILITIES_UPSTREAM -> activity.getString(
                    R.string.doctor_check_capabilities_upstream, item.expectedCapabilities());
            case CAPABILITIES_ERROR -> activity.getString(R.string.doctor_check_capabilities_error);
            case PUSH_READY -> activity.getString(R.string.doctor_check_push_ok);
            case PUSH_DISABLED -> activity.getString(R.string.doctor_check_push_disabled);
            case PUSH_BLOCKED -> activity.getString(R.string.doctor_check_push_blocked);
            case UPDATES_OK -> activity.getString(R.string.doctor_check_updates_ok, item.value());
            case UPDATES_AVAILABLE -> activity.getString(R.string.doctor_check_updates_available, item.value());
            case UPDATES_UNCHECKED -> activity.getString(R.string.doctor_check_updates_unchecked, item.value());
            case AMBIENT_CONNECTED -> activity.getString(R.string.doctor_check_ambient_ok);
            case AMBIENT_WAITING -> activity.getString(R.string.doctor_check_ambient_waiting);
            case AMBIENT_OFF -> activity.getString(R.string.doctor_check_ambient_off);
            case LANGUAGE_SYNCED -> activity.getString(R.string.doctor_check_language_ok, item.value());
            case LANGUAGE_MISMATCH -> activity.getString(R.string.doctor_check_language_mismatch, item.value());
        };
    }

    private String fixLabel(DoctorCheck.FixAction action) {
        return switch (action) {
            case RETRY -> activity.getString(R.string.doctor_fix_retry);
            case OPEN_PUSH -> activity.getString(R.string.doctor_fix_push);
            case OPEN_UPDATES -> activity.getString(R.string.doctor_fix_updates);
            case OPEN_AMBIENT -> activity.getString(R.string.doctor_fix_ambient);
            case OPEN_LANGUAGE -> activity.getString(R.string.doctor_fix_language);
            case NONE -> "";
        };
    }

    private void postUi(Runnable task) {
        main.post(() -> {
            if (!closed && !activity.isFinishing() && !activity.isDestroyed()) {
                task.run();
            }
        });
    }
}
