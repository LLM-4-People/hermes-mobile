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
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Native Smart Notes panel ("Notes intelligentes") inside Cerveau.
 * Supports viewing, creating, editing, searching, pinning, and sharing notes via ShareWithUserSheet
 * (universal_sharing or note_sharing), plus learned Habit Suggestions (strictly count >= 3).
 */
final class NotesPanel {
    private static final int TAB_MINE = 0;
    private static final int TAB_SHARED = 1;

    private final SessionPanel panel;
    private final BooleanSupplier activeThread;
    private final Consumer<String> stageDraft;
    private final Runnable parent;
    private int activeTab = TAB_MINE;
    private int loadSeq;
    private BrainCapabilities capabilities = BrainCapabilities.empty();
    private List<NotesModel.Note> notes = new ArrayList<>();
    private List<NotesModel.HabitSuggestion> learnedHabits = new ArrayList<>();
    private EditText filterField;
    private LinearLayout tabsRow;
    private LinearLayout habitsBox;
    private LinearLayout body;

    NotesPanel(SessionPanel panel, BooleanSupplier activeThread, Consumer<String> stageDraft, Runnable parent) {
        this.panel = panel;
        this.activeThread = activeThread == null ? () -> true : activeThread;
        this.stageDraft = stageDraft;
        this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("brain_notes", R.string.brain_notes_title, parent);
        panel.note(page, panel.t(R.string.brain_notes_subtitle));

        tabsRow = Ui.row(panel.activity);
        page.addView(BrainUi.chipBar(panel.activity, tabsRow));
        renderTabs();

        filterField = BrainUi.searchField(panel.activity, R.id.brain_notes_filter, R.string.brain_notes_filter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(panel.activity, 4), 0, Ui.dp(panel.activity, 8));
        page.addView(filterField, lp);
        filterField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderList(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        habitsBox = Ui.column(panel.activity);
        page.addView(habitsBox);
        body = Ui.column(panel.activity);
        page.addView(body);
        load();
    }

    private void renderTabs() {
        tabsRow.removeAllViews();
        tabsRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_notes_tab_mine),
                activeTab == TAB_MINE, () -> { activeTab = TAB_MINE; renderTabs(); load(); }));
        tabsRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_notes_tab_shared),
                activeTab == TAB_SHARED, () -> { activeTab = TAB_SHARED; renderTabs(); load(); }));
    }

    private void load() {
        body.removeAllViews();
        habitsBox.removeAllViews();
        // Each tab switch or reload supersedes any in-flight load: a stale response must never
        // overwrite the notes of the tab now on screen.
        int seq = ++loadSeq;
        boolean sharedTab = activeTab == TAB_SHARED;
        panel.request(body, () -> {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            if (!caps.smartNotes()) return new Object[]{caps, null, null};
            List<NotesModel.Note> fetched;
            if (sharedTab) {
                fetched = BrainJson.parseNotes(panel.api.get("/api/notes/shared-with-me"), true);
            } else {
                fetched = BrainJson.parseNotes(panel.api.get("/api/notes"), false);
            }
            List<NotesModel.HabitSuggestion> habits = fetchOptionalHabits(caps);
            return new Object[]{caps, fetched, habits};
        }, payload -> {
            if (seq != loadSeq) return;
            capabilities = (BrainCapabilities) payload[0];
            @SuppressWarnings("unchecked")
            List<NotesModel.Note> fetched = (List<NotesModel.Note>) payload[1];
            @SuppressWarnings("unchecked")
            List<NotesModel.HabitSuggestion> habits = (List<NotesModel.HabitSuggestion>) payload[2];
            if (fetched == null) {
                Diagnostics.event("notes", "capability_absent");
                BrainUi.showUnavailable(panel, body, R.string.brain_notes_unavailable, this::load);
                return;
            }
            notes = new ArrayList<>(fetched);
            learnedHabits = habits == null ? new ArrayList<>() : new ArrayList<>(habits);
            renderHabits();
            renderList();
        }, error -> {
            if (seq != loadSeq) return;
            if (error instanceof HermesApi.HttpError http && (http.status == 404 || http.status == 405 || http.status == 501)) {
                Diagnostics.warning("notes", "endpoint_absent", "http_" + http.status);
                BrainUi.showUnavailable(panel, body, R.string.brain_notes_unavailable, this::load);
            } else {
                Diagnostics.warning("notes", "load_failed", error);
                panel.failure(body, error, this::load);
            }
        });
    }

    private List<NotesModel.HabitSuggestion> fetchOptionalHabits(BrainCapabilities caps) {
        if (!caps.habitSuggestions()) return java.util.Collections.emptyList();
        try {
            return NotesModel.filterLearned(
                    BrainJson.parseHabitSuggestions(panel.api.get("/api/mobile/habits/suggestions")));
        } catch (Exception ignored) {
            Diagnostics.debug("habits", "suggestions_skipped", ignored);
            return java.util.Collections.emptyList();
        }
    }

    private void renderHabits() {
        habitsBox.removeAllViews();
        if (learnedHabits.isEmpty() || stageDraft == null || !activeThread.getAsBoolean()) return;
        panel.note(habitsBox, panel.t(R.string.brain_habits_header));
        LinearLayout row = Ui.row(panel.activity);
        for (NotesModel.HabitSuggestion habit : learnedHabits) {
            row.addView(BrainUi.chip(panel.activity, habit.displayLabel(panel.activity), false, () -> {
                String prompt = habit.resolveDraft(panel.activity);
                panel.close();
                stageDraft.accept(prompt);
            }));
        }
        habitsBox.addView(BrainUi.chipBar(panel.activity, row));
    }

    private void renderList() {
        body.removeAllViews();
        LinearLayout bar = Ui.row(panel.activity);
        if (activeTab == TAB_MINE) {
            bar.addView(Ui.button(panel.activity, panel.t(R.string.brain_notes_new), v -> openEditor(null)));
        }
        bar.addView(Ui.button(panel.activity, panel.t(R.string.panel_refresh), v -> load()));
        body.addView(bar);

        String query = filterField == null ? "" : filterField.getText().toString();
        List<NotesModel.Note> visible = NotesModel.filter(notes, query);
        if (visible.isEmpty()) {
            panel.note(body, panel.t(notes.isEmpty()
                    ? (activeTab == TAB_SHARED ? R.string.brain_notes_shared_empty : R.string.brain_notes_empty)
                    : R.string.brain_memory_empty_filter));
            return;
        }
        for (NotesModel.Note note : visible) {
            body.addView(noteCard(note));
        }
    }

    private View noteCard(NotesModel.Note note) {
        LinearLayout card = BrainUi.card(panel.activity);
        LinearLayout top = Ui.row(panel.activity);
        TextView title = Ui.text(panel.activity, note.displayTitle(panel.activity), 17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (note.pinned()) top.addView(BrainUi.badge(panel.activity, panel.t(R.string.brain_memory_pinned), true));
        card.addView(top);

        if (!note.body().isEmpty()) {
            TextView content = Ui.text(panel.activity, note.body(), 15);
            content.setTextIsSelectable(true);
            Ui.pad(content, 0, 4);
            card.addView(content);
        }

        LinearLayout badges = Ui.row(panel.activity);
        if (!note.isOwner()) {
            badges.addView(BrainUi.badge(panel.activity,
                    UniversalShareModel.permissionLabel(panel.activity, note.permission()), false));
            if (!note.ownerProfile().isEmpty()) {
                badges.addView(BrainUi.badge(panel.activity,
                        panel.t(R.string.ws_shared_by, note.ownerProfile()), false));
            }
        }
        for (String tag : note.tags()) badges.addView(BrainUi.badge(panel.activity, tag, false));
        if (badges.getChildCount() > 0) {
            card.addView(BrainUi.chipBar(panel.activity, badges));
        }

        LinearLayout actions = Ui.row(panel.activity);
        if (stageDraft != null && activeThread.getAsBoolean()) {
            actions.addView(Ui.button(panel.activity, panel.t(R.string.brain_skills_use_in_thread), v -> {
                panel.close();
                stageDraft.accept(NotesModel.draftPromptForNote(panel.activity, note));
            }));
        }
        if (note.canEdit()) {
            actions.addView(Ui.button(panel.activity, panel.t(R.string.panel_edit), v -> openEditor(note)));
        }
        if (note.canShare() && (capabilities.universalSharing() || capabilities.noteSharing())) {
            actions.addView(Ui.button(panel.activity, panel.t(R.string.brain_share_action),
                    v -> ShareWithUserSheet.openShareSheet(panel, UniversalShareModel.KIND_NOTE,
                            note.id(), note.displayTitle(panel.activity), this::show)));
        }
        if (note.canDelete()) {
            Button del = Ui.button(panel.activity, panel.t(R.string.panel_delete), v -> confirmDelete(note));
            del.setTextColor(panel.activity.getColor(R.color.danger));
            actions.addView(del);
        }
        if (actions.getChildCount() > 0) card.addView(BrainUi.chipBar(panel.activity, actions));
        return card;
    }

    void showWithDraft(String prefillTitle, String prefillBody) {
        show();
        openEditor(null, prefillTitle, prefillBody);
    }

    private void openEditor(NotesModel.Note existing) {
        openEditor(existing, null, null);
    }

    private void openEditor(NotesModel.Note existing, String prefillTitle, String prefillBody) {
        LinearLayout form = Ui.column(panel.activity);
        Ui.pad(form, 18, 10);
        EditText titleField = BrainUi.searchField(panel.activity, 0, R.string.brain_notes_title_hint);
        EditText bodyField = BrainUi.multilineField(panel.activity, R.string.brain_notes_body_hint, 5);
        EditText tagsField = BrainUi.searchField(panel.activity, 0, R.string.brain_notes_tags_hint);
        CheckBox pinBox = new CheckBox(panel.activity);
        pinBox.setMinHeight(Ui.dp(panel.activity, 48));
        pinBox.setText(R.string.brain_memory_pin_toggle);
        if (existing != null) {
            titleField.setText(existing.title());
            bodyField.setText(existing.body());
            tagsField.setText(String.join(", ", existing.tags()));
            pinBox.setChecked(existing.pinned());
        } else {
            if (prefillTitle != null && !prefillTitle.isEmpty()) titleField.setText(prefillTitle);
            if (prefillBody != null && !prefillBody.isEmpty()) bodyField.setText(prefillBody);
        }
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.setMargins(0, Ui.dp(panel.activity, 4), 0, Ui.dp(panel.activity, 6));
        form.addView(titleField, gap);
        form.addView(bodyField, gap);
        form.addView(tagsField, gap);
        // Only the owner may pin; a shared "edit" collaborator never sends "pinned" (Fork answers 403).
        boolean ownerControls = existing == null || existing.canPin();
        if (ownerControls) form.addView(pinBox);
        TextView errorView = panel.note(form, "");
        errorView.setTextColor(panel.activity.getColor(R.color.danger));
        errorView.setVisibility(View.GONE);

        AlertDialog dialog = new AlertDialog.Builder(panel.activity)
                .setTitle(existing == null ? R.string.brain_notes_new : R.string.panel_edit)
                .setView(form)
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_save, null)
                .create();
        panel.present(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String validTitle;
            String validBody;
            try {
                validTitle = NotesModel.validateTitle(titleField.getText().toString());
                validBody = NotesModel.validateBody(bodyField.getText().toString());
            } catch (LocalizedInputException invalid) {
                errorView.setText(invalid.resolve(panel.activity));
                errorView.setVisibility(View.VISIBLE);
                return;
            }
            List<String> tags = NotesModel.parseTagsInput(tagsField.getText().toString());
            boolean pinned = pinBox.isChecked();
            dialog.dismiss();
            JSONObject payload = new JSONObject();
            PanelData.put(payload, "title", validTitle);
            PanelData.put(payload, "content", validBody);
            PanelData.put(payload, "tags", new JSONArray(tags));
            if (ownerControls) PanelData.put(payload, "pinned", pinned);
            if (existing != null && !existing.isOwner() && !existing.ownerProfile().isEmpty()) {
                PanelData.put(payload, "owner_profile", existing.ownerProfile());
            }
            panel.request(body, () -> existing == null
                    ? panel.api.post("/api/notes", payload)
                    : panel.api.patch("/api/notes/" + HermesApi.segment(existing.id()), payload),
                    saved -> {
                        Diagnostics.event("notes", existing == null ? "created" : "updated");
                        load();
                    }, error -> {
                        Diagnostics.warning("notes", "save_failed", error);
                        panel.failure(body, error, this::load);
                    });
        });
    }

    private void confirmDelete(NotesModel.Note note) {
        if (!note.canDelete()) return;
        panel.present(new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.panel_delete)
                .setMessage(panel.t(R.string.panel_confirm_delete, note.displayTitle(panel.activity)))
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_delete, (d, w) -> {
                    panel.request(body, () -> panel.api.deleteJson("/api/notes/" + HermesApi.segment(note.id())),
                            ok -> {
                                Diagnostics.event("notes", "deleted");
                                load();
                            }, error -> {
                                Diagnostics.warning("notes", "delete_failed", error);
                                panel.failure(body, error, this::load);
                            });
                }).create());
    }
}
