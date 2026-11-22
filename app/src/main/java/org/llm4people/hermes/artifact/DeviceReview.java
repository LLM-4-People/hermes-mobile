package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import org.llm4people.hermes.places.NearbyResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The second, explicit gesture is the only way to send reviewed values to an Android app. */
public final class DeviceReview {
    private DeviceReview() {}

    public static void placeMap(Activity activity, ArtifactDesk.Host host, View source, NearbyResult.Place place) {
        open(activity, host, source, new DeviceAction.PlaceMap(place));
    }

    public static void placeSource(Activity activity, ArtifactDesk.Host host, View source, NearbyResult.Place place) {
        open(activity, host, source, new DeviceAction.PlaceSource(place));
    }

    static void open(Activity activity, ArtifactDesk.Host host, View source, DeviceAction request) {
        BooleanSupplier origin = host.deviceActionOrigin();
        BooleanSupplier current = () -> source.isAttachedToWindow() && source.isShown() && origin.getAsBoolean();
        if (!current.getAsBoolean()) return;
        AlertDialog.Builder review = new AlertDialog.Builder(activity)
                .setTitle(request.kind().title)
                .setMessage(request.preview())
                .setNegativeButton("Annuler", null)
                .setPositiveButton(request.kind().open, (dialog, which) -> {
                    if (!((AlertDialog) dialog).isShowing() || !current.getAsBoolean()) return;
                    dialog.dismiss();
                    try {
                        Intent target = request.target();
                        activity.startActivity(request.kind() == DeviceAction.Kind.SHARE
                                ? chooser(activity, target) : target);
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        DeskDialogs.open(activity, host, new AlertDialog.Builder(activity)
                                .setTitle("Application indisponible")
                                .setMessage("Aucune application disponible ne peut ouvrir cette proposition.")
                                .setPositiveButton("Fermer", null));
                    }
                });
        DeskDialogs.open(activity, host, review, source, current);
    }

    private static Intent chooser(Activity activity, Intent send) {
        List<ComponentName> internal = new ArrayList<>();
        boolean available = false;
        for (ResolveInfo match : activity.getPackageManager().queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo info = match.activityInfo;
            if (info == null) continue;
            if (activity.getPackageName().equals(info.packageName)) {
                internal.add(new ComponentName(info.packageName, info.name));
            } else if (info.exported && (info.permission == null ||
                    activity.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)) {
                available = true;
            }
        }
        if (!available) throw new ActivityNotFoundException();
        Intent chooser = Intent.createChooser(send, null);
        if (!internal.isEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, internal.toArray(new ComponentName[0]));
        return chooser;
    }
}
