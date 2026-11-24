package org.llm4people.hermes.ambient;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.Account;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Opt-in notification listener with strict in-memory-only sanitized digest buffering.
 * Off by default (empty active allowlist). Hard-blocks sensitive apps, OTP/2FA, secret visibility,
 * and ongoing system noise via {@link NotificationPrivacyGuard}. Never persists third-party bodies.
 */
public final class HermesNotificationListener extends NotificationListenerService {
    private static final int MAX_REPLY_CHARS = 1000;

    public enum ReplyOutcome {
        SENT,
        FAILED,
        CANCELLED
    }

    private record ReplyHandle(PendingIntent actionIntent, RemoteInput remoteInput, String label) {}

    private static final Object LOCK = new Object();
    private static final Map<String, AmbientNotification> BUFFER = new LinkedHashMap<>();
    private static final Map<String, ReplyHandle> REPLY_HANDLES = new HashMap<>();
    private static volatile Set<String> activeAllowlist = Collections.emptySet();
    private static long sessionEpoch = 0L;
    private static volatile HermesNotificationListener connectedInstance;

    public static ComponentName componentName(Context context) {
        return new ComponentName(context.getApplicationContext(), HermesNotificationListener.class);
    }

    /** True when the user enabled this service in Android's Notification Access settings. */
    public static boolean isListenerEnabled(Context context) {
        if (context == null) return false;
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName self = componentName(context);
            String flattened = self.flattenToString();
            String shortFlattened = self.flattenToShortString();
            for (String entry : enabled.split(":")) {
                String trimmed = entry.trim();
                if (flattened.equals(trimmed) || shortFlattened.equals(trimmed)) return true;
            }
            return false;
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "listener_check_failed", ex);
            return false;
        }
    }

    public static boolean isServiceConnected() {
        return connectedInstance != null;
    }

    /** Bind the currently unlocked profile's encrypted allowlist directly from its Account. */
    public static void bindProfile(Context context, Account account) {
        if (account == null) {
            clearSession();
            return;
        }
        bindProfile(NotificationAllowlist.forAccount(account).effectiveAllowedPackages());
    }

    /** Bind the currently unlocked profile's effective allowlist and refresh the in-memory digest. */
    public static void bindProfile(Set<String> allowedPackages) {
        Set<String> sanitized = sanitizeAllowlist(allowedPackages);
        HermesNotificationListener service;
        synchronized (LOCK) {
            sessionEpoch++;
            activeAllowlist = sanitized;
            BUFFER.clear();
            REPLY_HANDLES.clear();
            service = connectedInstance;
        }
        if (service != null && !sanitized.isEmpty()) {
            service.refreshActiveLocked();
        }
        Diagnostics.event("ambient", sanitized.isEmpty() ? "session_disabled" : "session_bound");
    }

    /** Clear all in-memory third-party notification content and reply handles on lock or forget. */
    public static void clearSession() {
        synchronized (LOCK) {
            sessionEpoch++;
            activeAllowlist = Collections.emptySet();
            BUFFER.clear();
            REPLY_HANDLES.clear();
        }
        Diagnostics.event("ambient", "session_cleared");
    }

    static long currentSessionEpoch() {
        synchronized (LOCK) {
            return sessionEpoch;
        }
    }

    static boolean commitBufferedIfCurrent(long expectedEpoch, Set<String> expectedAllowlist,
                                           AmbientNotification item, ReplyHandle reply) {
        synchronized (LOCK) {
            if (sessionEpoch != expectedEpoch || activeAllowlist != expectedAllowlist || activeAllowlist.isEmpty()) {
                return false;
            }
            BUFFER.remove(item.key);
            BUFFER.put(item.key, item);
            if (reply != null) REPLY_HANDLES.put(item.key, reply);
            else REPLY_HANDLES.remove(item.key);
            trimBufferLocked();
            return true;
        }
    }

    /** Bounded newest-first snapshot of currently buffered sanitized notifications (max 40). */
    public static List<AmbientNotification> snapshot() {
        synchronized (LOCK) {
            List<AmbientNotification> list = new ArrayList<>(BUFFER.values());
            list.sort(Comparator.comparingLong((AmbientNotification n) -> n.postedAtMillis).reversed());
            return Collections.unmodifiableList(list);
        }
    }

    /** Dismiss a buffered notification on the device and evict it from memory immediately. */
    public static boolean dismiss(String key) {
        if (key == null || key.isEmpty()) return false;
        HermesNotificationListener service;
        synchronized (LOCK) {
            BUFFER.remove(key);
            REPLY_HANDLES.remove(key);
            service = connectedInstance;
        }
        if (service == null) return false;
        try {
            service.cancelNotification(key);
            Diagnostics.event("ambient", "notification_dismissed");
            return true;
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "notification_dismiss_failed", ex);
            return false;
        }
    }

    /**
     * Send a RemoteInput quick reply ONLY after explicit user confirmation.
     * Refuses unconditionally when {@code userConfirmed} is false.
     */
    public static boolean sendReviewedReply(
            Context context, String key, String replyText, boolean userConfirmed) {
        if (!userConfirmed) {
            Diagnostics.warning("ambient", "quick_reply_rejected", "unconfirmed");
            throw new IllegalStateException("Quick reply requires explicit user confirmation");
        }
        if (context == null || key == null || key.isEmpty()) return false;
        String clean = NotificationPrivacyGuard.sanitizeText(replyText, MAX_REPLY_CHARS);
        if (clean.isEmpty()) return false;
        ReplyHandle handle;
        synchronized (LOCK) {
            handle = REPLY_HANDLES.get(key);
        }
        if (handle == null) {
            Diagnostics.warning("ambient", "quick_reply_missing_handle");
            return false;
        }
        try {
            Intent fillIn = new Intent();
            Bundle results = new Bundle();
            results.putCharSequence(handle.remoteInput().getResultKey(), clean);
            RemoteInput.addResultsToIntent(new RemoteInput[]{handle.remoteInput()}, fillIn, results);
            handle.actionIntent().send(context.getApplicationContext(), 0, fillIn);
            Diagnostics.event("ambient", "quick_reply_sent");
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException ex) {
            Diagnostics.warning("ambient", "quick_reply_failed", ex);
            return false;
        }
    }

    /**
     * Present an explicit, FLAG_SECURE confirmation dialog showing app name, sender, and an editable
     * reply text before calling {@link #sendReviewedReply}. Never sends without the user tapping Send.
     */
    public static AlertDialog showQuickReplyConfirmation(
            Activity activity,
            AmbientNotification item,
            String initialDraft,
            Consumer<ReplyOutcome> onResult) {
        return showQuickReplyConfirmation(activity, item, initialDraft, () -> true, onResult);
    }

    /**
     * Present an explicit, FLAG_SECURE confirmation dialog that re-verifies {@code stillAllowed}
     * at confirmation time before sending the quick reply.
     */
    public static AlertDialog showQuickReplyConfirmation(
            Activity activity,
            AmbientNotification item,
            String initialDraft,
            java.util.function.BooleanSupplier stillAllowed,
            Consumer<ReplyOutcome> onResult) {
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 20, 12);
        TextView target = Ui.text(activity,
                activity.getString(R.string.ambient_reply_target, item.displaySource(), item.displaySender()), 14);
        target.setTextColor(activity.getColor(R.color.muted));
        content.addView(target);

        EditText editor = new EditText(activity);
        editor.setHint(R.string.ambient_reply_hint);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        editor.setMinHeight(Ui.dp(activity, 56));
        editor.setTextColor(activity.getColor(R.color.ink));
        editor.setHintTextColor(activity.getColor(R.color.muted));
        editor.setSaveEnabled(false);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_REPLY_CHARS)});
        if (initialDraft != null) editor.setText(initialDraft);
        content.addView(editor);

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, -2));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.ambient_reply_title)
                .setView(scroll)
                .setNegativeButton(R.string.ambient_reply_cancel, (d, w) -> {
                    if (onResult != null) onResult.accept(ReplyOutcome.CANCELLED);
                })
                .setOnCancelListener(d -> {
                    if (onResult != null) onResult.accept(ReplyOutcome.CANCELLED);
                })
                .setPositiveButton(R.string.ambient_reply_send, (d, w) -> {
                    if (stillAllowed != null && !stillAllowed.getAsBoolean()) {
                        Diagnostics.warning("ambient", "quick_reply_revoked");
                        if (onResult != null) onResult.accept(ReplyOutcome.FAILED);
                        return;
                    }
                    boolean sent = sendReviewedReply(activity, item.key, editor.getText().toString(), true);
                    if (onResult != null) onResult.accept(sent ? ReplyOutcome.SENT : ReplyOutcome.FAILED);
                });
        return Ui.secureDialog(builder);
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        connectedInstance = this;
        Diagnostics.event("ambient", "listener_connected");
        refreshActiveLocked();
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connectedInstance = null;
        synchronized (LOCK) {
            BUFFER.clear();
            REPLY_HANDLES.clear();
        }
        Diagnostics.event("ambient", "listener_disconnected");
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        ingest(sbn);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || sbn.getKey() == null) return;
        synchronized (LOCK) {
            BUFFER.remove(sbn.getKey());
            REPLY_HANDLES.remove(sbn.getKey());
        }
    }

    private void refreshActiveLocked() {
        if (activeAllowlist.isEmpty()) return;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) {
                ingest(sbn);
            }
        } catch (RuntimeException ex) {
            Diagnostics.warning("ambient", "refresh_active_failed", ex);
        }
    }

    private void ingest(StatusBarNotification sbn) {
        final Set<String> allowlist;
        final long epoch;
        synchronized (LOCK) {
            allowlist = activeAllowlist;
            epoch = sessionEpoch;
        }
        if (allowlist.isEmpty()) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = readExtra(extras, Notification.EXTRA_TITLE);
        String bigText = readExtra(extras, Notification.EXTRA_BIG_TEXT);
        String text = !bigText.isEmpty() ? bigText : readExtra(extras, Notification.EXTRA_TEXT);
        String conversation = readExtra(extras, Notification.EXTRA_CONVERSATION_TITLE);
        String sender = !conversation.isEmpty() ? conversation : title;

        boolean ongoing = sbn.isOngoing() || (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        boolean foreground = (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        boolean groupSummary = (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;

        NotificationPrivacyGuard.Candidate candidate = new NotificationPrivacyGuard.Candidate(
                sbn.getPackageName(),
                sender,
                title,
                text,
                notification.visibility,
                ongoing,
                foreground,
                groupSummary,
                notification.category);
        NotificationPrivacyGuard.Decision decision = NotificationPrivacyGuard.evaluate(candidate, allowlist);
        if (decision != NotificationPrivacyGuard.Decision.ALLOW) {
            // Never retain previously buffered entry if an updated notification becomes blocked.
            synchronized (LOCK) {
                if (sessionEpoch == epoch && activeAllowlist == allowlist) {
                    BUFFER.remove(sbn.getKey());
                    REPLY_HANDLES.remove(sbn.getKey());
                }
            }
            return;
        }

        ReplyHandle reply = extractReplyHandle(notification);
        AmbientNotification item = new AmbientNotification(
                sbn.getKey(),
                sbn.getPackageName(),
                resolveAppLabel(sbn.getPackageName()),
                sender,
                title,
                text,
                sbn.getPostTime(),
                reply != null,
                reply != null ? reply.label() : "");

        commitBufferedIfCurrent(epoch, allowlist, item, reply);
    }

    private static void trimBufferLocked() {
        while (BUFFER.size() > NotificationPrivacyGuard.MAX_BUFFER_SIZE) {
            String oldestKey = BUFFER.keySet().iterator().next();
            BUFFER.remove(oldestKey);
            REPLY_HANDLES.remove(oldestKey);
        }
    }

    private static ReplyHandle extractReplyHandle(Notification notification) {
        if (notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.actionIntent == null) continue;
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null) continue;
            for (RemoteInput input : inputs) {
                if (input != null && input.getAllowFreeFormInput() && input.getResultKey() != null) {
                    String label = action.title != null ? action.title.toString() : "";
                    return new ReplyHandle(action.actionIntent, input, label);
                }
            }
        }
        return null;
    }

    private String resolveAppLabel(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return packageName;
        }
    }

    private static String readExtra(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private static Set<String> sanitizeAllowlist(Set<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        java.util.Set<String> clean = new java.util.LinkedHashSet<>();
        for (String pkg : raw) {
            String normalized = NotificationAllowlist.normalizePackage(pkg);
            if (normalized != null && !NotificationPrivacyGuard.isHardBlockedPackage(normalized)) {
                clean.add(normalized);
            }
        }
        return clean.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(clean);
    }
}
