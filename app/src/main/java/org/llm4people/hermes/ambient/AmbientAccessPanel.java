package org.llm4people.hermes.ambient;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
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

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public interface DraftSink {
        void stageDraft(String text);
    }

    public record AppEntry(String packageName, String label) {}

    private final Activity activity;
    private final NotificationAllowlist allowlist;
    private final DraftSink draftSink;
    private List<AppEntry> installedApps;
    private LinearLayout allowlistContainer;
    private LinearLayout digestContainer;
    private LinearLayout mediaContainer;
    private TextView statusBadge;
    private TextView feedback;
    private String searchFilter = "";

    public AmbientAccessPanel(Activity activity, NotificationAllowlist allowlist, DraftSink draftSink) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.draftSink = draftSink == null ? text -> {} : draftSink;
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
        title.setTypeface(MEDIUM);
        title.setAccessibilityHeading(true);
        body.addView(title);

        TextView intro = mutedNote(activity.getString(R.string.ambient_panel_intro));
        addTop(body, intro, 6);

        statusBadge = Ui.text(activity, "", 14);
        statusBadge.setTypeface(MEDIUM);
        Ui.pad(statusBadge, 12, 8);
        statusBadge.setBackgroundColor(activity.getColor(R.color.accent_surface));
        addTop(body, statusBadge, 12);

        feedback = Ui.text(activity, "", 14);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        addTop(body, feedback, 8);

        buildOnboardingSection(body);
        buildTogglesSection(body);
        buildAllowlistSection(body);
        buildMediaSection(body);
        buildDigestSection(body);

        refreshAll();
        return scroll;
    }

    /** Refresh status badge, media card, and live notification digest (e.g. on Activity resume). */
    public void refreshAll() {
        NotificationAllowlist.Settings current = allowlist.settings();
        HermesNotificationListener.bindProfile(allowlist.effectiveAllowedPackages());
        updateStatusBadge(current);
        renderAllowlist();
        renderMedia(current);
        renderDigest();
    }

    /** Open Android App Info so the user can allow restricted settings on Android 13-16. */
    public static boolean openAppInfoSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null));
        return startSettingsIntent(context, intent, "open_app_info");
    }

    /** Open Android's Notification Access screen so the user can enable the listener. */
    public static boolean openNotificationListenerSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        return startSettingsIntent(context, intent, "open_listener_settings");
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

        addSwitch(body, R.string.ambient_toggle_notifications, initial.notificationsEnabled(), checked -> {
            NotificationAllowlist.Settings cur = allowlist.settings();
            allowlist.saveSettings(new NotificationAllowlist.Settings(
                    checked, cur.mediaEnabled(), cur.calendarEnabled(),
                    cur.contactsEnabled(), cur.deviceContextEnabled()));
            refreshAll();
        });
        addSwitch(body, R.string.ambient_toggle_media, initial.mediaEnabled(), checked -> {
            NotificationAllowlist.Settings cur = allowlist.settings();
            allowlist.saveSettings(new NotificationAllowlist.Settings(
                    cur.notificationsEnabled(), checked, cur.calendarEnabled(),
                    cur.contactsEnabled(), cur.deviceContextEnabled()));
            refreshAll();
        });
        addSwitch(body, R.string.ambient_toggle_calendar, initial.calendarEnabled(), checked -> {
            if (checked && !CalendarContextReader.hasPermission(activity)) {
                activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQUEST_READ_CALENDAR);
                showFeedback(R.string.ambient_permission_needed, false);
            }
            NotificationAllowlist.Settings cur = allowlist.settings();
            allowlist.saveSettings(new NotificationAllowlist.Settings(
                    cur.notificationsEnabled(), cur.mediaEnabled(), checked,
                    cur.contactsEnabled(), cur.deviceContextEnabled()));
            refreshAll();
        });
        addSwitch(body, R.string.ambient_toggle_contacts, initial.contactsEnabled(), checked -> {
            if (checked && !ContactsContextReader.hasPermission(activity)) {
                activity.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_READ_CONTACTS);
                showFeedback(R.string.ambient_permission_needed, false);
            }
            NotificationAllowlist.Settings cur = allowlist.settings();
            allowlist.saveSettings(new NotificationAllowlist.Settings(
                    cur.notificationsEnabled(), cur.mediaEnabled(), cur.calendarEnabled(),
                    checked, cur.deviceContextEnabled()));
            refreshAll();
        });
        addSwitch(body, R.string.ambient_toggle_device_context, initial.deviceContextEnabled(), checked -> {
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
        track.setTypeface(MEDIUM);
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
                v -> MediaContextBridge.showControlConfirmation(
                        activity, settings.mediaEnabled(), current, MediaContextBridge.ControlAction.PREVIOUS,
                        ok -> refreshAll())));
        actions.addView(Ui.button(activity, activity.getString(toggleLabel),
                v -> MediaContextBridge.showControlConfirmation(
                        activity, settings.mediaEnabled(), current, toggleAction,
                        ok -> refreshAll())));
        actions.addView(Ui.button(activity, activity.getString(R.string.ambient_media_next),
                v -> MediaContextBridge.showControlConfirmation(
                        activity, settings.mediaEnabled(), current, MediaContextBridge.ControlAction.NEXT,
                        ok -> refreshAll())));
        mediaContainer.addView(actions);
        mediaContainer.addView(Ui.button(activity, activity.getString(R.string.ambient_media_add_to_thread),
                v -> draftSink.stageDraft(activity.getString(
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
            header.setTypeface(MEDIUM);
            card.addView(header);

            TextView content = Ui.text(activity, item.displayBody(), 14);
            content.setTextColor(activity.getColor(R.color.muted));
            card.addView(content);

            LinearLayout row1 = Ui.row(activity);
            row1.addView(Ui.button(activity, activity.getString(R.string.ambient_action_summarize),
                    v -> draftSink.stageDraft(activity.getString(
                            R.string.ambient_draft_summarize, item.displaySource(), item.displaySender(), item.displayBody()))));
            row1.addView(Ui.button(activity, activity.getString(R.string.ambient_action_note),
                    v -> draftSink.stageDraft(activity.getString(
                            R.string.ambient_draft_note, item.displaySource(), item.displaySender(), item.displayBody()))));
            card.addView(row1);

            LinearLayout row2 = Ui.row(activity);
            row2.addView(Ui.button(activity, activity.getString(R.string.ambient_action_calendar),
                    v -> draftSink.stageDraft(activity.getString(
                            R.string.ambient_draft_calendar, item.displaySource(), item.displaySender(), item.displayBody()))));
            if (item.replyable) {
                row2.addView(Ui.button(activity, activity.getString(R.string.ambient_action_reply),
                        v -> HermesNotificationListener.showQuickReplyConfirmation(activity, item, "", outcome -> {
                            if (outcome == HermesNotificationListener.ReplyOutcome.SENT) {
                                showFeedback(R.string.ambient_reply_sent, false);
                                renderDigest();
                            } else if (outcome == HermesNotificationListener.ReplyOutcome.FAILED) {
                                showFeedback(R.string.ambient_reply_failed, true);
                                renderDigest();
                            }
                        })));
            } else {
                row2.addView(Ui.button(activity, activity.getString(R.string.ambient_action_reply),
                        v -> draftSink.stageDraft(activity.getString(
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
        heading.setTypeface(MEDIUM);
        heading.setAccessibilityHeading(true);
        addTop(parent, heading, 14);
        if (detailRes != 0) addTop(parent, mutedNote(activity.getString(detailRes)), 4);
    }

    private void addSwitch(LinearLayout parent, int labelRes, boolean initial, Consumer<Boolean> onToggle) {
        Switch toggle = new Switch(activity);
        toggle.setText(labelRes);
        toggle.setTextColor(activity.getColor(R.color.ink));
        toggle.setMinHeight(Ui.dp(activity, 48));
        toggle.setChecked(initial);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> onToggle.accept(isChecked));
        addTop(parent, toggle, 4);
    }

    private TextView mutedNote(String text) {
        TextView view = Ui.text(activity, text, 14);
        view.setTextColor(activity.getColor(R.color.muted));
        return view;
    }

    private void showFeedback(int messageRes, boolean error) {
        if (feedback == null) return;
        feedback.setVisibility(View.VISIBLE);
        feedback.setTextColor(activity.getColor(error ? R.color.danger : R.color.accent));
        feedback.setText(messageRes);
    }

    private void addTop(LinearLayout parent, View child, int topDp) {
        ViewGroup.LayoutParams existing = child.getLayoutParams();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                -1, existing == null ? -2 : existing.height);
        params.topMargin = Ui.dp(activity, topDp);
        parent.addView(child, params);
    }

    private static boolean startSettingsIntent(Context context, Intent intent, String actionName) {
        try {
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Diagnostics.event("ambient", actionName);
            return true;
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", actionName + "_failed", ex);
            return false;
        }
    }
}
