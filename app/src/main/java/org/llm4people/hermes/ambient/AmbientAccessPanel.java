package org.llm4people.hermes.ambient;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.os.PermissionCenter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Native opt-in panel for Android Ambient Intelligence:
 * - Guided 2-step Android 13-16 restricted-settings + notification-access onboarding
 * - Live connection/opt-in status badge
 * - Per-profile opt-in toggles (all OFF by default)
 * - Searchable per-app allowlist (automatically excluding hard-blocked sensitive apps)
 * - Live sanitized notification digest (1-tap Summarize / Note / Calendar / Reviewed Reply / Dismiss)
 * - Now Playing card with user-confirmed playback controls
 */
public final class AmbientAccessPanel {
    public static final String ROUTE_ID = "ambient_intelligence";
    public static final int REQUEST_READ_CALENDAR = 4201;

    /** Optional richer callbacks wiring 1-tap digest actions into Discussions, Smart Notes, and Calendar. */
    public interface ActionCallbacks {
        void stageDraft(String text);

        default void createNote(String title, String body, String fallbackDraft) {
            stageDraft(fallbackDraft);
        }

        default void addToCalendar(String title, String description, String fallbackDraft) {
            stageDraft(fallbackDraft);
        }
    }

    public record AppEntry(String packageName, String label) {}

    public enum StatusBadgeState {
        OFF,
        ACTIVE,
        WAITING
    }

    /** Pure-Java classifier for the Ambient live status pill across OFF, ACTIVE, and WAITING states. */
    public static StatusBadgeState resolveStatusBadge(
            boolean notificationsEnabled, boolean mediaEnabled, boolean listenerEnabled) {
        if (!notificationsEnabled && !mediaEnabled) return StatusBadgeState.OFF;
        return listenerEnabled ? StatusBadgeState.ACTIVE : StatusBadgeState.WAITING;
    }

    private final Activity activity;
    private final NotificationAllowlist allowlist;
    private final ActionCallbacks callbacks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService appLoader = createAppLoader();

