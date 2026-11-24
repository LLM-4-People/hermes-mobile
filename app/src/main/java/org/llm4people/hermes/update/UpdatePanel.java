package org.llm4people.hermes.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.os.PermissionCenter;
import org.llm4people.hermes.thread.SafeLinks;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native bilingual GitHub release update panel mounted under Settings ({@link #ROUTE_ID}).
 * Checks GitHub Releases without transmitting profile data, displays release notes, APK size,
 * and verified SHA-256 status, downloads and verifies checksum + signing certificate off the UI
 * thread, and hands off to the native Android package installer.
 */
public final class UpdatePanel {
    public static final String ROUTE_ID = "app_updates";

    private final Activity activity;
    private final HermesConfig config;
    private final GithubReleaseClient client;
    private final UpdateCheckCoordinator coordinator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-update-panel");
        t.setDaemon(true);
        return t;
    });

    private TextView statusPill;
    private TextView statusTitle;
    private TextView statusMeta;
    private TextView checksumBadge;
    private TextView notesHeading;
    private LinearLayout notesCard;
    private TextView notesBody;
    private TextView feedback;
    private ProgressBar progressBar;
    private Button checkButton;
    private Button installButton;
    private Button cancelButton;
    private Button permissionButton;
    private Button githubButton;
    private AlertDialog activeDialog;

    private GithubReleaseClient.ReleaseInfo latestRelease;
    private File verifiedApkFile;
    private AtomicBoolean activeCancel;
    private boolean busy;
    private boolean closed;

    public UpdatePanel(Activity activity) {
        this(activity, HermesConfig.load(activity), null);
    }

    UpdatePanel(Activity activity, HermesConfig config, GithubReleaseClient client) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.config = Objects.requireNonNull(config, "config");
        this.client = client != null ? client : new GithubReleaseClient(this.config);
        this.coordinator = new UpdateCheckCoordinator(activity);
    }

    /** Register this panel in {@link RouteRegistry} under {@link RouteRegistry.Section#SETTINGS}. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Context context, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        return registry.register(new RouteRegistry.Destination(
                ROUTE_ID,
                RouteRegistry.Section.SETTINGS,
                context.getString(R.string.update_route_label),
                context.getString(R.string.update_route_hint),
                0,
                false,
                null,
                open));
    }

    /** Format the non-intrusive Settings badge text when a newer signed release is available, or null. */
    public static String badgeLabel(Context context, UpdateCheckCoordinator.BadgeState state) {
        if (context == null || state == null || !state.updateAvailable() || state.latestTag().isEmpty()) {
            return null;
        }
        return context.getString(R.string.update_badge_available, state.latestTag());
    }

    /** Read the installed app version name safely across Android API 28-36, or null if unavailable. */
    @SuppressWarnings("deprecation")
    public static String installedVersionName(Context context) {
        if (context == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = Build.VERSION.SDK_INT >= 33
                    ? pm.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0))
                    : pm.getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null || info.versionName.trim().isEmpty() ? null : info.versionName.trim();
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return null;
        }
    }

    /** Build the scrollable paper/ink/teal TalkBack-accessible update view. */
    public View buildView() {
        LinearLayout body = Ui.column(activity);
        ScrollView scroll = Ui.centeredScroll(activity, body, AdaptiveLayout.READING_COLUMN_MAX_DP, 20, 16, 24);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));

        TextView subtitle = Ui.text(activity, activity.getString(R.string.update_panel_subtitle), 15);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        subtitle.setPaddingRelative(0, 0, 0, Ui.dp(activity, 12));
        body.addView(subtitle);

        String installedDisplay = installedDisplayVersion();
        TextView installedLabel = Ui.text(activity,
                activity.getString(R.string.update_installed_version, installedDisplay), 15);
        installedLabel.setTypeface(Ui.mediumTypeface());
        installedLabel.setPaddingRelative(0, 0, 0, Ui.dp(activity, 14));
        body.addView(installedLabel);

        LinearLayout card = Ui.card(activity);

        statusPill = Ui.pill(activity, "", R.color.surface_container, R.color.muted);
        statusPill.setEllipsize(TextUtils.TruncateAt.END);
        statusPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusPillLp = new LinearLayout.LayoutParams(-2, -2);
        statusPillLp.bottomMargin = Ui.dp(activity, 8);
        card.addView(statusPill, statusPillLp);

        statusTitle = Ui.text(activity, activity.getString(R.string.update_status_unchecked), 17);
        statusTitle.setTypeface(Ui.mediumTypeface());
        statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(statusTitle);

        statusMeta = Ui.text(activity, "", 14);
        statusMeta.setTextColor(activity.getColor(R.color.muted));
        statusMeta.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        statusMeta.setVisibility(View.GONE);
        card.addView(statusMeta);

        checksumBadge = Ui.pill(activity, activity.getString(R.string.update_checksum_badge),
                R.color.accent_surface, R.color.accent);
        checksumBadge.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.topMargin = Ui.dp(activity, 8);
        checksumBadge.setVisibility(View.GONE);
        card.addView(checksumBadge, badgeLp);

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, -2);
        progressLp.topMargin = Ui.dp(activity, 10);
        card.addView(progressBar, progressLp);

        feedback = Ui.text(activity, "", 14);
        feedback.setTextColor(activity.getColor(R.color.muted));
        feedback.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        card.addView(feedback);

        int gap = Ui.dp(activity, 8);

        installButton = Ui.filledButton(activity, activity.getString(R.string.update_download_install_action),
                v -> downloadOrInstall());
        installButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(-1, -2);
        installLp.topMargin = Ui.dp(activity, 12);
        card.addView(installButton, installLp);

        permissionButton = Ui.outlinedButton(activity, activity.getString(R.string.perm_action_open_install_settings),
                v -> openInstallPermissionSettings());
        permissionButton.setTag("update:open_install_permission");
        permissionButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(-2, -2);
        permLp.topMargin = gap;
        card.addView(permissionButton, permLp);

        LinearLayout actions = Ui.row(activity);
        checkButton = Ui.tonalButton(activity, activity.getString(R.string.update_check_action), v -> checkNow());
        actions.addView(checkButton);

        cancelButton = Ui.outlinedButton(activity, activity.getString(R.string.update_cancel_action),
                v -> cancelActiveDownload());
        cancelButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-2, -2);
        cancelLp.setMarginStart(gap);
        actions.addView(cancelButton, cancelLp);

        HorizontalScrollView actionsScroll = new HorizontalScrollView(activity);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.addView(actions, new HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = Ui.dp(activity, 10);
        card.addView(actionsScroll, actionsLp);

        githubButton = Ui.button(activity, activity.getString(R.string.update_open_github_action),
                v -> openOnGitHub());
        LinearLayout.LayoutParams githubLp = new LinearLayout.LayoutParams(-2, -2);
        githubLp.topMargin = gap;
        card.addView(githubButton, githubLp);

        body.addView(card, new LinearLayout.LayoutParams(-1, -2));

        notesHeading = Ui.text(activity, activity.getString(R.string.update_notes_heading), 17);
        notesHeading.setTypeface(Ui.mediumTypeface());
        notesHeading.setAccessibilityHeading(true);
        notesHeading.setPaddingRelative(0, Ui.dp(activity, 18), 0, Ui.dp(activity, 8));
        notesHeading.setVisibility(View.GONE);
        body.addView(notesHeading);

        notesCard = Ui.tonalCard(activity);
        notesCard.setVisibility(View.GONE);
        notesBody = Ui.text(activity, "", 14);
        notesBody.setTextColor(activity.getColor(R.color.ink));
        notesBody.setLineSpacing(0f, 1.25f);
        notesBody.setTextIsSelectable(true);
        notesCard.addView(notesBody, new LinearLayout.LayoutParams(-1, -2));
        body.addView(notesCard, new LinearLayout.LayoutParams(-1, -2));

        return scroll;
    }

    /** Trigger a fresh check against GitHub Releases. */
    public void checkNow() {
        if (closed || busy) return;
        busy = true;
        updateActionButtons();
        showProgress(true, 0);
        showFeedback(activity.getString(R.string.update_checking), false);
        executor.execute(() -> {
            try {
                GithubReleaseClient.ReleaseInfo release = client.fetchLatestRelease();
                coordinator.recordManualCheck(System.currentTimeMillis(), release.tagName());
                postUi(() -> {
                    busy = false;
                    hideProgress();
                    latestRelease = release;
                    verifiedApkFile = null;
                    renderReleaseState(release);
                    updateActionButtons();
                });
            } catch (IOException | RuntimeException error) {
                Diagnostics.warning("update", "check_failed");
                boolean invalidRelease = error.getMessage() != null
                        && (error.getMessage().contains("Release") || error.getMessage().contains("checksum")
                        || error.getMessage().contains("signed") || error.getMessage().contains("Invalid"));
                postUi(() -> {
                    busy = false;
                    hideProgress();
                    showFeedback(activity.getString(invalidRelease
                            ? R.string.update_error_invalid_release
                            : R.string.update_error_network), true);
                    updateActionButtons();
                });
            }
        });
    }

    /** Refresh install button after user returns from Android's unknown-sources settings screen. */
    public void onResume() {
        if (closed || verifiedApkFile == null || !verifiedApkFile.isFile()) return;
        if (ApkUpdateInstaller.canRequestPackageInstalls(activity)) {
            if (permissionButton != null) permissionButton.setVisibility(View.GONE);
            installButton.setText(R.string.update_install_ready_action);
            installButton.setVisibility(View.VISIBLE);
            showFeedback(activity.getString(R.string.update_verified_ready), false);
        } else {
            showFeedback(activity.getString(R.string.perm_update_install_still_off), true);
            if (permissionButton != null) permissionButton.setVisibility(View.VISIBLE);
        }
    }

    public void dismissDialogs() {
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }

    public void close() {
        closed = true;
        cancelActiveDownload();
        dismissDialogs();
        executor.shutdownNow();
    }

    private String installedDisplayVersion() {
        String installed = installedVersionName(activity);
        return installed != null ? installed : activity.getString(R.string.update_version_unavailable);
    }

    private void renderReleaseState(GithubReleaseClient.ReleaseInfo release) {
        String installed = installedVersionName(activity);
        boolean newer = release.isNewerThan(installed);
        if (statusPill != null) {
            if (newer) {
                statusPill.setText(activity.getString(R.string.update_badge_available, release.tagName()));
                statusPill.setTextColor(activity.getColor(R.color.accent));
                statusPill.setBackground(Ui.shape(activity, 999, R.color.accent_surface, 0, 0));
            } else {
                statusPill.setText(R.string.doctor_badge_ok);
                statusPill.setTextColor(activity.getColor(R.color.muted));
                statusPill.setBackground(Ui.shape(activity, 999, R.color.surface_container, 0, 0));
            }
            statusPill.setVisibility(View.VISIBLE);
        }
        statusTitle.setText(newer
                ? activity.getString(R.string.update_available_title, release.tagName())
                : activity.getString(R.string.update_up_to_date_title, installedDisplayVersion()));

        StringBuilder meta = new StringBuilder();
        if (!release.publishedAt().isEmpty()) {
            String date = release.publishedAt().length() >= 10
                    ? release.publishedAt().substring(0, 10) : release.publishedAt();
            meta.append(activity.getString(R.string.update_published_date, date));
        }
        if (release.apkSizeBytes() > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(activity.getString(R.string.update_package_size, formatBytes(activity, release.apkSizeBytes())));
        }
        if (meta.length() > 0) {
            statusMeta.setText(meta.toString());
            statusMeta.setVisibility(View.VISIBLE);
        } else {
            statusMeta.setVisibility(View.GONE);
        }
        checksumBadge.setVisibility(View.VISIBLE);
        feedback.setVisibility(View.GONE);

        notesHeading.setVisibility(View.VISIBLE);
        if (notesCard != null) notesCard.setVisibility(View.VISIBLE);
        notesBody.setText(release.notes().isEmpty()
                ? activity.getString(R.string.update_notes_empty)
                : release.notes());
    }

    private void downloadOrInstall() {
        if (closed || busy || latestRelease == null) return;
        if (verifiedApkFile != null && verifiedApkFile.isFile()) {
            triggerInstall(verifiedApkFile);
            return;
        }
        GithubReleaseClient.ReleaseInfo target = latestRelease;
        AtomicBoolean cancelled = new AtomicBoolean(false);
        activeCancel = cancelled;
        busy = true;
        updateActionButtons();
        showProgress(false, 0);
        showFeedback(activity.getString(R.string.update_downloading, 0), false);

        executor.execute(() -> {
            try {
                File apk = ApkUpdateInstaller.downloadAndVerifyForInstall(
                        activity.getApplicationContext(),
                        client,
                        target,
                        config,
                        (pct, downloaded, total) -> postUi(() -> {
                            if (pct < 0) {
                                progressBar.setIndeterminate(true);
                                showFeedback(activity.getString(R.string.update_downloading_indeterminate), false, false);
                            } else {
                                progressBar.setIndeterminate(false);
                                progressBar.setProgress(pct);
                                if (pct >= 100) {
                                    showFeedback(activity.getString(R.string.update_verifying), false, true);
                                } else {
                                    showFeedback(activity.getString(R.string.update_downloading, pct), false, false);
                                }
                            }
                        }),
                        cancelled);
                postUi(() -> {
                    busy = false;
                    activeCancel = null;
                    verifiedApkFile = apk;
                    hideProgress();
                    showFeedback(activity.getString(R.string.update_verified_ready), false);
                    updateActionButtons();
                    triggerInstall(apk);
                });
            } catch (ApkUpdateInstaller.VerificationException verifyErr) {
                int msgRes = switch (verifyErr.reason) {
                    case CANCELLED -> R.string.update_cancelled;
                    case TOO_LARGE -> R.string.update_error_too_large;
                    case CHECKSUM_MISMATCH -> R.string.update_error_checksum;
                    case PACKAGE_MISMATCH -> R.string.update_error_package;
                    case SIGNATURE_MISMATCH -> R.string.update_error_signature;
                };
                boolean isError = verifyErr.reason != ApkUpdateInstaller.VerificationException.Reason.CANCELLED;
                postUi(() -> {
                    busy = false;
                    activeCancel = null;
                    verifiedApkFile = null;
                    hideProgress();
                    showFeedback(activity.getString(msgRes), isError);
                    updateActionButtons();
                });
            } catch (IOException | RuntimeException ioErr) {
                Diagnostics.warning("update", "download_failed");
                postUi(() -> {
                    busy = false;
                    activeCancel = null;
                    verifiedApkFile = null;
                    hideProgress();
                    showFeedback(activity.getString(R.string.update_error_network), true);
                    updateActionButtons();
                });
            }
        });
    }

    private void triggerInstall(File apk) {
        try {
            ApkUpdateInstaller.InstallAction action = ApkUpdateInstaller.launchInstall(activity, apk);
            if (action == ApkUpdateInstaller.InstallAction.NEEDS_UNKNOWN_SOURCES_PERMISSION) {
                showFeedback(activity.getString(R.string.perm_update_install_still_off), true);
                if (permissionButton != null) permissionButton.setVisibility(View.VISIBLE);
                dismissDialogs();
                activeDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                        .setTitle(R.string.update_permission_title)
                        .setMessage(R.string.update_permission_body)
                        .setNegativeButton(R.string.update_cancel_action, null)
                        .setPositiveButton(R.string.update_permission_open_settings,
                                (d, w) -> openInstallPermissionSettings()));
            } else if (permissionButton != null) {
                permissionButton.setVisibility(View.GONE);
            }
        } catch (IOException | RuntimeException error) {
            Diagnostics.warning("update", "install_launch_failed");
            showFeedback(activity.getString(R.string.update_error_install_launch), true);
        }
    }

    private void openInstallPermissionSettings() {
        if (!PermissionCenter.openUnknownSourcesSettings(activity)) {
            showFeedback(activity.getString(R.string.perm_settings_unavailable), true);
        }
    }

    private void cancelActiveDownload() {
        AtomicBoolean cancel = activeCancel;
        if (cancel != null) cancel.set(true);
    }

    private void openOnGitHub() {
        String raw = latestRelease != null && !latestRelease.htmlUrl().isEmpty()
                ? latestRelease.htmlUrl()
                : config.githubWebBaseUrl + "/" + config.updateRepo + "/releases/latest";
        SafeLinks.Validated safe = SafeLinks.validate(raw);
        if (safe == null || !GithubReleaseClient.isAllowedDownloadUrl(safe.url())) {
            Diagnostics.warning("update", "github_link_rejected");
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safe.url())));
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("update", "browser_unavailable");
        }
    }

    private void updateActionButtons() {
        checkButton.setEnabled(!busy);
        cancelButton.setVisibility(busy && activeCancel != null ? View.VISIBLE : View.GONE);
        String installed = installedVersionName(activity);
        boolean canInstall = !busy && latestRelease != null && latestRelease.isNewerThan(installed);
        installButton.setVisibility(canInstall ? View.VISIBLE : View.GONE);
        if (canInstall) {
            installButton.setText(verifiedApkFile != null && verifiedApkFile.isFile()
                    ? R.string.update_install_ready_action
                    : R.string.update_download_install_action);
        }
    }

    private void showProgress(boolean indeterminate, int percent) {
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) progressBar.setProgress(percent);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        progressBar.setVisibility(View.GONE);
    }

    private void showFeedback(String message, boolean error) {
        showFeedback(message, error, true);
    }

    private void showFeedback(String message, boolean error, boolean announce) {
        String next = message == null ? "" : message;
        int desiredLiveRegion = announce
                ? View.ACCESSIBILITY_LIVE_REGION_POLITE
                : View.ACCESSIBILITY_LIVE_REGION_NONE;
        if (feedback.getAccessibilityLiveRegion() != desiredLiveRegion) {
            feedback.setAccessibilityLiveRegion(desiredLiveRegion);
        }
        int desiredColor = activity.getColor(error ? R.color.danger : R.color.muted);
        if (feedback.getCurrentTextColor() != desiredColor) {
            feedback.setTextColor(desiredColor);
        }
        boolean wasVisible = feedback.getVisibility() == View.VISIBLE;
        if (!next.contentEquals(feedback.getText())) {
            feedback.setText(next);
        }
        if (!wasVisible) {
            feedback.setVisibility(View.VISIBLE);
        }
    }

    private void postUi(Runnable task) {
        main.post(() -> {
            if (!closed && !activity.isFinishing() && !activity.isDestroyed()) {
                task.run();
            }
        });
    }

    static String formatBytes(Context context, long bytes) {
        Context localized = AppLanguage.wrapContext(context);
        return formatBytes(
                bytes,
                AppLanguage.resolvedLocale(localized),
                localized.getString(R.string.update_size_bytes),
                localized.getString(R.string.update_size_kb),
                localized.getString(R.string.update_size_mb));
    }

    static String formatBytes(long bytes, Locale locale, String bytesTemplate, String kbTemplate, String mbTemplate) {
        long clamped = Math.max(0L, bytes);
        Locale effective = locale == null ? Locale.getDefault() : locale;
        if (clamped < 1024L) {
            NumberFormat nf = NumberFormat.getIntegerInstance(effective);
            return String.format(effective, bytesTemplate, nf.format(clamped));
        }
        NumberFormat nf = NumberFormat.getNumberInstance(effective);
        nf.setMinimumFractionDigits(1);
        nf.setMaximumFractionDigits(1);
        if (clamped < 1024L * 1024L) {
            return String.format(effective, kbTemplate, nf.format(clamped / 1024.0));
        }
        return String.format(effective, mbTemplate, nf.format(clamped / (1024.0 * 1024.0)));
    }
}
