package org.llm4people.hermes.presence;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.os.HermesWidgetSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Profile-scoped ({@code profileEpoch}-fenced) live app presence &amp; read-without-reply tracker.
 * Captures active screen/thread, concurrent run count, draft length (never leaking draft text
 * unless explicitly opted in), and read-without-reply signals for both in-app assistant messages
 * and opted-in Android notifications. Emits sanitized wire JSON matching Track 9A's
 * {@code mobile_app_presence} contract, failing closed whenever opt-in toggles are OFF.
 */
public final class AppPresenceTracker {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NOTIFICATION_SIGNALS = 8;
    public static final long NOTIFICATION_SIGNAL_TTL_MS = 30L * 60_000L;
    public static final int MAX_DRAFT_PREVIEW_CHARS = 120;

    private static final Pattern SCREEN_ID = Pattern.compile("[a-z0-9_/-]{1,64}");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,96}");

    public record NotificationReadSignal(String key, String appLabel, long readAtMs) {}

    public record ReadWithoutReplyState(
            boolean inAppUnreadReply,
            String sessionId,
            String messageId,
            long readAgeSec,
            List<DismissedNotificationView> dismissedNotifications) {
        public static final ReadWithoutReplyState NONE =
                new ReadWithoutReplyState(false, "", "", 0L, Collections.emptyList());

        public boolean hasAnySignal() {
            return inAppUnreadReply || !dismissedNotifications.isEmpty();
        }
    }

    public record DismissedNotificationView(String appLabel, long ageSec) {}

    private final Object lock = new Object();
    private String boundProfileId;
    private long profileEpoch;
    private String currentScreen = "";
    private String activeSessionId = "";
    private int activeRunCount;
    private int draftChars;
    private String draftPreview = "";

    // In-app read-without-reply state for the latest assistant message in a thread.
    private String lastAssistantSessionId = "";
    private String lastAssistantMessageId = "";
    private long lastAssistantReadAtMs;
    private boolean userRepliedAfterRead;

    // Opted-in notification read/dismiss signals without reply.
    private final Deque<NotificationReadSignal> notificationSignals = new ArrayDeque<>();

    /** Bind tracker to an unlocked profile and epoch, clearing any prior profile's transient state. */
    public long bindProfile(String profileId, long epoch) {
        synchronized (lock) {
            clearStateLocked();
            boundProfileId = profileId == null ? "" : profileId.trim();
            profileEpoch = epoch;
            Diagnostics.debug("presence", "tracker_bound");
            return profileEpoch;
        }
    }

    /** Immediately wipe all live state and fence out stale callbacks on profile lock/switch/forget. */
    public void clearOnLock() {
        synchronized (lock) {
            clearStateLocked();
            boundProfileId = null;
            profileEpoch++;
            Diagnostics.debug("presence", "tracker_cleared");
        }
    }

    public long currentEpoch() {
        synchronized (lock) {
            return profileEpoch;
        }
    }

    public String boundProfileId() {
        synchronized (lock) {
            return boundProfileId;
        }
    }

    public boolean updateScreen(long epoch, String screenId, String sessionId) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            currentScreen = sanitizeScreen(screenId);
            activeSessionId = sanitizeId(sessionId);
            return true;
        }
    }

    public boolean updateActiveRunCount(long epoch, int count) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            activeRunCount = Math.max(0, Math.min(32, count));
            return true;
        }
    }

    /** Updates draft character count (and optional sanitized preview kept only if explicitly opted in). */
    public boolean updateDraft(long epoch, String draftText) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            if (draftText == null || draftText.isEmpty()) {
                draftChars = 0;
                draftPreview = "";
            } else {
                draftChars = Math.min(200_000, draftText.length());
                draftPreview = sanitizeBoundedText(draftText, MAX_DRAFT_PREVIEW_CHARS);
            }
            return true;
        }
    }

    /** Records that an assistant message arrived in a thread, awaiting user read. */
    public boolean recordAssistantMessageDelivered(long epoch, String sessionId, String messageId) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            String safeSession = sanitizeId(sessionId);
            String safeMsg = sanitizeId(messageId);
            if (safeSession.isEmpty() || safeMsg.isEmpty()) return false;
            lastAssistantSessionId = safeSession;
            lastAssistantMessageId = safeMsg;
            lastAssistantReadAtMs = 0L;
            userRepliedAfterRead = false;
            return true;
        }
    }

    /** Marks the latest assistant message as visible/read by the user at {@code nowMs}. */
    public boolean markAssistantMessageRead(long epoch, String sessionId, String messageId, long nowMs) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            String safeSession = sanitizeId(sessionId);
            String safeMsg = sanitizeId(messageId);
            if (safeSession.isEmpty() || safeMsg.isEmpty()) return false;
            if (!safeSession.equals(lastAssistantSessionId) || !safeMsg.equals(lastAssistantMessageId)) {
                lastAssistantSessionId = safeSession;
                lastAssistantMessageId = safeMsg;
                userRepliedAfterRead = false;
            }
            if (!userRepliedAfterRead && lastAssistantReadAtMs == 0L) {
                lastAssistantReadAtMs = Math.max(1L, nowMs);
            }
            return true;
        }
    }

    /** Immediately clears in-app read-without-reply when the user sends a reply. */
    public boolean recordUserReplySent(long epoch, String sessionId) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            String safeSession = sanitizeId(sessionId);
            if (safeSession.isEmpty() || safeSession.equals(lastAssistantSessionId)) {
                userRepliedAfterRead = true;
                lastAssistantReadAtMs = 0L;
            }
            draftChars = 0;
            draftPreview = "";
            return true;
        }
    }

    /** Records an opted-in Android notification that the user read or dismissed without replying. */
    public boolean recordNotificationReadWithoutReply(long epoch, String key, String appLabel, long nowMs) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            String safeKey = sanitizeId(key);
            String safeApp = sanitizeBoundedText(appLabel, 48);
            if (safeKey.isEmpty() || safeApp.isEmpty()) return false;
            removeNotificationLocked(safeKey);
            while (notificationSignals.size() >= MAX_NOTIFICATION_SIGNALS) {
                notificationSignals.removeFirst();
            }
            notificationSignals.addLast(new NotificationReadSignal(safeKey, safeApp, Math.max(1L, nowMs)));
            return true;
        }
    }

    /** Clears an opted-in notification read-without-reply signal once the user replies. */
    public boolean recordNotificationReplied(long epoch, String key) {
        synchronized (lock) {
            if (!matchesEpochLocked(epoch)) return false;
            String safeKey = sanitizeId(key);
            if (safeKey.isEmpty()) return false;
            removeNotificationLocked(safeKey);
            return true;
        }
    }

    /** Pure evaluation of read-without-reply state respecting policy opt-in and grace window. */
    public ReadWithoutReplyState evaluateReadWithoutReply(PresencePolicy policy, long nowMs) {
        if (policy == null || !policy.shareReadWithoutReply) {
            return ReadWithoutReplyState.NONE;
        }
        long graceMs = policy.readWithoutReplyGraceSec * 1000L;
        synchronized (lock) {
            if (boundProfileId == null) return ReadWithoutReplyState.NONE;
            boolean inApp = false;
            long inAppAgeSec = 0L;
            if (!userRepliedAfterRead && lastAssistantReadAtMs > 0L) {
                long elapsed = nowMs - lastAssistantReadAtMs;
                if (elapsed >= graceMs) {
                    inApp = true;
                    inAppAgeSec = elapsed / 1000L;
                }
            }
            pruneStaleNotificationsLocked(nowMs);
            List<DismissedNotificationView> notifViews = new ArrayList<>();
            for (NotificationReadSignal sig : notificationSignals) {
                long elapsed = nowMs - sig.readAtMs();
                if (elapsed >= graceMs) {
                    notifViews.add(new DismissedNotificationView(sig.appLabel(), elapsed / 1000L));
                }
            }
            return new ReadWithoutReplyState(
                    inApp,
                    inApp ? lastAssistantSessionId : "",
                    inApp ? lastAssistantMessageId : "",
                    inAppAgeSec,
                    Collections.unmodifiableList(notifViews));
        }
    }

    /**
     * Produces sanitized wire JSON string matching Track 9A's {@code app_presence} contract.
     * Returns {@code null} (fail-closed) when no presence stream is opted in or profile is locked.
     */
    public String toWireContextJson(
            PresencePolicy policy,
            ContinuousLocationTracker.Snapshot locationSnapshot,
            long nowMs) {
        if (policy == null) return null;
        boolean includeScreen = policy.shareAppPresence;
        boolean includeRead = policy.shareReadWithoutReply;
        ContinuousLocationTracker.Snapshot validLoc =
                (policy.allowsLiveLocation() && locationSnapshot != null && !locationSnapshot.isStale(nowMs)
                        && (policy.preciseLocation || !locationSnapshot.precise()))
                        ? locationSnapshot : null;
        if (!includeScreen && !includeRead && validLoc == null) {
            return null;
        }

        ReadWithoutReplyState rwr = includeRead ? evaluateReadWithoutReply(policy, nowMs) : ReadWithoutReplyState.NONE;
        synchronized (lock) {
            if (boundProfileId == null) return null;
            StringBuilder json = new StringBuilder(256);
            json.append("{\"schema_version\":").append(SCHEMA_VERSION);
            if (includeScreen) {
                json.append(",\"current_screen\":").append(quoteJson(currentScreen));
                json.append(",\"active_session_id\":").append(quoteJson(activeSessionId));
                json.append(",\"active_run_count\":").append(activeRunCount);
                json.append(",\"draft_chars\":").append(draftChars);
                if (policy.shareDraftPreview && !draftPreview.isEmpty()) {
                    json.append(",\"draft_preview\":").append(quoteJson(draftPreview));
                }
            }
            if (includeRead) {
                json.append(",\"read_without_reply\":{");
                json.append("\"in_app_unread_reply\":").append(rwr.inAppUnreadReply());
                if (rwr.inAppUnreadReply()) {
                    json.append(",\"session_id\":").append(quoteJson(rwr.sessionId()));
                    json.append(",\"message_id\":").append(quoteJson(rwr.messageId()));
                    json.append(",\"in_app_seconds_since_read\":").append(rwr.readAgeSec());
                }
                json.append(",\"notifications\":[");
                List<DismissedNotificationView> items = rwr.dismissedNotifications();
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) json.append(',');
                    DismissedNotificationView item = items.get(i);
                    json.append("{\"source\":").append(quoteJson(item.appLabel()))
                            .append(",\"age_s\":").append(item.ageSec()).append('}');
                }
                json.append("]}");
            }
            if (validLoc != null) {
                boolean emitPrecise = policy.preciseLocation && validLoc.precise();
                String coordFmt = emitPrecise ? "%.5f" : "%.2f";
                double latVal = emitPrecise ? validLoc.lat() : ContinuousLocationTracker.quantizeCoarse(validLoc.lat());
                double lonVal = emitPrecise ? validLoc.lon() : ContinuousLocationTracker.quantizeCoarse(validLoc.lon());
                float accVal = emitPrecise ? validLoc.accuracyM()
                        : Math.max(ContinuousLocationTracker.COARSE_MIN_ACCURACY_M, validLoc.accuracyM());
                json.append(",\"continuous_location\":{");
                json.append("\"opted_in\":true");
                json.append(",\"mode\":").append(quoteJson(emitPrecise ? "precise" : "coarse"));
                json.append(",\"lat\":").append(String.format(Locale.ROOT, coordFmt, latVal));
                json.append(",\"lon\":").append(String.format(Locale.ROOT, coordFmt, lonVal));
                json.append(",\"accuracy_m\":").append(Math.round(accVal));
                json.append(",\"place_label\":").append(quoteJson(validLoc.placeLabel()));
                json.append(",\"moving_state\":").append(quoteJson(validLoc.movingState().wire));
                json.append(",\"age_s\":").append(validLoc.ageSeconds(nowMs));
                json.append('}');
            }
            json.append('}');
            return json.toString();
        }
    }

    /** Android convenience wrapper returning parsed {@link JSONObject}, or {@code null} when opted out. */
    public JSONObject toWireContext(
            PresencePolicy policy,
            ContinuousLocationTracker.Snapshot locationSnapshot,
            long nowMs) {
        String encoded = toWireContextJson(policy, locationSnapshot, nowMs);
        if (encoded == null) return null;
        try {
            return new JSONObject(encoded);
        } catch (JSONException unexpected) {
            Diagnostics.warning("presence", "wire_json_invalid");
            return null;
        }
    }

    private boolean matchesEpochLocked(long epoch) {
        return boundProfileId != null && epoch == profileEpoch;
    }

    private void clearStateLocked() {
        currentScreen = "";
        activeSessionId = "";
        activeRunCount = 0;
        draftChars = 0;
        draftPreview = "";
        lastAssistantSessionId = "";
        lastAssistantMessageId = "";
        lastAssistantReadAtMs = 0L;
        userRepliedAfterRead = false;
        notificationSignals.clear();
    }

    private void removeNotificationLocked(String key) {
        notificationSignals.removeIf(item -> item.key().equals(key));
    }

    private void pruneStaleNotificationsLocked(long nowMs) {
        notificationSignals.removeIf(item -> nowMs - item.readAtMs() > NOTIFICATION_SIGNAL_TTL_MS);
    }

    static String sanitizeScreen(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return SCREEN_ID.matcher(lower).matches() ? lower : "";
    }

    static String sanitizeId(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : "";
    }

    static String sanitizeBoundedText(String raw, int maxChars) {
        if (raw == null || raw.isEmpty()) return "";
        if (HermesWidgetSpec.containsSecretLikePattern(raw)) return "";
        StringBuilder out = new StringBuilder();
        boolean prevSpace = false;
        for (int i = 0; i < raw.length() && out.length() < maxChars; i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) continue;
            if (Character.isWhitespace(c)) {
                if (out.length() > 0 && !prevSpace) {
                    out.append(' ');
                    prevSpace = true;
                }
            } else {
                out.append(c);
                prevSpace = false;
            }
        }
        return out.toString().trim();
    }

    static String quoteJson(String raw) {
        return JsonValues.quote(raw);
    }
}