    private static ExecutorService createAppLoader() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 10L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "hermes-ambient-apps");
            t.setDaemon(true);
            return t;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
    private List<AppEntry> installedApps;
    private Future<?> appLoadTask;
    private final AtomicInteger appLoadGeneration = new AtomicInteger();
    private boolean loadingApps;
    private volatile boolean closed;
    private LinearLayout allowlistContainer;
    private LinearLayout digestContainer;
    private LinearLayout mediaContainer;
    private TextView statusBadge;
    private TextView feedback;
    private Button feedbackAction;
    private Switch notificationsSwitch;
    private Switch mediaSwitch;
    private Switch calendarSwitch;
    private boolean binding;
    private PermissionCenter.Key pendingDenialKey;
    private AlertDialog activeDialog;
    private String searchFilter = "";
    private String lastAllowlistEmptyAnnouncement;

    /** Pure-Java fail-closed reconciliation of Ambient Calendar opt-in against live permission. */
    public static NotificationAllowlist.Settings reconcilePermissions(
            NotificationAllowlist.Settings settings,
            boolean hasCalendarPermission) {
        if (settings == null) {
            return NotificationAllowlist.Settings.ALL_OFF;
        }
        boolean cal = settings.calendarEnabled() && hasCalendarPermission;
        if (cal == settings.calendarEnabled()
                && !settings.contactsEnabled()
                && !settings.deviceContextEnabled()) {
            return settings;
        }
        return new NotificationAllowlist.Settings(
                settings.notificationsEnabled(),
                settings.mediaEnabled(),
                cal,
                false,
                false);
    }

    public AmbientAccessPanel(Activity activity, NotificationAllowlist allowlist, ActionCallbacks callbacks) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.callbacks = callbacks == null ? text -> {} : callbacks;
    }

    /** Register this panel in {@link RouteRegistry} under Settings. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        return registry.registerAction(ROUTE_ID, open);
    }

    /** Build the full scrollable native panel view for mounting in any activity/host container. */
    public View buildView() {
        LinearLayout body = Ui.column(activity);
        ScrollView scroll = Ui.centeredScroll(activity, body, AdaptiveLayout.READING_COLUMN_MAX_DP, 20, 16, 24);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));

        TextView intro = mutedNote(activity.getString(R.string.ambient_panel_intro));
        body.addView(intro);

        statusBadge = Ui.pill(activity, "", R.color.surface_container, R.color.muted);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.topMargin = Ui.dp(activity, 12);
        body.addView(statusBadge, badgeLp);

        feedback = Ui.text(activity, "", 14);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        addTop(body, feedback, 8);

        feedbackAction = Ui.tonalButton(activity, "", null);
        feedbackAction.setVisibility(View.GONE);
        LinearLayout.LayoutParams feedbackActionLp = new LinearLayout.LayoutParams(-2, -2);
        feedbackActionLp.topMargin = Ui.dp(activity, 6);
        body.addView(feedbackAction, feedbackActionLp);

        buildOnboardingSection(body);
        buildTogglesSection(body);
        buildAllowlistSection(body);
        buildMediaSection(body);
        buildDigestSection(body);

        refreshAll();
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                cancelAppLoad();
                dismissDialogs();
            }
        });
        return scroll;
    }

    /** Dismiss any active quick-reply or media confirmation dialog (e.g. on lock or profile switch). */
    public void dismissDialogs() {
        AlertDialog dialog = activeDialog;
        activeDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /** Cancel background app inventory loading and release dialogs cleanly. */
    public void close() {
        closed = true;
        cancelAppLoad();
        dismissDialogs();
        appLoader.shutdownNow();
    }

    private void cancelAppLoad() {
        appLoadGeneration.incrementAndGet();
        Future<?> task = appLoadTask;
        appLoadTask = null;
        if (task != null) task.cancel(true);
        loadingApps = false;
        main.removeCallbacksAndMessages(null);
    }

    /** Fail-closed read of settings when the underlying encrypted profile may already be closed. */
    static NotificationAllowlist.Settings readSettingsSafely(NotificationAllowlist allowlist) {
        if (allowlist == null) return null;
        try {
            return allowlist.settings();
        } catch (IllegalStateException closedStore) {
            return null;
        }
    }

    /** Fail-closed read of allowed packages when the underlying encrypted profile may already be closed. */
    static Set<String> readAllowedPackagesSafely(NotificationAllowlist allowlist) {
        if (allowlist == null) return null;
        try {
            return allowlist.allowedPackages();
        } catch (IllegalStateException closedStore) {
            return null;
        }
    }

    /** Fail-closed read of effective allowed packages when the underlying encrypted profile may already be closed. */
    static Set<String> readEffectiveAllowedPackagesSafely(NotificationAllowlist allowlist) {
        if (allowlist == null) return null;
        try {
            return allowlist.effectiveAllowedPackages();
        } catch (IllegalStateException closedStore) {
            return null;
        }
    }

    /** Fail-closed persistence when the underlying encrypted profile may already be closed. */
    static boolean saveSettingsSafely(NotificationAllowlist allowlist, NotificationAllowlist.Settings next) {
        if (allowlist == null || next == null) return false;
        try {
            allowlist.saveSettings(next);
            return true;
        } catch (IllegalStateException closedStore) {
            return false;
        }
    }

    private void onStoreClosed() {
        if (!closed) {
            Diagnostics.debug("ambient", "panel_store_closed");
            close();
        }
    }

    private void trackDialog(AlertDialog dialog) {
        dismissDialogs();
        activeDialog = dialog;
        if (dialog != null) {
            dialog.setOnDismissListener(d -> {
                if (activeDialog == dialog) activeDialog = null;
            });
        }
    }

    /** Refresh status badge, toggles, media card, and live notification digest (e.g. on Activity resume). */
    public void refreshAll() {
        if (closed) return;
        NotificationAllowlist.Settings saved = readSettingsSafely(allowlist);
        if (saved == null) {
            onStoreClosed();
            return;
        }
        NotificationAllowlist.Settings current = reconcilePermissions(
                saved,
                CalendarContextReader.hasPermission(activity));
        if (!current.equals(saved) && !saveSettingsSafely(allowlist, current)) {
            onStoreClosed();
            return;
        }
        if (pendingDenialKey != null
                && PermissionCenter.state(activity, pendingDenialKey) == PermissionCenter.State.GRANTED) {
            clearFeedback();
        }
        syncSwitches(current);
        Set<String> effective = readEffectiveAllowedPackagesSafely(allowlist);
        if (effective == null) {
            onStoreClosed();
            return;
        }
        HermesNotificationListener.bindProfile(effective);
        updateStatusBadge(current);
        renderAllowlist();
        renderMedia(current);
        renderDigest();
    }

    private void syncSwitches(NotificationAllowlist.Settings settings) {
        binding = true;
        try {
            if (notificationsSwitch != null) notificationsSwitch.setChecked(settings.notificationsEnabled());
            if (mediaSwitch != null) mediaSwitch.setChecked(settings.mediaEnabled());
            if (calendarSwitch != null) calendarSwitch.setChecked(settings.calendarEnabled());
        } finally {
            binding = false;
        }
    }

    /** Handle Calendar runtime permission outcomes with fail-closed persistence and 1-tap recovery. */
    public void onPermissionResult(int requestCode, int[] results) {
        if (closed || requestCode != REQUEST_READ_CALENDAR) return;
        PermissionCenter.recordCompletedPrompt(activity, PermissionCenter.Key.CALENDAR, results);
        boolean granted = PermissionCenter.isCompletedResult(results)
                && results[0] == PackageManager.PERMISSION_GRANTED
                && CalendarContextReader.hasPermission(activity);
        saveCalendarSetting(granted);
        if (closed) return;
        handleRuntimePermissionOutcome(PermissionCenter.Key.CALENDAR, Manifest.permission.READ_CALENDAR,
                REQUEST_READ_CALENDAR, R.string.perm_ambient_calendar_denied, granted);
        refreshAll();
    }

    private void saveCalendarSetting(boolean enabled) {
        if (closed) return;
        NotificationAllowlist.Settings cur = readSettingsSafely(allowlist);
        if (cur == null) {
            onStoreClosed();
            return;
        }
        if (cur.calendarEnabled() == enabled && !cur.contactsEnabled() && !cur.deviceContextEnabled()) return;
        if (!saveSettingsSafely(allowlist, new NotificationAllowlist.Settings(
                cur.notificationsEnabled(), cur.mediaEnabled(), enabled, false, false))) {
            onStoreClosed();
        }
    }

    private void handleRuntimePermissionOutcome(
            PermissionCenter.Key key, String permission, int requestCode, int deniedMsgRes, boolean granted) {
        if (granted) {
            clearFeedback();
            return;
        }
        PermissionCenter.State st = PermissionCenter.state(activity, key);
        if (st == PermissionCenter.State.PERMANENTLY_DENIED) {
            showFeedbackWithAction(key, deniedMsgRes, true, R.string.perm_action_open_settings, () -> {
                if (!PermissionCenter.openAppSettings(activity)) {
                    showFeedback(R.string.ambient_settings_unavailable, true);
                }
            });
        } else {
            showFeedbackWithAction(key, deniedMsgRes, true, R.string.perm_action_allow, () ->
                    activity.requestPermissions(new String[]{permission}, requestCode));
        }
    }

    /** Filter candidate installed apps by search text while excluding hard-blocked packages. */
    public static List<AppEntry> filterSelectableApps(List<AppEntry> all, String query) {
        if (all == null || all.isEmpty()) return List.of();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry entry : all) {
            if (entry == null || NotificationPrivacyGuard.isHardBlockedPackage(entry.packageName())) continue;
            if (needle.isEmpty()
                    || entry.label().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.packageName().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(entry);
            }
        }
        out.sort(Comparator.comparing(AppEntry::label, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void buildOnboardingSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_onboarding_title, R.string.ambient_onboarding_detail);
        LinearLayout card = Ui.tonalCard(activity);
        Button step1 = Ui.tonalButton(activity, activity.getString(R.string.ambient_step_restricted), v -> {
            if (!PermissionCenter.openAppSettings(activity)) showFeedback(R.string.ambient_settings_unavailable, true);
        });
        step1.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        card.addView(step1, new LinearLayout.LayoutParams(-1, -2));

        Button step2 = Ui.outlinedButton(activity, activity.getString(R.string.ambient_step_listener), v -> {
            if (!PermissionCenter.openNotificationListenerSettings(activity)) showFeedback(R.string.ambient_settings_unavailable, true);
        });
        step2.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams step2Lp = new LinearLayout.LayoutParams(-1, -2);
        step2Lp.topMargin = Ui.dp(activity, 8);
        card.addView(step2, step2Lp);

        addTop(body, card, 10);
    }

    private void buildTogglesSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_section_toggles, 0);
        NotificationAllowlist.Settings initial = readSettingsSafely(allowlist);
        if (initial == null) {
            onStoreClosed();
            initial = NotificationAllowlist.Settings.ALL_OFF;
        }
        LinearLayout card = Ui.card(activity);

        notificationsSwitch = addSwitch(card, false, R.string.ambient_toggle_notifications, initial.notificationsEnabled(), checked -> {
            if (!checked) dismissDialogs();
            NotificationAllowlist.Settings cur = readSettingsSafely(allowlist);
            if (cur == null || !saveSettingsSafely(allowlist, new NotificationAllowlist.Settings(
                    checked, cur.mediaEnabled(), cur.calendarEnabled(), false, false))) {
                onStoreClosed();
                return;
            }
            refreshAll();
        });
        mediaSwitch = addSwitch(card, true, R.string.ambient_toggle_media, initial.mediaEnabled(), checked -> {
            if (!checked) dismissDialogs();
            NotificationAllowlist.Settings cur = readSettingsSafely(allowlist);
            if (cur == null || !saveSettingsSafely(allowlist, new NotificationAllowlist.Settings(
                    cur.notificationsEnabled(), checked, cur.calendarEnabled(), false, false))) {
                onStoreClosed();
                return;
            }
            refreshAll();
        });
        calendarSwitch = addSwitch(card, true, R.string.ambient_toggle_calendar, initial.calendarEnabled(), checked -> {
            if (checked && !CalendarContextReader.hasPermission(activity)) {
                saveCalendarSetting(false);
                PermissionCenter.State st = PermissionCenter.state(activity, PermissionCenter.Key.CALENDAR);
                if (st == PermissionCenter.State.PERMANENTLY_DENIED) {
                    showFeedbackWithAction(PermissionCenter.Key.CALENDAR, R.string.perm_ambient_calendar_denied, true,
                            R.string.perm_action_open_settings, () -> {
                                if (!PermissionCenter.openAppSettings(activity)) {
                                    showFeedback(R.string.ambient_settings_unavailable, true);
                                }
                            });
                } else {
                    activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQUEST_READ_CALENDAR);
                    showFeedback(R.string.ambient_permission_needed, false);
                }
                refreshAll();
                return;
            }
            saveCalendarSetting(checked);
            if (checked) clearFeedback();
            refreshAll();
        });
        addTop(body, card, 10);
    }

    private void buildAllowlistSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_section_allowlist, R.string.ambient_allowlist_protected);
        EditText search = new EditText(activity);
        search.setHint(R.string.ambient_allowlist_hint);
        search.setSingleLine(true);
        search.setSaveEnabled(false);
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Ui.styleDialogInput(activity, search);
        Ui.pad(search, 12, 8);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                searchFilter = s == null ? "" : s.toString();
                renderAllowlist();
            }
        });
        addTop(body, search, 10);

        allowlistContainer = Ui.card(activity);
        addTop(body, allowlistContainer, 10);
    }

    private void buildMediaSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_section_media, 0);
        mediaContainer = Ui.column(activity);
        addTop(body, mediaContainer, 8);
    }

    private void buildDigestSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_section_digest, 0);
        digestContainer = Ui.column(activity);
        addTop(body, digestContainer, 8);
    }

    private void updateStatusBadge(NotificationAllowlist.Settings settings) {
        if (statusBadge == null) return;
        boolean listenerOn = HermesNotificationListener.isListenerEnabled(activity);
        StatusBadgeState badgeState = resolveStatusBadge(
                settings.notificationsEnabled(), settings.mediaEnabled(), listenerOn);
        int textRes;
        int bgRes;
        int inkRes;
        switch (badgeState) {
            case ACTIVE -> {
                textRes = R.string.ambient_status_active;
                bgRes = R.color.accent_surface;
                inkRes = R.color.accent;
            }
            case WAITING -> {
                textRes = R.string.ambient_status_waiting;
                bgRes = R.color.danger_surface;
                inkRes = R.color.danger;
            }
            default -> {
                textRes = R.string.ambient_status_off;
                bgRes = R.color.surface_container;
                inkRes = R.color.muted;
            }
        }
        statusBadge.setText(textRes);
        statusBadge.setTextColor(activity.getColor(inkRes));
        statusBadge.setBackground(Ui.shape(activity, 999, bgRes, 0, 0));
    }

    private void renderAllowlist() {
        if (closed || allowlistContainer == null) return;
        allowlistContainer.removeAllViews();
        if (installedApps == null) {
            lastAllowlistEmptyAnnouncement = null;
            allowlistContainer.addView(mutedNote(activity.getString(R.string.ambient_allowlist_loading)));
            startLoadLaunchableAppsIfNeeded();
            return;
        }
        List<AppEntry> visible = filterSelectableApps(installedApps, searchFilter);
        if (visible.isEmpty()) {
            TextView empty = mutedNote(activity.getString(R.string.ambient_allowlist_empty));
            String emptyKey = "allowlist_empty";
            if (!emptyKey.equals(lastAllowlistEmptyAnnouncement)) {
                empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                lastAllowlistEmptyAnnouncement = emptyKey;
            }
            allowlistContainer.addView(empty);
            return;
        }
        lastAllowlistEmptyAnnouncement = null;
        Set<String> allowed = readAllowedPackagesSafely(allowlist);
        if (allowed == null) {
            onStoreClosed();
            return;
        }
        int limit = Math.min(visible.size(), 40);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                View divider = Ui.insetDivider(activity, 16);
                LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
                divLp.topMargin = Ui.dp(activity, 4);
                divLp.bottomMargin = Ui.dp(activity, 4);
                allowlistContainer.addView(divider, divLp);
            }
            AppEntry app = visible.get(i);
            Switch toggle = new Switch(activity);
            toggle.setText(app.label());
            toggle.setTextColor(activity.getColor(R.color.ink));
            toggle.setMinHeight(Ui.dp(activity, 48));
            toggle.setChecked(allowed.contains(app.packageName()));
            toggle.setOnCheckedChangeListener((btn, checked) -> {
                if (closed || binding) return;
                try {
                    allowlist.setAllowed(app.packageName(), checked);
                    HermesNotificationListener.bindProfile(allowlist.effectiveAllowedPackages());
                    renderDigest();
                } catch (IllegalStateException closedStore) {
                    onStoreClosed();
                } catch (IllegalArgumentException ex) {
                    Diagnostics.warning("ambient", "allowlist_toggle_failed", ex);
                    binding = true;
                    try {
                        btn.setChecked(!checked);
                    } finally {
                        binding = false;
                    }
                }
            });
            allowlistContainer.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void startLoadLaunchableAppsIfNeeded() {
        if (closed || loadingApps || installedApps != null || appLoader.isShutdown()) return;
        loadingApps = true;
        int gen = appLoadGeneration.incrementAndGet();
        try {
            appLoadTask = appLoader.submit(() -> {
                List<AppEntry> loaded = loadLaunchableApps();
                if (Thread.currentThread().isInterrupted() || closed || gen != appLoadGeneration.get()) return;
                main.post(() -> {
                    if (gen != appLoadGeneration.get()) return;
                    loadingApps = false;
                    appLoadTask = null;
                    if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                    installedApps = loaded;
                    renderAllowlist();
                });
            });
        } catch (RejectedExecutionException rejected) {
            loadingApps = false;
        }
    }

    private void renderMedia(NotificationAllowlist.Settings settings) {
        if (mediaContainer == null) return;
        mediaContainer.removeAllViews();
        MediaContextBridge.NowPlaying current = MediaContextBridge.selectPrimarySession(
                MediaContextBridge.readActiveSessions(activity, settings.mediaEnabled()));
        if (current == null) {
            mediaContainer.addView(mutedNote(activity.getString(R.string.ambient_media_empty)));
            return;
        }
        LinearLayout card = Ui.card(activity);
        TextView track = Ui.text(activity, current.displayTrack(), 15);
        track.setTypeface(Ui.mediumTypeface());
        card.addView(track);

        int gap = Ui.dp(activity, 8);
        LinearLayout actions = Ui.row(activity);
        MediaContextBridge.ControlAction toggleAction =
                current.playbackKind() == MediaContextBridge.PlaybackKind.PLAYING
                        ? MediaContextBridge.ControlAction.PAUSE
                        : MediaContextBridge.ControlAction.PLAY;
        int toggleLabel = toggleAction == MediaContextBridge.ControlAction.PAUSE
                ? R.string.ambient_media_pause
                : R.string.ambient_media_play;
        actions.addView(Ui.outlinedButton(activity, activity.getString(R.string.ambient_media_previous),
                v -> confirmMediaControl(current, MediaContextBridge.ControlAction.PREVIOUS)));
        addRowButton(actions, Ui.tonalButton(activity, activity.getString(toggleLabel),
                v -> confirmMediaControl(current, toggleAction)), gap);
        addRowButton(actions, Ui.outlinedButton(activity, activity.getString(R.string.ambient_media_next),
                v -> confirmMediaControl(current, MediaContextBridge.ControlAction.NEXT)), gap);
        addTop(card, horizontalRow(actions), 10);

        Button addDraft = Ui.outlinedButton(activity, activity.getString(R.string.ambient_media_add_to_thread),
                v -> callbacks.stageDraft(activity.getString(
                        R.string.ambient_draft_media, current.title(), current.artist())));
        LinearLayout.LayoutParams draftLp = new LinearLayout.LayoutParams(-2, -2);
        draftLp.topMargin = Ui.dp(activity, 8);
        card.addView(addDraft, draftLp);
        mediaContainer.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderDigest() {
        if (digestContainer == null) return;
        digestContainer.removeAllViews();
        List<AmbientNotification> items = HermesNotificationListener.snapshot();
        if (items.isEmpty()) {
            digestContainer.addView(mutedNote(activity.getString(R.string.ambient_digest_empty)));
            return;
        }
        int gap = Ui.dp(activity, 8);
        for (AmbientNotification item : items) {
            LinearLayout card = Ui.card(activity);

            TextView header = Ui.text(activity, item.displaySource() + " · " + item.displaySender(), 14);
            header.setTypeface(Ui.mediumTypeface());
            card.addView(header);

            TextView content = Ui.text(activity, item.displayBody(), 14);
            content.setTextColor(activity.getColor(R.color.muted));
            content.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
            card.addView(content);

            LinearLayout row1 = Ui.row(activity);
            row1.addView(Ui.tonalButton(activity, activity.getString(R.string.ambient_action_summarize),
                    v -> callbacks.stageDraft(activity.getString(
                            R.string.ambient_draft_summarize, item.displaySource(), item.displaySender(), item.displayBody()))));
            addRowButton(row1, Ui.outlinedButton(activity, activity.getString(R.string.ambient_action_note),
                    v -> callbacks.createNote(
                            item.displaySource() + " · " + item.displaySender(),
                            item.displayBody(),
                            activity.getString(
                                    R.string.ambient_draft_note, item.displaySource(), item.displaySender(), item.displayBody()))), gap);
            addTop(card, horizontalRow(row1), 10);

            LinearLayout row2 = Ui.row(activity);
            row2.addView(Ui.outlinedButton(activity, activity.getString(R.string.ambient_action_calendar),
                    v -> callbacks.addToCalendar(
                            item.displaySource() + " · " + item.displaySender(),
                            item.displayBody(),
                            activity.getString(
                                    R.string.ambient_draft_calendar, item.displaySource(), item.displaySender(), item.displayBody()))));
            if (item.replyable) {
                addRowButton(row2, Ui.outlinedButton(activity, activity.getString(R.string.ambient_action_reply),
                        v -> confirmQuickReply(item)), gap);
            } else {
                addRowButton(row2, Ui.outlinedButton(activity, activity.getString(R.string.ambient_action_reply),
                        v -> callbacks.stageDraft(activity.getString(
                                R.string.ambient_draft_reply, item.displaySource(), item.displaySender(), item.displayBody()))), gap);
            }
            addRowButton(row2, Ui.button(activity, activity.getString(R.string.ambient_action_dismiss), v -> {
                HermesNotificationListener.dismiss(item.key);
                renderDigest();
            }), gap);
            addTop(card, horizontalRow(row2), 8);

            addTop(digestContainer, card, 10);
        }
    }

    private HorizontalScrollView horizontalRow(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private static void addRowButton(LinearLayout row, Button button, int gapPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (row.getChildCount() > 0) lp.setMarginStart(gapPx);
        row.addView(button, lp);
    }

    private void confirmMediaControl(MediaContextBridge.NowPlaying current, MediaContextBridge.ControlAction action) {
        if (!isMediaStillEnabled()) return;
        dismissDialogs();
        trackDialog(MediaContextBridge.showControlConfirmation(
                activity,
                this::isMediaStillEnabled,
                current,
                action,
                ok -> refreshAll()));
    }

    private boolean isMediaStillEnabled() {
        if (closed) return false;
        NotificationAllowlist.Settings settings = readSettingsSafely(allowlist);
        if (settings == null) {
            onStoreClosed();
            return false;
        }
        return settings.mediaEnabled();
    }

    private void confirmQuickReply(AmbientNotification item) {
        if (!isReplyStillAllowed(item)) return;
        dismissDialogs();
        trackDialog(HermesNotificationListener.showQuickReplyConfirmation(
                activity,
                item,
                "",
                () -> isReplyStillAllowed(item),
                outcome -> {
                    if (outcome == HermesNotificationListener.ReplyOutcome.SENT) {
                        showFeedback(R.string.ambient_reply_sent, false);
                        renderDigest();
                    } else if (outcome == HermesNotificationListener.ReplyOutcome.FAILED) {
                        showFeedback(R.string.ambient_reply_failed, true);
                        renderDigest();
                    }
                }));
    }

    private boolean isReplyStillAllowed(AmbientNotification item) {
        if (closed || item == null) return false;
        NotificationAllowlist.Settings settings = readSettingsSafely(allowlist);
        if (settings == null) {
            onStoreClosed();
            return false;
        }
        if (!settings.notificationsEnabled()) return false;
        Set<String> effective = readEffectiveAllowedPackagesSafely(allowlist);
        if (effective == null) {
            onStoreClosed();
            return false;
        }
        return effective.contains(item.packageName);
    }

    private List<AppEntry> loadLaunchableApps() {
        List<AppEntry> entries = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            PackageManager pm = activity.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
            for (ResolveInfo info : resolved) {
                if (info == null || info.activityInfo == null || info.activityInfo.packageName == null) continue;
                String pkg = info.activityInfo.packageName.toLowerCase(Locale.ROOT);
                if (!seen.add(pkg) || NotificationPrivacyGuard.isHardBlockedPackage(pkg)) continue;
                CharSequence label = info.loadLabel(pm);
                entries.add(new AppEntry(pkg, label == null || label.length() == 0 ? pkg : label.toString()));
            }
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "app_inventory_failed", ex);
        }
        return entries;
    }

    private void addSectionHeader(LinearLayout parent, int titleRes, int detailRes) {
        addTop(parent, Ui.divider(activity), 18);
        TextView heading = Ui.text(activity, activity.getString(titleRes), 18);
        heading.setTypeface(Ui.mediumTypeface());
        heading.setAccessibilityHeading(true);
        addTop(parent, heading, 14);
        if (detailRes != 0) addTop(parent, mutedNote(activity.getString(detailRes)), 4);
    }

    private Switch addSwitch(
            LinearLayout card, boolean withDivider, int labelRes, boolean initial, Consumer<Boolean> onToggle) {
        if (withDivider) {
            View divider = Ui.insetDivider(activity, 16);
            LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
            divLp.topMargin = Ui.dp(activity, 4);
            divLp.bottomMargin = Ui.dp(activity, 4);
            card.addView(divider, divLp);
        }
        Switch toggle = new Switch(activity);
        toggle.setText(labelRes);
        toggle.setTextColor(activity.getColor(R.color.ink));
        toggle.setMinHeight(Ui.dp(activity, 48));
        toggle.setChecked(initial);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!binding) onToggle.accept(isChecked);
        });
        card.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        return toggle;
    }

    private TextView mutedNote(String text) {
        TextView view = Ui.text(activity, text, 14);
        view.setTextColor(activity.getColor(R.color.muted));
        return view;
    }

    private void showFeedback(int messageRes, boolean error) {
        pendingDenialKey = null;
        if (feedback == null) return;
        feedback.setTextColor(activity.getColor(error ? R.color.danger : R.color.accent));
        Ui.announceLiveStatus(feedback, activity.getString(messageRes));
        if (feedbackAction != null) {
            feedbackAction.setVisibility(View.GONE);
            feedbackAction.setOnClickListener(null);
        }
    }

    private void showFeedbackWithAction(
            PermissionCenter.Key denialKey, int messageRes, boolean error, int actionRes, Runnable onClick) {
        showFeedback(messageRes, error);
        pendingDenialKey = denialKey;
        if (feedbackAction != null && onClick != null) {
            feedbackAction.setText(actionRes);
            feedbackAction.setOnClickListener(v -> onClick.run());
            feedbackAction.setVisibility(View.VISIBLE);
        }
    }

    private void clearFeedback() {
        pendingDenialKey = null;
        if (feedback != null) feedback.setVisibility(View.GONE);
        if (feedbackAction != null) {
            feedbackAction.setVisibility(View.GONE);
            feedbackAction.setOnClickListener(null);
        }
    }

    private void addTop(LinearLayout parent, View child, int topDp) {
        ViewGroup.LayoutParams existing = child.getLayoutParams();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                -1, existing == null ? -2 : existing.height);
        params.topMargin = Ui.dp(activity, topDp);
        parent.addView(child, params);
    }
}
