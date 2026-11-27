package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;

import java.util.List;

/** Native Memory Inspector & Editor for MEMORY.md and USER.md. */
final class MemoryPanel {
    private final SessionPanel panel;
    private final Runnable parent;
    private String activeTarget = MemoryModel.TARGET_MEMORY;
    private MemoryModel.Snapshot snapshot;
    private MemoryModel.DeletedEntry lastDeleted;
    private MemoryModel.DeletedEntry renderedUndoEntry;
    private EditText filterField;
    private LinearLayout tabsRow;
    private LinearLayout undoSlot;
    private LinearLayout body;
    private int loadSeq;
    private String lastDisabledAnnouncement;
    private String lastEmptyAnnouncement;

    MemoryPanel(SessionPanel panel, Runnable parent) {
        this.panel = panel;
        this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("brain_memory", R.string.brain_memory_title, parent);
        tabsRow = Ui.row(panel.activity);
        page.addView(BrainUi.chipBar(panel.activity, tabsRow));
        renderTabs();

        filterField = BrainUi.searchField(panel.activity, R.id.brain_memory_filter, R.string.brain_memory_filter);
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(-1, -2);
        filterLp.setMargins(0, Ui.dp(panel.activity, 4), 0, Ui.dp(panel.activity, 8));
        page.addView(filterField, filterLp);
        filterField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderEntries(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        undoSlot = Ui.column(panel.activity);
        page.addView(undoSlot);
        body = Ui.column(panel.activity);
        page.addView(body);
        load();
    }

    private void renderTabs() {
        tabsRow.removeAllViews();
        tabsRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_memory_tab_hermes),
                MemoryModel.TARGET_MEMORY.equals(activeTarget), () -> switchTarget(MemoryModel.TARGET_MEMORY)));
        tabsRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_memory_tab_user),
                MemoryModel.TARGET_USER.equals(activeTarget), () -> switchTarget(MemoryModel.TARGET_USER)));
    }

    private void switchTarget(String target) {
        if (activeTarget.equals(target)) return;
        activeTarget = MemoryModel.validateTarget(target);
        renderTabs();
        renderEntries();
    }

    private void load() {
        int seq = ++loadSeq;
        body.removeAllViews();
        panel.request(body, () -> {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            if (!caps.memoryApi()) return null;
            return BrainJson.parseMemorySnapshot(panel.api.get("/api/memory"));
        }, loaded -> {
            if (seq != loadSeq) return;
            if (loaded == null) {
                Diagnostics.event("memory", "capability_absent");
                body.removeAllViews();
                BrainUi.showUnavailable(panel, body, R.string.brain_upgrade_required, this::load);
                return;
            }
            snapshot = loaded;
            renderEntries();
        }, error -> {
            if (seq != loadSeq) return;
            handleLoadError(error);
        });
    }

    private void handleLoadError(Exception error) {
        body.removeAllViews();
        if (error instanceof HermesApi.HttpError http) {
            Diagnostics.warning("memory", "load_http_error", "http_" + http.status);
            BrainUi.liveNote(panel, body, MemoryModel.describeHttpError(panel.activity, http.status, http.code));
            panel.action(body, R.string.panel_retry, this::load);
        } else {
            Diagnostics.warning("memory", "load_failed", error);
            panel.failure(body, error, this::load);
        }
    }

    private void renderUndoBanner() {
        if (undoSlot == null) return;
        MemoryModel.DeletedEntry deleted = lastDeleted;
        if (deleted == null) {
            if (renderedUndoEntry != null) {
                renderedUndoEntry = null;
                undoSlot.removeAllViews();
            }
            return;
        }
        if (deleted == renderedUndoEntry && undoSlot.getChildCount() > 0) return;
        renderedUndoEntry = deleted;
        undoSlot.removeAllViews();
        LinearLayout banner = BrainUi.tintedBanner(panel.activity);
        TextView msg = Ui.text(panel.activity, panel.t(R.string.brain_memory_deleted_banner), 15);
        msg.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        banner.addView(msg, new LinearLayout.LayoutParams(0, -2, 1));
        banner.addView(Ui.button(panel.activity, panel.t(R.string.brain_memory_undo_delete),
                v -> restoreDeleted(deleted, v)));
        undoSlot.addView(banner);
    }

    private void renderEntries() {
        renderUndoBanner();
        if (snapshot == null) return;
        body.removeAllViews();
        MemoryModel.Bucket bucket = snapshot.bucket(activeTarget);
        int hintRes = MemoryModel.TARGET_USER.equals(activeTarget)
                ? R.string.brain_memory_hint_user : R.string.brain_memory_hint_hermes;
        panel.note(body, panel.t(hintRes));

        LinearLayout metaRow = Ui.row(panel.activity);
        TextView usage = Ui.text(panel.activity,
                MemoryModel.usageSummary(panel.activity, bucket.chars(), bucket.limit()), 14);
        usage.setTextColor(panel.activity.getColor(R.color.muted));
        metaRow.addView(usage, new LinearLayout.LayoutParams(0, -2, 1));
        metaRow.addView(Ui.outlinedButton(panel.activity, panel.t(R.string.panel_refresh), v -> load()));
        body.addView(metaRow);

        if (!bucket.enabled()) {
            TextView disabled = panel.note(body, panel.t(R.string.brain_memory_disabled));
            if (!activeTarget.equals(lastDisabledAnnouncement)) {
                disabled.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                lastDisabledAnnouncement = activeTarget;
            }
        } else {
            lastDisabledAnnouncement = null;
            int addLabel = MemoryModel.TARGET_USER.equals(activeTarget)
                    ? R.string.brain_memory_add_user : R.string.brain_memory_add_hermes;
            Button addBtn = Ui.filledButton(panel.activity, panel.t(addLabel), v -> openEditor(bucket, null));
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, -2);
            addLp.setMargins(0, Ui.dp(panel.activity, 8), 0, Ui.dp(panel.activity, 8));
            body.addView(addBtn, addLp);
        }

        String query = filterField == null ? "" : filterField.getText().toString();
        List<MemoryModel.Entry> visible = bucket.filter(query);
        if (visible.isEmpty()) {
            int msgRes = bucket.entries().isEmpty()
                    ? R.string.brain_memory_empty : R.string.brain_memory_empty_filter;
            TextView empty = panel.note(body, panel.t(msgRes));
            String emptyKey = activeTarget + ":" + msgRes;
            if (!emptyKey.equals(lastEmptyAnnouncement)) {
                empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                lastEmptyAnnouncement = emptyKey;
            }
            return;
        }
        lastEmptyAnnouncement = null;
        for (MemoryModel.Entry entry : visible) {
            body.addView(entryCard(bucket, entry));
        }
    }

    private View entryCard(MemoryModel.Bucket bucket, MemoryModel.Entry entry) {
        LinearLayout card = BrainUi.card(panel.activity);
        if (entry.pinned()) {
            LinearLayout badges = Ui.row(panel.activity);
            badges.addView(BrainUi.badge(panel.activity, panel.t(R.string.brain_memory_pinned), true));
            card.addView(badges);
        }
        TextView text = Ui.text(panel.activity, entry.text(), 16);
        text.setLineSpacing(0f, 1.2f);
        text.setTextIsSelectable(true);
        Ui.pad(text, 0, 4);
        card.addView(text);

        if (bucket.enabled()) {
            LinearLayout actions = Ui.row(panel.activity);
            int gap = Ui.dp(panel.activity, 8);
            Button edit = Ui.button(panel.activity, panel.t(R.string.panel_edit), v -> openEditor(bucket, entry));
            LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(-2, -2);
            editLp.setMarginEnd(gap);
            actions.addView(edit, editLp);

            Button pin = Ui.button(panel.activity,
                    panel.t(entry.pinned() ? R.string.brain_memory_unpin : R.string.brain_memory_pin_toggle),
                    v -> togglePin(bucket.target(), entry, (Button) v));
            LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(-2, -2);
            pinLp.setMarginEnd(gap);
            actions.addView(pin, pinLp);

            Button del = Ui.button(panel.activity, panel.t(R.string.panel_delete),
                    v -> confirmDelete(bucket.target(), entry));
            del.setTextColor(Ui.dangerInk(panel.activity));
            actions.addView(del);
            card.addView(BrainUi.chipBar(panel.activity, actions));
        }
        return card;
    }

    private void openEditor(MemoryModel.Bucket bucket, MemoryModel.Entry existing) {
        LinearLayout form = Ui.column(panel.activity);
        Ui.pad(form, 18, 10);
        EditText input = BrainUi.multilineField(panel.activity, R.string.brain_memory_input_hint, 4);
        if (existing != null) input.setText(existing.text());
        form.addView(input);

        TextView counter = panel.note(form,
                MemoryModel.usageSummary(panel.activity, input.getText().length(), bucket.limit()));
        TextView errorView = panel.note(form, "");
        errorView.setTextColor(panel.activity.getColor(R.color.danger));
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorView.setVisibility(View.GONE);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                counter.setText(MemoryModel.usageSummary(panel.activity, s.length(), bucket.limit()));
                errorView.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        CheckBox pinBox = new CheckBox(panel.activity);
        pinBox.setMinHeight(Ui.dp(panel.activity, 48));
        pinBox.setText(R.string.brain_memory_pin_toggle);
        pinBox.setTextColor(panel.activity.getColor(R.color.ink));
        pinBox.setChecked(existing != null && existing.pinned());
        form.addView(pinBox);

        LinearLayout progressSlot = Ui.column(panel.activity);
        form.addView(progressSlot);

        ScrollView formScroll = new ScrollView(panel.activity);
        formScroll.setFillViewport(true);
        formScroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(panel.activity)
                .setTitle(existing == null ? R.string.brain_memory_add_title : R.string.brain_memory_edit_title)
                .setView(formScroll)
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_save, null)
                .create();
        panel.present(dialog);
        Button saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        saveBtn.setOnClickListener(v -> {
            if (!saveBtn.isEnabled()) return;
            String validated;
            try {
                validated = MemoryModel.validateEntryText(input.getText().toString(), bucket.limit());
            } catch (LocalizedInputException invalid) {
                Ui.showInlineError(errorView, invalid.resolve(panel.activity));
                return;
            }
            boolean pinned = pinBox.isChecked();
            if (existing == null) {
                errorView.setVisibility(View.GONE);
                saveBtn.setEnabled(false);
                commitAdd(bucket.target(), validated, pinned, dialog, saveBtn, errorView, progressSlot);
            } else {
                saveBtn.setEnabled(false);
                dialog.dismiss();
                confirmDiffAndPatch(bucket.target(), existing, validated, pinned);
            }
        });
    }

    private void confirmDiffAndPatch(String target, MemoryModel.Entry existing, String updatedText, boolean pinned) {
        ScrollView scroll = new ScrollView(panel.activity);
        scroll.setFillViewport(true);
        LinearLayout diff = Ui.column(panel.activity);
        Ui.pad(diff, 16, 12);

        LinearLayout beforeCard = Ui.tonalCard(panel.activity);
        TextView beforeTitle = Ui.text(panel.activity, panel.t(R.string.brain_memory_before), 13);
        beforeTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        beforeTitle.setTextColor(panel.activity.getColor(R.color.muted));
        beforeCard.addView(beforeTitle);
        TextView beforeText = Ui.text(panel.activity, existing.text(), 15);
        beforeText.setTextColor(panel.activity.getColor(R.color.muted));
        beforeText.setLineSpacing(0f, 1.2f);
        Ui.pad(beforeText, 0, 4);
        beforeCard.addView(beforeText);
        diff.addView(beforeCard, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout afterCard = Ui.card(panel.activity);
        TextView afterTitle = Ui.text(panel.activity, panel.t(R.string.brain_memory_after), 13);
        afterTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        afterTitle.setTextColor(panel.activity.getColor(R.color.accent));
        afterCard.addView(afterTitle);
        TextView afterText = Ui.text(panel.activity, updatedText, 16);
        afterText.setLineSpacing(0f, 1.2f);
        Ui.pad(afterText, 0, 4);
        afterCard.addView(afterText);
        LinearLayout.LayoutParams afterLp = new LinearLayout.LayoutParams(-1, -2);
        afterLp.topMargin = Ui.dp(panel.activity, 10);
        diff.addView(afterCard, afterLp);

        TextView errorView = panel.note(diff, "");
        errorView.setTextColor(panel.activity.getColor(R.color.danger));
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorView.setVisibility(View.GONE);
        LinearLayout progressSlot = Ui.column(panel.activity);
        diff.addView(progressSlot);

        scroll.addView(diff, new ScrollView.LayoutParams(-1, -2));
        AlertDialog confirmDialog = new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.brain_memory_confirm_save_title)
                .setView(scroll)
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.brain_memory_confirm_save_action, null)
                .create();
        panel.present(confirmDialog);
        Button confirmBtn = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        confirmBtn.setOnClickListener(v -> {
            if (!confirmBtn.isEnabled()) return;
            errorView.setVisibility(View.GONE);
            confirmBtn.setEnabled(false);
            commitPatch(target, existing.id(), updatedText, pinned, confirmDialog, confirmBtn, errorView, progressSlot);
        });
    }

    private void commitAdd(String target, String text, boolean pinned, AlertDialog dialog,
                           Button saveBtn, TextView errorView, LinearLayout progressSlot) {
        String safeTarget;
        try {
            safeTarget = MemoryModel.validateTarget(target);
        } catch (LocalizedInputException invalid) {
            Diagnostics.warning("memory", "invalid_target");
            saveBtn.setEnabled(true);
            Ui.showInlineError(errorView, invalid.resolve(panel.activity));
            return;
        }
        JSONObject payload = PanelData.put(PanelData.put(new JSONObject(), "text", text), "pin", pinned);
        if (!panel.request(progressSlot, () -> BrainJson.parseMemorySnapshot(
                panel.api.post("/api/memory/" + HermesApi.segment(safeTarget), payload)),
                updated -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    Diagnostics.event("memory", "entry_added");
                    snapshot = updated;
                    renderEntries();
                }, error -> showDialogMutationError(dialog, saveBtn, errorView, error))) {
            saveBtn.setEnabled(true);
        }
    }

    private void commitPatch(String target, String entryId, String text, boolean pinned, AlertDialog dialog,
                             Button confirmBtn, TextView errorView, LinearLayout progressSlot) {
        String safeTarget;
        String safeId;
        try {
            safeTarget = MemoryModel.validateTarget(target);
            safeId = MemoryModel.validateEntryId(entryId);
        } catch (LocalizedInputException invalid) {
            Diagnostics.warning("memory", "invalid_entry_id");
            confirmBtn.setEnabled(true);
            Ui.showInlineError(errorView, invalid.resolve(panel.activity));
            return;
        }
        JSONObject payload = PanelData.put(PanelData.put(new JSONObject(), "text", text), "pinned", pinned);
        if (!panel.request(progressSlot, () -> BrainJson.parseMemorySnapshot(
                panel.api.patch("/api/memory/" + HermesApi.segment(safeTarget) + "/" + HermesApi.segment(safeId), payload)),
                updated -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    Diagnostics.event("memory", "entry_updated");
                    snapshot = updated;
                    renderEntries();
                }, error -> showDialogMutationError(dialog, confirmBtn, errorView, error))) {
            confirmBtn.setEnabled(true);
        }
    }

    private void showDialogMutationError(AlertDialog dialog, Button actionBtn, TextView errorView, Exception error) {
        String message;
        if (error instanceof HermesApi.HttpError http) {
            Diagnostics.warning("memory", "mutation_http_error", "http_" + http.status);
            message = MemoryModel.describeHttpError(panel.activity, http.status, http.code);
            if (http.status == 409) load();
        } else {
            Diagnostics.warning("memory", "mutation_failed", error);
            message = panel.describeError(error);
        }
        if (dialog.isShowing()) {
            actionBtn.setEnabled(true);
            Ui.showInlineError(errorView, message);
        }
    }

    private void togglePin(String target, MemoryModel.Entry entry, Button trigger) {
        if (trigger != null && !trigger.isEnabled()) return;
        String safeTarget;
        String safeId;
        try {
            safeTarget = MemoryModel.validateTarget(target);
            safeId = MemoryModel.validateEntryId(entry.id());
        } catch (LocalizedInputException invalid) {
            Diagnostics.warning("memory", "invalid_entry_id");
            BrainUi.liveNote(panel, body, invalid.resolve(panel.activity));
            return;
        }
        if (trigger != null) trigger.setEnabled(false);
        JSONObject payload = PanelData.put(new JSONObject(), "pinned", !entry.pinned());
        if (!panel.request(body, () -> BrainJson.parseMemorySnapshot(
                panel.api.patch("/api/memory/" + HermesApi.segment(safeTarget) + "/" + HermesApi.segment(safeId), payload)),
                updated -> {
                    if (trigger != null) trigger.setEnabled(true);
                    Diagnostics.event("memory", "entry_pin_toggled");
                    snapshot = updated;
                    renderEntries();
                }, error -> {
                    if (trigger != null) trigger.setEnabled(true);
                    handleMutationError(error);
                })) {
            if (trigger != null) trigger.setEnabled(true);
        }
    }

    private void confirmDelete(String target, MemoryModel.Entry entry) {
        boolean[] handled = {false};
        panel.present(new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.brain_memory_delete_title)
                .setMessage(entry.text())
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_delete, (d, w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    commitDelete(target, entry);
                })
                .create());
    }

    private void commitDelete(String target, MemoryModel.Entry entry) {
        String safeTarget;
        String safeId;
        try {
            safeTarget = MemoryModel.validateTarget(target);
            safeId = MemoryModel.validateEntryId(entry.id());
        } catch (LocalizedInputException invalid) {
            Diagnostics.warning("memory", "invalid_entry_id");
            BrainUi.liveNote(panel, body, invalid.resolve(panel.activity));
            return;
        }
        panel.request(body, () -> {
            JSONObject reply = panel.api.deleteJson(
                    "/api/memory/" + HermesApi.segment(safeTarget) + "/" + HermesApi.segment(safeId));
            if (reply.length() == 0) return BrainJson.parseMemorySnapshot(panel.api.get("/api/memory"));
            return BrainJson.parseMemorySnapshot(reply);
        }, updated -> {
            Diagnostics.event("memory", "entry_deleted");
            lastDeleted = new MemoryModel.DeletedEntry(safeTarget, entry);
            snapshot = updated;
            renderEntries();
        }, error -> handleMutationError(error));
    }

    private void restoreDeleted(MemoryModel.DeletedEntry deleted, View trigger) {
        if (lastDeleted != deleted || (trigger != null && !trigger.isEnabled())) return;
        if (trigger != null) trigger.setEnabled(false);
        lastDeleted = null;
        renderUndoBanner();
        JSONObject payload = PanelData.put(
                PanelData.put(new JSONObject(), "text", deleted.entry().text()), "pin", deleted.entry().pinned());
        if (!panel.request(body, () -> BrainJson.parseMemorySnapshot(
                panel.api.post("/api/memory/" + HermesApi.segment(deleted.target()), payload)),
                updated -> {
                    Diagnostics.event("memory", "entry_restored");
                    snapshot = updated;
                    renderEntries();
                    BrainUi.liveNote(panel, undoSlot, panel.t(R.string.brain_memory_restored));
                }, error -> {
                    lastDeleted = deleted;
                    renderUndoBanner();
                    handleMutationError(error);
                })) {
            lastDeleted = deleted;
            renderUndoBanner();
        }
    }

    private void handleMutationError(Exception error) {
        if (error instanceof HermesApi.HttpError http) {
            Diagnostics.warning("memory", "mutation_http_error", "http_" + http.status);
            BrainUi.liveNote(panel, body, MemoryModel.describeHttpError(panel.activity, http.status, http.code));
            if (http.status == 409) load();
        } else {
            Diagnostics.warning("memory", "mutation_failed", error);
            panel.failure(body, error, this::load);
        }
    }
}
