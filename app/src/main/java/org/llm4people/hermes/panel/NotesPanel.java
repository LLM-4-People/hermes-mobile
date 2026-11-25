package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Native Smart Notes panel ("Notes intelligentes") under Notes &amp; Tasks.
 * Supports viewing, creating, editing, searching, pinning, and sharing notes via ShareWithUserSheet
 * (universal_sharing or note_sharing), plus learned Habit Suggestions (strictly count >= 3).
 */
final class NotesPanel {
    private final SessionPanel panel;
    private final BooleanSupplier activeThread;
    private final Consumer<String> stageDraft;
    private final Runnable parent;
    private NotesModel.FilterTab activeTab = NotesModel.FilterTab.MINE;
    private int loadSeq;
    private boolean loadFailed;
    private String pendingNoteId;
    private BrainCapabilities capabilities = BrainCapabilities.empty();
    private List<NotesModel.Note> notes = new ArrayList<>();
    private List<NotesModel.HabitSuggestion> learnedHabits = new ArrayList<>();
    private EditText filterField;
    private LinearLayout tabsRow;
    private LinearLayout habitsBox;
    private LinearLayout body;
    private String lastEmptyAnnouncement;

    NotesPanel(SessionPanel panel, BooleanSupplier activeThread, Consumer<String> stageDraft, Runnable parent) {
        this.panel = panel;
        this.activeThread = activeThread == null ? () -> true : activeThread;
        this.stageDraft = stageDraft;
        this.parent = parent;
    }

    void show() {
        showTab(NotesModel.FilterTab.MINE, null);
    }

    void show(String initialNoteId) {
        showTab(NotesModel.FilterTab.MINE, initialNoteId);
    }

    void showShared() {
        showTab(NotesModel.FilterTab.SHARED, null);
    }

