package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Covers dialogs that would otherwise outlive the content behind the profile lock. UI thread only. */
final class DeskDialogs {
    private static final Map<Activity, List<WeakReference<AlertDialog>>> VISIBLE = new WeakHashMap<>();

    static void open(Activity activity, AlertDialog.Builder builder) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog dialog = builder.create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        List<WeakReference<AlertDialog>> list = VISIBLE.computeIfAbsent(activity, a -> new ArrayList<>());
        list.removeIf(ref -> ref.get() == null);
        WeakReference<AlertDialog> reference = new WeakReference<>(dialog);
        list.add(reference);
        dialog.setOnDismissListener(d -> list.remove(reference));
        dialog.show();
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
