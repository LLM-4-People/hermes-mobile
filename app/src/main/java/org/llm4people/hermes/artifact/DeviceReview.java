package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.PersistableBundle;
import android.view.View;
import android.widget.Toast;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.places.NearbyResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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

    public static void review(Activity activity, ArtifactDesk.Host host, View source, DeviceAction request) {
        open(activity, host, source, request, null, null, null);
    }

    public static void review(Activity activity, ArtifactDesk.Host host, View source, DeviceAction request,
                              Runnable onCompleted, Runnable onCancelled) {
        open(activity, host, source, request, onCompleted, onCancelled, onCancelled);
    }

    public static void review(Activity activity, ArtifactDesk.Host host, View source, DeviceAction request,
                              Runnable onCompleted, Runnable onCancelled, Runnable onUnavailable) {
        open(activity, host, source, request, onCompleted, onCancelled, onUnavailable);
    }

    static void open(Activity activity, ArtifactDesk.Host host, View source, DeviceAction request) {
        open(activity, host, source, request, null, null, null);
    }

    private static void open(Activity activity, ArtifactDesk.Host host, View source, DeviceAction request,
                             Runnable onCompleted, Runnable onCancelled, Runnable onUnavailable) {
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicBoolean handlingPositive = new AtomicBoolean(false);
        Runnable fireCompleted = () -> { if (settled.compareAndSet(false, true) && onCompleted != null) onCompleted.run(); };
        Runnable fireCancelled = () -> { if (settled.compareAndSet(false, true) && onCancelled != null) onCancelled.run(); };
        Runnable fireUnavailable = () -> { if (settled.compareAndSet(false, true) && onUnavailable != null) onUnavailable.run(); };

        BooleanSupplier origin = host.deviceActionOrigin();
        BooleanSupplier current = () -> source != null && source.isAttachedToWindow() && source.isShown() && origin.getAsBoolean();
        if (!current.getAsBoolean()) {
            Diagnostics.debug("device_action", "origin_unavailable");
            fireUnavailable.run();
            return;
        }
        AlertDialog.Builder review = new AlertDialog.Builder(activity)
                .setTitle(request.kind().title)
                .setMessage(request.preview())
                .setNegativeButton("Annuler", (dialog, which) -> fireCancelled.run())
                .setOnCancelListener(dialog -> fireCancelled.run())
                .setPositiveButton(request.kind().open, (dialog, which) -> {
                    if (!((AlertDialog) dialog).isShowing() || !current.getAsBoolean()) {
                        fireUnavailable.run();
                        return;
                    }
                    handlingPositive.set(true);
                    dialog.dismiss();
                    if (request instanceof DeviceAction.CopySensitive sensitive) {
                        if (copySensitive(activity, sensitive.text())) {
                            fireCompleted.run();
                        } else {
                            fireUnavailable.run();
                        }
                        return;
                    }
                    try {
                        Intent target = request.target();
                        activity.startActivity(request.kind() == DeviceAction.Kind.SHARE
                                ? chooser(activity, target) : target);
                        fireCompleted.run();
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        Diagnostics.warning("device_action", "app_unavailable", unavailable);
                        DeskDialogs.open(activity, host, new AlertDialog.Builder(activity)
                                .setTitle("Application indisponible")
                                .setMessage("Aucune application disponible ne peut ouvrir cette proposition.")
                                .setPositiveButton("Fermer", null));
                        fireUnavailable.run();
                    }
                });
        DeskDialogs.open(activity, host, review, source, current, () -> {
            if (!handlingPositive.get()) fireUnavailable.run();
        });
    }

    private static boolean copySensitive(Activity activity, String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Diagnostics.warning("device_action", "clipboard_unavailable");
            return false;
        }
        ClipData clip = ClipData.newPlainText("Hermes Mobile", text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        try {
            clipboard.setPrimaryClip(clip);
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(activity, "Copié dans le presse-papiers.", Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (RuntimeException failed) {
            Diagnostics.warning("device_action", "clipboard_copy_failed", failed);
            Toast.makeText(activity, "Impossible de copier ce texte.", Toast.LENGTH_LONG).show();
            return false;
        }
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
