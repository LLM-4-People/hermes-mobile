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

import org.llm4people.hermes.profile.Account;

/** The external picker can stop and lock the activity. Only the original unlocked owner may import its result. */
public final class ThreadPhotoPicker {
    private final Activity activity;
    private int requestCode = 0x5000;
    private String nonce;
    private String ownerProof;
    private String threadProof;
    private boolean reselect;
    private Uri selected;

    public ThreadPhotoPicker(Activity activity, Bundle state) {
        this.activity = activity;
        if (state != null && state.getString("photo_nonce") != null) {
            nonce = state.getString("photo_nonce");
            requestCode = state.getInt("photo_request");
            ownerProof = state.getString("photo_owner");
            threadProof = state.getString("photo_thread");
            reselect = state.getBoolean("photo_reselect");
        }
    }

    public void save(Bundle state) {
        if (nonce == null) return;
        state.putString("photo_nonce", nonce);
        state.putInt("photo_request", requestCode);
        state.putString("photo_owner", ownerProof);
        state.putString("photo_thread", threadProof);
        // An unimported URI is a temporary grant and never belongs in saved activity state.
        state.putBoolean("photo_reselect", reselect || selected != null);
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
            clear();
            Toast.makeText(activity, "Le sélecteur d’images est indisponible.", Toast.LENGTH_LONG).show();
        }
    }

    void begin(Account account, String session) {
        nonce = UUID.randomUUID().toString();
        ownerProof = account.selectionBinding(nonce, "");
        threadProof = account.selectionBinding(nonce, session);
        reselect = false;
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
        if (account == null || workspace == null || nonce == null || selected == null && !reselect
                || workspace.sessionId() == null) return false;
        boolean owns = owns(account, workspace.sessionId());
        Uri uri = selected;
        clear();
        if (owns && uri != null) workspace.importPhoto(uri);
        else if (owns) Toast.makeText(activity, "Sélection interrompue. Joignez de nouveau l’image.", Toast.LENGTH_LONG).show();
        return owns;
    }

    private void clear() { nonce = ownerProof = threadProof = null; selected = null; reselect = false; }
}
