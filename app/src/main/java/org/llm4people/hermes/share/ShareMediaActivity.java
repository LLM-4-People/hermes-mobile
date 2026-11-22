package org.llm4people.hermes.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.profile.IncomingMediaShare;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exported FLAG_SECURE intake boundary for inbound images, PDFs, code/text files, and multi-file shares.
 * Clears untrusted extras ClassLoader, strips EXIF & seals off the UI thread, and forwards only an opaque UUID.
 */
public final class ShareMediaActivity extends Activity {
    public static final String REVIEW_MEDIA = "org.llm4people.hermes.REVIEW_SHARED_MEDIA";
    public static final String MEDIA_RECEIPT = "org.llm4people.hermes.MEDIA_SHARE_RECEIPT";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String waiting;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguage.wrapContext(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setResult(RESULT_CANCELED);
        try {
            if (state != null) {
                String previous = state.getString(MEDIA_RECEIPT);
                if (previous != null && previous.equals(IncomingMediaShare.pending(this))) {
                    busy(previous);
                } else {
                    unavailable("Relancez le partage depuis l’application d’origine.");
                }
                return;
            }
            Intent incoming = getIntent();
            List<Uri> uris = extractUris(incoming);
            String declaredMime = incoming == null ? null : incoming.getType();
            if (uris.isEmpty() || uris.size() > IncomingMediaShare.MAX_ITEMS) {
                setIntent(new Intent());
                Diagnostics.warning("share", "media_count_rejected");
                unavailable("Vous pouvez partager jusqu’à " + IncomingMediaShare.MAX_ITEMS + " photos ou fichiers à la fois.");
                return;
            }
            long maxBytes = HermesConfig.load(this).artifactDefaultMaxBytes;
            worker.execute(() -> processOffUiThread(uris, declaredMime, maxBytes));
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "media_unavailable", unavailable);
            unavailable("Le partage n’a pas pu être ouvert. Réessayez depuis l’application d’origine.");
        }
    }

    private void processOffUiThread(List<Uri> uris, String declaredMime, long maxBytes) {
        try {
            List<CapturedMedia.Item> sanitized = new ArrayList<>(uris.size());
            for (Uri uri : uris) {
                if (Thread.currentThread().isInterrupted()) return;
                sanitized.add(CapturedMedia.readFromUri(getContentResolver(), uri, declaredMime, maxBytes));
            }
            String receipt = IncomingMediaShare.create(this, sanitized);
            if (receipt != null && (Thread.currentThread().isInterrupted() || isFinishing() || isDestroyed())) {
                IncomingMediaShare.discard(this, receipt);
                return;
            }
            String existing = receipt == null ? IncomingMediaShare.pending(this) : null;
            runOnUiThread(() -> {
                setIntent(new Intent());
                if (isFinishing() || isDestroyed()) {
                    if (receipt != null) IncomingMediaShare.discard(this, receipt);
                    return;
                }
                if (receipt != null) review(receipt);
                else busy(existing);
            });
        } catch (IOException | IllegalArgumentException rejected) {
            Diagnostics.warning("share", "media_rejected", rejected);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    unavailable("Ce type de fichier ou sa taille n’est pas pris en charge. Partagez des photos, PDF ou fichiers texte.");
                }
            });
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "media_unavailable", unavailable);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    unavailable("Le partage n’a pas pu être ouvert. Réessayez depuis l’application d’origine.");
                }
            });
        }
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.clear();
        if (waiting != null) state.putString(MEDIA_RECEIPT, waiting);
    }

    @SuppressWarnings("deprecation")
    static List<Uri> extractUris(Intent intent) {
        if (intent == null) return Collections.emptyList();
        // Prevent untrusted Parcelable deserialization from third-party callers.
        intent.setExtrasClassLoader(null);
        String action = intent.getAction();
        List<Uri> out = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null && "content".equals(single.getScheme())) out.add(single);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                for (Uri u : many) {
                    if (u != null && "content".equals(u.getScheme())) out.add(u);
                }
            }
        }
        return out;
    }

    private void busy(String previous) {
        waiting = previous;
        Ui.secureDialog(new AlertDialog.Builder(this)
                .setTitle("Un partage est déjà en attente")
                .setMessage("Terminez ou annulez le partage précédent dans Hermes avant d’en ajouter un nouveau.")
                .setPositiveButton("Reprendre le partage précédent", (d, w) -> review(previous))
                .setNegativeButton("Fermer", (d, w) -> finish())
                .setOnCancelListener(d -> finish()));
    }

    private void unavailable(String message) {
        setIntent(new Intent());
        Ui.secureDialog(new AlertDialog.Builder(this)
                .setTitle("Partage indisponible")
                .setMessage(message)
                .setPositiveButton("Fermer", (d, w) -> finish())
                .setOnCancelListener(d -> finish()));
    }

    private void review(String id) {
        if (id == null || !id.equals(IncomingMediaShare.pending(this))) {
            unavailable("Le partage a expiré. Repartagez les fichiers depuis l’application d’origine.");
            return;
        }
        startActivity(new Intent(this, MainActivity.class)
                .setAction(REVIEW_MEDIA)
                .putExtra(MEDIA_RECEIPT, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
