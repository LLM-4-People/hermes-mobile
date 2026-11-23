package org.llm4people.hermes.artifact;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;

/**
 * Fork {@code mobile_deliver(kind="ui_patch")} validates only {@code payload.ops}: the revision identity is
 * the top-level delivery ID and the human title lives on the delivery. Fill exactly those two gaps before
 * {@link LiveUiPatch#parse}; every present value (including an invalid one) is left for strict validation.
 */
public final class LiveUiDeliveryDefaults {
    private LiveUiDeliveryDefaults() {}

    /** Payload revision when present, otherwise the delivery ID. */
    public static String revisionId(String payloadRevisionId, String deliveryId) {
        return isBlank(payloadRevisionId) ? deliveryId : payloadRevisionId;
    }

    /** Payload summary when present, otherwise the delivery title. */
    public static String summary(String payloadSummary, String deliveryTitle) {
        return isBlank(payloadSummary) ? deliveryTitle : payloadSummary;
    }

    /** Returns a copy of the delivery payload (or of its nested {@code patch}) with defaults applied. */
    public static JSONObject apply(JSONObject payload, String deliveryId, String deliveryTitle) {
        if (payload == null) return new JSONObject();
        try {
            JSONObject root = new JSONObject(payload.toString());
            JSONObject target = root.opt("patch") instanceof JSONObject nested ? nested : root;
            String revision = revisionId(target.optString("revision_id", ""), deliveryId);
            if (!isBlank(revision)) target.put("revision_id", revision);
            String summary = summary(target.optString("summary", ""), deliveryTitle);
            if (!isBlank(summary)) target.put("summary", summary);
            return root;
        } catch (JSONException malformed) {
            Diagnostics.warning("live_ui", "delivery_defaults_failed");
            return payload;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
