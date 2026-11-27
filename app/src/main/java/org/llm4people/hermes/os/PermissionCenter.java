package org.llm4people.hermes.os;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.ambient.HermesNotificationListener;
import org.llm4people.hermes.update.ApkUpdateInstaller;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single Source of Truth for Android runtime and special-access permission state,
 * prior-request tracking, and 1-tap request or Android settings recovery.
 */
public final class PermissionCenter {
    private static final String PREFS = "hermes_permissions_prefs";
    private static final String KEY_PREFIX_REQUESTED = "requested_";

    public enum State {
        GRANTED,
        NOT_REQUESTED,
        DENIED,
        PERMANENTLY_DENIED,
        UNAVAILABLE
    }

    public enum Key {
        NOTIFICATIONS("notifications", Manifest.permission.POST_NOTIFICATIONS, 4301, false),
        CAMERA("camera", Manifest.permission.CAMERA, 4302, false),
        MICROPHONE("microphone", Manifest.permission.RECORD_AUDIO, 4303, false),
        LOCATION("location", Manifest.permission.ACCESS_COARSE_LOCATION, 4304, false),
        PRECISE_LOCATION("precise_location", Manifest.permission.ACCESS_FINE_LOCATION, 4305, false),
        CALENDAR("calendar", Manifest.permission.READ_CALENDAR, 4306, false),
        NOTIFICATION_ACCESS("notification_access", null, 0, true),
        INSTALL_UPDATES("install_updates", null, 0, true);

        public final String storageKey;
        public final String manifestPermission;
        public final int requestCode;
        public final boolean specialAccess;

        Key(String storageKey, String manifestPermission, int requestCode, boolean specialAccess) {
            this.storageKey = storageKey;
            this.manifestPermission = manifestPermission;
            this.requestCode = requestCode;
            this.specialAccess = specialAccess;
        }
    }

    /** The 7 user-facing permissions displayed in {@link PermissionsPanel}. */
    public static final List<Key> PANEL_KEYS = Collections.unmodifiableList(Arrays.asList(
            Key.NOTIFICATIONS,
            Key.CAMERA,
            Key.MICROPHONE,
            Key.LOCATION,
            Key.CALENDAR,
            Key.NOTIFICATION_ACCESS,
            Key.INSTALL_UPDATES
    ));

    public record Summary(int allowed, int blocked, int off, int unavailable, boolean notificationsOff) {
        public int total() {
            return allowed + blocked + off + unavailable;
        }
    }

    private PermissionCenter() {}

    /** Pure-Java runtime permission state classifier (SSoT for host JVM and Android). */
    public static State evaluateRuntimeState(
            boolean available,
            boolean granted,
            boolean requestedBefore,
            boolean shouldShowRationale) {
        if (!available) return State.UNAVAILABLE;
        if (granted) return State.GRANTED;
        if (!requestedBefore) return State.NOT_REQUESTED;
        return shouldShowRationale ? State.DENIED : State.PERMANENTLY_DENIED;
    }

    /** Pure-Java notification permission classifier across API 28-32 and API 33+. */
    public static State evaluateNotificationState(
            int sdkInt,
            boolean notificationsEnabled,
            boolean runtimeGranted,
            boolean requestedBefore,
            boolean shouldShowRationale) {
        if (sdkInt >= 33) {
            if (runtimeGranted && notificationsEnabled) return State.GRANTED;
            if (runtimeGranted) return State.PERMANENTLY_DENIED;
            return evaluateRuntimeState(true, false, requestedBefore, shouldShowRationale);
        }
        return notificationsEnabled ? State.GRANTED : State.PERMANENTLY_DENIED;
    }

    /** Pure-Java classifier for special Android access screens (Notification Listener, Unknown Sources). */
    public static State evaluateSpecialState(boolean available, boolean granted) {
        if (!available) return State.UNAVAILABLE;
        return granted ? State.GRANTED : State.NOT_REQUESTED;
    }

    /** Resolve a {@link Key} from an activity permission request code, or null if unmanaged. */
    public static Key fromRequestCode(int requestCode) {
        if (requestCode <= 0) return null;
        for (Key key : Key.values()) {
            if (key.requestCode == requestCode) return key;
        }
        return null;
    }

    public static boolean isManagedRequestCode(int requestCode) {
        return fromRequestCode(requestCode) != null;
    }

    /**
     * Pure-Java check for whether an Android {@code onRequestPermissionsResult} callback reflects a
     * completed user decision rather than an interrupted/cancelled dialog (empty {@code grantResults}).
     */
    public static boolean isCompletedResult(int[] grantResults) {
        return grantResults != null && grantResults.length > 0;
    }

