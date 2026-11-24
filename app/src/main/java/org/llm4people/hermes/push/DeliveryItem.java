package org.llm4people.hermes.push;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.EpochTimestamps;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable typed Hermes-to-Phone delivery model (mobile_deliver contract).
 * Core construction and validation are pure Java so host JVM tests verify all boundaries.
 */
public final class DeliveryItem {
    public static final int MAX_TITLE_CHARS = 240;
    public static final int MAX_PAYLOAD_CHARS = 131_072;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public enum Category {
        BRIEFINGS_CARDS("briefings_cards", R.string.push_cat_briefings_label, R.string.push_cat_briefings_desc),
        SCREEN_UPDATES("screen_updates", R.string.push_cat_screen_label, R.string.push_cat_screen_desc),
        FILES("files", R.string.push_cat_files_label, R.string.push_cat_files_desc),
        PROPOSED_ACTIONS("proposed_actions", R.string.push_cat_actions_label, R.string.push_cat_actions_desc),
        REMINDERS("reminders", R.string.push_cat_reminders_label, R.string.push_cat_reminders_desc);

        public final String wireKey;
        public final int labelRes;
        public final int hintRes;

        Category(String wireKey, int labelRes, int hintRes) {
            this.wireKey = wireKey;
            this.labelRes = labelRes;
            this.hintRes = hintRes;
        }

        public String label(Context context) { return AppLanguage.wrapContext(context).getString(labelRes); }
        public String hint(Context context) { return AppLanguage.wrapContext(context).getString(hintRes); }

        public static Category fromWireKey(String raw) {
            if (raw == null) return null;
            for (Category c : values()) {
                if (c.wireKey.equals(raw)) return c;
            }
            return null;
        }

        /** Single source of truth mapping every push wire kind (typed delivery + legacy) to a user category. */
        public static Category forWireKind(String wireKind) {
            if (wireKind == null) return null;
            Kind typed = Kind.fromWire(wireKind);
            if (typed != null) return typed.category;
            return switch (wireKind) {
                case "run_completed", "run_failed" -> BRIEFINGS_CARDS;
                case "question_requested", "approval_requested" -> PROPOSED_ACTIONS;
                case "reminder" -> REMINDERS;
                default -> null;
            };
        }
    }

    public enum Kind {
        NOTICE("notice", Category.BRIEFINGS_CARDS,
                R.string.push_kind_notice_label, R.string.push_kind_notice_title, R.string.push_kind_notice_action),
        CARD("card", Category.BRIEFINGS_CARDS,
                R.string.push_kind_card_label, R.string.push_kind_card_title, R.string.push_kind_card_action),
        BRIEFING("briefing", Category.BRIEFINGS_CARDS,
                R.string.push_kind_briefing_label, R.string.push_kind_briefing_title, R.string.push_kind_briefing_action),
        FILE("file", Category.FILES,
                R.string.push_kind_file_label, R.string.push_kind_file_title, R.string.push_kind_file_action),
        UI_PATCH("ui_patch", Category.SCREEN_UPDATES,
                R.string.push_kind_ui_patch_label, R.string.push_kind_ui_patch_title, R.string.push_kind_ui_patch_action),
        WIDGET_UPDATE("widget_update", Category.SCREEN_UPDATES,
                R.string.push_kind_widget_label, R.string.push_kind_widget_title, R.string.push_kind_widget_action),
        DEVICE_ACTION("device_action", Category.PROPOSED_ACTIONS,
                R.string.push_kind_device_action_label, R.string.push_kind_device_action_title, R.string.push_kind_device_action_action),
        REMINDER_RESULT("reminder_result", Category.REMINDERS,
                R.string.push_kind_reminder_result_label, R.string.push_kind_reminder_result_title, R.string.push_kind_reminder_result_action);

        public final String wire;
        public final Category category;
        public final int labelRes;
        public final int lockscreenNoticeTitleRes;
        public final int primaryActionRes;

        Kind(String wire, Category category, int labelRes, int lockscreenNoticeTitleRes, int primaryActionRes) {
            this.wire = wire;
            this.category = category;
            this.labelRes = labelRes;
            this.lockscreenNoticeTitleRes = lockscreenNoticeTitleRes;
            this.primaryActionRes = primaryActionRes;
        }

