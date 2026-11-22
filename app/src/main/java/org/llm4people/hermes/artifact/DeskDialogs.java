package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import org.llm4people.hermes.Ui;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Covers dialogs that would otherwise outlive the content behind the profile lock. UI thread only. */
final class DeskDialogs {
    private record Open(WeakReference<AlertDialog> dialog, WeakReference<ArtifactDesk.Host> owner) {}
    private static final Map<Activity, List<Open>> VISIBLE = new WeakHashMap<>();

    static AlertDialog open(Activity activity, AlertDialog.Builder builder) {
        return open(activity, null, builder);
    }

    static AlertDialog open(Activity activity, ArtifactDesk.Host owner, AlertDialog.Builder builder) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        AlertDialog dialog = Ui.secureDialog(builder);
        List<Open> list = VISIBLE.computeIfAbsent(activity, a -> new ArrayList<>());
        list.removeIf(entry -> entry.dialog.get() == null);
        Open entry = new Open(new WeakReference<>(dialog), new WeakReference<>(owner));
        list.add(entry);
        dialog.setOnDismissListener(d -> list.remove(entry));
        return dialog;
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
