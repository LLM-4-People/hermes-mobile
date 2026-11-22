package org.llm4people.hermes.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Activity-owned Thread Power Actions:
 * 1. Pin / unpin discussion (PATCH /api/sessions/{id} pinned)
 * 2. Branch / fork from any visible turn (POST /api/sessions/{id}/fork)
 * 3. Export transcript via Android ACTION_CREATE_DOCUMENT (GET /api/sessions/{id}/export?format=markdown|json)
 */
public final class ThreadActions implements Closeable {
    private static final int EXPORT_REQUEST_CODE = 0x18B2;

    private final Activity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private HermesApi api;
    private String ownerProfileId;
    private AlertDialog activeDialog;
    private PendingExport pendingExport;

    private static final class PendingExport {
        final String ownerProfileId;
        final String sessionId;
        final ThreadActionModel.ExportSpec spec;
        Uri destination;

        PendingExport(String ownerProfileId, String sessionId, ThreadActionModel.ExportSpec spec) {
            this.ownerProfileId = ownerProfileId;
            this.sessionId = sessionId;
            this.spec = spec;
        }
    }

    public ThreadActions(Activity activity) {
        this.activity = activity;
    }

    public void unlock(Account account, HermesApi client) {
        this.ownerProfileId = account == null ? null : account.id;
        this.api = client;
        if (pendingExport != null && (ownerProfileId == null || !ownerProfileId.equals(pendingExport.ownerProfileId))) {
            pendingExport = null;
            toast("Exportation annulée après le changement de profil.");
        }
        finishPendingExport();
    }

    public void lock() {
        api = null;
        ownerProfileId = null;
        dismiss();
    }

    public void dismiss() {
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }

    /** Toggle pinned status for a thread via PATCH /api/sessions/{id}. */
    public void togglePin(String sessionId, Runnable onChanged) {
        if (api == null || sessionId == null || sessionId.isEmpty()) return;
        HermesApi client = api;
        String owner = ownerProfileId;
        io.execute(() -> {
            try {
                JSONObject current = client.get("/api/sessions/" + HermesApi.segment(sessionId));
                JSONObject row = current.optJSONObject("session");
                boolean currentlyPinned = row != null && row.optBoolean("pinned", false);
                boolean nextPinned = !currentlyPinned;
                client.setSessionPinned(sessionId, nextPinned);
                Diagnostics.event("thread_actions", nextPinned ? "pinned" : "unpinned");
                activity.runOnUiThread(() -> {
                    if (!sameOwner(owner)) return;
                    toast(activity.getString(nextPinned ? R.string.brain_thread_pinned_ok : R.string.brain_thread_unpinned_ok));
                    if (onChanged != null) onChanged.run();
                });
            } catch (Exception error) {
                Diagnostics.warning("thread_actions", "pin_failed", error);
                activity.runOnUiThread(() -> {
                    if (sameOwner(owner)) toast(activity.getString(R.string.panel_network_error));
                });
            }
        });
    }

    /** Show native "Nouvelle piste à partir d'ici" dialog letting user pick any visible turn. */
    public void showForkDialog(String sessionId, JSONArray messages, Consumer<String> openForkedThread) {
        if (api == null || sessionId == null || sessionId.isEmpty()) return;
        List<ThreadActionModel.TurnChoice> turns = extractTurnChoices(messages);
        HermesApi client = api;
        String owner = ownerProfileId;
        io.execute(() -> {
            boolean supported;
            try {
                supported = BrainJson.parseCapabilities(client.capabilities()).sessionForkAtTurn();
            } catch (Exception error) {
                Diagnostics.warning("thread_actions", "fork_capability_failed", error);
                activity.runOnUiThread(() -> {
                    if (sameOwner(owner)) toast(activity.getString(R.string.panel_network_error));
                });
                return;
            }
            activity.runOnUiThread(() -> {
                if (!sameOwner(owner)) return;
                if (!supported) {
                    presentNotice(R.string.brain_thread_fork, R.string.brain_thread_fork_unavailable);
                    return;
                }
                if (turns.isEmpty()) {
                    presentNotice(R.string.brain_thread_fork, R.string.brain_thread_fork_empty);
                    return;
                }
                presentForkPicker(sessionId, turns, openForkedThread);
            });
        });
    }

