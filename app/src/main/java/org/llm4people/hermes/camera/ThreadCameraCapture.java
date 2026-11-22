package org.llm4people.hermes.camera;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.UUID;

/**
 * System camera capture controller requiring zero CAMERA runtime permission.
 * Binds each capture to one profile/thread nonce and strips EXIF/GPS metadata immediately on import.
 */
public final class ThreadCameraCapture {
    private static final String STATE_CODE = "camera_request";
    private static final String STATE_NONCE = "camera_nonce";
    private static final String STATE_OWNER = "camera_owner";
    private static final String STATE_THREAD = "camera_thread";

    private final Activity activity;
    private int requestCode = 0x5400;
    private String nonce;
    private String ownerProof;
    private String threadProof;
    private Uri pendingSlot;
    private boolean readyToImport;

    public ThreadCameraCapture(Activity activity, Bundle state) {
        this.activity = activity;
        if (state != null) {
            requestCode = state.getInt(STATE_CODE, requestCode);
            nonce = state.getString(STATE_NONCE);
            ownerProof = state.getString(STATE_OWNER);
            threadProof = state.getString(STATE_THREAD);
        }
    }

    public static boolean isAvailable(Activity activity) {
        if (activity == null) return false;
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        return intent.resolveActivity(activity.getPackageManager()) != null
                || activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    public void save(Bundle state) {
        state.putInt(STATE_CODE, requestCode);
        if (nonce == null) return;
        state.putString(STATE_NONCE, nonce);
        state.putString(STATE_OWNER, ownerProof);
        state.putString(STATE_THREAD, threadProof);
    }

    public boolean launch(Account account, String sessionId) {
        if (account == null || sessionId == null || sessionId.isEmpty()) return false;
        clear();
        try {
            pendingSlot = PrivateCaptureProvider.createSlot(activity);
            nonce = UUID.randomUUID().toString();
            ownerProof = account.selectionBinding(nonce, "");
            threadProof = account.selectionBinding(nonce, sessionId);
            requestCode = requestCode == 0x57ff ? 0x5400 : requestCode + 1;

            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, pendingSlot)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(capture, requestCode);
            Diagnostics.event("camera", "capture_launched");
            return true;
        } catch (IOException | ActivityNotFoundException | SecurityException unavailable) {
            Diagnostics.warning("camera", "capture_unavailable", unavailable);
            clear();
            Toast.makeText(activity, "L’appareil photo n’est pas disponible sur cet appareil.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    public boolean onActivityResult(int code, int resultCode) {
        if (code != requestCode || nonce == null) return false;
        if (resultCode == Activity.RESULT_OK && pendingSlot != null) {
            readyToImport = true;
        } else {
            Diagnostics.debug("camera", "capture_cancelled");
            clear();
        }
        return true;
    }

    public void unlocked(Account account) {
        if (nonce != null && (account == null || !account.selectionBinding(nonce, "").equals(ownerProof))) {
            clear();
        }
    }

    /** Consume and EXIF-strip the captured photo only if the unlocked profile and thread still match. */
    public CapturedMedia.Item consumeIfReady(Account account, String sessionId) throws IOException {
        if (!readyToImport || pendingSlot == null || account == null || sessionId == null) return null;
        if (!owns(account, sessionId)) {
            clear();
            return null;
        }
        Uri slot = pendingSlot;
        try {
            return CapturedMedia.readFromUri(activity.getContentResolver(), slot, "image/jpeg",
                    HermesConfig.load(activity).artifactDefaultMaxBytes);
        } finally {
            PrivateCaptureProvider.deleteSlot(activity, slot);
            clear();
        }
    }

    boolean owns(Account account, String sessionId) {
        return nonce != null
                && account.selectionBinding(nonce, "").equals(ownerProof)
                && account.selectionBinding(nonce, sessionId).equals(threadProof);
    }

    public void clear() {
        if (pendingSlot != null) {
            PrivateCaptureProvider.deleteSlot(activity, pendingSlot);
        }
        pendingSlot = null;
        nonce = ownerProof = threadProof = null;
        readyToImport = false;
    }
}