        public String label(Context context) { return AppLanguage.wrapContext(context).getString(labelRes); }
        public String primaryAction(Context context) { return AppLanguage.wrapContext(context).getString(primaryActionRes); }

        public static Kind fromWire(String raw) {
            if (raw == null) return null;
            for (Kind k : values()) {
                if (k.wire.equals(raw)) return k;
            }
            return null;
        }
    }

    public enum Urgency {
        NORMAL("normal"),
        HIGH("high"),
        SILENT("silent");

        public final String wire;

        Urgency(String wire) {
            this.wire = wire;
        }

        public static Urgency fromWire(String raw) {
            if (raw == null) return null;
            String norm = raw.trim().toLowerCase(Locale.ROOT);
            if ("urgent".equals(norm)) return HIGH;
            for (Urgency u : values()) {
                if (u.wire.equals(norm)) return u;
            }
            return null;
        }

        public static Urgency fromWireOrDefault(String raw) {
            Urgency parsed = fromWire(raw);
            return parsed == null ? NORMAL : parsed;
        }
    }

    public final String id;
    public final Kind kind;
    public final Urgency urgency;
    public final String title;
    public final String summary;
    public final String payloadJson;
    public final long createdAtEpochSec;
    public final String sessionId;
    public final String runId;
    public final String jobId;
    public final long ackedAtEpochSec;

    public DeliveryItem(
            String id,
            Kind kind,
            Urgency urgency,
            String title,
            String summary,
            String payloadJson,
            long createdAtEpochSec,
            String sessionId,
            String runId,
            String jobId,
            long ackedAtEpochSec) {
        if (!isValidId(id)) throw new IllegalArgumentException("Invalid delivery id");
        this.id = id;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.urgency = urgency == null ? Urgency.NORMAL : urgency;
        String cleanTitle = cleanLine(title, MAX_TITLE_CHARS);
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("Delivery title required");
        this.title = cleanTitle;
        this.summary = clampSummary(summary);
        String safePayload = (payloadJson == null || payloadJson.trim().isEmpty()) ? "{}" : payloadJson;
        if (safePayload.length() > MAX_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("Delivery payload exceeds bound");
        }
        this.payloadJson = safePayload;
        this.createdAtEpochSec = Math.max(0L, createdAtEpochSec);
        this.sessionId = optionalLink(sessionId);
        this.runId = optionalLink(runId);
        this.jobId = optionalLink(jobId);
        this.ackedAtEpochSec = Math.max(0L, ackedAtEpochSec);
    }

    public boolean isAcknowledged() {
        return ackedAtEpochSec > 0L;
    }

    public int primaryActionRes() {
        return kind.primaryActionRes;
    }

    public String primaryActionLabel(Context context) {
        return kind.primaryAction(context);
    }

    public DeliveryItem withAcknowledged(long epochSec) {
        long stamp = Math.max(1L, epochSec);
        if (ackedAtEpochSec > 0L) return this;
        return new DeliveryItem(id, kind, urgency, title, summary, payloadJson,
                createdAtEpochSec, sessionId, runId, jobId, stamp);
    }

    public JSONObject payloadObject() {
        try {
            return new JSONObject(payloadJson);
        } catch (JSONException malformed) {
            return new JSONObject();
        }
    }

    public static boolean isValidId(String raw) {
        return raw != null && SAFE_ID.matcher(raw).matches() && !raw.contains("..");
    }

