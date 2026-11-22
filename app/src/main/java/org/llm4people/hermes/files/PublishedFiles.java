package org.llm4people.hermes.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.format.Formatter;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Activity-owned file picker, with the normal profile lock enforced across the SAF round trip. */
public final class PublishedFiles implements Closeable {
    private static final int SAVE = 0x1465;
    private final Activity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private HermesApi api;
    private String owner;
    private Future<?> worker;
    private int generation;
    private AlertDialog dialog;
    private Selection selection;

    private static final class Selection {
        final String owner;
        final PublishedFile item;
        Uri destination;
        Selection(String owner, PublishedFile item) { this.owner = owner; this.item = item; }
    }

    public PublishedFiles(Activity activity) { this.activity = activity; }

    public void unlock(Account account, HermesApi client) {
        owner = account.id;
        api = client;
        if (selection != null && !selection.owner.equals(owner)) {
            selection = null;
            toast("Exportation annulée après le changement de profil.");
        }
        finishSelection();
    }

    public void show(String sessionId) {
        if (sessionId == null || api == null) return;
        page("/api/mobile/files/" + HermesApi.segment(sessionId), null);
    }

    private void page(String path, Long before) {
        perform(client -> {
            JSONObject result = client.get(path + (before == null ? "" : "?before=" + before));
            JSONArray rows = result.getJSONArray("data");
            List<PublishedFile> files = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                PublishedFile file = new PublishedFile(path, rows.getJSONObject(i));
                files.add(file);
                labels.add(file.name + "\n" + Formatter.formatShortFileSize(activity, file.size));
            }
            Long next = result.isNull("next") ? null : result.getLong("next");
            if (next != null) labels.add("Fichiers plus anciens");
            return () -> {
                AlertDialog.Builder sheet = new AlertDialog.Builder(activity).setTitle("Fichiers publiés")
                        .setNegativeButton("Fermer", null);
                if (files.isEmpty()) sheet.setMessage("Les fichiers que Hermès publie dans cette discussion apparaîtront ici.");
                else sheet.setItems(labels.toArray(new String[0]), (ignored, index) -> {
                    if (index == files.size()) page(path, next);
                    else options(files.get(index));
                });
                present(sheet);
            };
        }, true);
    }

    private void options(PublishedFile item) {
        AlertDialog.Builder sheet = new AlertDialog.Builder(activity).setTitle(item.name)
                .setMessage(item.mime + "\n" + Formatter.formatShortFileSize(activity, item.size))
                .setNegativeButton("Fermer", null)
                .setPositiveButton("Enregistrer sous…", (d, w) -> select(item));
        if (item.text() || item.image()) {
            sheet.setNeutralButton("Aperçu", (d, w) -> preview(item));
        }
        present(sheet);
    }

    private void select(PublishedFile item) {
        if (owner == null) return;
        Intent request = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(item.mime)
                .putExtra(Intent.EXTRA_TITLE, item.name);
        selection = new Selection(owner, item);
        try { activity.startActivityForResult(request, SAVE); }
        catch (ActivityNotFoundException e) {
            selection = null;
            toast("Aucun sélecteur de fichiers disponible.");
        }
    }

    public boolean result(int code, int resultCode, Intent data) {
        if (code != SAVE) return false;
        Uri uri = data == null ? null : data.getData();
        if (resultCode != Activity.RESULT_OK || uri == null || !"content".equals(uri.getScheme())) {
            selection = null;
        } else if (selection != null) {
            selection.destination = uri;
            finishSelection();
        } else {
            toast("L’exportation a expiré. Sélectionnez de nouveau le fichier.");
        }
        return true;
    }

    private void finishSelection() {
        if (api == null || selection == null || !selection.owner.equals(owner) || selection.destination == null) return;
        Selection target = selection;
        selection = null;
        perform(client -> {
            try (VerifiedFile file = VerifiedFile.fetch(activity.getCacheDir(), client, target.item)) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
                try (OutputStream out = activity.getContentResolver().openOutputStream(target.destination, "wt")) {
                    if (out == null) throw new IOException("Destination unavailable");
                    file.save(out);
                }
            }
            return () -> toast("Fichier enregistré.");
        }, false);
    }

    private void preview(PublishedFile item) {
        perform(client -> {
            try (VerifiedFile file = VerifiedFile.fetch(activity.getCacheDir(), client, item)) {
                if (item.image()) {
                    Bitmap bitmap = file.previewImage();
                    return () -> {
                        ImageView image = new ImageView(activity);
                        image.setImageBitmap(bitmap);
                        image.setAdjustViewBounds(true);
                        image.setContentDescription(item.name);
                        present(previewDialog(item).setView(image));
                    };
                }
                String text = file.previewText();
                return () -> {
                    TextView content = new TextView(activity);
                    content.setText(text);
                    content.setTextIsSelectable(true);
                    int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
                    content.setPadding(pad, pad, pad, pad);
                    ScrollView scroll = new ScrollView(activity);
                    scroll.addView(content);
                    present(previewDialog(item).setView(scroll));
                };
            }
        }, false);
    }

    private AlertDialog.Builder previewDialog(PublishedFile item) {
        return new AlertDialog.Builder(activity).setTitle(item.name).setNegativeButton("Fermer", null)
                .setPositiveButton("Enregistrer sous…", (d, w) -> select(item));
    }

    private interface Operation { Runnable execute(HermesApi client) throws Exception; }

    private void perform(Operation operation, boolean discovery) {
        if (api == null) return;
        if (worker != null) worker.cancel(true);
        int token = ++generation;
        HermesApi client = api;
        AlertDialog progress = present(new AlertDialog.Builder(activity)
                .setTitle(discovery ? "Chargement des fichiers…" : "Transfert du fichier…")
                .setView(new ProgressBar(activity)).setNegativeButton("Annuler", (d, w) -> cancel(token))
                .setOnCancelListener(d -> cancel(token)));
        worker = io.submit(() -> {
            Runnable update;
            try { update = operation.execute(client); }
            catch (Exception e) {
                boolean absent = discovery && e instanceof HermesApi.HttpError && ((HermesApi.HttpError) e).status == 404;
                Diagnostics.warning("files", absent ? "extension_absent" : "transfer_failed");
                update = () -> toast(absent ? "L’extension de fichiers est absente de ce serveur."
                        : "Impossible d’ouvrir ou d’enregistrer le fichier. Réessayez.");
            }
            Runnable result = update;
            activity.runOnUiThread(() -> {
                if (generation != token || activity.isDestroyed()) return;
                progress.dismiss();
                result.run();
            });
        });
    }

    private void cancel(int token) {
        if (generation != token) return;
        generation++;
        if (worker != null) worker.cancel(true);
    }

    AlertDialog present(AlertDialog.Builder sheet) {
        if (dialog != null) dialog.dismiss();
        dialog = Ui.secureDialog(sheet);
        return dialog;
    }

    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }

    public void lock() {
        generation++;
        if (worker != null) worker.cancel(true);
        worker = null;
        if (dialog != null) dialog.dismiss();
        dialog = null;
        api = null;
        owner = null;
    }

    @Override public void close() {
        lock();
        selection = null;
        io.shutdownNow();
    }
}
