package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.widget.Toast;

import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.os.HermesWidgetPinning;
import org.llm4people.hermes.os.HermesWidgetProvider;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.os.HermesWidgetStore;
import org.llm4people.hermes.os.OsEntryIntent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Android launcher pinning bridge for Hermes-created surfaces and widgets.
 * Bridges Live UI Patch v2 widget specs directly into the OS widget engine
 * ({@link HermesWidgetPinning}, {@link HermesWidgetStore}, {@link HermesWidgetProvider})
 * and passes strictly home-screen-safe titles and opaque IDs — never private transcripts or credentials.
 */
public final class HomePinBridge {
    public static final String ACTION_WIDGET_UPDATED = "org.llm4people.hermes.ACTION_WIDGET_SPEC_UPDATED";
    public static final String EXTRA_WIDGET_ID = "hermes_widget_id";
    public static final String EXTRA_SURFACE_ID = "hermes_surface_id";
    private static final ExecutorService PIN_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-home-pin");
        t.setDaemon(true);
        return t;
    });

    private HomePinBridge() {}

    /** Package-boundary validator for custom surface shortcut targets. */
    public static boolean isValidSurfaceId(String surfaceId) {
        return surfaceId != null && WorkspaceLayout.custom(surfaceId);
    }

    /** Converts a validated Live UI WidgetSpec into an OS-level HermesWidgetSpec (fail-closed if unsafe). */
    public static HermesWidgetSpec toOsWidgetSpec(LiveUiPatch.WidgetSpec spec) {
        return toOsWidgetSpec(spec, null);
    }

    /** Converts a validated Live UI WidgetSpec into an OS-level HermesWidgetSpec bound to an owner profile. */
    public static HermesWidgetSpec toOsWidgetSpec(LiveUiPatch.WidgetSpec spec, String ownerProfileId) {
        if (spec == null || !spec.homeScreenSafe) return null;
        HermesWidgetSpec.Template template = HermesWidgetSpec.Template.parse(spec.template);
        if (template == null) template = HermesWidgetSpec.Template.STATUS;
        List<HermesWidgetSpec.Item> items = new ArrayList<>();
        if (spec.items != null) {
            for (LiveUiPatch.WidgetItem item : spec.items) {
                if (items.size() >= HermesWidgetSpec.MAX_ITEMS) break;
                if (item == null) continue;
                String label = Boolean.TRUE.equals(item.checked) ? "✓ " + item.label : item.label;
                items.add(new HermesWidgetSpec.Item(
                        label,
                        item.value == null ? "" : item.value,
                        item.actionDraft == null ? "" : item.actionDraft));
            }
        }
        try {
            return new HermesWidgetSpec(
                    spec.widgetId,
                    spec.title,
                    spec.subtitle,
                    template,
                    items,
                    spec.accentHex,
                    true,
                    ownerProfileId);
        } catch (IllegalArgumentException rejected) {
            Diagnostics.warning("home_pin", "widget_convert_rejected");
            return null;
        }
    }

    /** Parses a pushed widget_update delivery payload strictly enforcing home_screen_safe: true. */
    public static HermesWidgetSpec parseDeliveryWidgetSpec(JSONObject payload) {
        if (payload == null) return null;
        JSONObject candidate = payload.optJSONObject("widget");
        if (candidate == null) candidate = payload;
        if (!candidate.optBoolean("home_screen_safe", false)) return null;
        try {
            HermesWidgetSpec spec = HermesWidgetSpec.fromJson(candidate);
            return spec.homeScreenSafe ? spec : null;
        } catch (RuntimeException rejected) {
            Diagnostics.warning("home_pin", "delivery_widget_rejected");
            return null;
        }
    }

    /** Prompts the user and opens Android's native Home Screen widget pinning sheet bound to an owner profile. */
    public static void requestPinWidget(Activity activity, LiveUiPatch.WidgetSpec spec, String ownerProfileId) {
        if (activity == null || spec == null || !spec.homeScreenSafe) return;
        String preview = spec.title
                + (spec.subtitle.isEmpty() ? "" : "\n" + spec.subtitle)
                + "\n\n" + activity.getString(R.string.home_pin_widget_privacy_note);
        DeskDialogs.open(activity, new AlertDialog.Builder(activity)
                .setTitle(R.string.home_pin_dialog_title)
                .setMessage(preview)
                .setNegativeButton(R.string.home_pin_cancel, null)
                .setPositiveButton(R.string.home_pin_widget_action, (d, w) ->
                        launchWidgetPin(activity, spec, ownerProfileId))
                .setNeutralButton(R.string.home_pin_shortcut_action, (d, w) ->
                        pinShortcutAsync(activity, "widget-" + spec.widgetId, spec.title, null, spec.widgetId)));
    }

    /** Offers 1-tap pinning to the Android Home Screen for any Hermes-created surface/tool/dashboard. */
    public static void offerPinSurface(Activity activity, String surfaceId, String title) {
        if (activity == null || !WorkspaceLayout.custom(surfaceId) || title == null || title.isEmpty()) return;
        String safeTitle = title.length() > 40 ? title.substring(0, 40) : title;
        DeskDialogs.open(activity, new AlertDialog.Builder(activity)
                .setTitle(R.string.home_pin_dialog_title)
                .setMessage(activity.getString(R.string.home_pin_surface_body, safeTitle))
                .setNegativeButton(R.string.home_pin_cancel, null)
                .setPositiveButton(R.string.home_pin_shortcut_action, (d, w) ->
                        pinShortcutAsync(activity, "page-" + surfaceId.substring(5), safeTitle, surfaceId, null)));
    }

    private static void pinShortcutAsync(Activity activity, String shortcutId, String label,
                                         String surfaceId, String widgetId) {
        PIN_EXECUTOR.execute(() -> requestPinShortcut(activity, shortcutId, label, surfaceId, widgetId));
    }

    public static boolean requestPinShortcut(Activity activity, String shortcutId, String label,
                                             String surfaceId, String widgetId) {
        if (activity == null || shortcutId == null || shortcutId.isEmpty() || label == null || label.isEmpty()) {
            return false;
        }
        // Widget shortcuts delegate directly to PR #14's native HermesWidgetPinning shortcut engine.
        if (surfaceId == null && HermesWidgetSpec.validWidgetId(widgetId)
                && HermesWidgetSpec.validWidgetId(shortcutId)) {
            HermesWidgetPinning.PinResult result = HermesWidgetPinning.requestPinShortcut(
                    activity, shortcutId, label, OsEntryIntent.Target.WIDGET_ACTION, widgetId);
            if (result == HermesWidgetPinning.PinResult.REQUESTED) {
                Diagnostics.event("home_pin", "shortcut_requested");
                return true;
            }
            Diagnostics.event("home_pin", "shortcut_unsupported");
            guidance(activity, activity.getString(R.string.home_pin_shortcut_unsupported_title),
                    activity.getString(R.string.home_pin_shortcut_unsupported_body));
            return false;
        }
        ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Diagnostics.event("home_pin", "shortcut_unsupported");
            guidance(activity, activity.getString(R.string.home_pin_shortcut_unsupported_title),
                    activity.getString(R.string.home_pin_shortcut_unsupported_body));
            return false;
        }
        Intent launch = new Intent(activity, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (surfaceId != null && WorkspaceLayout.custom(surfaceId)) {
            launch.putExtra(EXTRA_SURFACE_ID, surfaceId);
        }
        if (widgetId != null && DraftForm.validId(widgetId)) {
            launch.putExtra(EXTRA_WIDGET_ID, widgetId);
        }
        String safeLabel = HermesWidgetSpec.sanitizeHomeText(label, 50);
        if (safeLabel.isEmpty()) return false;
        ShortcutInfo info = new ShortcutInfo.Builder(activity, shortcutId)
                .setShortLabel(safeLabel.length() > 25 ? safeLabel.substring(0, 25) : safeLabel)
                .setLongLabel(safeLabel)
                .setIcon(Icon.createWithResource(activity, R.mipmap.ic_launcher))
                .setIntent(launch)
                .build();
        boolean requested = manager.requestPinShortcut(info, null);
        Diagnostics.event("home_pin", requested ? "shortcut_requested" : "shortcut_declined");
        return requested;
    }

    private static void launchWidgetPin(Activity activity, LiveUiPatch.WidgetSpec spec, String ownerProfileId) {
        HermesWidgetSpec osSpec = toOsWidgetSpec(spec, ownerProfileId);
        if (osSpec != null) {
            HermesWidgetPinning.PinResult result = HermesWidgetPinning.requestPinWidget(activity, osSpec);
            broadcastWidgetUpdated(activity, osSpec.widgetId);
            if (result == HermesWidgetPinning.PinResult.REQUESTED) {
                Diagnostics.event("home_pin", "widget_requested");
                Toast.makeText(activity, HermesWidgetPinning.guidanceMessage(activity, result),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        Diagnostics.event("home_pin", "widget_fallback_guidance");
        DeskDialogs.open(activity, new AlertDialog.Builder(activity)
                .setTitle(R.string.home_pin_widget_fallback_title)
                .setMessage(R.string.home_pin_widget_fallback_body)
                .setNegativeButton(R.string.home_pin_close, null)
                .setPositiveButton(R.string.home_pin_shortcut_action, (d, w) ->
                        pinShortcutAsync(activity, "widget-" + spec.widgetId, spec.title, null, spec.widgetId)));
    }

    /** Upserts a validated Live UI widget bound to an owner profile into HermesWidgetStore. */
    public static void syncWidget(Context context, LiveUiPatch.WidgetSpec spec, String ownerProfileId) {
        if (context == null || spec == null) return;
        HermesWidgetSpec osSpec = toOsWidgetSpec(spec, ownerProfileId);
        if (osSpec == null) return;
        Context app = context.getApplicationContext();
        if (!new HermesWidgetStore(app).upsertSpec(osSpec, ownerProfileId)) return;
        HermesWidgetProvider.refreshWidget(app, osSpec.widgetId);
        broadcastWidgetUpdated(app, osSpec.widgetId);
    }

    /** Removes a widget spec owned by {@code ownerProfileId} (or unowned) and refreshes Android widgets. */
    public static void removeWidget(Context context, String widgetId, String ownerProfileId) {
        if (context == null || !HermesWidgetSpec.validWidgetId(widgetId)) return;
        Context app = context.getApplicationContext();
        if (!new HermesWidgetStore(app).removeSpec(widgetId, ownerProfileId)) return;
        HermesWidgetProvider.refresh(app, false);
        broadcastWidgetUpdated(app, widgetId);
    }

    /** Reconciles HermesWidgetStore for a specific owner profile after a Live UI rollback. */
    public static void syncWidgets(Context context, List<LiveUiPatch.WidgetSpec> activeSpecs,
                                   Set<String> affectedIds, String ownerProfileId) {
        if (context == null) return;
        Set<String> present = new HashSet<>();
        if (activeSpecs != null) {
            for (LiveUiPatch.WidgetSpec spec : activeSpecs) {
                present.add(spec.widgetId);
                syncWidget(context, spec, ownerProfileId);
            }
        }
        if (affectedIds != null) {
            for (String wid : affectedIds) {
                if (!present.contains(wid)) removeWidget(context, wid, ownerProfileId);
            }
        }
    }

    /** Applies a Hermes-pushed widget_update delivery into HermesWidgetStore and refreshes the launcher widget. */
    public static boolean applyDeliveryWidgetUpdate(Context context, JSONObject payload) {
        return applyDeliveryWidgetUpdate(context, payload, null);
    }

    /** Applies a Hermes-pushed widget_update delivery bound to an owner profile into HermesWidgetStore. */
    public static boolean applyDeliveryWidgetUpdate(Context context, JSONObject payload, String ownerProfileId) {
        if (context == null || payload == null) return false;
        JSONObject candidate = payload.optJSONObject("widget");
        if (candidate == null) candidate = payload;
        if (candidate.has("home_screen_safe") && !candidate.optBoolean("home_screen_safe", false)) {
            Diagnostics.warning("widget", "delivery_widget_unsafe");
            return false;
        }
        HermesWidgetSpec spec = parseDeliveryWidgetSpec(payload);
        Context app = context.getApplicationContext();
        if (spec != null) {
            if (!new HermesWidgetStore(app).upsertSpec(spec, ownerProfileId)) return false;
            HermesWidgetProvider.refreshWidget(app, spec.widgetId);
            broadcastWidgetUpdated(app, spec.widgetId);
            Diagnostics.event("widget", "delivery_widget_updated");
            return true;
        }
        String widgetId = candidate.optString("widget_id", "").trim();
        if (HermesWidgetSpec.validWidgetId(widgetId) && !candidate.has("title")) {
            HermesWidgetProvider.refreshWidget(app, widgetId);
            broadcastWidgetUpdated(app, widgetId);
            Diagnostics.event("widget", "delivery_widget_refreshed");
            return true;
        }
        Diagnostics.warning("widget", "delivery_widget_invalid");
        return false;
    }

    public static void notifyWidgetUpdated(Activity activity, String widgetId) {
        if (activity == null || widgetId == null || widgetId.isEmpty()) return;
        if (HermesWidgetSpec.validWidgetId(widgetId)) {
            HermesWidgetProvider.refreshWidget(activity.getApplicationContext(), widgetId);
        }
        broadcastWidgetUpdated(activity, widgetId);
    }

    private static void broadcastWidgetUpdated(Context context, String widgetId) {
        if (context == null || widgetId == null || widgetId.isEmpty()) return;
        Intent update = new Intent(ACTION_WIDGET_UPDATED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_WIDGET_ID, widgetId);
        context.sendBroadcast(update);
    }

    private static void guidance(Activity activity, String title, String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            DeskDialogs.open(activity, new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.home_pin_close, null));
        });
    }
}
