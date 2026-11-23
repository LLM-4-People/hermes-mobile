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
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;

import java.util.List;

/** Native Memory Inspector & Editor for MEMORY.md ("Ce que Hermes retient") and USER.md ("À propos de vous"). */
final class MemoryPanel {
    private final SessionPanel panel;
    private final Runnable parent;
    private String activeTarget = MemoryModel.TARGET_MEMORY;
    private MemoryModel.Snapshot snapshot;
    private MemoryModel.DeletedEntry lastDeleted;
    private EditText filterField;
    private LinearLayout tabsRow;
    private LinearLayout undoSlot;
    private LinearLayout body;

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
        body.removeAllViews();
        panel.request(body, () -> {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            if (!caps.memoryApi()) return null;
            return BrainJson.parseMemorySnapshot(panel.api.get("/api/memory"));
        }, loaded -> {
            if (loaded == null) {
                Diagnostics.event("memory", "capability_absent");
                BrainUi.showUnavailable(panel, body, R.string.brain_upgrade_required, this::load);
                return;
            }
            snapshot = loaded;
            renderEntries();
        }, error -> handleLoadError(error));
    }

    private void handleLoadError(Exception error) {
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
        undoSlot.removeAllViews();
        MemoryModel.DeletedEntry deleted = lastDeleted;
        if (deleted == null) return;
        LinearLayout banner = BrainUi.tintedBanner(panel.activity);
        TextView msg = Ui.text(panel.activity, panel.t(R.string.brain_memory_deleted_banner), 15);
        msg.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        banner.addView(msg, new LinearLayout.LayoutParams(0, -2, 1));
        banner.addView(Ui.button(panel.activity, panel.t(R.string.brain_memory_undo_delete),
                v -> restoreDeleted(deleted)));
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
        metaRow.addView(Ui.button(panel.activity, panel.t(R.string.panel_refresh), v -> load()));
        body.addView(metaRow);

        if (!bucket.enabled()) {
            BrainUi.liveNote(panel, body, panel.t(R.string.brain_memory_disabled));
        } else {
            int addLabel = MemoryModel.TARGET_USER.equals(activeTarget)
                    ? R.string.brain_memory_add_user : R.string.brain_memory_add_hermes;
            panel.action(body, addLabel, () -> openEditor(bucket, null));
        }

        String query = filterField == null ? "" : filterField.getText().toString();
        List<MemoryModel.Entry> visible = bucket.filter(query);
        if (visible.isEmpty()) {
            panel.note(body, panel.t(bucket.entries().isEmpty()
                    ? R.string.brain_memory_empty : R.string.brain_memory_empty_filter));
            return;
        }
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
        text.setTextIsSelectable(true);
        Ui.pad(text, 0, 4);
        card.addView(text);

        if (bucket.enabled()) {
            LinearLayout actions = Ui.row(panel.activity);
            actions.addView(Ui.button(panel.activity, panel.t(R.string.panel_edit), v -> openEditor(bucket, entry)));
            actions.addView(Ui.button(panel.activity,
                    panel.t(entry.pinned() ? R.string.brain_memory_unpin : R.string.brain_memory_pin_toggle),
                    v -> togglePin(bucket.target(), entry)));
            Button del = Ui.button(panel.activity, panel.t(R.string.panel_delete),
                    v -> confirmDelete(bucket.target(), entry));
            del.setTextColor(panel.activity.getColor(R.color.danger));
            actions.addView(del);
            card.addView(actions);
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
        pinBox.setChecked(existing != null && existing.pinned());
        form.addView(pinBox);

        AlertDialog dialog = new AlertDialog.Builder(panel.activity)
                .setTitle(existing == null ? R.string.brain_memory_add_title : R.string.brain_memory_edit_title)
                .setView(form)
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_save, null)
                .create();
        panel.present(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String validated;
            try {
                validated = MemoryModel.validateEntryText(input.getText().toString(), bucket.limit());
            } catch (LocalizedInputException invalid) {
                errorView.setText(invalid.resolve(panel.activity));
                errorView.setVisibility(View.VISIBLE);
                return;
            }
            boolean pinned = pinBox.isChecked();
            dialog.dismiss();
            if (existing == null) {
                commitAdd(bucket.target(), validated, pinned);
            } else {
                confirmDiffAndPatch(bucket.target(), existing, validated, pinned);
            }
        });
    }

    private void confirmDiffAndPatch(String target, MemoryModel.Entry existing, String updatedText, boolean pinned) {
        LinearLayout diff = Ui.column(panel.activity);
        Ui.pad(diff, 18, 10);
        TextView beforeTitle = Ui.text(panel.activity, panel.t(R.string.brain_memory_before), 13);
        beforeTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        beforeTitle.setTextColor(panel.activity.getColor(R.color.muted));
        diff.addView(beforeTitle);
        TextView beforeText = Ui.text(panel.activity, existing.text(), 15);
        beforeText.setTextColor(panel.activity.getColor(R.color.muted));
        Ui.pad(beforeText, 0, 4);
        diff.addView(beforeText);
        diff.addView(Ui.divider(panel.activity));
        TextView afterTitle = Ui.text(panel.activity, panel.t(R.string.brain_memory_after), 13);
        afterTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        afterTitle.setTextColor(panel.activity.getColor(R.color.accent));
        afterTitle.setPaddingRelative(0, Ui.dp(panel.activity, 8), 0, 0);
        diff.addView(afterTitle);
        TextView afterText = Ui.text(panel.activity, updatedText, 16);
        Ui.pad(afterText, 0, 4);
        diff.addView(afterText);

        panel.present(new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.brain_memory_confirm_save_title)
                .setView(diff)
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.brain_memory_confirm_save_action,
                        (d, w) -> commitPatch(target, existing.id(), updatedText, pinned))
                .create());
    }

    private void commitAdd(String target, String text, boolean pinned) {
        String safeTarget = MemoryModel.validateTarget(target);
        JSONObject payload = PanelData.put(PanelData.put(new JSONObject(), "text", text), "pin", pinned);
        panel.request(body, () -> BrainJson.parseMemorySnapshot(
                panel.api.post("/api/memory/" + HermesApi.segment(safeTarget), payload)),
                updated -> {
                    Diagnostics.event("memory", "entry_added");
                    snapshot = updated;
                    renderEntries();
                }, error -> handleMutationError(error));
    }

    private void commitPatch(String target, String entryId, String text, boolean pinned) {
        String safeTarget = MemoryModel.validateTarget(target);
        String safeId = MemoryModel.validateEntryId(entryId);
        JSONObject payload = PanelData.put(PanelData.put(new JSONObject(), "text", text), "pinned", pinned);
        panel.request(body, () -> BrainJson.parseMemorySnapshot(
                panel.api.patch("/api/memory/" + HermesApi.segment(safeTarget) + "/" + HermesApi.segment(safeId), payload)),
                updated -> {
                    Diagnostics.event("memory", "entry_updated");
                    snapshot = updated;
                    renderEntries();
                }, error -> handleMutationError(error));
    }

    private void togglePin(String target, MemoryModel.Entry entry) {
        String safeTarget = MemoryModel.validateTarget(target);
        String safeId = MemoryModel.validateEntryId(entry.id());
        JSONObject payload = PanelData.put(new JSONObject(), "pinned", !entry.pinned());
        panel.request(body, () -> BrainJson.parseMemorySnapshot(
                panel.api.patch("/api/memory/" + HermesApi.segment(safeTarget) + "/" + HermesApi.segment(safeId), payload)),
                updated -> {
                    Diagnostics.event("memory", "entry_pin_toggled");
                    snapshot = updated;
                    renderEntries();
                }, error -> handleMutationError(error));
    }

    private void confirmDelete(String target, MemoryModel.Entry entry) {
        panel.present(new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.brain_memory_delete_title)
                .setMessage(entry.text())
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_delete, (d, w) -> commitDelete(target, entry))
                .create());
    }

    private void commitDelete(String target, MemoryModel.Entry entry) {
        String safeTarget = MemoryModel.validateTarget(target);
        String safeId = MemoryModel.validateEntryId(entry.id());
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

    private void restoreDeleted(MemoryModel.DeletedEntry deleted) {
        lastDeleted = null;
        renderUndoBanner();
        JSONObject payload = PanelData.put(
                PanelData.put(new JSONObject(), "text", deleted.entry().text()), "pin", deleted.entry().pinned());
        panel.request(body, () -> BrainJson.parseMemorySnapshot(
                panel.api.post("/api/memory/" + HermesApi.segment(deleted.target()), payload)),
                updated -> {
                    Diagnostics.event("memory", "entry_restored");
                    snapshot = updated;
                    renderEntries();
                    BrainUi.liveNote(panel, undoSlot, panel.t(R.string.brain_memory_restored));
                }, error -> {
                    lastDeleted = deleted;
                    handleMutationError(error);
                });
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
