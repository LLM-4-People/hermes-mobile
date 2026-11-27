package org.llm4people.hermes.os;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;

import java.util.UUID;

/**
 * Native on-the-fly Android Home Screen widget and pinned shortcut installer.
 * Uses Android's built-in {@link AppWidgetManager#requestPinAppWidget} and
 * {@link ShortcutManager#requestPinShortcut} with graceful guidance when the launcher lacks support.
 */
public final class HermesWidgetPinning {
    public enum PinResult {
        REQUESTED,
        UNSUPPORTED_LAUNCHER,
        INVALID_SPEC,
        FAILED
    }

    private HermesWidgetPinning() {}

    /** Pins a dynamic Hermes widget onto the user's Android Home Screen. */
    public static PinResult requestPinWidget(Context context, HermesWidgetSpec spec) {
        if (context == null || spec == null || !spec.homeScreenSafe) return PinResult.INVALID_SPEC;
        Context app = context.getApplicationContext();
        HermesWidgetStore store = new HermesWidgetStore(app);
        if (!store.upsertSpec(spec)) return PinResult.INVALID_SPEC;
        HermesWidgetProvider.refreshWidget(app, spec.widgetId);

        String token = null;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            if (manager == null || !manager.isRequestPinAppWidgetSupported()) {
                Diagnostics.event("widget", "pin_unsupported");
                return PinResult.UNSUPPORTED_LAUNCHER;
            }
            token = UUID.randomUUID().toString();
            store.savePendingPin(token, spec.widgetId);
            Intent callbackIntent = new Intent(app, HermesWidgetProvider.class)
                    .setAction(HermesWidgetProvider.ACTION_WIDGET_PINNED)
                    .putExtra(HermesWidgetProvider.EXTRA_PIN_TOKEN, token);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent successCallback = PendingIntent.getBroadcast(
                    app, token.hashCode(), callbackIntent, flags);
            Bundle extras = new Bundle();
            extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                    HermesWidgetProvider.buildDynamicViews(app, spec));
            boolean accepted = manager.requestPinAppWidget(
                    new ComponentName(app, HermesWidgetProvider.class), extras, successCallback);
            if (!accepted) {
                store.consumePendingPin(token);
                Diagnostics.event("widget", "pin_unsupported");
                return PinResult.UNSUPPORTED_LAUNCHER;
            }
            Diagnostics.event("widget", "pin_requested");
            return PinResult.REQUESTED;
        } catch (RuntimeException error) {
            if (token != null) store.consumePendingPin(token);
            Diagnostics.warning("widget", "pin_failed", error);
            return PinResult.FAILED;
        }
    }

    /** Pins a privacy-safe launcher shortcut onto the user's Android Home Screen. */
    public static PinResult requestPinShortcut(Context context, String shortcutId, String label,
                                               OsEntryIntent.Target target, String widgetId) {
        if (context == null || !HermesWidgetSpec.validWidgetId(shortcutId) || target == null) {
            return PinResult.INVALID_SPEC;
        }
        if (target == OsEntryIntent.Target.WIDGET_ACTION && !HermesWidgetSpec.validWidgetId(widgetId)) {
            return PinResult.INVALID_SPEC;
        }
        String safeLabel = HermesWidgetSpec.sanitizeHomeText(label, HermesWidgetSpec.MAX_TITLE_CHARS);
        if (safeLabel.isEmpty()) return PinResult.INVALID_SPEC;
        Context app = context.getApplicationContext();
        try {
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) {
                Diagnostics.event("shortcut", "pin_unsupported");
                return PinResult.UNSUPPORTED_LAUNCHER;
            }
            Intent launch = target == OsEntryIntent.Target.WIDGET_ACTION
                    ? OsEntryIntent.widgetActionIntent(app, widgetId, -1)
                    : OsEntryIntent.intent(app, target);
            ShortcutInfo info = new ShortcutInfo.Builder(app, "pinned_" + shortcutId)
                    .setShortLabel(safeLabel)
                    .setLongLabel(safeLabel)
                    .setIcon(Icon.createWithResource(app, R.mipmap.ic_launcher))
                    .setIntent(launch)
                    .build();
            boolean accepted = manager.requestPinShortcut(info, null);
            Diagnostics.event("shortcut", accepted ? "pin_requested" : "pin_unsupported");
            return accepted ? PinResult.REQUESTED : PinResult.UNSUPPORTED_LAUNCHER;
        } catch (RuntimeException error) {
            Diagnostics.warning("shortcut", "pin_failed", error);
            return PinResult.FAILED;
        }
    }

    /** Plain-French guidance message for every pin outcome. */
    public static String guidanceMessage(Context context, PinResult result) {
        if (result == null) return context.getString(R.string.widget_pin_failed);
        return switch (result) {
            case REQUESTED -> context.getString(R.string.widget_pin_requested);
            case UNSUPPORTED_LAUNCHER -> context.getString(R.string.widget_pin_unsupported);
            case INVALID_SPEC -> context.getString(R.string.widget_pin_invalid);
            case FAILED -> context.getString(R.string.widget_pin_failed);
        };
    }
}
