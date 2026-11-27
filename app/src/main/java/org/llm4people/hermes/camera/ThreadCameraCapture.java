package org.llm4people.hermes.camera;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.UUID;

/**
 * System camera capture controller requiring zero CAMERA runtime permission.
 * Binds each capture to one profile/thread nonce, persists its private slot across recreation,
 * grants ClipData URI permissions cleanly, and strips EXIF/GPS metadata immediately on import.
 */
public final class ThreadCameraCapture {
    private static final String STATE_CODE = "camera_request";
    private static final String STATE_NONCE = "camera_nonce";
    private static final String STATE_OWNER = "camera_owner";
    private static final String STATE_THREAD = "camera_thread";
    private static final String STATE_SLOT = "camera_slot";
    private static final String STATE_READY = "camera_ready";

    public static final int REQUEST_CODE_MIN = 0x5400;
    public static final int REQUEST_CODE_MAX = 0x57ff;

    private final Activity activity;
    private int requestCode = REQUEST_CODE_MIN;
    private String nonce;
    private String ownerProof;
    private String threadProof;
    private Uri pendingSlot;
    private boolean readyToImport;

    public ThreadCameraCapture(Activity activity, Bundle state) {
        this.activity = activity;
        if (state != null) {
            int savedCode = state.getInt(STATE_CODE, requestCode);
            if (savedCode >= REQUEST_CODE_MIN && savedCode <= REQUEST_CODE_MAX) {
                requestCode = savedCode;
            }
            nonce = state.getString(STATE_NONCE);
            ownerProof = state.getString(STATE_OWNER);
            threadProof = state.getString(STATE_THREAD);
            String savedSlot = state.getString(STATE_SLOT);
            if (savedSlot != null && isOwnedSlot(savedSlot)) {
                pendingSlot = Uri.parse(savedSlot);
                readyToImport = state.getBoolean(STATE_READY, false);
            } else if (nonce != null) {
                nonce = ownerProof = threadProof = null;
                readyToImport = false;
            }
        }
    }

    private boolean isOwnedSlot(String rawUri) {
        if (rawUri == null || !rawUri.startsWith("content://")) return false;
        if (activity != null) {
            return rawUri.startsWith("content://" + PrivateCaptureProvider.authority(activity) + "/");
        }
        return rawUri.contains(".capture/capture/");
    }

    public static boolean isAvailable(Activity activity) {
        if (activity == null) return false;
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        return intent.resolveActivity(activity.getPackageManager()) != null
                || activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    public void save(Bundle state) {
        state.putInt(STATE_CODE, requestCode);
        if (nonce == null || pendingSlot == null) return;
        state.putString(STATE_NONCE, nonce);
        state.putString(STATE_OWNER, ownerProof);
        state.putString(STATE_THREAD, threadProof);
        state.putString(STATE_SLOT, pendingSlot.toString());
        state.putBoolean(STATE_READY, readyToImport);
    }

    public static Intent buildCaptureIntent(Uri slot) {
        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, slot)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        capture.setClipData(ClipData.newRawUri("", slot));
        return capture;
    }

    public boolean launch(Account account, String sessionId) {
        if (account == null || sessionId == null || sessionId.isEmpty()) return false;
        clear();
        try {
            pendingSlot = PrivateCaptureProvider.createSlot(activity);
            nonce = UUID.randomUUID().toString();
            ownerProof = account.selectionBinding(nonce, "");
            threadProof = account.selectionBinding(nonce, sessionId);
            requestCode = nextRequestCode(requestCode);

            activity.startActivityForResult(buildCaptureIntent(pendingSlot), requestCode);
            Diagnostics.event("camera", "capture_launched");
            return true;
        } catch (IOException | ActivityNotFoundException | SecurityException unavailable) {
            Diagnostics.warning("camera", "capture_unavailable", unavailable);
            clear();
            Toast.makeText(activity, R.string.camera_unavailable, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    public static int nextRequestCode(int current) {
        return current < REQUEST_CODE_MIN || current >= REQUEST_CODE_MAX ? REQUEST_CODE_MIN : current + 1;
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

    /**
     * Synchronously claims the ready private capture slot on the UI thread when owned by
     * {@code account}/{@code sessionId}. Clears and deletes the slot on owner/thread mismatch;
     * atomically clears instance fields on match so the caller owns reading and deleting the slot.
     */
    public Uri claimReadySlot(Account account, String sessionId) {
        if (!readyToImport || pendingSlot == null || account == null || sessionId == null) return null;
        if (!owns(account, sessionId)) {
            clear();
            return null;
        }
        Uri slot = pendingSlot;
        pendingSlot = null;
        nonce = ownerProof = threadProof = null;
        readyToImport = false;
        return slot;
    }

    /** Worker-safe reader that EXIF-strips a previously claimed private slot and always deletes it. */
    public static CapturedMedia.Item readAndDeleteSlot(Context context, Uri slot) throws IOException {
        if (context == null || slot == null) return null;
        try {
            return CapturedMedia.readFromUri(context.getContentResolver(), slot, "image/jpeg",
                    HermesConfig.load(context).artifactDefaultMaxBytes);
        } finally {
            PrivateCaptureProvider.deleteSlot(context, slot);
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
