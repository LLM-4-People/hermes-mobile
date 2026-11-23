package org.llm4people.hermes.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.VisibleText;
import org.llm4people.hermes.ambient.AmbientNotification;
import org.llm4people.hermes.ambient.CalendarContextReader;
import org.llm4people.hermes.ambient.HermesNotificationListener;
import org.llm4people.hermes.ambient.MediaContextBridge;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DeviceAction;
import org.llm4people.hermes.artifact.DeviceReview;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.presence.DeepAndroidActions;
import org.llm4people.hermes.presence.PresencePolicy;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.push.PushPolicy;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Approved-only Bidirectional Device Bridge (paired 1:1 with Fork PR #15 ALLOWED_DEVICE_CAPABILITIES).
 * Advertises per-run device capabilities, validates incoming device.requested events,
 * always asks for explicit plain-French user confirmation before acting, and posts
 * POST /v1/runs/{run_id}/device-result with completed, denied, or unavailable status.
 */
public final class DeviceBridgeFlow {
    /** Exact 1:1 allowlist matching Fork PR #15 + PR #18 + PR #24 ALLOWED_DEVICE_CAPABILITIES. */
    public static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "camera", "location_coarse", "nearby_osm",
            "calendar_draft", "alarm_timer", "maps_handoff",
            "share_sheet", "clipboard_read",
            "dial_draft", "email_draft", "contacts_pick", "ui_surfaces_v2",
            "notifications_digest", "notification_reply", "media_control", "calendar_read",
            "app_navigate", "app_customize", "app_read_state", "app_create_template",
            "android_set_timer", "android_share_sheet", "android_open_app",
            "android_sync_shortcuts", "android_proactive_nudge");

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int MAX_NOTIFICATION_REPLY_CHARS = 1000;

    public enum Status {
        COMPLETED("completed"),
        DENIED("denied"),
        UNAVAILABLE("unavailable");

        public final String wire;
        Status(String wire) { this.wire = wire; }
    }

    public record BridgeRequest(
            String requestId,
            String actionType,
            String reason,
            JSONObject params,
            long expiresAtEpochSec) {
        public boolean expiredAt(long nowEpochSec) {
            return expiresAtEpochSec > 0 && nowEpochSec >= expiresAtEpochSec;
        }
    }

    public interface Executor {
        void execute(BridgeRequest request, Callback callback);
    }

    /** Executor providing its own dedicated FLAG_SECURE confirmation dialog for specific actions. */
    public interface InteractiveExecutor extends Executor {
        boolean handleReviewed(Activity activity, BridgeRequest request, Callback callback);

        default boolean handleReviewed(Activity activity, BridgeRequest request, Callback callback,
                                       Consumer<AlertDialog> onDialogShown) {
            return handleReviewed(activity, request, callback);
        }
    }

    public interface Callback {
        void finish(Status status, JSONObject data);
    }

    private record OpenPrompt(AlertDialog dialog, Callback settle) {}

    private final Set<String> handled = new LinkedHashSet<>();
    private final List<OpenPrompt> openPrompts = new ArrayList<>();

    /** Dismisses every active confirmation dialog fail-closed (reporting UNAVAILABLE for unsettled prompts). */
    public void dismissDialogs() {
        List<OpenPrompt> snapshot = new ArrayList<>(openPrompts);
        openPrompts.clear();
        for (OpenPrompt entry : snapshot) {
            try {
                if (entry.dialog().isShowing()) entry.dialog().dismiss();
            } catch (RuntimeException ignored) {
                Diagnostics.debug("device_bridge", "dialog_dismiss_ignored", ignored);
            }
            entry.settle().finish(Status.UNAVAILABLE, null);
        }
    }

    private void trackDialog(AlertDialog dialog, Callback settle) {
        if (dialog == null) return;
        OpenPrompt entry = new OpenPrompt(dialog, settle);
        openPrompts.add(entry);
        dialog.setOnDismissListener(d -> openPrompts.remove(entry));
    }

    /** Build the bounded per-run device_capabilities list strictly within Fork PR #15's allowlist. */
    public static List<String> advertisedCapabilities(
            boolean cameraAvailable, boolean locationGranted, boolean mapsAvailable, boolean alarmAvailable) {
        List<String> caps = new ArrayList<>();
        if (cameraAvailable) caps.add("camera");
        if (locationGranted) {
            caps.add("location_coarse");
            caps.add("nearby_osm");
        }
        caps.add("calendar_draft");
        if (alarmAvailable) caps.add("alarm_timer");
        if (mapsAvailable) caps.add("maps_handoff");
        caps.add("share_sheet");
        caps.add("clipboard_read");
        caps.add("dial_draft");
        caps.add("email_draft");
        caps.add("contacts_pick");
        caps.add("ui_surfaces_v2");
        return Collections.unmodifiableList(caps);
    }

    /**
     * Exact subset of Fork PR #15 + PR #18 capabilities backed by a real DeviceReview handoff or wired Executor
     * in ThreadWorkspace — never advertises a capability that would unconditionally return UNAVAILABLE.
     */
    public static List<String> executableCapabilities(
            boolean mapsAvailable, boolean alarmAvailable, boolean clipboardReadSupported) {
        return executableCapabilities(mapsAvailable, alarmAvailable, clipboardReadSupported,
                NotificationAllowlist.Settings.ALL_OFF, false, false, false);
    }

    /** Fail-closed subset of PR #18 ambient capabilities ready on this profile and device right now. */
    public static List<String> ambientExecutableCapabilities(
            NotificationAllowlist.Settings settings,
            boolean listenerEnabled,
            boolean calendarPermissionGranted,
            boolean hasAllowlistedApps) {
        if (settings == null) return Collections.emptyList();
        List<String> caps = new ArrayList<>();
        if (settings.notificationsEnabled() && listenerEnabled && hasAllowlistedApps) {
            caps.add("notifications_digest");
            caps.add("notification_reply");
        }
        if (settings.mediaEnabled() && listenerEnabled) {
            caps.add("media_control");
        }
        if (settings.calendarEnabled() && calendarPermissionGranted) {
            caps.add("calendar_read");
        }
        return Collections.unmodifiableList(caps);
    }

    public static List<String> executableCapabilities(
            boolean mapsAvailable, boolean alarmAvailable, boolean clipboardReadSupported,
            NotificationAllowlist.Settings ambientSettings, boolean listenerEnabled,
            boolean calendarPermissionGranted, boolean hasAllowlistedApps) {
        List<String> caps = new ArrayList<>();
        caps.add("calendar_draft");
        if (alarmAvailable) caps.add("alarm_timer");
        if (mapsAvailable) caps.add("maps_handoff");
        caps.add("share_sheet");
        if (clipboardReadSupported) caps.add("clipboard_read");
        caps.add("dial_draft");
        caps.add("email_draft");
        caps.addAll(ambientExecutableCapabilities(
                ambientSettings, listenerEnabled, calendarPermissionGranted, hasAllowlistedApps));
        return Collections.unmodifiableList(caps);
    }

    /** Fail-closed subset of Track 9A/9B deep Android capabilities ready for the active profile right now. */
    public static List<String> deepExecutableCapabilities(PresencePolicy presencePolicy) {
        PresencePolicy safe = presencePolicy == null ? PresencePolicy.defaults() : presencePolicy;
        List<String> caps = new ArrayList<>();
        caps.add("app_navigate");
        caps.add("app_customize");
        if (safe.shareAppPresence) caps.add("app_read_state");
        caps.add("app_create_template");
        caps.add("android_set_timer");
        caps.add("android_share_sheet");
        caps.add("android_open_app");
        caps.add("android_sync_shortcuts");
        if (safe.proactiveNudgesEnabled) caps.add("android_proactive_nudge");
        return Collections.unmodifiableList(caps);
    }

    public static List<String> executableCapabilities(
            boolean mapsAvailable, boolean alarmAvailable, boolean clipboardReadSupported,
            NotificationAllowlist.Settings ambientSettings, boolean listenerEnabled,
            boolean calendarPermissionGranted, boolean hasAllowlistedApps,
            PresencePolicy presencePolicy) {
        List<String> caps = new ArrayList<>(executableCapabilities(
                mapsAvailable, alarmAvailable, clipboardReadSupported,
                ambientSettings, listenerEnabled, calendarPermissionGranted, hasAllowlistedApps));
        caps.addAll(deepExecutableCapabilities(presencePolicy));
        return Collections.unmodifiableList(caps);
    }

    static String rawMediaCommand(JSONObject params) {
        if (params == null) return "";
        String action = params.optString("action", "").trim();
        if (!action.isEmpty()) return action;
        return params.optString("command", "").trim();
    }

    static boolean isMediaStatusCommand(String rawCommand) {
        String cmd = rawCommand == null ? "" : rawCommand.trim();
        return cmd.isEmpty() || "status".equalsIgnoreCase(cmd);
    }

    static boolean isMediaStatusCommand(JSONObject params) {
        return isMediaStatusCommand(rawMediaCommand(params));
    }

    static int resolveCalendarDays(Integer daysAhead, Integer days) {
        if (daysAhead != null) return daysAhead;
        if (days != null) return days;
        return CalendarContextReader.DEFAULT_WINDOW_DAYS;
    }

    static int resolveCalendarDays(JSONObject params) {
        if (params == null) return CalendarContextReader.DEFAULT_WINDOW_DAYS;
        Integer ahead = params.has("days_ahead") && params.opt("days_ahead") instanceof Number n ? n.intValue() : null;
        Integer legacy = params.has("days") && params.opt("days") instanceof Number n ? n.intValue() : null;
        return resolveCalendarDays(ahead, legacy);
    }

    private static NotificationAllowlist.Settings liveSettings(Account account) {
        if (account == null) return null;
        try {
            return NotificationAllowlist.forAccount(account).settings();
        } catch (IllegalStateException lockedOrUnavailable) {
            Diagnostics.warning("device_bridge", "account_locked");
            return null;
        }
    }

    private static boolean packageStillAllowed(Account account, String packageName) {
        if (account == null || packageName == null || packageName.isEmpty()) return false;
        try {
            return NotificationAllowlist.forAccount(account).effectiveAllowedPackages().contains(packageName);
        } catch (IllegalStateException lockedOrUnavailable) {
            return false;
        }
    }

    private static String mediaActionLabel(Activity activity, MediaContextBridge.ControlAction action) {
        return switch (action) {
            case PLAY -> activity.getString(R.string.ambient_media_play);
            case PAUSE -> activity.getString(R.string.ambient_media_pause);
            case NEXT -> activity.getString(R.string.ambient_media_next);
            case PREVIOUS -> activity.getString(R.string.ambient_media_previous);
        };
    }

    /**
     * Unified executor for clipboard_read + fail-closed ambient capabilities
     * (notifications_digest, notification_reply, media_control, calendar_read).
     */
    public static InteractiveExecutor ambientAwareExecutor(
            Activity activity, Account account, ClipboardPastePill pill) {
        Executor clipboard = clipboardExecutor(pill);
        return new InteractiveExecutor() {
            @Override public boolean handleReviewed(Activity hostActivity, BridgeRequest request, Callback callback) {
                return handleReviewed(hostActivity, request, callback, null);
            }

            @Override public boolean handleReviewed(Activity hostActivity, BridgeRequest request, Callback callback,
                                                    Consumer<AlertDialog> onDialogShown) {
                if (hostActivity == null || request == null || callback == null || account == null) return false;
                NotificationAllowlist.Settings settings = liveSettings(account);
                if ("notification_reply".equals(request.actionType())) {
                    if (settings == null || !settings.notificationsEnabled()
                            || !HermesNotificationListener.isListenerEnabled(hostActivity)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return true;
                    }
                    AmbientNotification target = findReplyTarget(request.params());
                    if (target == null || !target.replyable || !packageStillAllowed(account, target.packageName)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return true;
                    }
                    String draft = request.params().optString("text",
                            request.params().optString("reply_text", "")).trim();
                    LinearLayout content = Ui.column(hostActivity);
                    Ui.pad(content, 20, 12);
                    TextView header = Ui.text(hostActivity,
                            hostActivity.getString(R.string.ambient_reply_target, target.displaySource(), target.displaySender()), 14);
                    header.setTextColor(hostActivity.getColor(R.color.muted));
                    content.addView(header);
                    EditText editor = new EditText(hostActivity);
                    editor.setHint(R.string.ambient_reply_hint);
                    editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                    editor.setMinHeight(Ui.dp(hostActivity, 56));
                    editor.setTextColor(hostActivity.getColor(R.color.ink));
                    editor.setHintTextColor(hostActivity.getColor(R.color.muted));
                    editor.setSaveEnabled(false);
                    editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_NOTIFICATION_REPLY_CHARS)});
                    editor.setText(draft);
                    content.addView(editor);
                    AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(hostActivity)
                            .setTitle(R.string.ambient_reply_title)
                            .setView(content)
                            .setNegativeButton(R.string.ambient_reply_cancel, (d, w) -> callback.finish(Status.DENIED, null))
                            .setOnCancelListener(d -> callback.finish(Status.DENIED, null))
                            .setPositiveButton(R.string.ambient_reply_send, (d, w) -> {
                                NotificationAllowlist.Settings current = liveSettings(account);
                                if (current == null || !current.notificationsEnabled()
                                        || !HermesNotificationListener.isListenerEnabled(hostActivity)
                                        || !packageStillAllowed(account, target.packageName)) {
                                    callback.finish(Status.UNAVAILABLE, null);
                                    return;
                                }
                                boolean sent = HermesNotificationListener.sendReviewedReply(
                                        hostActivity, target.key, editor.getText().toString(), true);
                                if (sent) {
                                    try {
                                        callback.finish(Status.COMPLETED, new JSONObject()
                                                .put("sent", true)
                                                .put("key", target.key)
                                                .put("app", target.displaySource()));
                                    } catch (JSONException invalid) {
                                        callback.finish(Status.COMPLETED, null);
                                    }
                                } else {
                                    callback.finish(Status.UNAVAILABLE, null);
                                }
                            }));
                    if (onDialogShown != null) onDialogShown.accept(dialog);
                    return true;
                }
                if ("media_control".equals(request.actionType())) {
                    if (settings == null || !settings.mediaEnabled()
                            || !HermesNotificationListener.isListenerEnabled(hostActivity)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return true;
                    }
                    if (isMediaStatusCommand(request.params())) {
                        // Fall through to promptUser() confirmation dialog, then execute() reads sanitized status.
                        return false;
                    }
                    MediaContextBridge.ControlAction action = MediaContextBridge.ControlAction.fromWire(
                            rawMediaCommand(request.params()));
                    if (action == null) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return true;
                    }
                    List<MediaContextBridge.NowPlaying> sessions =
                            MediaContextBridge.readActiveSessions(hostActivity, true);
                    MediaContextBridge.NowPlaying track = MediaContextBridge.selectTargetSession(
                            sessions, request.params().optString("package", ""));
                    if (track == null) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return true;
                    }
                    String actionLabel = mediaActionLabel(hostActivity, action);
                    AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(hostActivity)
                            .setTitle(R.string.ambient_media_confirm_title)
                            .setMessage(hostActivity.getString(R.string.ambient_media_confirm_body, actionLabel, track.displayTrack()))
                            .setNegativeButton(R.string.ambient_reply_cancel, (d, w) -> callback.finish(Status.DENIED, null))
                            .setOnCancelListener(d -> callback.finish(Status.DENIED, null))
                            .setPositiveButton(R.string.ambient_media_confirm_ok, (d, w) -> {
                                NotificationAllowlist.Settings current = liveSettings(account);
                                if (current == null || !current.mediaEnabled()
                                        || !HermesNotificationListener.isListenerEnabled(hostActivity)) {
                                    callback.finish(Status.UNAVAILABLE, null);
                                    return;
                                }
                                boolean ok = MediaContextBridge.dispatchConfirmedAction(
                                        hostActivity, true, track.packageName(), action, true);
                                if (ok) {
                                    try {
                                        callback.finish(Status.COMPLETED, new JSONObject()
                                                .put("applied", true)
                                                .put("action", action.name().toLowerCase(Locale.ROOT))
                                                .put("package", track.packageName()));
                                    } catch (JSONException invalid) {
                                        callback.finish(Status.COMPLETED, null);
                                    }
                                } else {
                                    callback.finish(Status.UNAVAILABLE, null);
                                }
                            }));
                    if (onDialogShown != null) onDialogShown.accept(dialog);
                    return true;
                }
                return false;
            }

            @Override public void execute(BridgeRequest request, Callback callback) {
                if (request == null || callback == null) return;
                NotificationAllowlist.Settings settings = liveSettings(account);
                if (settings == null) {
                    callback.finish(Status.UNAVAILABLE, null);
                    return;
                }
                if ("clipboard_read".equals(request.actionType())) {
                    clipboard.execute(request, callback);
                    return;
                }
                if (activity == null) {
                    callback.finish(Status.UNAVAILABLE, null);
                    return;
                }
                if ("notifications_digest".equals(request.actionType())) {
                    if (!settings.notificationsEnabled() || !HermesNotificationListener.isListenerEnabled(activity)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return;
                    }
                    int limit = Math.max(1, Math.min(request.params().optInt("limit", 20), 40));
                    List<AmbientNotification> snapshot = HermesNotificationListener.snapshot();
                    JSONArray items = new JSONArray();
                    try {
                        for (int i = 0; i < Math.min(snapshot.size(), limit); i++) {
                            items.put(new JSONObject(snapshot.get(i).toSanitizedJson()));
                        }
                        callback.finish(Status.COMPLETED, new JSONObject()
                                .put("notifications", items)
                                .put("count", items.length()));
                    } catch (JSONException invalid) {
                        callback.finish(Status.UNAVAILABLE, null);
                    }
                    return;
                }
                if ("media_control".equals(request.actionType()) && isMediaStatusCommand(request.params())) {
                    if (!settings.mediaEnabled() || !HermesNotificationListener.isListenerEnabled(activity)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return;
                    }
                    List<MediaContextBridge.NowPlaying> sessions =
                            MediaContextBridge.readActiveSessions(activity, true);
                    MediaContextBridge.NowPlaying primary = MediaContextBridge.selectTargetSession(
                            sessions, request.params().optString("package", ""));
                    JSONArray items = new JSONArray();
                    try {
                        for (MediaContextBridge.NowPlaying session : sessions) {
                            items.put(new JSONObject(session.toSanitizedJson()));
                        }
                        JSONObject payload = new JSONObject()
                                .put("sessions", items)
                                .put("count", items.length());
                        if (primary != null) payload.put("primary", new JSONObject(primary.toSanitizedJson()));
                        else payload.put("primary", JSONObject.NULL);
                        callback.finish(Status.COMPLETED, payload);
                    } catch (JSONException invalid) {
                        callback.finish(Status.UNAVAILABLE, null);
                    }
                    return;
                }
                if ("calendar_read".equals(request.actionType())) {
                    if (!settings.calendarEnabled() || !CalendarContextReader.hasPermission(activity)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return;
                    }
                    int requestedDays = resolveCalendarDays(request.params());
                    CalendarContextReader.Window window = CalendarContextReader.resolveWindow(
                            System.currentTimeMillis(), requestedDays);
                    List<CalendarContextReader.AgendaEvent> events =
                            CalendarContextReader.readUpcoming(activity, true, window.days());
                    JSONArray array = new JSONArray();
                    try {
                        for (CalendarContextReader.AgendaEvent event : events) {
                            array.put(new JSONObject(event.toSanitizedJson()));
                        }
                        callback.finish(Status.COMPLETED, new JSONObject()
                                .put("events", array)
                                .put("count", array.length())
                                .put("window_days", window.days()));
                    } catch (JSONException invalid) {
                        callback.finish(Status.UNAVAILABLE, null);
                    }
                    return;
                }
                callback.finish(Status.UNAVAILABLE, null);
            }
        };
    }

    /**
     * Unified executor wiring clipboard + ambient + all 9 Track 9B {@link DeepAndroidActions}
     * with strict consent-tier enforcement (AUTOMATIC_SAFE/OPT_IN_ONLY validated fail-closed;
     * CONFIRM_REQUIRED presented via FLAG_SECURE dialog before execution).
     */
    public static InteractiveExecutor deepAwareExecutor(
            Activity activity, Account account, ClipboardPastePill pill, DeepAndroidActions deepActions) {
        InteractiveExecutor base = ambientAwareExecutor(activity, account, pill);
        if (deepActions == null) return base;
        return new InteractiveExecutor() {
            @Override public boolean handleReviewed(Activity hostActivity, BridgeRequest request, Callback callback) {
                return handleReviewed(hostActivity, request, callback, null);
            }

            @Override public boolean handleReviewed(Activity hostActivity, BridgeRequest request, Callback callback,
                                                    Consumer<AlertDialog> onDialogShown) {
                if (request == null || callback == null || account == null) return false;
                DeepAndroidActions.Action deep = DeepAndroidActions.Action.parse(request.actionType());
                if (deep != null) {
                    if (deep.consentTier == DeepAndroidActions.ConsentTier.AUTOMATIC_SAFE
                            || deep.consentTier == DeepAndroidActions.ConsentTier.OPT_IN_ONLY) {
                        runDeepAction(activity, account, deepActions, request, false, callback);
                        return true;
                    }
                    // Fall through to promptUser() FLAG_SECURE confirmation dialog before execute().
                    return false;
                }
                return base.handleReviewed(hostActivity, request, callback, onDialogShown);
            }

            @Override public void execute(BridgeRequest request, Callback callback) {
                if (request == null || callback == null) return;
                if (DeepAndroidActions.Action.parse(request.actionType()) != null) {
                    runDeepAction(activity, account, deepActions, request, true, callback);
                    return;
                }
                base.execute(request, callback);
            }
        };
    }

    private static void runDeepAction(
            Activity activity,
            Account account,
            DeepAndroidActions deepActions,
            BridgeRequest request,
            boolean userConfirmed,
            Callback callback) {
        if (account == null) {
            callback.finish(Status.UNAVAILABLE, null);
            return;
        }
        PresencePolicy presencePolicy;
        try {
            presencePolicy = PresencePolicy.load(account);
        } catch (IllegalStateException locked) {
            callback.finish(Status.UNAVAILABLE, null);
            return;
        }
        PushPolicy pushPolicy = PushPolicy.load(activity, account.id);
        LocalTime nowLocal = LocalTime.now();
        int minuteOfDay = nowLocal.getHour() * 60 + nowLocal.getMinute();
        DeepAndroidActions.Result res = deepActions.executeJson(
                request.actionType(),
                request.params(),
                presencePolicy,
                pushPolicy,
                userConfirmed,
                System.currentTimeMillis(),
                minuteOfDay);
        if (res.ok()) {
            JSONObject payload = null;
            if (res.payloadJson() != null && !res.payloadJson().isEmpty()) {
                try { payload = new JSONObject(res.payloadJson()); }
                catch (JSONException ignored) { payload = null; }
            }
            callback.finish(Status.COMPLETED, payload);
        } else if (res.status() == DeepAndroidActions.Status.DENIED_CONFIRMATION_REQUIRED
                || res.status() == DeepAndroidActions.Status.DENIED_NOT_OPTED_IN) {
            callback.finish(Status.DENIED, null);
        } else {
            callback.finish(Status.UNAVAILABLE, null);
        }
    }

    private static AmbientNotification findReplyTarget(JSONObject params) {
        if (params == null) return null;
        String key = params.optString("key", "").trim();
        String pkg = params.optString("package", "").trim();
        String sender = params.optString("sender", "").trim();
        List<AmbientNotification> items = HermesNotificationListener.snapshot();
        if (!key.isEmpty()) {
            for (AmbientNotification item : items) {
                if (key.equals(item.key)) return item;
            }
            return null;
        }
        AmbientNotification match = null;
        for (AmbientNotification item : items) {
            if (!item.replyable) continue;
            boolean pkgOk = pkg.isEmpty() || pkg.equalsIgnoreCase(item.packageName);
            boolean senderOk = sender.isEmpty() || sender.equalsIgnoreCase(item.displaySender());
            if (pkgOk && senderOk) {
                if (match != null) return null; // Fail closed on ambiguous match.
                match = item;
            }
        }
        return match;
    }

    /** Executor for explicit user-approved clipboard_read requests (only invoked after "Autoriser une fois"). */
    public static Executor clipboardExecutor(ClipboardPastePill pill) {
        return (request, callback) -> {
            if (request == null || callback == null) return;
            if (!"clipboard_read".equals(request.actionType()) || pill == null) {
                callback.finish(Status.UNAVAILABLE, null);
                return;
            }
            String text = pill.consumeOnUserTap();
            if (text == null || text.isEmpty()) {
                callback.finish(Status.UNAVAILABLE, null);
                return;
            }
            try {
                callback.finish(Status.COMPLETED, new JSONObject().put("text", text));
            } catch (JSONException invalid) {
                callback.finish(Status.UNAVAILABLE, null);
            }
        };
    }

    public static JSONArray capabilitiesJson(List<String> capabilities) {
        JSONArray array = new JSONArray();
        if (capabilities != null) {
            for (String cap : capabilities) {
                if (cap != null && SUPPORTED_ACTIONS.contains(cap)) array.put(cap);
            }
        }
        return array;
    }

    public static BridgeRequest parseRequest(JSONObject event) {
        if (event == null) return null;
        String id = event.optString("request_id", "").trim();
        String actionType = event.optString("action_type", "").trim();
        String reason = event.optString("reason", "").trim();
        if (!REQUEST_ID.matcher(id).matches() || !SUPPORTED_ACTIONS.contains(actionType)) {
            Diagnostics.warning("device_bridge", "invalid_request");
            return null;
        }
        if (reason.length() > 300) reason = reason.substring(0, 300);
        if (VisibleText.isBlank(reason)) reason = defaultFrenchReason(actionType);
        JSONObject params = event.optJSONObject("params");
        long expiresAt = event.optLong("expires_at", 0L);
        return new BridgeRequest(id, actionType, reason, params == null ? new JSONObject() : params, expiresAt);
    }

    public static List<BridgeRequest> parsePending(JSONObject runStatus) {
        if (runStatus == null) return Collections.emptyList();
        JSONArray rows = runStatus.optJSONArray("pending_device_requests");
        if (rows == null || rows.length() == 0) return Collections.emptyList();
        List<BridgeRequest> result = new ArrayList<>();
        int cap = Math.min(rows.length(), 8);
        for (int i = 0; i < cap; i++) {
            BridgeRequest req = parseRequest(rows.optJSONObject(i));
            if (req != null) result.add(req);
        }
        return Collections.unmodifiableList(result);
    }

    public static JSONObject buildResultPayload(String requestId, Status status, JSONObject data) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches() || status == null) {
            throw new IllegalArgumentException("Invalid device bridge result");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("request_id", requestId);
            body.put("status", status.wire);
            if (status == Status.COMPLETED && data != null && data.length() > 0) {
                body.put("data", data);
            }
        } catch (JSONException invalid) {
            throw new IllegalStateException(invalid);
        }
        return body;
    }

    /** Submit the user's decision or completed result back to POST /v1/runs/{run_id}/device-result. */
    public static void submitResult(HermesApi api, String runId, String requestId, Status status, JSONObject data)
            throws IOException {
        if (api == null || runId == null || runId.isEmpty()) throw new IllegalArgumentException("Missing run target");
        JSONObject payload = buildResultPayload(requestId, status, data);
        api.post("/v1/runs/" + HermesApi.segment(runId) + "/device-result", payload);
        Diagnostics.event("device_bridge", "result_" + status.wire);
    }

    /** Convert a bridge request into a reviewed DeviceAction when it maps to a standard OS handoff. */
    public static DeviceAction toDeviceAction(BridgeRequest request) {
        if (request == null) return null;
        try {
            JSONObject params = normalizeForkAliases(request.actionType(), request.params());
            String mappedKind = switch (request.actionType()) {
                case "calendar_draft" -> "calendar_event";
                case "maps_handoff" -> params.has("destination") ? "map_navigation" : "map_search";
                case "share_sheet" -> "share_text";
                case "dial_draft" -> "dial_draft";
                case "email_draft" -> "email_draft";
                case "contacts_pick" -> (params.has("name") || params.has("phone") || params.has("email"))
                        ? "insert_contact" : null;
                case "alarm_timer" -> params.has("seconds") ? "set_timer" : "set_alarm";
                default -> null;
            };
            if (mappedKind == null) return null;
            params.put("kind", mappedKind);
            return DeviceAction.parseJson(params.toString());
        } catch (JSONException | IOException invalid) {
            Diagnostics.warning("device_bridge", "action_parse_failed", invalid);
            return null;
        }
    }

    /**
     * Maps the parameter names Fork's {@code agent/mobile_companion_prompt.py} teaches the model
     * ({@code duration_seconds}, {@code phone}, {@code to}, {@code start} without {@code end}) onto the
     * reviewed {@link DeviceAction} schema. A canonical key always wins over its alias; DeviceAction
     * still validates every value, so a malformed alias fails closed exactly like a malformed canonical key.
     */
    static JSONObject normalizeForkAliases(String actionType, JSONObject params) throws JSONException {
        JSONObject out = new JSONObject(params == null ? "{}" : params.toString());
        switch (actionType == null ? "" : actionType) {
            case "alarm_timer" -> renameAlias(out, "duration_seconds", "seconds");
            case "dial_draft" -> renameAlias(out, "phone", "number");
            case "email_draft" -> renameAlias(out, "to", "recipient");
            case "calendar_draft" -> {
                if (!out.has("end")) {
                    String end = defaultCalendarEnd(out.optString("start", null));
                    if (end != null) out.put("end", end);
                }
            }
            default -> { }
        }
        return out;
    }

    private static void renameAlias(JSONObject params, String alias, String canonical) throws JSONException {
        if (!params.has(alias)) return;
        Object value = params.remove(alias);
        if (!params.has(canonical)) params.put(canonical, value);
    }

    private static final DateTimeFormatter CALENDAR_WIRE = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    /** One-hour default end for a calendar draft whose start is a valid ISO offset date-time; otherwise null. */
    static String defaultCalendarEnd(String start) {
        if (start == null || start.trim().isEmpty()) return null;
        try {
            return OffsetDateTime.parse(start.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .plusHours(1).format(CALENDAR_WIRE);
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    /** Present a calm, plain-French confirmation card; never executes silently and always reports a terminal status. */
    public void promptUser(Activity activity, ArtifactDesk.Host host, View anchor, BridgeRequest request,
                           Executor executor, Callback onDecision) {
        if (request == null || onDecision == null || !handled.add(request.requestId())) return;
        AtomicBoolean settled = new AtomicBoolean(false);
        Callback once = (status, data) -> {
            if (settled.compareAndSet(false, true)) onDecision.finish(status, data);
        };
        if (request.expiredAt(System.currentTimeMillis() / 1000L)) {
            Diagnostics.debug("device_bridge", "request_expired");
            once.finish(Status.UNAVAILABLE, null);
            return;
        }
        if (executor instanceof InteractiveExecutor interactive
                && interactive.handleReviewed(activity, request, once, shown -> trackDialog(shown, once))) {
            return;
        }
        DeviceAction action = toDeviceAction(request);
        if (action != null && host != null && anchor != null) {
            DeviceReview.review(activity, host, anchor, action,
                    () -> once.finish(Status.COMPLETED, null),
                    () -> once.finish(Status.DENIED, null),
                    () -> once.finish(Status.UNAVAILABLE, null));
            return;
        }
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            once.finish(Status.UNAVAILABLE, null);
            return;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(frenchTitle(request.actionType()))
                .setMessage(request.reason() + "\n\nRien n’est envoyé sans votre accord.")
                .setNegativeButton("Refuser", (d, w) -> once.finish(Status.DENIED, null))
                .setOnCancelListener(d -> once.finish(Status.DENIED, null))
                .setPositiveButton("Autoriser une fois", (d, w) -> {
                    if (executor == null) {
                        once.finish(Status.UNAVAILABLE, null);
                    } else {
                        executor.execute(request, once);
                    }
                });
        trackDialog(Ui.secureDialog(dialog), once);
    }

    public static String frenchTitle(String actionType) {
        return switch (actionType) {
            case "camera" -> "Prendre une photo pour Hermes ?";
            case "location_coarse", "nearby_osm" -> "Partager votre zone approximative ?";
            case "clipboard_read" -> "Partager le texte du presse-papiers ?";
            case "calendar_draft" -> "Préparer un événement dans l’agenda ?";
            case "alarm_timer" -> "Régler une alarme ou un minuteur ?";
            case "maps_handoff" -> "Ouvrir l’application de carte ?";
            case "share_sheet" -> "Partager avec une autre application ?";
            case "dial_draft" -> "Ouvrir le téléphone avec ce numéro ?";
            case "email_draft" -> "Préparer un courriel ?";
            case "contacts_pick" -> "Choisir ou enregistrer un contact ?";
            case "ui_surfaces_v2" -> "Afficher un écran interactif proposé ?";
            case "notifications_digest" -> "Partager le résumé des notifications choisies ?";
            case "notification_reply" -> "Envoyer une réponse à une notification ?";
            case "media_control" -> "Contrôler la lecture audio en cours ?";
            case "calendar_read" -> "Consulter vos prochains rendez-vous d’agenda ?";
            case "app_navigate" -> "Ouvrir cet écran dans l’application ?";
            case "app_customize" -> "Modifier l’apparence de l’application ?";
            case "app_read_state" -> "Partager votre écran actuel avec Hermes ?";
            case "app_create_template" -> "Enregistrer un nouveau modèle réutilisable ?";
            case "android_set_timer" -> "Régler un minuteur sur votre téléphone ?";
            case "android_share_sheet" -> "Ouvrir le partage Android ?";
            case "android_open_app" -> "Ouvrir cette application ou ce lien ?";
            case "android_sync_shortcuts" -> "Mettre à jour vos raccourcis et widgets ?";
            case "android_proactive_nudge" -> "Afficher un rappel local discret ?";
            default -> "Autoriser cette action sur votre téléphone ?";
        };
    }

    private static String defaultFrenchReason(String actionType) {
        return switch (actionType) {
            case "camera" -> "Hermes souhaite utiliser une photo prise maintenant pour répondre à votre demande.";
            case "location_coarse", "nearby_osm" -> "Hermes demande votre quartier approximatif pour cette réponse.";
            case "clipboard_read" -> "Hermes propose de lire le texte copié dans votre presse-papiers.";
            case "contacts_pick" -> "Hermes propose d’ouvrir votre carnet de contacts.";
            case "notifications_digest" -> "Hermes propose de consulter les notifications des applications que vous avez autorisées.";
            case "notification_reply" -> "Hermes propose de répondre à une notification après votre validation.";
            case "media_control" -> "Hermes propose de piloter la lecture audio après votre accord.";
            case "calendar_read" -> "Hermes propose de consulter vos créneaux disponibles dans votre agenda.";
            case "app_navigate" -> "Hermes propose d’ouvrir directement cette section de l’application.";
            case "app_customize" -> "Hermes propose d’ajuster les couleurs ou l’espacement de votre espace.";
            case "app_read_state" -> "Hermes demande votre état actuel dans l’application.";
            case "app_create_template" -> "Hermes propose d’enregistrer un modèle dans votre coffre chiffré.";
            case "android_set_timer" -> "Hermes propose de démarrer un minuteur dans l’horloge Android.";
            case "android_share_sheet" -> "Hermes propose de préparer un partage vers une autre application.";
            case "android_open_app" -> "Hermes propose d’ouvrir une application ou un lien vérifié.";
            case "android_sync_shortcuts" -> "Hermes propose d’actualiser vos raccourcis et widgets d’accueil.";
            case "android_proactive_nudge" -> "Hermes propose d’afficher un rappel discret sur votre téléphone.";
            default -> "Hermes propose d’ouvrir cette action sur votre téléphone.";
        };
    }
}