    public static DeliveryItem fromJson(JSONObject raw) {
        if (raw == null) throw new IllegalArgumentException("Missing delivery object");
        String id = raw.optString("delivery_id", "");
        if (id.isEmpty()) id = raw.optString("id", "");
        if (id.isEmpty()) id = raw.optString("event_id", "");
        Kind kind = Kind.fromWire(raw.optString("kind", ""));
        if (kind == null) throw new IllegalArgumentException("Unsupported delivery kind");
        String urgencyRaw = raw.optString("urgency", "");
        Urgency urgency = urgencyRaw.isEmpty() ? Urgency.NORMAL : Urgency.fromWire(urgencyRaw);
        if (urgency == null) throw new IllegalArgumentException("Unsupported delivery urgency");
        String title = raw.optString("title", "");
        JSONObject payload = raw.optJSONObject("payload");
        if (raw.has("payload") && !raw.isNull("payload") && payload == null) {
            throw new IllegalArgumentException("Delivery payload must be a JSON object");
        }
        String payloadJson = payload == null ? "{}" : payload.toString();
        String summary = extractSummary(raw, payload);
        long created = readEpochSec(raw, "created_at");
        long acked = readEpochSec(raw, "acked_at");
        String sessionId = raw.isNull("session_id") ? null : raw.optString("session_id", null);
        String runId = raw.isNull("run_id") ? null : raw.optString("run_id", null);
        String jobId = raw.isNull("job_id") ? null : raw.optString("job_id", null);
        return new DeliveryItem(id, kind, urgency, title, summary, payloadJson, created, sessionId, runId, jobId, acked);
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("delivery_id", id);
            out.put("kind", kind.wire);
            out.put("urgency", urgency.wire);
            out.put("title", title);
            if (!summary.isEmpty()) out.put("summary", summary);
            out.put("payload", payloadObject());
            out.put("created_at", createdAtEpochSec);
            if (sessionId != null) out.put("session_id", sessionId);
            if (runId != null) out.put("run_id", runId);
            if (jobId != null) out.put("job_id", jobId);
            if (ackedAtEpochSec > 0L) out.put("acked_at", ackedAtEpochSec);
        } catch (JSONException impossible) {
            throw new IllegalStateException("Failed to serialize delivery item", impossible);
        }
        return out;
    }

    private static final String[] SUMMARY_PAYLOAD_KEYS = {
            "summary", "subtitle", "message", "body", "text", "description", "filename"
    };

    private static String extractSummary(JSONObject raw, JSONObject payload) {
        String top = raw.isNull("summary") ? "" : raw.optString("summary", "");
        if (payload == null) return selectSummary(top);
        String[] fallbacks = new String[SUMMARY_PAYLOAD_KEYS.length];
        for (int i = 0; i < SUMMARY_PAYLOAD_KEYS.length; i++) {
            String key = SUMMARY_PAYLOAD_KEYS[i];
            fallbacks[i] = payload.isNull(key) ? null : payload.optString(key, "");
        }
        return selectSummary(top, fallbacks);
    }

    static String selectSummary(String topSummary, String... payloadFallbacks) {
        String top = clampSummary(topSummary);
        if (!top.isEmpty()) return top;
        if (payloadFallbacks != null) {
            for (String candidate : payloadFallbacks) {
                String val = clampSummary(candidate);
                if (!val.isEmpty()) return val;
            }
        }
        return "";
    }

    static String clampSummary(String raw) {
        String cleaned = PlainTextSanitizer.collapseWithEllipsis(raw, 300);
        return PlainTextSanitizer.hasVisibleText(cleaned) ? cleaned : "";
    }

    private static long readEpochSec(JSONObject raw, String field) {
        if (!raw.has(field) || raw.isNull(field)) return 0L;
        return parseEpochSecValue(raw.opt(field));
    }

    static long parseEpochSecValue(Object value) {
        return EpochTimestamps.parseEpochSeconds(value);
    }

    private static void rejectUnsafeCodePoints(String value, String message) {
        if (PlainTextSanitizer.hasUnsafeSingleLineCodePoint(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String cleanLine(String raw, int maxLen) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.length() > maxLen) throw new IllegalArgumentException("Field exceeds maximum length");
        rejectUnsafeCodePoints(trimmed, "Control character in delivery title");
        return PlainTextSanitizer.hasVisibleText(trimmed) ? trimmed : "";
    }

    private static String optionalLink(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 256) throw new IllegalArgumentException("Link identifier exceeds bound");
        rejectUnsafeCodePoints(trimmed, "Control character in link identifier");
        return PlainTextSanitizer.hasVisibleText(trimmed) ? trimmed : null;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DeliveryItem o)) return false;
        return createdAtEpochSec == o.createdAtEpochSec
                && ackedAtEpochSec == o.ackedAtEpochSec
                && id.equals(o.id)
                && kind == o.kind
                && urgency == o.urgency
                && title.equals(o.title)
                && summary.equals(o.summary)
                && payloadJson.equals(o.payloadJson)
                && Objects.equals(sessionId, o.sessionId)
                && Objects.equals(runId, o.runId)
                && Objects.equals(jobId, o.jobId);
    }

    @Override public int hashCode() {
        return Objects.hash(id, kind, urgency, title, createdAtEpochSec, ackedAtEpochSec);
    }
}
