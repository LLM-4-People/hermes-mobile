package org.llm4people.hermes.os;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;

/**
 * Android 16 Live Updates ongoing run notification.
 * Privacy by construction: Snapshot carries only bounded phase and step counters, never private
 * prompt, transcript, thread title, tool argument, or profile text.
 */
public final class LiveRunNotification {
    public static final String CHANNEL_ID = "hermes_live_run";
    public static final int NOTIFICATION_ID = 4106;
    private static final int REQUEST_OPEN = 4107;
    private static final int REQUEST_STOP = 4108;
    private static final int SEGMENT_PREP = 20;
    private static final int SEGMENT_WORK = 60;
    private static final int SEGMENT_FINISH = 20;
    private static final int TOTAL_UNITS = SEGMENT_PREP + SEGMENT_WORK + SEGMENT_FINISH;

    public enum Phase {
        IDLE,
        STARTING,
        WORKING,
        TOOL_RUNNING,
        AWAITING_DECISION
    }

    /** Strictly non-private run progress state. Never add user/assistant strings here. */
    public record Snapshot(Phase phase, int completedSteps, int totalSteps, boolean awaitingDecision, boolean canStop) {
        public static Snapshot idle() {
            return new Snapshot(Phase.IDLE, 0, 0, false, false);
        }

        public boolean active() {
            return phase != null && phase != Phase.IDLE;
        }
    }

    public record Presentation(String title, String body, String shortCriticalText,
                               int progress, int max, boolean indeterminate) {}

    private LiveRunNotification() {}

    /** Pure privacy-safe presentation mapper tested deterministically on the host JVM. */
    public static Presentation present(Snapshot snapshot) {
        if (snapshot == null || !snapshot.active()) return null;
        int completed = Math.max(0, Math.min(99, snapshot.completedSteps()));
        int total = Math.max(0, Math.min(99, snapshot.totalSteps()));
        if (snapshot.awaitingDecision() || snapshot.phase() == Phase.AWAITING_DECISION) {
            return new Presentation(
                    "Décision requise",
                    "Hermes attend votre réponse dans l’application.",
                    "Action",
                    SEGMENT_PREP + SEGMENT_WORK / 2,
                    TOTAL_UNITS,
                    false);
        }
        String chip;
        int progress;
        boolean indeterminate;
        if (total > 0 && completed > 0) {
            int boundedCompleted = Math.min(completed, total);
            chip = "Étape " + boundedCompleted + "/" + total;
            progress = SEGMENT_PREP + Math.round((SEGMENT_WORK * (float) boundedCompleted) / total);
            indeterminate = false;
        } else if (completed > 0) {
            chip = "Étape " + completed;
            progress = Math.min(SEGMENT_PREP + SEGMENT_WORK, SEGMENT_PREP + completed * 12);
            indeterminate = false;
        } else if (snapshot.phase() == Phase.STARTING) {
            chip = "Envoi";
            progress = SEGMENT_PREP / 2;
            indeterminate = true;
        } else {
            chip = "En cours";
            progress = SEGMENT_PREP + SEGMENT_WORK / 3;
            indeterminate = true;
        }
        return new Presentation(
                "Hermes travaille…",
                "Ouvrez l’application pour suivre la réponse.",
                chip,
                progress,
                TOTAL_UNITS,
                indeterminate);
    }

    @SuppressLint("MissingPermission")
    public static void update(Context context, Snapshot snapshot) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (snapshot == null || !snapshot.active()) {
            cancel(app);
            HermesWidgetProvider.refresh(app, false);
            return;
        }
        HermesWidgetProvider.refresh(app, true);
        if (!allowed(app)) return;
        Presentation model = present(snapshot);
        if (model == null) {
            cancel(app);
            return;
        }
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannel(app, manager);
        Notification notification = build(app, manager, snapshot, model);
        try {
            manager.notify(NOTIFICATION_ID, notification);
        } catch (RuntimeException error) {
            Diagnostics.warning("live_run", "notify_failed");
        }
    }

    public static void cancel(Context context) {
        if (context == null) return;
        NotificationManager manager = context.getApplicationContext().getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    static Notification build(Context context, NotificationManager manager,
                              Snapshot snapshot, Presentation model) {
        PendingIntent open = OsEntryIntent.pendingIntent(context, OsEntryIntent.Target.ASK, REQUEST_OPEN);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_push_notification)
                .setContentTitle(model.title())
                .setContentText(model.body())
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_push_notification),
                        context.getString(R.string.live_run_action_open),
                        open).build());
        if (snapshot.canStop()) {
            Intent stopIntent = new Intent(context, LiveRunActionReceiver.class)
                    .setAction(LiveRunActionReceiver.ACTION_STOP_LIVE_RUN);
            PendingIntent stop = PendingIntent.getBroadcast(context, REQUEST_STOP, stopIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.thread_stop),
                    context.getString(R.string.live_run_action_stop),
                    stop).build());
        }
        if (Build.VERSION.SDK_INT >= 36) {
            applyAndroid16LiveUpdate(context, manager, builder, model);
        } else {
            builder.setProgress(model.max(), model.progress(), model.indeterminate());
        }
        Notification built = builder.build();
        if (Build.VERSION.SDK_INT >= 36 && manager != null && manager.canPostPromotedNotifications()) {
            built.flags |= Notification.FLAG_PROMOTED_ONGOING;
        }
        return built;
    }

    private static void applyAndroid16LiveUpdate(Context context, NotificationManager manager,
                                                 Notification.Builder builder, Presentation model) {
        if (Build.VERSION.SDK_INT < 36) return;
        int accent = context.getColor(R.color.accent);
        int muted = context.getColor(R.color.line);
        Notification.ProgressStyle style = new Notification.ProgressStyle()
                .addProgressSegment(new Notification.ProgressStyle.Segment(SEGMENT_PREP).setColor(accent))
                .addProgressSegment(new Notification.ProgressStyle.Segment(SEGMENT_WORK).setColor(accent))
                .addProgressSegment(new Notification.ProgressStyle.Segment(SEGMENT_FINISH).setColor(muted))
                .addProgressPoint(new Notification.ProgressStyle.Point(SEGMENT_PREP).setColor(accent))
                .addProgressPoint(new Notification.ProgressStyle.Point(SEGMENT_PREP + SEGMENT_WORK).setColor(accent))
                .setStyledByProgress(true);
        if (model.indeterminate()) {
            style.setProgressIndeterminate(true);
        } else {
            style.setProgress(model.progress());
        }
        builder.setStyle(style);
        builder.setShortCriticalText(model.shortCriticalText());
        if (manager != null && manager.canPostPromotedNotifications()) {
            Bundle extras = new Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            builder.addExtras(extras);
        }
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.live_run_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    private static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
}
