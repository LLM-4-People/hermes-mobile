package org.llm4people.hermes.os;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;

/** Single source of truth for privacy-safe OS entry intents (QS Tile, Shortcuts, Widget, Live Run). */
public final class OsEntryIntent {
    public static final String ACTION_ENTRY = "org.llm4people.hermes.OS_ENTRY";
    public static final String EXTRA_TARGET = "os_entry_target";
    public static final String EXTRA_WIDGET_ID = "os_widget_id";
    public static final String EXTRA_ITEM_INDEX = "os_widget_item";

    public enum Target {
        ASK("ask"),
        NEW_DISCUSSION("new_discussion"),
        DICTATE("dictate"),
        ATELIER("atelier"),
        ACTIVITY("activity"),
        WIDGET_ACTION("widget_action");

        public final String wire;

        Target(String wire) { this.wire = wire; }

        public static Target parse(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            for (Target value : values()) {
                if (value.wire.equals(raw)) return value;
            }
            return null;
        }
    }

    public record Request(Target target, String widgetId, int itemIndex) {}

    private OsEntryIntent() {}

    public static Intent intent(Context context, Target target) {
        if (target == null) throw new IllegalArgumentException("Missing OS entry target");
        return new Intent(context, MainActivity.class)
                .setAction(ACTION_ENTRY)
                .putExtra(EXTRA_TARGET, target.wire)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** Opaque widget action intent: carries ONLY widgetId + itemIndex, never private draft text. */
    public static Intent widgetActionIntent(Context context, String widgetId, int itemIndex) {
        if (!HermesWidgetSpec.validWidgetId(widgetId) || itemIndex < -1 || itemIndex >= HermesWidgetSpec.MAX_ITEMS) {
            throw new IllegalArgumentException("Invalid widget action binding");
        }
        return intent(context, Target.WIDGET_ACTION)
                .putExtra(EXTRA_WIDGET_ID, widgetId)
                .putExtra(EXTRA_ITEM_INDEX, itemIndex);
    }

    public static PendingIntent pendingIntent(Context context, Target target, int requestCode) {
        return PendingIntent.getActivity(context, requestCode, intent(context, target),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static PendingIntent widgetActionPendingIntent(Context context, String widgetId,
                                                          int itemIndex, int requestCode) {
        return PendingIntent.getActivity(context, requestCode,
                widgetActionIntent(context, widgetId, itemIndex),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static Request parseRequest(Intent intent) {
        if (intent == null || !ACTION_ENTRY.equals(intent.getAction())) return null;
        try {
            Target target = Target.parse(intent.getStringExtra(EXTRA_TARGET));
            if (target == null) return null;
            if (target == Target.WIDGET_ACTION) {
                String widgetId = intent.getStringExtra(EXTRA_WIDGET_ID);
                int index = intent.getIntExtra(EXTRA_ITEM_INDEX, -1);
                if (!HermesWidgetSpec.validWidgetId(widgetId) || index < -1 || index >= HermesWidgetSpec.MAX_ITEMS) {
                    return null;
                }
                return new Request(target, widgetId, index);
            }
            return new Request(target, null, -1);
        } catch (RuntimeException malformed) {
            Diagnostics.warning("os", "entry_intent_rejected");
            return null;
        }
    }
}
