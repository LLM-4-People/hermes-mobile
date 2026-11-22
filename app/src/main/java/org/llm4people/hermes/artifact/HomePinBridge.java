package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import java.util.List;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/**
 * Native Android launcher pinning bridge for Hermes-created surfaces and widgets.
 * Uses only Android platform APIs (ShortcutManager + AppWidgetManager) and passes strictly
 * home-screen-safe titles and opaque IDs — never private transcripts or credentials.
 */
public final class HomePinBridge {
    public static final String ACTION_WIDGET_UPDATED = "org.llm4people.hermes.ACTION_WIDGET_SPEC_UPDATED";
    public static final String EXTRA_WIDGET_ID = "hermes_widget_id";
    public static final String EXTRA_SURFACE_ID = "hermes_surface_id";

    private HomePinBridge() {}

    /** Prompts the user and opens Android's native Home Screen widget pinning sheet when available. */
    public static void requestPinWidget(Activity activity, LiveUiPatch.WidgetSpec spec) {
        if (activity == null || spec == null || !spec.homeScreenSafe) return;
        String preview = spec.title
                + (spec.subtitle.isEmpty() ? "" : "\n" + spec.subtitle)
                + "\n\nSeul ce texte prévu pour l’écran d’accueil sera visible hors de l’application.";
        Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle("Épingler sur l’écran d’accueil ?")
                .setMessage(preview)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Épingler le widget", (d, w) -> launchWidgetPin(activity, spec))
                .setNeutralButton("Créer un raccourci", (d, w) ->
                        requestPinShortcut(activity, "widget-" + spec.widgetId, spec.title, null, spec.widgetId)));
    }

    /** Offers 1-tap pinning to the Android Home Screen for any Hermes-created surface/tool/dashboard. */
    public static void offerPinSurface(Activity activity, String surfaceId, String title) {
        if (activity == null || !WorkspaceLayout.custom(surfaceId) || title == null || title.isEmpty()) return;
        String safeTitle = title.length() > 40 ? title.substring(0, 40) : title;
        Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle("Épingler sur l’écran d’accueil ?")
                .setMessage("Ajoutez « " + safeTitle + " » à votre écran d’accueil pour l’ouvrir en un geste. "
                        + "Votre profil reste protégé avant l’affichage du contenu.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Créer un raccourci", (d, w) ->
                        requestPinShortcut(activity, "page-" + surfaceId.substring(5), safeTitle, surfaceId, null)));
    }

    public static boolean requestPinShortcut(Activity activity, String shortcutId, String label,
                                             String surfaceId, String widgetId) {
        if (activity == null || shortcutId == null || shortcutId.isEmpty() || label == null || label.isEmpty()) {
            return false;
        }
        ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Diagnostics.event("home_pin", "shortcut_unsupported");
            guidance(activity, "Raccourci non pris en charge",
                    "Votre écran d’accueil actuel ne permet pas d’épingler un raccourci directement.");
            return false;
        }
        Intent launch = new Intent(activity, MainActivity.class)
                .setAction(Intent.ACTION_VIEW);
        if (surfaceId != null && WorkspaceLayout.custom(surfaceId)) {
            launch.putExtra(EXTRA_SURFACE_ID, surfaceId);
        }
        if (widgetId != null && DraftForm.validId(widgetId)) {
            launch.putExtra(EXTRA_WIDGET_ID, widgetId);
        }
        ShortcutInfo info = new ShortcutInfo.Builder(activity, shortcutId)
                .setShortLabel(label.length() > 25 ? label.substring(0, 25) : label)
                .setLongLabel(label.length() > 50 ? label.substring(0, 50) : label)
                .setIcon(Icon.createWithResource(activity, R.mipmap.ic_launcher))
                .setIntent(launch)
                .build();
        boolean requested = manager.requestPinShortcut(info, null);
        Diagnostics.event("home_pin", requested ? "shortcut_requested" : "shortcut_declined");
        return requested;
    }

    private static void launchWidgetPin(Activity activity, LiveUiPatch.WidgetSpec spec) {
        AppWidgetManager manager = AppWidgetManager.getInstance(activity);
        ComponentName provider = findPackageWidgetProvider(activity, manager);
        if (manager != null && provider != null && manager.isRequestPinAppWidgetSupported()) {
            Bundle extras = new Bundle();
            extras.putString(EXTRA_WIDGET_ID, spec.widgetId);
            boolean requested = manager.requestPinAppWidget(provider, extras, null);
            Diagnostics.event("home_pin", requested ? "widget_requested" : "widget_declined");
            notifyWidgetUpdated(activity, spec.widgetId);
            if (requested) return;
        }
        Diagnostics.event("home_pin", "widget_fallback_guidance");
        Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle("Ajout depuis l’écran d’accueil")
                .setMessage("Votre lanceur ne propose pas l’épinglage direct de widget. "
                        + "Vous pouvez créer un raccourci direct ou ajouter le widget Hermes depuis un appui long sur l’écran d’accueil.")
                .setNegativeButton("Fermer", null)
                .setPositiveButton("Créer un raccourci", (d, w) ->
                        requestPinShortcut(activity, "widget-" + spec.widgetId, spec.title, null, spec.widgetId)));
    }

    private static ComponentName findPackageWidgetProvider(Activity activity, AppWidgetManager manager) {
        if (manager == null) return null;
        try {
            List<AppWidgetProviderInfo> providers = manager.getInstalledProvidersForPackage(activity.getPackageName(), null);
            if (providers != null && !providers.isEmpty()) return providers.get(0).provider;
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("home_pin", "provider_lookup_failed");
        }
        return null;
    }

    public static void notifyWidgetUpdated(Activity activity, String widgetId) {
        if (activity == null || widgetId == null || widgetId.isEmpty()) return;
        Intent update = new Intent(ACTION_WIDGET_UPDATED)
                .setPackage(activity.getPackageName())
                .putExtra(EXTRA_WIDGET_ID, widgetId);
        activity.sendBroadcast(update);
    }

    private static void guidance(Activity activity, String title, String message) {
        Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Fermer", null));
    }
}
