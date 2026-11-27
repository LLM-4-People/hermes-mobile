package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.CodeReader;
import org.llm4people.hermes.net.HermesApi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Full native Skill Studio: read SKILL.md, create/edit/delete user skills,
 * toggle enabled/disabled with strict rollback, and 1-tap editable draft handoff.
 * Degrades cleanly to read-only catalog when gateway lacks features.skill_studio.
 */
final class SkillStudio {
    private final SessionPanel panel;
    private final BooleanSupplier activeThread;
    private final Consumer<String> stageDraft;
    private final Runnable parent;
    private final SkillStudioModel.ToggleState toggleState = new SkillStudioModel.ToggleState();
    private final Set<String> pendingToggles = new java.util.HashSet<>();
    private boolean studioSupported;
    private List<SkillInventory> inventory = new ArrayList<>();
    private String selectedCategory = "";
    private EditText searchField;
    private LinearLayout categoryRow;
    private LinearLayout statusBox;
    private LinearLayout listBox;
    private String lastEmptyAnnouncement;

    SkillStudio(SessionPanel panel, BooleanSupplier activeThread, Consumer<String> stageDraft, Runnable parent) {
        this.panel = panel;
        this.activeThread = activeThread == null ? () -> true : activeThread;
        this.stageDraft = stageDraft;
        this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("brain_skills", R.string.brain_skills_title, parent);
        panel.note(page, panel.t(R.string.brain_skills_subtitle));

        LinearLayout toolbar = Ui.row(panel.activity);
        searchField = BrainUi.searchField(panel.activity, R.id.brain_skills_search, R.string.brain_skills_search);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, -2, 1);
        searchLp.setMarginEnd(Ui.dp(panel.activity, 8));
        toolbar.addView(searchField, searchLp);
        toolbar.addView(Ui.outlinedButton(panel.activity, panel.t(R.string.panel_refresh), v -> load()));
        page.addView(toolbar);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderList(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        categoryRow = Ui.row(panel.activity);
        page.addView(BrainUi.chipBar(panel.activity, categoryRow));

        statusBox = Ui.column(panel.activity);
        page.addView(statusBox);
        listBox = Ui.column(panel.activity);
        page.addView(listBox);
        load();
    }

    private void load() {
        statusBox.removeAllViews();
        listBox.removeAllViews();
        panel.request(statusBox, () -> {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            boolean studio = caps.skillStudio();
            String path = studio ? "/v1/skills?include_disabled=true" : "/v1/skills";
            List<SkillInventory> rows = SkillInventory.parse(panel.api.get(path));
            return new Object[]{studio, rows};
        }, payload -> {
            studioSupported = (Boolean) payload[0];
            @SuppressWarnings("unchecked")
            List<SkillInventory> rows = (List<SkillInventory>) payload[1];
            inventory = new ArrayList<>(rows);
            for (SkillInventory item : inventory) toggleState.sync(item.name, item.enabled);
            renderHeaderActions();
            renderCategories();
            renderList();
        }, error -> {
            if (error instanceof HermesApi.HttpError http) {
                Diagnostics.warning("skills", "studio_load_http_error", "http_" + http.status);
                int msg = switch (http.status) {
                    case 404, 405, 501 -> R.string.skills_missing;
                    case 401, 403 -> R.string.skills_denied;
                    default -> 0;
                };
                if (msg != 0) {
                    BrainUi.liveNote(panel, statusBox, panel.t(msg));
                    panel.action(statusBox, R.string.panel_retry, this::load);
                    return;
                }
            } else {
                Diagnostics.warning("skills", "studio_load_failed", error);
            }
            panel.failure(statusBox, error, this::load);
        });
    }

    private void renderHeaderActions() {
        statusBox.removeAllViews();
        LinearLayout actions = Ui.row(panel.activity);
        int gap = Ui.dp(panel.activity, 8);
        if (studioSupported) {
            Button create = Ui.filledButton(panel.activity, panel.t(R.string.brain_skills_new), v -> openCreateForm());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(gap);
            actions.addView(create, lp);
        } else {
            panel.note(statusBox, panel.t(R.string.brain_skills_readonly_notice));
        }
        if (stageDraft != null && activeThread.getAsBoolean()) {
            Button ask = Ui.tonalButton(panel.activity, panel.t(R.string.brain_skills_ask_hermes), v -> {
                panel.close();
                stageDraft.accept(SkillStudioModel.draftPromptToCreateSkill(panel.activity));
            });
            actions.addView(ask);
        }
        if (actions.getChildCount() > 0) statusBox.addView(BrainUi.chipBar(panel.activity, actions));
    }

