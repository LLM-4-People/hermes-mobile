package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.Toast;

import org.llm4people.hermes.profile.Account;

/** The external picker can stop and lock the activity. Only the original unlocked owner may import its result. */
public final class ThreadPhotoPicker {
    private final Activity activity;
    private int requestCode = 0x5000;
    private String profile;
    private String thread;
    private String scope;
    private Uri selected;

    public ThreadPhotoPicker(Activity activity) { this.activity = activity; }

    public void launch(Account account, ThreadWorkspace workspace) {
        if (account == null || workspace == null || !workspace.readyForPhoto()) return;
        thread = workspace.sessionId();
        profile = account.id;
        scope = ThreadJournal.scope(profile, account.endpoint, account.bearer, thread);
        selected = null;
        requestCode = requestCode == 0x6fff ? 0x5000 : requestCode + 1;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    activity.startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), requestCode);
                    return;
                } catch (ActivityNotFoundException unavailable) { /* Use the system document picker. */ }
            }
            activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), requestCode);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            clear();
            Toast.makeText(activity, "Le sélecteur d’images est indisponible.", Toast.LENGTH_LONG).show();
        }
    }

    public boolean result(int code, int result, Intent data) {
        if (code != requestCode || profile == null) return false;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null || !"content".equals(uri.getScheme())) clear();
        else selected = uri;
        return true;
    }

    public void unlocked(Account account) {
        if (profile != null && !scope.equals(ThreadJournal.scope(account.id, account.endpoint, account.bearer, thread))) clear();
    }

    public boolean deliver(Account account, ThreadWorkspace workspace) {
        if (account == null || workspace == null || selected == null || workspace.sessionId() == null) return false;
        boolean owns = thread.equals(workspace.sessionId())
                && scope.equals(ThreadJournal.scope(account.id, account.endpoint, account.bearer, thread));
        Uri uri = selected;
        clear();
        if (owns) workspace.importPhoto(uri);
        return owns;
    }

    private void clear() { profile = thread = scope = null; selected = null; }
}