    private void showTab(NotesModel.FilterTab initialTab, String initialNoteId) {
        activeTab = initialTab == null ? NotesModel.FilterTab.MINE : initialTab;
        pendingNoteId = initialNoteId == null ? null : initialNoteId.trim();
        if (pendingNoteId != null && pendingNoteId.isEmpty()) pendingNoteId = null;
        loadFailed = false;
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
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!loadFailed) renderList();
            }
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
        int gap = Ui.dp(panel.activity, 8);
        NotesModel.FilterTab[] tabs = NotesModel.FilterTab.values();
        for (int i = 0; i < tabs.length; i++) {
            NotesModel.FilterTab tab = tabs[i];
            boolean selected = activeTab == tab;
            String label = panel.t(tab.labelRes);
            Button pill = Ui.choiceChip(panel.activity, label, v -> {
                if (activeTab == tab) return;
                activeTab = tab;
                renderTabs();
                load();
            });
            pill.setTag("notes:filter:" + tab.name().toLowerCase(java.util.Locale.ROOT));
            Ui.styleChoiceChip(pill, selected);
            pill.setPaddingRelative(Ui.dp(panel.activity, 18), Ui.dp(panel.activity, 10),
                    Ui.dp(panel.activity, 18), Ui.dp(panel.activity, 10));
            pill.setContentDescription(label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i < tabs.length - 1) lp.setMarginEnd(gap);
            tabsRow.addView(pill, lp);
        }
    }

    private void load() {
        body.removeAllViews();
        habitsBox.removeAllViews();
        loadFailed = false;
        // Each tab switch or reload supersedes any in-flight load: a stale response must never
        // overwrite the notes of the tab now on screen.
        int seq = ++loadSeq;
        boolean sharedTab = activeTab == NotesModel.FilterTab.SHARED;
        panel.request(body, () -> {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            if (!caps.smartNotes()) return new Object[]{caps, null, null};
            List<NotesModel.Note> fetched;
            if (sharedTab) {
                fetched = BrainJson.parseNotes(panel.api.get("/api/notes/shared-with-me"), true);
            } else {
                fetched = BrainJson.parseNotes(panel.api.get("/api/notes"), false);
            }
            List<NotesModel.HabitSuggestion> habits = sharedTab
                    ? java.util.Collections.emptyList()
                    : fetchOptionalHabits(caps);
            return new Object[]{caps, fetched, habits};
        }, payload -> {
            if (seq != loadSeq) return;
            capabilities = (BrainCapabilities) payload[0];
            @SuppressWarnings("unchecked")
            List<NotesModel.Note> fetched = (List<NotesModel.Note>) payload[1];
            @SuppressWarnings("unchecked")
            List<NotesModel.HabitSuggestion> habits = (List<NotesModel.HabitSuggestion>) payload[2];
            if (fetched == null) {
                loadFailed = true;
                Diagnostics.event("notes", "capability_absent");
                BrainUi.showUnavailable(panel, body, R.string.brain_notes_unavailable, this::load);
                return;
            }
            loadFailed = false;
            notes = new ArrayList<>(fetched);
            learnedHabits = habits == null ? new ArrayList<>() : new ArrayList<>(habits);
            renderHabits();
            renderList();
            openPendingNoteIfPresent();
        }, error -> {
            if (seq != loadSeq) return;
            if (error instanceof HermesApi.HttpError http && (http.status == 404 || http.status == 405 || http.status == 501)) {
                Diagnostics.warning("notes", "endpoint_absent", "http_" + http.status);
                if (sharedTab) {
                    loadFailed = false;
                    notes = new ArrayList<>();
                    renderList();
                } else {
                    loadFailed = true;
                    BrainUi.showUnavailable(panel, body, R.string.brain_notes_unavailable, this::load);
                }
            } else {
                loadFailed = true;
                Diagnostics.warning("notes", "load_failed", error);
                panel.failure(body, error, this::load);
            }
        });
    }

    private void openPendingNoteIfPresent() {
        String targetId = pendingNoteId;
        if (targetId == null || targetId.isEmpty()) return;
        pendingNoteId = null;
        for (NotesModel.Note note : notes) {
            if (targetId.equals(note.id())) {
                openReader(note);
                return;
            }
        }
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
        if (loadFailed) return;
        body.removeAllViews();
        LinearLayout bar = Ui.row(panel.activity);
        if (activeTab == NotesModel.FilterTab.MINE) {
            Button createBtn = Ui.filledButton(panel.activity, panel.t(R.string.brain_notes_new), v -> openEditor(null));
            LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(-2, -2);
            createLp.setMarginEnd(Ui.dp(panel.activity, 8));
            bar.addView(createBtn, createLp);
        }
        bar.addView(Ui.outlinedButton(panel.activity, panel.t(R.string.panel_refresh), v -> load()));
        body.addView(BrainUi.chipBar(panel.activity, bar));

        String query = filterField == null ? "" : filterField.getText().toString();
        List<NotesModel.Note> visible = NotesModel.filterForTab(notes, activeTab, query);
        if (visible.isEmpty()) {
            int msgRes = NotesModel.emptyMessageRes(activeTab, notes.isEmpty());
            TextView empty = panel.note(body, panel.t(msgRes));
            String emptyKey = activeTab.name() + ":" + msgRes;
            if (!emptyKey.equals(lastEmptyAnnouncement)) {
                empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                lastEmptyAnnouncement = emptyKey;
            }
            return;
        }
        lastEmptyAnnouncement = null;
        int cardGap = Ui.dp(panel.activity, 10);
        for (int i = 0; i < visible.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i < visible.size() - 1) lp.bottomMargin = cardGap;
            body.addView(noteCard(visible.get(i)), lp);
        }
    }

    private View noteCard(NotesModel.Note note) {
        LinearLayout card = Ui.card(panel.activity);

        LinearLayout headerArea = Ui.column(panel.activity);
        headerArea.setMinimumHeight(Ui.dp(panel.activity, 48));
        headerArea.setBackground(Ui.roundedRipple(panel.activity, Ui.CARD_CORNER_DP, null));
        headerArea.setFocusable(true);
        headerArea.setClickable(true);
        Ui.setButtonRole(headerArea);
        headerArea.setOnClickListener(v -> openReader(note));

        List<String> descriptionParts = new ArrayList<>();
        String displayTitle = note.displayTitle(panel.activity);
        descriptionParts.add(panel.t(R.string.notes_open_note_cd, displayTitle));

        LinearLayout top = Ui.row(panel.activity);
        top.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView title = Ui.text(panel.activity, displayTitle, 16);
        title.setTypeface(Ui.mediumTypeface());
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (note.pinned()) {
            String pinnedLabel = panel.t(R.string.brain_memory_pinned);
            descriptionParts.add(pinnedLabel);
            top.addView(Ui.pill(panel.activity, pinnedLabel,
                    R.color.accent_surface, R.color.accent));
        }
        headerArea.addView(top);

        if (!note.body().isEmpty()) {
            String preview = PlainTextSanitizer.collapseWithEllipsis(note.body(), 241);
            if (!preview.isEmpty()) descriptionParts.add(preview);
            TextView content = Ui.text(panel.activity, note.body(), 14);
            content.setTextColor(panel.activity.getColor(R.color.muted));
            content.setMaxLines(4);
            content.setEllipsize(android.text.TextUtils.TruncateAt.END);
            content.setPaddingRelative(0, Ui.dp(panel.activity, 6), 0, 0);
            content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            headerArea.addView(content);
        }

        LinearLayout badges = Ui.row(panel.activity);
        badges.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int badgeGap = Ui.dp(panel.activity, 6);
        if (!note.isOwner()) {
            String permLabel = UniversalShareModel.permissionLabel(panel.activity, note.permission());
            descriptionParts.add(permLabel);
            TextView permBadge = Ui.pill(panel.activity, permLabel,
                    R.color.accent_surface, R.color.accent);
            LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(-2, -2);
            permLp.setMarginEnd(badgeGap);
            badges.addView(permBadge, permLp);
            if (!note.ownerProfile().isEmpty()) {
                String sharedBy = panel.t(R.string.ws_shared_by, note.ownerProfile());
                descriptionParts.add(sharedBy);
                TextView ownerBadge = Ui.pill(panel.activity, sharedBy,
                        R.color.surface_container, R.color.muted);
                LinearLayout.LayoutParams ownerLp = new LinearLayout.LayoutParams(-2, -2);
                ownerLp.setMarginEnd(badgeGap);
                badges.addView(ownerBadge, ownerLp);
            }
        }
        for (String tag : note.tags()) {
            descriptionParts.add(tag);
            TextView tagPill = Ui.pill(panel.activity, tag, R.color.surface_container, R.color.ink);
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
            tagLp.setMarginEnd(badgeGap);
            badges.addView(tagPill, tagLp);
        }
        if (badges.getChildCount() > 0) {
            badges.setPaddingRelative(0, Ui.dp(panel.activity, 8), 0, 0);
            View badgeBar = BrainUi.chipBar(panel.activity, badges);
            badgeBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            headerArea.addView(badgeBar);
        }
        headerArea.setContentDescription(String.join(". ", descriptionParts));
        card.addView(headerArea);

        LinearLayout actions = Ui.row(panel.activity);
        int actionGap = Ui.dp(panel.activity, 8);
        if (stageDraft != null && activeThread.getAsBoolean()) {
            addActionButton(actions, Ui.button(panel.activity, panel.t(R.string.brain_skills_use_in_thread), v -> {
                panel.close();
                stageDraft.accept(NotesModel.draftPromptForNote(panel.activity, note));
            }), actionGap);
        }
        if (note.canEdit()) {
            addActionButton(actions, Ui.button(panel.activity, panel.t(R.string.panel_edit), v -> openEditor(note)), actionGap);
        }
        if (note.canShare() && (capabilities.universalSharing() || capabilities.noteSharing())) {
            addActionButton(actions, Ui.button(panel.activity, panel.t(R.string.brain_share_action),
                    v -> ShareWithUserSheet.openShareSheet(panel, UniversalShareModel.KIND_NOTE,
                            note.id(), note.displayTitle(panel.activity), this::show)), actionGap);
        }
        if (note.canDelete()) {
            Button del = Ui.button(panel.activity, panel.t(R.string.panel_delete), v -> confirmDelete(note));
            del.setTextColor(Ui.dangerInk(panel.activity));
            addActionButton(actions, del, actionGap);
        }
        if (actions.getChildCount() > 0) card.addView(BrainUi.chipBar(panel.activity, actions));
        return card;
    }

    private static void addActionButton(LinearLayout actions, Button button, int gapPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (actions.getChildCount() > 0) lp.setMarginStart(gapPx);
        actions.addView(button, lp);
    }

    private void openReader(NotesModel.Note note) {
        AlertDialog.Builder reader = org.llm4people.hermes.artifact.CodeReader.dialog(
                panel.activity, note.displayTitle(panel.activity), "markdown", note.body());
        if (note.canEdit()) {
            reader.setNeutralButton(R.string.panel_edit, (d, w) -> openEditor(note));
        }
        if (stageDraft != null && activeThread.getAsBoolean()) {
            reader.setPositiveButton(R.string.brain_skills_use_in_thread, (d, w) -> {
                panel.close();
                stageDraft.accept(NotesModel.draftPromptForNote(panel.activity, note));
            });
        }
        panel.present(reader.create());
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
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorView.setVisibility(View.GONE);

        ScrollView scroll = new ScrollView(panel.activity);
        scroll.setFillViewport(true);
        scroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(panel.activity)
                .setTitle(existing == null ? R.string.brain_notes_new : R.string.panel_edit)
                .setView(scroll)
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
