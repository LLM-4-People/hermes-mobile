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
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
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
    private Selection exporting;
    private final Set<String> incomplete = new HashSet<>();

    private static final class ExportFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final transient Selection transfer;
        ExportFailure(Selection transfer, Exception cause) {
            super("Document export failed", cause);
            this.transfer = transfer;
        }
    }

    private static final class Selection {
        final String owner;
        final PublishedFile item;
        Uri destination;
        boolean warned;
        Selection(String owner, PublishedFile item) { this.owner = owner; this.item = item; }
    }

    public PublishedFiles(Activity activity) { this.activity = activity; }

    public void unlock(Account account, HermesApi client) {
        owner = account.id;
        api = client;
        if (selection != null && !selection.owner.equals(owner)) {
            selection = null;
            toast(activity.getString(R.string.files_export_cancelled_profile));
        }
        notifyIncomplete();
        finishSelection();
    }

    private void notifyIncomplete() {
        if (owner != null && incomplete.remove(owner)) {
            toast(activity.getString(R.string.files_export_interrupted));
        }
    }

    public void show(String sessionId) {
        if (sessionId == null || api == null) return;
        page("/api/mobile/files/" + HermesApi.segment(sessionId), null);
    }

    private void page(String path, String before) {
        perform(client -> {
            JSONObject result = client.get(path + (before == null ? "" : "?before=" + Uri.encode(before)));
            long limit = HermesConfig.load(activity).resolveArtifactLimit(result.opt("max_artifact_bytes"));
            JSONArray rows = result.getJSONArray("data");
            List<PublishedFile> files = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                PublishedFile file = new PublishedFile(path, rows.getJSONObject(i), limit);
                files.add(file);
                labels.add(file.name + "\n" + Formatter.formatShortFileSize(activity, file.size));
            }
            String next = result.isNull("next") ? null : result.getString("next");
            if (next != null) labels.add(activity.getString(R.string.files_older_files));
            return () -> {
                boolean[] handled = {false};
                AlertDialog.Builder sheet = new AlertDialog.Builder(activity).setTitle(R.string.files_published_title)
                        .setNegativeButton(R.string.ws_close, null);
                if (files.isEmpty()) sheet.setMessage(R.string.files_empty_message);
                else sheet.setItems(labels.toArray(new String[0]), (ignored, index) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    if (index == files.size()) page(path, next);
                    else options(files.get(index));
                });
                present(sheet);
            };
        }, true);
    }

    private void options(PublishedFile item) {
        boolean[] handled = {false};
        AlertDialog.Builder sheet = new AlertDialog.Builder(activity).setTitle(item.name)
                .setMessage(item.mime + "\n" + Formatter.formatShortFileSize(activity, item.size))
                .setNegativeButton(R.string.ws_close, null)
                .setPositiveButton(R.string.files_save_as, (d, w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    select(item);
                });
        if (item.text() || item.image()) {
            sheet.setNeutralButton(R.string.files_preview, (d, w) -> {
                if (handled[0]) return;
                handled[0] = true;
                preview(item);
            });
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
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Diagnostics.warning("files", "picker_unavailable", unavailable);
            selection = null;
            toast(activity.getString(R.string.files_picker_unavailable));
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
            toast(activity.getString(R.string.files_export_expired));
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
                DocumentWriter.save(activity.getContentResolver().openFileDescriptor(target.destination, "wt"), file::save);
            } catch (IOException | RuntimeException failure) {
                // The system picker may already have created a document, even when the fetch failed.
                throw new ExportFailure(target, failure);
            }
            return () -> toast(activity.getString(R.string.files_saved));
        }, false);
        exporting = target;
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
        boolean[] handled = {false};
        return new AlertDialog.Builder(activity).setTitle(item.name).setNegativeButton(R.string.ws_close, null)
                .setPositiveButton(R.string.files_save_as, (d, w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    select(item);
                });
    }

    private interface Operation { Runnable execute(HermesApi client) throws Exception; }

    private void perform(Operation operation, boolean discovery) {
        if (api == null) return;
        stopWorker();
        int token = ++generation;
        HermesApi client = api;
        AlertDialog progress = present(new AlertDialog.Builder(activity)
                .setTitle(discovery ? R.string.files_loading_title : R.string.files_transferring_title)
                .setView(new ProgressBar(activity)).setNegativeButton(R.string.ws_cancel, (d, w) -> cancel(token))
                .setOnCancelListener(d -> cancel(token)));
        worker = io.submit(() -> {
            Runnable update;
            try { update = operation.execute(client); }
            catch (ExportFailure failure) {
                Diagnostics.warning("files", "export_incomplete");
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    progress.dismiss();
                    if (generation == token) exporting = null;
                    warnIncomplete(failure.transfer);
                });
                return;
            }
            catch (Exception e) {
                boolean absent = discovery && e instanceof HermesApi.HttpError && ((HermesApi.HttpError) e).status == 404;
                Diagnostics.warning("files", absent ? "extension_absent" : "transfer_failed");
                update = () -> toast(activity.getString(absent
                        ? R.string.files_extension_absent
                        : R.string.files_open_or_save_failed));
            }
            Runnable result = update;
            activity.runOnUiThread(() -> {
                if (generation != token || activity.isDestroyed()) return;
                progress.dismiss();
                exporting = null;
                result.run();
            });
        });
    }

    private void warnIncomplete(Selection transfer) {
        if (transfer.warned) return;
        transfer.warned = true;
        incomplete.add(transfer.owner);
        notifyIncomplete();
    }

    private void stopWorker() {
        if (worker != null && worker.cancel(true) && exporting != null) warnIncomplete(exporting);
        exporting = null;
        worker = null;
    }

    private void cancel(int token) {
        if (generation != token) return;
        generation++;
        stopWorker();
    }

    AlertDialog present(AlertDialog.Builder sheet) {
        if (dialog != null) dialog.dismiss();
        dialog = Ui.secureDialog(sheet);
        return dialog;
    }

    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }

    public void dismiss() {
        generation++;
        stopWorker();
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    public void lock() {
        api = null;
        owner = null;
        dismiss();
    }

    @Override public void close() {
        lock();
        selection = null;
        io.shutdownNow();
    }
}
