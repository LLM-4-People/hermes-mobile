package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import org.llm4people.hermes.Ui;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.WeakHashMap;

/** Covers dialogs that would otherwise outlive the content behind the profile lock. UI thread only. */
final class DeskDialogs {
    private record Open(WeakReference<AlertDialog> dialog, WeakReference<ArtifactDesk.Host> owner, WeakReference<BooleanSupplier> current) {}
    private static final Map<Activity, List<Open>> VISIBLE = new WeakHashMap<>();

    static AlertDialog open(Activity activity, AlertDialog.Builder builder) {
        return open(activity, null, builder);
    }

    static AlertDialog open(Activity activity, ArtifactDesk.Host owner, AlertDialog.Builder builder) {
        return open(activity, owner, builder, null, null);
    }

    static AlertDialog open(Activity activity, ArtifactDesk.Host owner, AlertDialog.Builder builder,
                            View source, BooleanSupplier current) {
        return open(activity, owner, builder, source, current, null);
    }

    static AlertDialog open(Activity activity, ArtifactDesk.Host owner, AlertDialog.Builder builder,
                            View source, BooleanSupplier current, Runnable onDismissed) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        AlertDialog dialog = Ui.secureDialog(builder);
        List<Open> list = VISIBLE.computeIfAbsent(activity, a -> new ArrayList<>());
        list.removeIf(entry -> entry.dialog.get() == null);
        Open entry = new Open(new WeakReference<>(dialog), new WeakReference<>(owner), new WeakReference<>(current));
        list.add(entry);
        View.OnAttachStateChangeListener departure = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { dialog.dismiss(); }
        };
        if (source != null) source.addOnAttachStateChangeListener(departure);
        dialog.setOnDismissListener(d -> {
            list.remove(entry);
            if (source != null) source.removeOnAttachStateChangeListener(departure);
            if (onDismissed != null) onDismissed.run();
        });
        return dialog;
    }

    static void dismissStale() {
        if (VISIBLE.isEmpty()) return;
        for (List<Open> dialogs : new ArrayList<>(VISIBLE.values())) {
            for (Open entry : new ArrayList<>(dialogs)) {
                AlertDialog dialog = entry.dialog.get();
                BooleanSupplier current = entry.current.get();
                if (dialog != null && current != null && !current.getAsBoolean()) dialog.dismiss();
            }
        }
    }

    static void navigate(Activity activity, Runnable destination) {
        dismiss(activity);
        destination.run();
    }

    static void dismiss(Activity activity) {
        dismiss(activity, null);
    }

    static void dismiss(Activity activity, ArtifactDesk.Host owner) {
        List<Open> list = VISIBLE.get(activity);
        if (list == null) return;
        for (Open entry : new ArrayList<>(list)) {
            AlertDialog dialog = entry.dialog.get();
            if (dialog == null || owner == null || entry.owner.get() == owner) {
                list.remove(entry);
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
            }
        }
        if (list.isEmpty()) VISIBLE.remove(activity);
    }
}
