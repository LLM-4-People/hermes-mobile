package org.llm4people.hermes.push;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;

import java.time.LocalTime;
import java.util.Map;
import java.util.regex.Pattern;

final class PushNotices {
    static final String QUESTION = "question_requested";
    static final String APPROVAL = "approval_requested";
    static final String DISMISS_APPROVAL = "approval_settled";
    static final long PROMPT_DISPLAY_MILLIS = java.util.concurrent.TimeUnit.HOURS.toMillis(24);
    static final String DISMISS_QUESTION = "question_settled";
    static final String CHANNEL = "hermes_activity";
    static final String ACTION = "org.llm4people.hermes.OPEN_EVENT";
    private static final Pattern EVENT = Pattern.compile("[0-9a-f]{32}|dlv_[0-9a-f]{16}");
    private static final Pattern INSTALLATION = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    static boolean validIds(String installation, String event) {
        return installation != null && INSTALLATION.matcher(installation).matches() && validEvent(event);
    }

    static boolean validEvent(String event) {
        return event != null && EVENT.matcher(event).matches();
    }

    static int titleRes(String kind) {
        if (kind == null) return 0;
        DeliveryItem.Kind typed = DeliveryItem.Kind.fromWire(kind);
        if (typed != null) return typed.lockscreenNoticeTitleRes;
        return switch (kind) {
            case "run_completed" -> R.string.push_notice_run_completed;
            case "run_failed" -> R.string.push_notice_run_attention;
            case QUESTION -> R.string.push_notice_question;
            case APPROVAL -> R.string.push_notice_approval;
            case "reminder" -> R.string.push_notice_reminder;
            default -> 0;
        };
    }

    static String title(Context context, String kind) {
        int res = titleRes(kind);
        if (res == 0 || context == null) return null;
        return AppLanguage.wrapContext(context).getString(res);
    }

    static boolean isPrompt(String kind) {
        return QUESTION.equals(kind) || APPROVAL.equals(kind);
    }

    static void channel(Context context) {
        Context localized = AppLanguage.wrapContext(context);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                localized.getString(R.string.push_channel_activity),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null
                || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    @SuppressLint("MissingPermission") // allowed() checks the runtime grant before posting.
    static void deliver(Context context, String sender, Map<String,String> data) {
        String id = data.get("installation_id"), event = data.get("event_id");
        if (!validIds(id, event)) return;
        PushStore.Device owner = PushStore.get(context).forInstallation(id);
        if (owner != null && !owner.unified() && owner.config.senderId.equals(sender)) {
            DeliveryItem.Urgency urgency = DeliveryItem.Urgency.fromWireOrDefault(data.get("urgency"));
            post(context, owner, event, data.get("kind"), urgency);
        }
    }

    static void post(Context context, PushStore.Device owner, String event, String kind) {
        post(context, owner, event, kind, DeliveryItem.Urgency.NORMAL);
    }

    static void post(Context context, PushStore.Device owner, String event, String kind,
                     DeliveryItem.Urgency urgency) {
        LocalTime now = LocalTime.now();
        postAt(context, owner, event, kind, urgency, now.getHour() * 60 + now.getMinute());
    }

    @SuppressLint("MissingPermission") // allowed() checks the current runtime grant.
    static void postAt(Context context, PushStore.Device owner, String event, String kind,
                       DeliveryItem.Urgency urgency, int minuteOfDay) {
        String id = owner.installation;
        if (!owner.receives() || !validIds(id, event)) return;
        PushStore store = PushStore.get(context);
        if (DISMISS_QUESTION.equals(kind) || DISMISS_APPROVAL.equals(kind)) {
            // A transport may deliver the dismissal before the original notice.
            synchronized (PushNotices.class) {
                store.dismissPrompt(id, event);
                context.getSystemService(NotificationManager.class).cancel(id + "/" + event, 1);
            }
            return;
        }
        Context localized = AppLanguage.wrapContext(context);
        String noticeTitle = title(localized, kind);
        if (noticeTitle == null || !allowed(context)) return;
        PushPolicy policy = PushPolicy.load(context, owner.profile);
        PushPolicy.Decision decision = policy.evaluate(kind, urgency, minuteOfDay);
        if (decision != PushPolicy.Decision.NOTIFY) {
            Diagnostics.event("push", "notice_suppressed", decision.reasonCode);
            return;
        }
        Intent intent = tapIntent(context, id, event);
        if (intent == null) return;
        PendingIntent tap = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        channel(localized);
        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_push_notification)
                .setContentTitle(noticeTitle)
                .setContentText(localized.getString(R.string.push_notice_open_details))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(tap)
                .setOnlyAlertOnce(true).setAutoCancel(true)
                .setTimeoutAfter(isPrompt(kind) ? PROMPT_DISPLAY_MILLIS : 0).build();

        synchronized (PushNotices.class) {
            if (store.remember(id, event, kind)) {
                context.getSystemService(NotificationManager.class).notify(id + "/" + event, 1, notice);
            }
        }
    }

    static Intent tapIntent(Context context, String id, String event) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) return null;
        intent.setAction(ACTION).setData(new Uri.Builder().scheme("hermes-mobile").authority("event")
                .appendPath(id).appendPath(event).build())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    static synchronized void cancel(Context context, String installation) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getTag() != null && active.getTag().startsWith(installation + "/")) {
                manager.cancel(active.getTag(), active.getId());
            }
        }
    }
}
