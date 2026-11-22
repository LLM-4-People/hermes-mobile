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

import org.llm4people.hermes.R;

import java.util.Map;
import java.util.regex.Pattern;

final class PushNotices {
    static final String CHANNEL = "hermes_activity";
    static final String ACTION = "org.llm4people.hermes.OPEN_EVENT";
    private static final Pattern EVENT = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern INSTALLATION = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    static boolean validIds(String installation, String event) {
        return installation != null && INSTALLATION.matcher(installation).matches() && validEvent(event);
    }

    static boolean validEvent(String event) {
        return event != null && EVENT.matcher(event).matches();
    }

    static String title(String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case "run_completed" -> "Hermes a terminé";
            case "run_failed" -> "Vérifiez votre session";
            case "approval_requested" -> "Autorisation requise";
            case "reminder" -> "Un rappel vous attend";
            default -> null;
        };
    }

    static void channel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Activité d’Hermes",
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
        if (owner != null && !owner.unified() && owner.config.senderId.equals(sender))
            post(context, owner, event, data.get("kind"));
    }

    @SuppressLint("MissingPermission") // allowed() checks the current runtime grant.
    static void post(Context context, PushStore.Device owner, String event, String kind) {
        String id = owner.installation;
        if (!owner.receives() || !validIds(id, event)
                || title(kind) == null || !allowed(context)) return;
        PushStore store = PushStore.get(context);
        Intent intent = tapIntent(context, id, event);
        if (intent == null) return;
        PendingIntent tap = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        channel(context);
        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_push_notification)
                .setContentTitle(title(kind))
                .setContentText("Ouvrez votre profil pour voir les détails.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(tap)
                .setOnlyAlertOnce(true).setAutoCancel(true).build();

        synchronized (PushNotices.class) {
            if (store.remember(id,event,kind)) {
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
