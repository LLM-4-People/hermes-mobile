package org.llm4people.hermes.thread;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import java.util.UUID;
import android.provider.MediaStore;
import android.widget.Toast;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.profile.Account;

/** The external picker can stop and lock the activity. Only the original unlocked owner may import its result. */
public final class ThreadPhotoPicker {
    private final Activity activity;
    private int requestCode = 0x5000;
    private String nonce;
    private String ownerProof;
    private String threadProof;
    private boolean reselect;
    private boolean importing;
    private Uri selected;

    public ThreadPhotoPicker(Activity activity, Bundle state) {
        this.activity = activity;
        if (state != null) requestCode = state.getInt("photo_request", requestCode);
        if (state != null && state.getString("photo_nonce") != null) {
            nonce = state.getString("photo_nonce");
            ownerProof = state.getString("photo_owner");
            threadProof = state.getString("photo_thread");
            reselect = state.getBoolean("photo_reselect");
        }
    }

    public void save(Bundle state) {
        // The next generation must not accept an older picker's delayed result, even after cancellation.
        state.putInt("photo_request", requestCode);
        if (nonce == null) return;
        state.putString("photo_nonce", nonce);
        state.putString("photo_owner", ownerProof);
        state.putString("photo_thread", threadProof);
        // An unimported URI is a temporary grant and never belongs in saved activity state.
        state.putBoolean("photo_reselect", reselect || importing || selected != null);
    }

    public void launch(Account account, ThreadWorkspace workspace) {
        if (account == null || workspace == null || !workspace.readyForPhoto()) return;
        begin(account, workspace.sessionId());
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
            Diagnostics.warning("photo_picker", "picker_unavailable", unavailable);
            clear();
            Toast.makeText(activity, R.string.thread_photo_picker_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    void begin(Account account, String session) {
        nonce = UUID.randomUUID().toString();
        ownerProof = account.selectionBinding(nonce, "");
        threadProof = account.selectionBinding(nonce, session);
        reselect = importing = false;
        selected = null;
        requestCode = requestCode == 0x6fff ? 0x5000 : requestCode + 1;
    }

    boolean owns(Account account, String session) {
        return nonce != null && account.selectionBinding(nonce, "").equals(ownerProof)
                && account.selectionBinding(nonce, session).equals(threadProof);
    }

    public boolean result(int code, int result, Intent data) {
        if (code != requestCode || nonce == null) return false;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null || !"content".equals(uri.getScheme())) clear();
        else selected = uri;
        return true;
    }

    public void unlocked(Account account) {
        if (nonce != null && !account.selectionBinding(nonce, "").equals(ownerProof)) clear();
    }

    public boolean deliver(Account account, ThreadWorkspace workspace) {
        if (account == null || workspace == null || nonce == null || workspace.sessionId() == null) return false;
        if (!owns(account, workspace.sessionId())) { clear(); return false; }
        if (importing) return false;
        if (selected != null) {
            Uri uri = selected;
            Runnable completed = startImport();
            workspace.importPhoto(uri, completed);
            return true;
        }
        if (reselect) {
            clear();
            if (!workspace.hasPhotoDraft())
                Toast.makeText(activity, R.string.thread_photo_selection_interrupted, Toast.LENGTH_LONG).show();
        }
        return false;
    }

    Runnable startImport() {
        selected = null;
        importing = true;
        String ticket = nonce;
        return () -> { if (ticket.equals(nonce)) clear(); };
    }

    public void interruptImport() {
        if (importing) {
            importing = false;
            reselect = true;
        }
    }

    private void clear() { nonce = ownerProof = threadProof = null; selected = null; reselect = importing = false; }
}
