package org.llm4people.hermes.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
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
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Approved-only Bidirectional Device Bridge (paired 1:1 with Fork PR #15 ALLOWED_DEVICE_CAPABILITIES).
 * Advertises per-run device capabilities, validates incoming device.requested events,
 * always asks for explicit plain-French user confirmation before acting, and posts
 * POST /v1/runs/{run_id}/device-result with completed, denied, or unavailable status.
 */
public final class DeviceBridgeFlow {
    /** Exact 1:1 allowlist matching Fork PR #15 + PR #18 ALLOWED_DEVICE_CAPABILITIES. */
    public static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "camera", "location_coarse", "nearby_osm",
            "calendar_draft", "alarm_timer", "maps_handoff",
            "share_sheet", "clipboard_read",
            "dial_draft", "email_draft", "contacts_pick", "ui_surfaces_v2",
            "notifications_digest", "notification_reply", "media_control", "calendar_read");

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

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
    }

    public interface Callback {
        void finish(Status status, JSONObject data);
    }

    private final Set<String> handled = new LinkedHashSet<>();

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

    /**
     * Unified executor for clipboard_read + fail-closed ambient capabilities
     * (notifications_digest, notification_reply, media_control, calendar_read).
     */
    public static InteractiveExecutor ambientAwareExecutor(
            Activity activity, Account account, ClipboardPastePill pill) {
        Executor clipboard = clipboardExecutor(pill);
        return new InteractiveExecutor() {
            @Override public boolean handleReviewed(Activity hostActivity, BridgeRequest request, Callback callback) {
                if (hostActivity == null || request == null || callback == null || account == null) return false;
                NotificationAllowlist allowlist = NotificationAllowlist.forAccount(account);
                NotificationAllowlist.Settings settings = allowlist.settings();
                if ("notification_reply".equals(request.actionType())) {
                    if (!settings.notificationsEnabled() || !HermesNotificationListener.isListenerEnabled(hostActivity)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return true;
                    }
                    AmbientNotification target = findReplyTarget(request.params());
                    if (target == null || !target.replyable) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return true;
                    }
                    String draft = request.params().optString("text",
                            request.params().optString("reply_text", "")).trim();
                    HermesNotificationListener.showQuickReplyConfirmation(hostActivity, target, draft, outcome -> {
                        if (outcome == HermesNotificationListener.ReplyOutcome.SENT) {
                            try {
                                callback.finish(Status.COMPLETED, new JSONObject()
                                        .put("sent", true)
                                        .put("key", target.key)
                                        .put("app", target.displaySource()));
                            } catch (JSONException invalid) {
                                callback.finish(Status.COMPLETED, null);
                            }
                        } else if (outcome == HermesNotificationListener.ReplyOutcome.CANCELLED) {
                            callback.finish(Status.DENIED, null);
                        } else {
                            callback.finish(Status.UNAVAILABLE, null);
                        }
                    });
                    return true;
                }
                if ("media_control".equals(request.actionType())) {
                    if (!settings.mediaEnabled() || !HermesNotificationListener.isListenerEnabled(hostActivity)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return true;
                    }
                    MediaContextBridge.ControlAction action = MediaContextBridge.ControlAction.fromWire(
                            request.params().optString("action", request.params().optString("command", "")));
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
                    MediaContextBridge.showControlConfirmationOutcome(hostActivity, true, track, action, outcome -> {
                        if (outcome == MediaContextBridge.ControlOutcome.APPLIED) {
                            try {
                                callback.finish(Status.COMPLETED, new JSONObject()
                                        .put("applied", true)
                                        .put("action", action.name().toLowerCase(java.util.Locale.ROOT))
                                        .put("package", track.packageName()));
                            } catch (JSONException invalid) {
                                callback.finish(Status.COMPLETED, null);
                            }
                        } else if (outcome == MediaContextBridge.ControlOutcome.CANCELLED) {
                            callback.finish(Status.DENIED, null);
                        } else {
                            callback.finish(Status.UNAVAILABLE, null);
                        }
                    });
                    return true;
                }
                return false;
            }

            @Override public void execute(BridgeRequest request, Callback callback) {
                if (request == null || callback == null) return;
                if ("clipboard_read".equals(request.actionType())) {
                    clipboard.execute(request, callback);
                    return;
                }
                if (activity == null || account == null) {
                    callback.finish(Status.UNAVAILABLE, null);
                    return;
                }
                NotificationAllowlist allowlist = NotificationAllowlist.forAccount(account);
                NotificationAllowlist.Settings settings = allowlist.settings();
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
                if ("calendar_read".equals(request.actionType())) {
                    if (!settings.calendarEnabled() || !CalendarContextReader.hasPermission(activity)) {
                        callback.finish(Status.UNAVAILABLE, null);
                        return;
                    }
                    int requestedDays = request.params().optInt("days", CalendarContextReader.DEFAULT_WINDOW_DAYS);
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
        JSONObject params = request.params();
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
        try {
            JSONObject normalized = new JSONObject(params.toString());
            normalized.put("kind", mappedKind);
            return DeviceAction.parseJson(normalized.toString());
        } catch (JSONException | IOException invalid) {
            Diagnostics.warning("device_bridge", "action_parse_failed", invalid);
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
                && interactive.handleReviewed(activity, request, once)) {
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
        Ui.secureDialog(dialog);
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
            default -> "Hermes propose d’ouvrir cette action sur votre téléphone.";
        };
    }
}