    /** Resolve a managed runtime {@link Key} from an Android manifest permission string, or null. */
    public static Key fromManifestPermission(String permission) {
        if (permission == null || permission.isEmpty()) return null;
        for (Key key : Key.values()) {
            if (!key.specialAccess && permission.equals(key.manifestPermission)) return key;
        }
        return null;
    }

    /**
     * Record that a runtime permission prompt actually completed ({@code grantResults.length > 0})
     * so interrupted dialogs leave {@link State#NOT_REQUESTED} intact instead of falsely marking
     * {@link State#PERMANENTLY_DENIED}.
     */
    public static boolean recordCompletedPrompt(Context context, Key key, int[] grantResults) {
        if (context == null || key == null || key.specialAccess || !isCompletedResult(grantResults)) {
            return false;
        }
        recordRequested(context, key);
        return true;
    }

    /**
     * Record every completed managed runtime permission from an {@code onRequestPermissionsResult}
     * callback (no-op when {@code grantResults} is null or empty).
     */
    public static void recordCompletedPrompts(Context context, String[] permissions, int[] grantResults) {
        if (context == null || permissions == null || !isCompletedResult(grantResults)) return;
        int count = Math.min(permissions.length, grantResults.length);
        for (int i = 0; i < count; i++) {
            Key key = fromManifestPermission(permissions[i]);
            if (key != null) {
                recordRequested(context, key);
            }
        }
    }

    /** Summarize a snapshot map of permission states. */
    public static Summary summarizeStates(Map<Key, State> states) {
        int allowed = 0;
        int blocked = 0;
        int off = 0;
        int unavailable = 0;
        boolean notificationsOff = false;
        if (states != null) {
            for (Map.Entry<Key, State> entry : states.entrySet()) {
                Key key = entry.getKey();
                State value = entry.getValue();
                if (key == null || value == null) continue;
                switch (value) {
                    case GRANTED -> allowed++;
                    case PERMANENTLY_DENIED -> blocked++;
                    case NOT_REQUESTED, DENIED -> off++;
                    case UNAVAILABLE -> unavailable++;
                }
                if (key == Key.NOTIFICATIONS && value != State.GRANTED && value != State.UNAVAILABLE) {
                    notificationsOff = true;
                }
            }
        }
        return new Summary(allowed, blocked, off, unavailable, notificationsOff);
    }