    private void renderCategories() {
        categoryRow.removeAllViews();
        Set<String> categories = new LinkedHashSet<>();
        for (SkillInventory skill : inventory) {
            if (!skill.category.isEmpty()) categories.add(skill.category);
        }
        if (categories.isEmpty()) return;
        categoryRow.addView(BrainUi.chip(panel.activity, panel.t(R.string.brain_skills_all_categories),
                selectedCategory.isEmpty(), () -> { selectedCategory = ""; renderCategories(); renderList(); }));
        for (String cat : categories) {
            categoryRow.addView(BrainUi.chip(panel.activity, cat, cat.equals(selectedCategory),
                    () -> { selectedCategory = cat; renderCategories(); renderList(); }));
        }
    }

    private void renderList() {
        listBox.removeAllViews();
        String query = searchField == null ? "" : searchField.getText().toString();
        List<SkillInventory> matches = SkillInventory.search(inventory, query);
        List<SkillInventory> filtered = new ArrayList<>();
        for (SkillInventory item : matches) {
            if (selectedCategory.isEmpty() || selectedCategory.equals(item.category)) filtered.add(item);
        }
        if (filtered.isEmpty()) {
            int msgRes = inventory.isEmpty() ? R.string.skills_empty : R.string.skills_no_matches;
            TextView empty = panel.note(listBox, panel.t(msgRes));
            String emptyKey = selectedCategory + ":" + msgRes;
            if (!emptyKey.equals(lastEmptyAnnouncement)) {
                empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                lastEmptyAnnouncement = emptyKey;
            }
            return;
        }
        lastEmptyAnnouncement = null;
        for (SkillInventory skill : filtered) {
            listBox.addView(skillRow(skill));
        }
    }

