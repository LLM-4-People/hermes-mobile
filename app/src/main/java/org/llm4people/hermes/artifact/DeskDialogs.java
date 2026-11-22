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
    private static final Map<Activity, List<WeakReference<AlertDialog>>> VISIBLE = new WeakHashMap<>();

    static AlertDialog open(Activity activity, AlertDialog.Builder builder) {
        if (activity.isFinishing() || activity.isDestroyed()) return null;
        AlertDialog dialog = Ui.secureDialog(builder);
        List<WeakReference<AlertDialog>> list = VISIBLE.computeIfAbsent(activity, a -> new ArrayList<>());
        list.removeIf(ref -> ref.get() == null);
        WeakReference<AlertDialog> reference = new WeakReference<>(dialog);
        list.add(reference);
        dialog.setOnDismissListener(d -> list.remove(reference));
        return dialog;
    }

    static void navigate(Activity activity, Runnable destination) {
        dismiss(activity);
        destination.run();
    }

    static void dismiss(Activity activity) {
        List<WeakReference<AlertDialog>> list = VISIBLE.remove(activity);
        if (list == null) return;
        for (WeakReference<AlertDialog> ref : new ArrayList<>(list)) {
            AlertDialog d = ref.get();
            if (d != null && d.isShowing()) d.dismiss();
        }
        list.clear();
    }
}
