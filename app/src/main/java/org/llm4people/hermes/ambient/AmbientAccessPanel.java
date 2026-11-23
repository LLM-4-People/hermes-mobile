package org.llm4people.hermes.ambient;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
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
    public static final int REQUEST_READ_CONTACTS = 4202;

    private static volatile Typeface mediumTypeface;

    private static Typeface mediumTypeface() {
        Typeface cached = mediumTypeface;
        if (cached == null) {
            cached = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            mediumTypeface = cached;
        }
        return cached;
    }

    public interface DraftSink {
        void stageDraft(String text);
    }

    /** Optional richer callbacks wiring 1-tap digest actions into Discussions, Smart Notes, and Calendar. */
    public interface ActionCallbacks extends DraftSink {
        default void createNote(String title, String body, String fallbackDraft) {
            stageDraft(fallbackDraft);
        }

        default void addToCalendar(String title, String description, String fallbackDraft) {
            stageDraft(fallbackDraft);
        }
    }

    public record AppEntry(String packageName, String label) {}

    private final Activity activity;
    private final NotificationAllowlist allowlist;
    private final ActionCallbacks callbacks;
    private List<AppEntry> installedApps;
    private LinearLayout allowlistContainer;
    private LinearLayout digestContainer;
    private LinearLayout mediaContainer;
    private TextView statusBadge;
    private TextView feedback;
    private Button feedbackAction;
    private Switch notificationsSwitch;
    private Switch mediaSwitch;
    private Switch calendarSwitch;
    private Switch contactsSwitch;
    private Switch deviceContextSwitch;
    private boolean binding;
    private PermissionCenter.Key pendingDenialKey;
    private AlertDialog activeDialog;
    private String searchFilter = "";

    /** Pure-Java fail-closed reconciliation of Ambient Calendar/Contacts opt-ins against live permissions. */
    public static NotificationAllowlist.Settings reconcilePermissions(
            NotificationAllowlist.Settings settings,
            boolean hasCalendarPermission,
            boolean hasContactsPermission) {
        if (settings == null) {
            return new NotificationAllowlist.Settings(false, false, false, false, false);
        }
        boolean cal = settings.calendarEnabled() && hasCalendarPermission;
        boolean contacts = settings.contactsEnabled() && hasContactsPermission;
        if (cal == settings.calendarEnabled() && contacts == settings.contactsEnabled()) {
            return settings;
        }
        return new NotificationAllowlist.Settings(
                settings.notificationsEnabled(),
                settings.mediaEnabled(),
                cal,
                contacts,
                settings.deviceContextEnabled());
    }

    public AmbientAccessPanel(Activity activity, NotificationAllowlist allowlist, DraftSink draftSink) {
        this(activity, allowlist, draftSink instanceof ActionCallbacks ac
                ? ac
                : (draftSink == null ? text -> {} : draftSink::stageDraft));
    }

    public AmbientAccessPanel(Activity activity, NotificationAllowlist allowlist, ActionCallbacks callbacks) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.callbacks = callbacks == null ? text -> {} : callbacks;
    }

    /** Register this panel in {@link RouteRegistry} under {@link RouteRegistry.Section#ACTIVITE}. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Context context, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        return registry.register(new RouteRegistry.Destination(
                ROUTE_ID,
                RouteRegistry.Section.ACTIVITE,
                context.getString(R.string.ambient_route_label),
                context.getString(R.string.ambient_route_hint),
                0,
                false,
                null,
                open));
    }

    /** Build the full scrollable native panel view for mounting in any activity/host container. */
    public View buildView() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));

        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 20, 18);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(activity, activity.getString(R.string.ambient_panel_title), 24);
        title.setTypeface(mediumTypeface());
        title.setAccessibilityHeading(true);
        body.addView(title);

        TextView intro = mutedNote(activity.getString(R.string.ambient_panel_intro));
        addTop(body, intro, 6);

        statusBadge = Ui.text(activity, "", 14);
        statusBadge.setTypeface(mediumTypeface());
        Ui.pad(statusBadge, 12, 8);
        statusBadge.setBackgroundColor(activity.getColor(R.color.accent_surface));
        addTop(body, statusBadge, 12);

        feedback = Ui.text(activity, "", 14);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        addTop(body, feedback, 8);

        feedbackAction = Ui.button(activity, "", null);
        feedbackAction.setVisibility(View.GONE);
        addTop(body, feedbackAction, 4);

        buildOnboardingSection(body);
        buildTogglesSection(body);
        buildAllowlistSection(body);
        buildMediaSection(body);
        buildDigestSection(body);

        refreshAll();
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { dismissDialogs(); }
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
        NotificationAllowlist.Settings saved = allowlist.settings();
        NotificationAllowlist.Settings current = reconcilePermissions(
                saved,
                CalendarContextReader.hasPermission(activity),
                ContactsContextReader.hasPermission(activity));
        if (!current.equals(saved)) {
            allowlist.saveSettings(current);
        }
        if (pendingDenialKey != null
                && PermissionCenter.state(activity, pendingDenialKey) == PermissionCenter.State.GRANTED) {
            clearFeedback();
        }
        syncSwitches(current);
        HermesNotificationListener.bindProfile(allowlist.effectiveAllowedPackages());
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
            if (contactsSwitch != null) contactsSwitch.setChecked(settings.contactsEnabled());
            if (deviceContextSwitch != null) deviceContextSwitch.setChecked(settings.deviceContextEnabled());
        } finally {
            binding = false;
        }
    }

    /** Open Android App Info so the user can allow restricted settings on Android 13-16. */
    public static boolean openAppInfoSettings(Context context) {
        return PermissionCenter.openAppSettings(context);
    }

    /** Open Android's Notification Access screen so the user can enable the listener. */
    public static boolean openNotificationListenerSettings(Context context) {
        return PermissionCenter.openNotificationListenerSettings(context);
    }

    /** Handle Calendar or Contacts runtime permission outcomes with fail-closed persistence and 1-tap recovery. */
    public void onPermissionResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_READ_CALENDAR) {
            PermissionCenter.recordCompletedPrompt(activity, PermissionCenter.Key.CALENDAR, results);
            boolean granted = PermissionCenter.isCompletedResult(results)
                    && results[0] == PackageManager.PERMISSION_GRANTED
                    && CalendarContextReader.hasPermission(activity);
            saveCalendarSetting(granted);
            handleRuntimePermissionOutcome(PermissionCenter.Key.CALENDAR, Manifest.permission.READ_CALENDAR,
                    REQUEST_READ_CALENDAR, R.string.perm_ambient_calendar_denied, granted);
        } else if (requestCode == REQUEST_READ_CONTACTS) {
            PermissionCenter.recordCompletedPrompt(activity, PermissionCenter.Key.CONTACTS, results);
            boolean granted = PermissionCenter.isCompletedResult(results)
                    && results[0] == PackageManager.PERMISSION_GRANTED
                    && ContactsContextReader.hasPermission(activity);
            saveContactsSetting(granted);
            handleRuntimePermissionOutcome(PermissionCenter.Key.CONTACTS, Manifest.permission.READ_CONTACTS,
                    REQUEST_READ_CONTACTS, R.string.perm_ambient_contacts_denied, granted);
        } else {
            return;
        }
        refreshAll();
    }

    private void saveCalendarSetting(boolean enabled) {
        NotificationAllowlist.Settings cur = allowlist.settings();
        if (cur.calendarEnabled() == enabled) return;
        allowlist.saveSettings(new NotificationAllowlist.Settings(
                cur.notificationsEnabled(), cur.mediaEnabled(), enabled,
                cur.contactsEnabled(), cur.deviceContextEnabled()));
    }

    private void saveContactsSetting(boolean enabled) {
        NotificationAllowlist.Settings cur = allowlist.settings();
        if (cur.contactsEnabled() == enabled) return;
        allowlist.saveSettings(new NotificationAllowlist.Settings(
                cur.notificationsEnabled(), cur.mediaEnabled(), cur.calendarEnabled(),
                enabled, cur.deviceContextEnabled()));
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
        Button step1 = Ui.button(activity, activity.getString(R.string.ambient_step_restricted), v -> {
            if (!openAppInfoSettings(activity)) showFeedback(R.string.ambient_settings_unavailable, true);
        });
        addTop(body, step1, 6);
        Button step2 = Ui.button(activity, activity.getString(R.string.ambient_step_listener), v -> {
            if (!openNotificationListenerSettings(activity)) showFeedback(R.string.ambient_settings_unavailable, true);
        });
        addTop(body, step2, 2);
    }

    private void buildTogglesSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_section_toggles, 0);
        NotificationAllowlist.Settings initial = allowlist.settings();

        notificationsSwitch = addSwitch(body, R.string.ambient_toggle_notifications, initial.notificationsEnabled(), checked -> {
            if (!checked) dismissDialogs();
            NotificationAllowlist.Settings cur = allowlist.settings();
            allowlist.saveSettings(new NotificationAllowlist.Settings(
                    checked, cur.mediaEnabled(), cur.calendarEnabled(),
                    cur.contactsEnabled(), cur.deviceContextEnabled()));
            refreshAll();
        });
        mediaSwitch = addSwitch(body, R.string.ambient_toggle_media, initial.mediaEnabled(), checked -> {
            if (!checked) dismissDialogs();
            NotificationAllowlist.Settings cur = allowlist.settings();
            allowlist.saveSettings(new NotificationAllowlist.Settings(
                    cur.notificationsEnabled(), checked, cur.calendarEnabled(),
                    cur.contactsEnabled(), cur.deviceContextEnabled()));
            refreshAll();
        });
        calendarSwitch = addSwitch(body, R.string.ambient_toggle_calendar, initial.calendarEnabled(), checked -> {
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
        contactsSwitch = addSwitch(body, R.string.ambient_toggle_contacts, initial.contactsEnabled(), checked -> {
            if (checked && !ContactsContextReader.hasPermission(activity)) {
                saveContactsSetting(false);
                PermissionCenter.State st = PermissionCenter.state(activity, PermissionCenter.Key.CONTACTS);
                if (st == PermissionCenter.State.PERMANENTLY_DENIED) {
                    showFeedbackWithAction(PermissionCenter.Key.CONTACTS, R.string.perm_ambient_contacts_denied, true,
                            R.string.perm_action_open_settings, () -> {
                                if (!PermissionCenter.openAppSettings(activity)) {
                                    showFeedback(R.string.ambient_settings_unavailable, true);
                                }
                            });
                } else {
                    activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_READ_CONTACTS);
                    showFeedback(R.string.ambient_permission_needed, false);
                }
                refreshAll();
                return;
            }
            saveContactsSetting(checked);
            if (checked) clearFeedback();
            refreshAll();
        });
        deviceContextSwitch = addSwitch(body, R.string.ambient_toggle_device_context, initial.deviceContextEnabled(), checked -> {
            NotificationAllowlist.Settings cur = allowlist.settings();
            allowlist.saveSettings(new NotificationAllowlist.Settings(
                    cur.notificationsEnabled(), cur.mediaEnabled(), cur.calendarEnabled(),
                    cur.contactsEnabled(), checked));
            refreshAll();
        });
    }

    private void buildAllowlistSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_section_allowlist, R.string.ambient_allowlist_protected);
        EditText search = new EditText(activity);
        search.setHint(R.string.ambient_allowlist_hint);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setMinHeight(Ui.dp(activity, 48));
        search.setTextColor(activity.getColor(R.color.ink));
        search.setHintTextColor(activity.getColor(R.color.muted));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                searchFilter = s == null ? "" : s.toString();
                renderAllowlist();
            }
        });
        addTop(body, search, 8);

        allowlistContainer = Ui.column(activity);
        addTop(body, allowlistContainer, 6);
    }

    private void buildMediaSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_section_media, 0);
        mediaContainer = Ui.column(activity);
        addTop(body, mediaContainer, 6);
    }

    private void buildDigestSection(LinearLayout body) {
        addSectionHeader(body, R.string.ambient_section_digest, 0);
        digestContainer = Ui.column(activity);
        addTop(body, digestContainer, 6);
    }

    private void updateStatusBadge(NotificationAllowlist.Settings settings) {
        boolean listenerOn = HermesNotificationListener.isListenerEnabled(activity);
        boolean anyListenerFeature = settings.notificationsEnabled() || settings.mediaEnabled();
        if (!anyListenerFeature) {
            statusBadge.setText(R.string.ambient_status_off);
            statusBadge.setTextColor(activity.getColor(R.color.muted));
        } else if (listenerOn) {
            statusBadge.setText(R.string.ambient_status_active);
            statusBadge.setTextColor(activity.getColor(R.color.accent));
        } else {
            statusBadge.setText(R.string.ambient_status_waiting);
            statusBadge.setTextColor(activity.getColor(R.color.danger));
        }
    }

    private void renderAllowlist() {
        if (allowlistContainer == null) return;
        allowlistContainer.removeAllViews();
        if (installedApps == null) installedApps = loadLaunchableApps();
        List<AppEntry> visible = filterSelectableApps(installedApps, searchFilter);
        if (visible.isEmpty()) {
            allowlistContainer.addView(mutedNote(activity.getString(R.string.ambient_allowlist_empty)));
            return;
        }
        Set<String> allowed = allowlist.allowedPackages();
        int limit = Math.min(visible.size(), 40);
        for (int i = 0; i < limit; i++) {
            AppEntry app = visible.get(i);
            Switch toggle = new Switch(activity);
            toggle.setText(app.label());
            toggle.setTextColor(activity.getColor(R.color.ink));
            toggle.setMinHeight(Ui.dp(activity, 48));
            toggle.setChecked(allowed.contains(app.packageName()));
            toggle.setOnCheckedChangeListener((btn, checked) -> {
                try {
                    allowlist.setAllowed(app.packageName(), checked);
                    HermesNotificationListener.bindProfile(allowlist.effectiveAllowedPackages());
                    renderDigest();
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    Diagnostics.warning("ambient", "allowlist_toggle_failed", ex);
                    btn.setChecked(!checked);
                }
            });
            allowlistContainer.addView(toggle);
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
        TextView track = Ui.text(activity, current.displayTrack(), 15);
        track.setTypeface(mediumTypeface());
        mediaContainer.addView(track);
        LinearLayout actions = Ui.row(activity);
        MediaContextBridge.ControlAction toggleAction =
                current.playbackKind() == MediaContextBridge.PlaybackKind.PLAYING
                        ? MediaContextBridge.ControlAction.PAUSE
                        : MediaContextBridge.ControlAction.PLAY;
        int toggleLabel = toggleAction == MediaContextBridge.ControlAction.PAUSE
                ? R.string.ambient_media_pause
                : R.string.ambient_media_play;
        actions.addView(Ui.button(activity, activity.getString(R.string.ambient_media_previous),
                v -> confirmMediaControl(current, MediaContextBridge.ControlAction.PREVIOUS)));
        actions.addView(Ui.button(activity, activity.getString(toggleLabel),
                v -> confirmMediaControl(current, toggleAction)));
        actions.addView(Ui.button(activity, activity.getString(R.string.ambient_media_next),
                v -> confirmMediaControl(current, MediaContextBridge.ControlAction.NEXT)));
        mediaContainer.addView(actions);
        mediaContainer.addView(Ui.button(activity, activity.getString(R.string.ambient_media_add_to_thread),
                v -> callbacks.stageDraft(activity.getString(
                        R.string.ambient_draft_media, current.title(), current.artist()))));
    }

    private void renderDigest() {
        if (digestContainer == null) return;
        digestContainer.removeAllViews();
        List<AmbientNotification> items = HermesNotificationListener.snapshot();
        if (items.isEmpty()) {
            digestContainer.addView(mutedNote(activity.getString(R.string.ambient_digest_empty)));
            return;
        }
        for (AmbientNotification item : items) {
            LinearLayout card = Ui.column(activity);
            Ui.pad(card, 12, 10);
            card.setBackgroundColor(activity.getColor(R.color.surface));

            TextView header = Ui.text(activity, item.displaySource() + " · " + item.displaySender(), 14);
            header.setTypeface(mediumTypeface());
            card.addView(header);

            TextView content = Ui.text(activity, item.displayBody(), 14);
            content.setTextColor(activity.getColor(R.color.muted));
            card.addView(content);

            LinearLayout row1 = Ui.row(activity);
            row1.addView(Ui.button(activity, activity.getString(R.string.ambient_action_summarize),
                    v -> callbacks.stageDraft(activity.getString(
                            R.string.ambient_draft_summarize, item.displaySource(), item.displaySender(), item.displayBody()))));
            row1.addView(Ui.button(activity, activity.getString(R.string.ambient_action_note),
                    v -> callbacks.createNote(
                            item.displaySource() + " · " + item.displaySender(),
                            item.displayBody(),
                            activity.getString(
                                    R.string.ambient_draft_note, item.displaySource(), item.displaySender(), item.displayBody()))));
            card.addView(row1);

            LinearLayout row2 = Ui.row(activity);
            row2.addView(Ui.button(activity, activity.getString(R.string.ambient_action_calendar),
                    v -> callbacks.addToCalendar(
                            item.displaySource() + " · " + item.displaySender(),
                            item.displayBody(),
                            activity.getString(
                                    R.string.ambient_draft_calendar, item.displaySource(), item.displaySender(), item.displayBody()))));
            if (item.replyable) {
                row2.addView(Ui.button(activity, activity.getString(R.string.ambient_action_reply),
                        v -> confirmQuickReply(item)));
            } else {
                row2.addView(Ui.button(activity, activity.getString(R.string.ambient_action_reply),
                        v -> callbacks.stageDraft(activity.getString(
                                R.string.ambient_draft_reply, item.displaySource(), item.displaySender(), item.displayBody()))));
            }
            row2.addView(Ui.button(activity, activity.getString(R.string.ambient_action_dismiss), v -> {
                HermesNotificationListener.dismiss(item.key);
                renderDigest();
            }));
            card.addView(row2);

            addTop(digestContainer, card, 8);
        }
    }

    private void confirmMediaControl(MediaContextBridge.NowPlaying current, MediaContextBridge.ControlAction action) {
        if (!allowlist.settings().mediaEnabled()) return;
        dismissDialogs();
        trackDialog(MediaContextBridge.showControlConfirmation(
                activity,
                () -> allowlist.settings().mediaEnabled(),
                current,
                action,
                ok -> refreshAll()));
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
        return item != null
                && allowlist.settings().notificationsEnabled()
                && allowlist.effectiveAllowedPackages().contains(item.packageName);
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
        heading.setTypeface(mediumTypeface());
        heading.setAccessibilityHeading(true);
        addTop(parent, heading, 14);
        if (detailRes != 0) addTop(parent, mutedNote(activity.getString(detailRes)), 4);
    }

    private Switch addSwitch(LinearLayout parent, int labelRes, boolean initial, Consumer<Boolean> onToggle) {
        Switch toggle = new Switch(activity);
        toggle.setText(labelRes);
        toggle.setTextColor(activity.getColor(R.color.ink));
        toggle.setMinHeight(Ui.dp(activity, 48));
        toggle.setChecked(initial);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!binding) onToggle.accept(isChecked);
        });
        addTop(parent, toggle, 4);
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
        feedback.setVisibility(View.VISIBLE);
        feedback.setTextColor(activity.getColor(error ? R.color.danger : R.color.accent));
        feedback.setText(messageRes);
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