    private View skillRow(SkillInventory skill) {
        LinearLayout card = BrainUi.card(panel.activity);
        LinearLayout row = Ui.row(panel.activity);

        LinearLayout detailArea = Ui.column(panel.activity);
        detailArea.setMinimumHeight(Ui.dp(panel.activity, 48));
        detailArea.setBackground(Ui.roundedRipple(panel.activity, Ui.CARD_CORNER_DP, null));
        detailArea.setFocusable(true);
        detailArea.setClickable(true);
        Ui.setButtonRole(detailArea);
        detailArea.setOnClickListener(v -> openDetail(skill));

        List<String> descriptionParts = new ArrayList<>();
        descriptionParts.add(skill.name);

        LinearLayout detailTop = Ui.row(panel.activity);
        detailTop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout labels = Ui.column(panel.activity);
        TextView name = Ui.text(panel.activity, skill.name, 17);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(name);
        if (!skill.description.isEmpty()) {
            descriptionParts.add(skill.description);
            TextView desc = Ui.text(panel.activity, skill.description, 14);
            desc.setTextColor(panel.activity.getColor(R.color.muted));
            desc.setLineSpacing(0f, 1.2f);
            labels.addView(desc);
        }
        detailTop.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView arrow = new ImageView(panel.activity);
        arrow.setImageResource(R.drawable.ic_navigate_forward);
        arrow.setImageTintList(ColorStateList.valueOf(panel.activity.getColor(R.color.muted)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                Ui.dp(panel.activity, 18), Ui.dp(panel.activity, 18));
        arrowLp.setMarginStart(Ui.dp(panel.activity, 8));
        detailTop.addView(arrow, arrowLp);
        detailArea.addView(detailTop);

        LinearLayout badges = Ui.row(panel.activity);
        if (!skill.category.isEmpty()) {
            descriptionParts.add(skill.category);
            badges.addView(BrainUi.badge(panel.activity, skill.category, true));
        }
        if (studioSupported) {
            String sourceLabel = SkillStudioModel.sourceLabel(panel.activity, skill.source, skill.writable);
            descriptionParts.add(sourceLabel);
            badges.addView(BrainUi.badge(panel.activity, sourceLabel, false));
        }
        if (badges.getChildCount() > 0) {
            View badgeBar = BrainUi.chipBar(panel.activity, badges);
            badgeBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            detailArea.addView(badgeBar);
        }
        detailArea.setContentDescription(String.join(". ", descriptionParts));
        row.addView(detailArea, new LinearLayout.LayoutParams(0, -2, 1));

        if (studioSupported) {
            int minTouch = Ui.dp(panel.activity, 48);
            Switch toggle = new Switch(panel.activity);
            toggle.setMinHeight(minTouch);
            toggle.setMinimumHeight(minTouch);
            toggle.setMinWidth(minTouch);
            toggle.setMinimumWidth(minTouch);
            boolean currentEnabled = toggleState.isEnabled(skill.name, skill.enabled);
            toggle.setChecked(currentEnabled);
            toggle.setEnabled(!pendingToggles.contains(skill.name));
            toggle.setContentDescription(skill.name + " · "
                    + panel.t(currentEnabled ? R.string.brain_skills_enabled : R.string.brain_skills_disabled));
            toggle.setOnClickListener(v -> toggleSkillEnabled(skill, toggle, toggle.isChecked()));
            LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(-2, -2);
            toggleLp.setMarginStart(Ui.dp(panel.activity, 8));
            row.addView(toggle, toggleLp);
        }

        card.addView(row);
        return card;
    }

    private void toggleSkillEnabled(SkillInventory skill, Switch toggle, boolean targetEnabled) {
        if (!pendingToggles.add(skill.name)) {
            toggle.setChecked(toggleState.isEnabled(skill.name, skill.enabled));
            return;
        }
        boolean previous = toggleState.beginToggle(skill.name, targetEnabled);
        toggle.setEnabled(false);
        JSONObject payload = PanelData.put(new JSONObject(), "enabled", targetEnabled);
        if (!panel.request(statusBox, () -> panel.api.put(
                "/v1/skills/" + HermesApi.relativePath(skill.name) + "/enabled", payload),
                ok -> {
                    pendingToggles.remove(skill.name);
                    Diagnostics.event("skills", "toggle_updated");
                    replaceInventoryEnabled(skill.name, targetEnabled);
                    if (!toggle.isAttachedToWindow()) {
                        renderList();
                    } else {
                        toggle.setEnabled(true);
                        toggle.setContentDescription(skill.name + " · "
                                + panel.t(targetEnabled ? R.string.brain_skills_enabled : R.string.brain_skills_disabled));
                    }
                }, error -> {
                    pendingToggles.remove(skill.name);
                    Diagnostics.warning("skills", "toggle_failed", error);
                    toggleState.rollback(skill.name, previous);
                    if (!toggle.isAttachedToWindow()) {
                        renderList();
                    } else {
                        toggle.setEnabled(true);
                        toggle.setChecked(previous);
                    }
                    BrainUi.liveNote(panel, statusBox, panel.t(R.string.brain_skills_toggle_failed));
                })) {
            pendingToggles.remove(skill.name);
            toggleState.rollback(skill.name, previous);
            if (!toggle.isAttachedToWindow()) {
                renderList();
            } else {
                toggle.setEnabled(true);
                toggle.setChecked(previous);
            }
        }
    }

    private void replaceInventoryEnabled(String name, boolean enabled) {
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).name.equals(name)) {
                inventory.set(i, inventory.get(i).withEnabled(enabled));
                return;
            }
        }
    }

    private void openDetail(SkillInventory skill) {
        if (!studioSupported) {
            openReadOnlyDetail(skill);
            return;
        }
        LinearLayout page = panel.page("brain_skill_detail", R.string.brain_skills_title, this::show);
        TextView heading = Ui.text(panel.activity, skill.name, 22);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        page.addView(heading);
        LinearLayout content = Ui.column(panel.activity);
        page.addView(content);
        panel.load(content, () -> BrainJson.parseSkillDetail(
                panel.api.get("/v1/skills/" + HermesApi.relativePath(skill.name))),
                detail -> renderDetail(content, detail), () -> openDetail(skill));
    }

    private void openReadOnlyDetail(SkillInventory skill) {
        String description = skill.description.isEmpty() ? panel.t(R.string.skills_no_summary) : skill.description;
        String summary = skill.category.isEmpty() ? description
                : panel.t(R.string.skills_category, skill.category) + "\n\n" + description;
        AlertDialog.Builder builder = new AlertDialog.Builder(panel.activity)
                .setTitle(skill.name)
                .setMessage(summary)
                .setNegativeButton(R.string.panel_back, null);
        if (stageDraft != null && activeThread.getAsBoolean()) {
            boolean[] handled = {false};
            builder.setPositiveButton(R.string.brain_skills_use_in_thread, (d, w) -> {
                if (handled[0]) return;
                handled[0] = true;
                panel.close();
                stageDraft.accept(SkillStudioModel.draftPromptForSkill(panel.activity, skill.name));
            });
        }
        panel.present(builder.create());
    }

    private void renderDetail(LinearLayout box, SkillStudioModel.SkillDetail detail) {
        box.removeAllViews();
        LinearLayout badges = Ui.row(panel.activity);
        if (!detail.category().isEmpty()) badges.addView(BrainUi.badge(panel.activity, detail.category(), true));
        badges.addView(BrainUi.badge(panel.activity,
                SkillStudioModel.sourceLabel(panel.activity, detail.source(), detail.writable()), false));
        badges.addView(BrainUi.badge(panel.activity,
                panel.t(detail.enabled() ? R.string.brain_skills_enabled : R.string.brain_skills_disabled), detail.enabled()));
        box.addView(BrainUi.chipBar(panel.activity, badges));

        if (!detail.description().isEmpty()) panel.note(box, detail.description());
        if (!detail.supportingFiles().isEmpty()) {
            panel.note(box, panel.t(R.string.brain_skills_supporting_files, String.join(", ", detail.supportingFiles())));
        }
        if (!detail.lintWarnings().isEmpty()) {
            LinearLayout warnCard = BrainUi.card(panel.activity);
            warnCard.setBackground(Ui.shape(panel.activity, Ui.CARD_CORNER_DP,
                    R.color.warning_surface, R.color.warning, 1));
            TextView warnTitle = Ui.text(panel.activity, panel.t(R.string.brain_skills_lint_header), 14);
            warnTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            warnTitle.setTextColor(panel.activity.getColor(R.color.warning));
            warnCard.addView(warnTitle);
            for (String w : detail.lintWarnings()) {
                TextView row = Ui.text(panel.activity, w, 14);
                row.setTextColor(panel.activity.getColor(R.color.ink));
                row.setLineSpacing(0f, 1.2f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = Ui.dp(panel.activity, 4);
                warnCard.addView(row, lp);
            }
            box.addView(warnCard);
        }

        int gap = Ui.dp(panel.activity, 8);
        LinearLayout actions = Ui.row(panel.activity);
        if (stageDraft != null && activeThread.getAsBoolean()) {
            Button useBtn = Ui.filledButton(panel.activity, panel.t(R.string.brain_skills_use_in_thread), v -> {
                panel.close();
                stageDraft.accept(SkillStudioModel.draftPromptForSkill(panel.activity, detail.name()));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(gap);
            actions.addView(useBtn, lp);
        }
        String fullText = detail.raw().isEmpty() ? detail.body() : detail.raw();
        if (!fullText.isEmpty()) {
            actions.addView(Ui.tonalButton(panel.activity, panel.t(R.string.brain_skills_view_full),
                    v -> panel.present(CodeReader.dialog(panel.activity, detail.name() + " · SKILL.md", "markdown", fullText).create())));
        }
        if (actions.getChildCount() > 0) box.addView(BrainUi.chipBar(panel.activity, actions));

        if (detail.writable()) {
            LinearLayout editRow = Ui.row(panel.activity);
            Button editBtn = Ui.button(panel.activity, panel.t(R.string.panel_edit), v -> openEditForm(detail));
            LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(-2, -2);
            editLp.setMarginEnd(gap);
            editRow.addView(editBtn, editLp);

            Button shareBtn = Ui.button(panel.activity, panel.t(R.string.brain_share_action),
                    v -> ShareWithUserSheet.openShareSheet(panel, UniversalShareModel.KIND_SKILL,
                            detail.name(), detail.name(), () -> openDetail(new SkillInventoryPlaceholder(detail).toInventory())));
            LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-2, -2);
            shareLp.setMarginEnd(gap);
            editRow.addView(shareBtn, shareLp);

            Button del = Ui.button(panel.activity, panel.t(R.string.panel_delete), v -> confirmDelete(box, detail));
            del.setTextColor(Ui.dangerInk(panel.activity));
            editRow.addView(del);
            box.addView(BrainUi.chipBar(panel.activity, editRow));
        } else {
            panel.note(box, panel.t(R.string.brain_skills_builtin_readonly));
        }

        String preview = detail.body().isEmpty() ? detail.raw() : detail.body();
        if (!preview.isEmpty()) {
            LinearLayout previewCard = BrainUi.card(panel.activity);
            TextView bodyView = Ui.text(panel.activity, WorkspacePathGuard.boundContent(preview), 15);
            bodyView.setLineSpacing(0f, 1.2f);
            bodyView.setTextIsSelectable(true);
            previewCard.addView(bodyView);
            box.addView(previewCard);
        }
    }

    private EditText formField(LinearLayout parent, int labelAndHintRes, boolean capitalize) {
        TextView label = Ui.text(panel.activity, panel.t(labelAndHintRes), 14);
        label.setTypeface(Ui.mediumTypeface());
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = Ui.dp(panel.activity, 12);
        parent.addView(label, labelLp);

        EditText field = new EditText(panel.activity);
        field.setId(View.generateViewId());
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | (capitalize ? InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        Ui.styleDialogInput(panel.activity, field);
        field.setHint(labelAndHintRes);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        label.setLabelFor(field.getId());

        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, -2);
        fieldLp.topMargin = Ui.dp(panel.activity, 4);
        parent.addView(field, fieldLp);
        return field;
    }

    private void openCreateForm() {
        LinearLayout page = panel.page("brain_skill_create", R.string.brain_skills_new, this::show);
        EditText nameField = formField(page, R.string.brain_skills_name_hint, false);
        EditText catField = formField(page, R.string.brain_skills_category_hint, false);
        EditText descField = formField(page, R.string.brain_skills_desc_hint, true);
        descField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SkillStudioModel.MAX_DESCRIPTION_CHARS)});

        TextView bodyLabel = Ui.text(panel.activity, panel.t(R.string.brain_skills_instructions_hint), 14);
        bodyLabel.setTypeface(Ui.mediumTypeface());
        LinearLayout.LayoutParams bodyLabelLp = new LinearLayout.LayoutParams(-1, -2);
        bodyLabelLp.topMargin = Ui.dp(panel.activity, 12);
        page.addView(bodyLabel, bodyLabelLp);

        EditText bodyField = BrainUi.multilineField(panel.activity, R.string.brain_skills_instructions_hint, 6);
        bodyField.setId(View.generateViewId());
        bodyLabel.setLabelFor(bodyField.getId());
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
        bodyLp.topMargin = Ui.dp(panel.activity, 4);
        page.addView(bodyField, bodyLp);

        CheckBox rawMode = new CheckBox(panel.activity);
        rawMode.setMinHeight(Ui.dp(panel.activity, 48));
        rawMode.setText(R.string.brain_skills_advanced_raw);
        rawMode.setTextColor(panel.activity.getColor(R.color.ink));
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(-1, -2);
        checkLp.topMargin = Ui.dp(panel.activity, 8);
        page.addView(rawMode, checkLp);

        LinearLayout feedback = Ui.column(panel.activity);
        page.addView(feedback);

        Button saveBtn = Ui.filledButton(panel.activity, panel.t(R.string.panel_save), v -> {
            if (!v.isEnabled()) return;
            feedback.removeAllViews();
            SkillStudioModel.SkillLocation location;
            String content;
            try {
                location = SkillStudioModel.resolveSkillLocation(
                        nameField.getText().toString(), catField.getText().toString());
                if (rawMode.isChecked()) {
                    content = SkillStudioModel.validateContent(bodyField.getText().toString());
                } else {
                    content = SkillStudioModel.buildSkillDocument(
                            location.name(), descField.getText().toString(), location.category(),
                            bodyField.getText().toString());
                }
            } catch (LocalizedInputException invalid) {
                TextView err = BrainUi.liveNote(panel, feedback, invalid.resolve(panel.activity));
                err.setTextColor(panel.activity.getColor(R.color.danger));
                return;
            }
            v.setEnabled(false);
            JSONObject payload = PanelData.put(
                    PanelData.put(new JSONObject(), "name", location.name()), "content", content);
            if (!location.category().isEmpty()) PanelData.put(payload, "category", location.category());
            if (!panel.request(feedback, () -> panel.api.post("/v1/skills", payload),
                    created -> {
                        v.setEnabled(true);
                        Diagnostics.event("skills", "skill_created");
                        show();
                    }, error -> {
                        v.setEnabled(true);
                        Diagnostics.warning("skills", "create_failed", error);
                        panel.failure(feedback, error, null);
                    })) {
                v.setEnabled(true);
            }
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, -2);
        saveLp.topMargin = Ui.dp(panel.activity, 12);
        page.addView(saveBtn, saveLp);
    }

    private void openEditForm(SkillStudioModel.SkillDetail detail) {
        LinearLayout page = panel.page("brain_skill_edit", R.string.panel_edit, () -> openDetail(
                new SkillInventoryPlaceholder(detail).toInventory()));
        panel.note(page, detail.name());
        TextView editorLabel = Ui.text(panel.activity, panel.t(R.string.brain_skills_instructions_hint), 14);
        editorLabel.setTypeface(Ui.mediumTypeface());
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = Ui.dp(panel.activity, 8);
        page.addView(editorLabel, labelLp);

        EditText editor = BrainUi.multilineField(panel.activity, R.string.brain_skills_instructions_hint, 10);
        editor.setId(View.generateViewId());
        editorLabel.setLabelFor(editor.getId());
        editor.setText(detail.raw().isEmpty() ? detail.body() : detail.raw());
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(-1, -2);
        editorLp.topMargin = Ui.dp(panel.activity, 4);
        page.addView(editor, editorLp);
        LinearLayout feedback = Ui.column(panel.activity);
        page.addView(feedback);

        Button saveBtn = Ui.filledButton(panel.activity, panel.t(R.string.panel_save), v -> {
            if (!v.isEnabled()) return;
            feedback.removeAllViews();
            String content;
            try {
                content = SkillStudioModel.validateContent(editor.getText().toString());
            } catch (LocalizedInputException invalid) {
                TextView err = BrainUi.liveNote(panel, feedback, invalid.resolve(panel.activity));
                err.setTextColor(panel.activity.getColor(R.color.danger));
                return;
            }
            v.setEnabled(false);
            JSONObject payload = PanelData.put(new JSONObject(), "content", content);
            if (!panel.request(feedback, () -> panel.api.put(
                    "/v1/skills/" + HermesApi.relativePath(detail.name()), payload),
                    updated -> {
                        v.setEnabled(true);
                        Diagnostics.event("skills", "skill_updated");
                        show();
                    }, error -> {
                        v.setEnabled(true);
                        Diagnostics.warning("skills", "update_failed", error);
                        panel.failure(feedback, error, null);
                    })) {
                v.setEnabled(true);
            }
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, -2);
        saveLp.topMargin = Ui.dp(panel.activity, 12);
        page.addView(saveBtn, saveLp);
    }

    private void confirmDelete(LinearLayout feedbackTarget, SkillStudioModel.SkillDetail detail) {
        if (!detail.writable()) return;
        boolean[] handled = {false};
        panel.present(new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.panel_delete)
                .setMessage(panel.t(R.string.brain_skills_delete_confirm, detail.name()))
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_delete, (d, w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    panel.request(feedbackTarget, () -> panel.api.deleteJson(
                            "/v1/skills/" + HermesApi.relativePath(detail.name())),
                            ok -> {
                                Diagnostics.event("skills", "skill_deleted");
                                show();
                            }, error -> {
                                Diagnostics.warning("skills", "delete_failed", error);
                                panel.failure(feedbackTarget, error, null);
                            });
                }).create());
    }

    /** Minimal adapter to reopen detail view from edit back-navigation. */
    private record SkillInventoryPlaceholder(SkillStudioModel.SkillDetail detail) {
        SkillInventory toInventory() {
            try {
                JSONObject row = PanelData.put(new JSONObject(), "name", detail.name());
                PanelData.put(row, "description", detail.description());
                PanelData.put(row, "category", detail.category());
                PanelData.put(row, "enabled", detail.enabled());
                PanelData.put(row, "writable", detail.writable());
                PanelData.put(row, "source", detail.source());
                org.json.JSONArray arr = new org.json.JSONArray().put(row);
                return SkillInventory.parse(PanelData.put(new JSONObject(), "data", arr)).get(0);
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
