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
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DeviceAction;
import org.llm4people.hermes.artifact.DeviceReview;
import org.llm4people.hermes.net.HermesApi;

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
    /** Exact 1:1 allowlist matching Fork PR #15 tools/mobile_device_bridge.py ALLOWED_DEVICE_CAPABILITIES. */
    public static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "camera", "location_coarse", "nearby_osm",
            "calendar_draft", "alarm_timer", "maps_handoff",
            "share_sheet", "clipboard_read",
            "dial_draft", "email_draft", "contacts_pick", "ui_surfaces_v2");

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
            default -> "Autoriser cette action sur votre téléphone ?";
        };
    }

    private static String defaultFrenchReason(String actionType) {
        return switch (actionType) {
            case "camera" -> "Hermes souhaite utiliser une photo prise maintenant pour répondre à votre demande.";
            case "location_coarse", "nearby_osm" -> "Hermes demande votre quartier approximatif pour cette réponse.";
            case "clipboard_read" -> "Hermes propose de lire le texte copié dans votre presse-papiers.";
            case "contacts_pick" -> "Hermes propose d’ouvrir votre carnet de contacts.";
            default -> "Hermes propose d’ouvrir cette action sur votre téléphone.";
        };
    }
}
