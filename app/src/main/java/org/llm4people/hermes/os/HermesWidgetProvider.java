package org.llm4people.hermes.os;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;

import java.util.List;

/**
 * Multi-instance Home Screen widget engine for Hermes.
 * Default instances offer quick-ask &amp; voice launch; dynamically pinned instances render
 * home-screen-safe declarative specs from {@link HermesWidgetStore}.
 */
public final class HermesWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_WIDGET_PINNED = "org.llm4people.hermes.os.WIDGET_PINNED";
    public static final String ACTION_REFRESH_WIDGETS = "org.llm4people.hermes.os.REFRESH_WIDGETS";
    public static final String EXTRA_PIN_TOKEN = "pin_token";
    public static final String EXTRA_REFRESH_WIDGET_ID = "refresh_widget_id";

    private static final int REQUEST_WIDGET_ASK = 4130;
    private static final int REQUEST_WIDGET_DICTATE = 4131;
    private static final int REQUEST_DYNAMIC_BASE = 4200;
    private static final int[] ITEM_VIEW_IDS = {
            R.id.widget_dynamic_item_0,
            R.id.widget_dynamic_item_1,
            R.id.widget_dynamic_item_2,
            R.id.widget_dynamic_item_3
    };
    private static volatile boolean runActive;

    /** Pure privacy-safe status line tested on the host JVM. */
    public static String statusText(boolean active) {
        return active ? "Travail en cours" : "Assistant privé";
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (context == null || intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (ACTION_WIDGET_PINNED.equals(action)) {
            int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            String token = intent.getStringExtra(EXTRA_PIN_TOKEN);
            HermesWidgetStore store = new HermesWidgetStore(context);
            String widgetId = store.consumePendingPin(token);
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && widgetId != null) {
                store.bindInstance(appWidgetId, widgetId);
                updateOne(context, AppWidgetManager.getInstance(context), store, appWidgetId);
            }
        } else if (ACTION_REFRESH_WIDGETS.equals(action)) {
            String widgetId = intent.getStringExtra(EXTRA_REFRESH_WIDGET_ID);
            if (HermesWidgetSpec.validWidgetId(widgetId)) {
                refreshWidget(context, widgetId);
            } else {
                refresh(context, runActive);
            }
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (context == null || manager == null || ids == null) return;
        HermesWidgetStore store = new HermesWidgetStore(context);
        for (int id : ids) {
            updateOne(context, manager, store, id);
        }
    }

    @Override public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        if (context == null || appWidgetIds == null) return;
        HermesWidgetStore store = new HermesWidgetStore(context);
        for (int id : appWidgetIds) {
            store.unbindInstance(id);
        }
    }

    public static void refresh(Context context, boolean active) {
        runActive = active;
        if (context == null) return;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) return;
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, HermesWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            HermesWidgetStore store = new HermesWidgetStore(context);
            for (int id : ids) {
                updateOne(context, manager, store, id);
            }
        } catch (RuntimeException error) {
            Diagnostics.warning("os", "widget_refresh_failed");
        }
    }

    public static void refreshWidget(Context context, String widgetId) {
        if (context == null || !HermesWidgetSpec.validWidgetId(widgetId)) return;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) return;
            HermesWidgetStore store = new HermesWidgetStore(context);
            List<Integer> bound = store.instancesForWidget(widgetId);
            for (int appWidgetId : bound) {
                updateOne(context, manager, store, appWidgetId);
            }
        } catch (RuntimeException error) {
            Diagnostics.warning("os", "widget_instance_refresh_failed");
        }
    }

    private static void updateOne(Context context, AppWidgetManager manager,
                                  HermesWidgetStore store, int appWidgetId) {
        if (manager == null) return;
        try {
            HermesWidgetSpec bound = store.specForInstance(appWidgetId);
            RemoteViews views = bound != null
                    ? buildDynamicViews(context, bound)
                    : buildDefaultViews(context, runActive);
            manager.updateAppWidget(appWidgetId, views);
        } catch (RuntimeException error) {
            Diagnostics.warning("os", "widget_update_failed");
        }
    }

    static RemoteViews buildDefaultViews(Context context, boolean active) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.hermes_widget);
        views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_title));
        views.setTextViewText(R.id.widget_status,
                context.getString(active ? R.string.widget_status_working : R.string.widget_status_private));
        views.setOnClickPendingIntent(R.id.widget_ask,
                OsEntryIntent.pendingIntent(context, OsEntryIntent.Target.ASK, REQUEST_WIDGET_ASK));
        views.setOnClickPendingIntent(R.id.widget_dictate,
                OsEntryIntent.pendingIntent(context, OsEntryIntent.Target.DICTATE, REQUEST_WIDGET_DICTATE));
        return views;
    }

    static RemoteViews buildDynamicViews(Context context, HermesWidgetSpec rawSpec) {
        HermesWidgetSpec safe = rawSpec.redactedForHomeScreen();
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.hermes_widget_dynamic);
        views.setTextViewText(R.id.widget_dynamic_title, safe.title);
        views.setTextViewText(R.id.widget_dynamic_badge, badgeForTemplate(safe.template));
        if (safe.accentHex != null) {
            try {
                views.setTextColor(R.id.widget_dynamic_badge, Color.parseColor(safe.accentHex));
            } catch (IllegalArgumentException ignored) {
                // Keep default theme accent color.
            }
        }
        if (safe.subtitle.isEmpty()) {
            views.setViewVisibility(R.id.widget_dynamic_subtitle, View.GONE);
        } else {
            views.setViewVisibility(R.id.widget_dynamic_subtitle, View.VISIBLE);
            views.setTextViewText(R.id.widget_dynamic_subtitle, safe.subtitle);
        }
        int baseCode = REQUEST_DYNAMIC_BASE + Math.abs(safe.widgetId.hashCode() % 1000) * 10;
        views.setOnClickPendingIntent(R.id.widget_dynamic_root,
                OsEntryIntent.widgetActionPendingIntent(context, safe.widgetId, -1, baseCode));
        for (int i = 0; i < ITEM_VIEW_IDS.length; i++) {
            int viewId = ITEM_VIEW_IDS[i];
            if (i < safe.items.size()) {
                HermesWidgetSpec.Item item = safe.items.get(i);
                views.setViewVisibility(viewId, View.VISIBLE);
                views.setTextViewText(viewId, safe.formattedItemLine(item));
                views.setOnClickPendingIntent(viewId,
                        OsEntryIntent.widgetActionPendingIntent(context, safe.widgetId, i, baseCode + i + 1));
            } else {
                views.setViewVisibility(viewId, View.GONE);
            }
        }
        return views;
    }

    private static String badgeForTemplate(HermesWidgetSpec.Template template) {
        return switch (template) {
            case METRIC -> "Indicateur";
            case CHECKLIST -> "Liste";
            case QUICK_ACTIONS -> "Actions";
            case BRIEFING -> "Résumé";
            case STATUS -> "Hermes";
        };
    }
}