    private void presentForkPicker(String sessionId, List<ThreadActionModel.TurnChoice> turns,
                                   Consumer<String> openForkedThread) {
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 18, 10);
        content.addView(Ui.text(activity, activity.getString(R.string.brain_thread_fork_hint), 15));

        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < turns.size(); i++) {
            ThreadActionModel.TurnChoice turn = turns.get(i);
            RadioButton rb = new RadioButton(activity);
            rb.setId(i + 1);
            rb.setMinHeight(Ui.dp(activity, 48));
            rb.setText(turn.displayLabel());
            rb.setTextColor(activity.getColor(R.color.ink));
            group.addView(rb);
        }
        group.check(turns.size());
        content.addView(group);

        CheckBox keepOpen = new CheckBox(activity);
        keepOpen.setMinHeight(Ui.dp(activity, 48));
        keepOpen.setText(R.string.brain_thread_fork_keep_open);
        keepOpen.setChecked(true);
        content.addView(keepOpen);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);

        present(new AlertDialog.Builder(activity)
                .setTitle(R.string.brain_thread_fork)
                .setView(scroll)
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.brain_thread_fork_create, (d, w) -> {
                    int checkedId = group.getCheckedRadioButtonId();
                    int index = checkedId > 0 && checkedId <= turns.size() ? checkedId - 1 : turns.size() - 1;
                    ThreadActionModel.TurnChoice chosen = turns.get(index);
                    executeFork(chosen.targetSessionId(sessionId), chosen.messageId(),
                            keepOpen.isChecked(), openForkedThread);
                }));
    }

    private void executeFork(String sessionId, Long upToMessageId, boolean keepSourceOpen,
                             Consumer<String> openForkedThread) {
        if (api == null) return;
        HermesApi client = api;
        String owner = ownerProfileId;
        Long safeMessageId = ThreadActionModel.validateForkMessageId(upToMessageId);
        io.execute(() -> {
            try {
                JSONObject response = client.forkSession(sessionId, safeMessageId, keepSourceOpen);
                JSONObject sessionObj = response.optJSONObject("session");
                String forkedId = sessionObj != null ? PanelData.text(sessionObj, "id") : PanelData.text(response, "session_id");
                if (forkedId.isEmpty()) forkedId = PanelData.text(response, "id");
                if (forkedId.isEmpty()) throw new IOException("Missing forked session id");
                Diagnostics.event("thread_actions", "forked");
                String targetId = forkedId;
                activity.runOnUiThread(() -> {
                    if (sameOwner(owner) && openForkedThread != null) openForkedThread.accept(targetId);
                });
            } catch (Exception error) {
                Diagnostics.warning("thread_actions", "fork_failed", error);
                activity.runOnUiThread(() -> {
                    if (!sameOwner(owner)) return;
                    if (error instanceof HermesApi.HttpError http && http.status == 404
                            && "message_not_found".equals(http.code)) {
                        presentNotice(R.string.brain_thread_fork, R.string.brain_thread_fork_message_not_found);
                    } else if (error instanceof HermesApi.HttpError http && (http.status == 404 || http.status == 501)) {
                        presentNotice(R.string.brain_thread_fork, R.string.brain_thread_fork_unavailable);
                    } else {
                        toast(activity.getString(R.string.panel_network_error));
                    }
                });
            }
        });
    }

    /** Show plain-French export format chooser and launch native ACTION_CREATE_DOCUMENT. */
    public void showExportDialog(String sessionId, String threadTitle) {
        if (api == null || sessionId == null || sessionId.isEmpty()) return;
        HermesApi client = api;
        String owner = ownerProfileId;
        io.execute(() -> {
            boolean supported;
            try {
                supported = BrainJson.parseCapabilities(client.capabilities()).sessionExport();
            } catch (Exception error) {
                Diagnostics.warning("thread_actions", "export_capability_failed", error);
                activity.runOnUiThread(() -> {
                    if (sameOwner(owner)) toast(activity.getString(R.string.panel_network_error));
                });
                return;
            }
            activity.runOnUiThread(() -> {
                if (!sameOwner(owner)) return;
                if (!supported) {
                    presentNotice(R.string.brain_thread_export, R.string.brain_thread_export_unavailable);
                    return;
                }
                String[] labels = {
                        activity.getString(R.string.brain_thread_export_md),
                        activity.getString(R.string.brain_thread_export_json)
                };
                present(new AlertDialog.Builder(activity)
                        .setTitle(R.string.brain_thread_export)
                        .setNegativeButton(R.string.panel_cancel, null)
                        .setItems(labels, (d, which) -> launchExportPicker(sessionId, threadTitle,
                                which == 1 ? ThreadActionModel.FORMAT_JSON : ThreadActionModel.FORMAT_MARKDOWN)));
            });
        });
    }

    private void launchExportPicker(String sessionId, String threadTitle, String format) {
        if (ownerProfileId == null) return;
        ThreadActionModel.ExportSpec spec = ThreadActionModel.buildExportSpec(threadTitle, format);
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(spec.mimeType())
                .putExtra(Intent.EXTRA_TITLE, spec.suggestedFilename());
        pendingExport = new PendingExport(ownerProfileId, sessionId, spec);
        try {
            activity.startActivityForResult(create, EXPORT_REQUEST_CODE);
        } catch (ActivityNotFoundException unavailable) {
            Diagnostics.warning("thread_actions", "export_picker_unavailable");
            pendingExport = null;
            toast(activity.getString(R.string.brain_thread_export_no_picker));
        }
    }

    public boolean result(int requestCode, int resultCode, Intent data) {
        if (requestCode != EXPORT_REQUEST_CODE) return false;
        Uri uri = data == null ? null : data.getData();
        if (resultCode != Activity.RESULT_OK || uri == null || !"content".equals(uri.getScheme())) {
            pendingExport = null;
        } else if (pendingExport != null) {
            pendingExport.destination = uri;
            finishPendingExport();
        }
        return true;
    }

    private void finishPendingExport() {
        if (api == null || pendingExport == null || pendingExport.destination == null) return;
        if (!sameOwner(pendingExport.ownerProfileId)) {
            pendingExport = null;
            return;
        }
        PendingExport job = pendingExport;
        pendingExport = null;
        HermesApi client = api;
        long maxBytes = HermesConfig.load(activity).artifactDefaultMaxBytes;
        io.execute(() -> {
            try {
                writeExportToUri(client, job, maxBytes);
                Diagnostics.event("thread_actions", "exported");
                activity.runOnUiThread(() -> {
                    if (sameOwner(job.ownerProfileId)) toast(activity.getString(R.string.brain_thread_export_saved));
                });
            } catch (Exception error) {
                Diagnostics.warning("thread_actions", "export_failed", error);
                activity.runOnUiThread(() -> {
                    if (sameOwner(job.ownerProfileId)) toast(activity.getString(R.string.brain_thread_export_failed));
                });
            }
        });
    }

    private void writeExportToUri(HermesApi client, PendingExport job, long maxBytes) throws IOException {
        ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(job.destination, "wt");
        if (pfd == null) throw new IOException("Destination unavailable");
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) {
            try {
                client.exportSession(job.sessionId, job.spec.format(), out, maxBytes);
                out.flush();
            } catch (IOException | RuntimeException failure) {
                try {
                    pfd.closeWithError("Incomplete transcript export");
                } catch (IOException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
                throw failure;
            }
        }
    }

    static List<ThreadActionModel.TurnChoice> extractTurnChoices(JSONArray messages) {
        List<ThreadActionModel.TurnChoice> turns = new ArrayList<>();
        if (messages == null) return turns;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null) continue;
            long id = row.optLong("id", -1L);
            if (id <= 0) id = row.optLong("message_id", -1L);
            if (id <= 0) continue;
            String role = PanelData.text(row, "role");
            String speaker = SearchResultModel.roleLabel(role);
            String content = PanelData.text(row, "content");
            if (content.isEmpty()) content = PanelData.text(row, "text");
            if (content.isEmpty()) continue;
            String turnSessionId = PanelData.text(row, "session_id");
            turns.add(new ThreadActionModel.TurnChoice(id, speaker, content.replace('\n', ' '), turnSessionId));
        }
        return turns;
    }

    private boolean sameOwner(String expectedOwner) {
        return expectedOwner != null && expectedOwner.equals(ownerProfileId) && !activity.isDestroyed();
    }

    private void presentNotice(int titleRes, int bodyRes) {
        present(new AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.panel_done, null));
    }

    private void present(AlertDialog.Builder builder) {
        dismiss();
        activeDialog = Ui.secureDialog(builder);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    @Override public void close() {
        lock();
        pendingExport = null;
        io.shutdownNow();
    }
}