    /** Read live state for one permission using an Activity for accurate rationale detection. */
    public static State state(Activity activity, Key key) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(key, "key");
        return resolveState(activity, activity, key);
    }

    /** Read live state for one permission using any Context. */
    public static State state(Context context, Key key) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        Activity activity = context instanceof Activity a ? a : null;
        return resolveState(context, activity, key);
    }

    /** Snapshot all 7 user-facing panel permissions in display order. */
    public static Map<Key, State> snapshot(Context context) {
        Objects.requireNonNull(context, "context");
        Activity activity = context instanceof Activity a ? a : null;
        Map<Key, State> out = new LinkedHashMap<>();
        for (Key key : PANEL_KEYS) {
            out.put(key, resolveState(context, activity, key));
        }
        return out;
    }

    public static Summary summarize(Context context) {
        return summarizeStates(snapshot(context));
    }

    /** Record that the app has explicitly prompted for this runtime permission at least once. */
    public static void recordRequested(Context context, Key key) {
        if (context == null || key == null || key.specialAccess) return;
        try {
            prefs(context).edit().putBoolean(KEY_PREFIX_REQUESTED + key.storageKey, true).apply();
        } catch (RuntimeException error) {
            Diagnostics.warning("permissions", "record_requested_failed", error);
        }
    }

    public static boolean wasRequested(Context context, Key key) {
        if (context == null || key == null || key.specialAccess) return false;
        try {
            return prefs(context).getBoolean(KEY_PREFIX_REQUESTED + key.storageKey, false);
        } catch (RuntimeException error) {
            Diagnostics.warning("permissions", "read_requested_failed", error);
            return false;
        }
    }

    /**
     * Trigger the native runtime prompt when eligible, or jump straight to the exact Android
     * settings screen when blocked or for special-access permissions.
     */
    public static boolean requestOrOpenSettings(Activity activity, Key key) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(key, "key");
        State current = state(activity, key);
        if (current == State.GRANTED) return true;
        if (current == State.UNAVAILABLE) return false;
        if (key == Key.NOTIFICATION_ACCESS) {
            return openNotificationListenerSettings(activity);
        }
        if (key == Key.INSTALL_UPDATES) {
            return openUnknownSourcesSettings(activity);
        }
        if (key == Key.NOTIFICATIONS) {
            if (Build.VERSION.SDK_INT < 33 || current == State.PERMANENTLY_DENIED) {
                return openNotificationSettings(activity);
            }
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, key.requestCode);
            Diagnostics.event("permissions", "request_" + key.storageKey);
            return true;
        }
        if (current == State.PERMANENTLY_DENIED) {
            return openAppSettings(activity);
        }
        if (key.manifestPermission == null || key.requestCode <= 0) return false;
        activity.requestPermissions(new String[]{key.manifestPermission}, key.requestCode);
        Diagnostics.event("permissions", "request_" + key.storageKey);
        return true;
    }

    /** Open this app's Android App Info screen for direct permission recovery. */
    public static boolean openAppSettings(Context context) {
        if (context == null) return false;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null));
        return startIntent(context, intent, "open_app_settings");
    }

    /** Open this app's Android notification settings screen (falling back to App Info). */
    public static boolean openNotificationSettings(Context context) {
        if (context == null) return false;
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        if (startIntent(context, intent, "open_notification_settings")) return true;
        return openAppSettings(context);
    }

    /** Open Android's Notification Access settings for the ambient notification listener. */
    public static boolean openNotificationListenerSettings(Context context) {
        if (context == null) return false;
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (startIntent(context, intent, "open_listener_settings")) return true;
        return openAppSettings(context);
    }

    /** Open Android's Install Unknown Apps screen for signed GitHub APK updates. */
    public static boolean openUnknownSourcesSettings(Context context) {
        if (context == null) return false;
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        if (startIntent(context, intent, "open_install_settings")) return true;
        return openAppSettings(context);
    }

    /** Check whether a manifest permission is declared by this installed APK. */
    @SuppressWarnings("deprecation")
    public static boolean isPermissionDeclared(Context context, String permission) {
        if (context == null || permission == null || permission.isEmpty()) return false;
        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) return true;
            PackageInfo info = Build.VERSION.SDK_INT >= 33
                    ? pm.getPackageInfo(context.getPackageName(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS))
                    : pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            String[] requested = info == null ? null : info.requestedPermissions;
            if (requested == null) return false;
            for (String declared : requested) {
                if (permission.equals(declared)) return true;
            }
            return false;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            Diagnostics.warning("permissions", "declared_check_failed", unavailable);
            return false;
        }
    }

    private static State resolveState(Context context, Activity activity, Key key) {
        if (key == Key.NOTIFICATION_ACCESS) {
            return evaluateSpecialState(true, HermesNotificationListener.isListenerEnabled(context));
        }
        if (key == Key.INSTALL_UPDATES) {
            return evaluateSpecialState(true, ApkUpdateInstaller.canRequestPackageInstalls(context));
        }
        if (key == Key.NOTIFICATIONS) {
            boolean enabled = areNotificationsEnabled(context);
            boolean runtimeGranted = Build.VERSION.SDK_INT < 33
                    || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            boolean requestedBefore = wasRequested(context, key);
            boolean rationale = Build.VERSION.SDK_INT >= 33
                    && shouldShowRationale(activity, Manifest.permission.POST_NOTIFICATIONS);
            return evaluateNotificationState(Build.VERSION.SDK_INT, enabled, runtimeGranted, requestedBefore, rationale);
        }
        boolean available = isKeyAvailable(context, key);
        boolean granted = available && key.manifestPermission != null
                && context.checkSelfPermission(key.manifestPermission) == PackageManager.PERMISSION_GRANTED;
        boolean requestedBefore = wasRequested(context, key);
        boolean rationale = key.manifestPermission != null && shouldShowRationale(activity, key.manifestPermission);
        return evaluateRuntimeState(available, granted, requestedBefore, rationale);
    }

    private static boolean isKeyAvailable(Context context, Key key) {
        if (key.manifestPermission != null && !isPermissionDeclared(context, key.manifestPermission)) {
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) return true;
            if (key == Key.CAMERA) {
                return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
            }
            if (key == Key.MICROPHONE) {
                return pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
            }
            return true;
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("permissions", "feature_check_failed", unavailable);
            return true;
        }
    }

    private static boolean areNotificationsEnabled(Context context) {
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            return nm != null && nm.areNotificationsEnabled();
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("permissions", "notifications_enabled_check_failed", unavailable);
            return false;
        }
    }

    private static boolean shouldShowRationale(Activity activity, String permission) {
        if (activity == null) return true;
        try {
            return activity.shouldShowRequestPermissionRationale(permission);
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("permissions", "rationale_check_failed", unavailable);
            return true;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean startIntent(Context context, Intent intent, String eventName) {
        try {
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            Diagnostics.event("permissions", eventName);
            return true;
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("permissions", eventName + "_failed");
            return false;
        }
    }
}
