package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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
 * Full native Skill Studio ("Savoir-faire"): read SKILL.md, create/edit/delete user skills,
 * toggle enabled/disabled with strict rollback, and 1-tap "Utiliser dans la discussion" editable draft.
 * Degrades cleanly to read-only catalog when gateway lacks features.skill_studio.
 */
final class SkillStudio {
    private final SessionPanel panel;
    private final BooleanSupplier activeThread;
    private final Consumer<String> stageDraft;
    private final Runnable parent;
    private final SkillStudioModel.ToggleState toggleState = new SkillStudioModel.ToggleState();
    private boolean studioSupported;
    private List<SkillInventory> inventory = new ArrayList<>();
    private String selectedCategory = "";
    private EditText searchField;
    private LinearLayout categoryRow;
    private LinearLayout statusBox;
    private LinearLayout listBox;

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
        toolbar.addView(searchField, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(Ui.button(panel.activity, panel.t(R.string.panel_refresh), v -> load()));
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
        if (studioSupported) {
            actions.addView(Ui.button(panel.activity, panel.t(R.string.brain_skills_new), v -> openCreateForm()));
        } else {
            panel.note(statusBox, panel.t(R.string.brain_skills_readonly_notice));
        }
        if (stageDraft != null && activeThread.getAsBoolean()) {
            actions.addView(Ui.button(panel.activity, panel.t(R.string.brain_skills_ask_hermes), v -> {
                panel.close();
                stageDraft.accept(SkillStudioModel.draftPromptToCreateSkill(panel.activity));
            }));
        }
        if (actions.getChildCount() > 0) statusBox.addView(actions);
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
            BrainUi.liveNote(panel, listBox, panel.t(inventory.isEmpty() ? R.string.skills_empty : R.string.skills_no_matches));
            return;
        }
        for (SkillInventory skill : filtered) {
            listBox.addView(skillRow(skill));
        }
    }

    private View skillRow(SkillInventory skill) {
        LinearLayout card = BrainUi.card(panel.activity);
        LinearLayout top = Ui.row(panel.activity);
        LinearLayout labels = Ui.column(panel.activity);
        TextView name = Ui.text(panel.activity, skill.name, 17);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(name);
        if (!skill.description.isEmpty()) {
            TextView desc = Ui.text(panel.activity, skill.description, 14);
            desc.setTextColor(panel.activity.getColor(R.color.muted));
            labels.addView(desc);
        }
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        if (studioSupported) {
            Switch toggle = new Switch(panel.activity);
            toggle.setMinHeight(Ui.dp(panel.activity, 48));
            boolean currentEnabled = toggleState.isEnabled(skill.name, skill.enabled);
            toggle.setChecked(currentEnabled);
            toggle.setContentDescription(skill.name + " · "
                    + panel.t(currentEnabled ? R.string.brain_skills_enabled : R.string.brain_skills_disabled));
            toggle.setOnClickListener(v -> toggleSkillEnabled(skill, toggle, toggle.isChecked()));
            top.addView(toggle);
        }
        card.addView(top);

        LinearLayout badges = Ui.row(panel.activity);
        if (!skill.category.isEmpty()) badges.addView(BrainUi.badge(panel.activity, skill.category, true));
        if (studioSupported) {
            badges.addView(BrainUi.badge(panel.activity,
                    SkillStudioModel.sourceLabel(panel.activity, skill.source, skill.writable), false));
        }
        if (badges.getChildCount() > 0) {
            badges.setPaddingRelative(0, Ui.dp(panel.activity, 6), 0, 0);
            card.addView(badges);
        }

        card.setFocusable(true);
        card.setOnClickListener(v -> openDetail(skill));
        return card;
    }

    private void toggleSkillEnabled(SkillInventory skill, Switch toggle, boolean targetEnabled) {
        boolean previous = toggleState.beginToggle(skill.name, targetEnabled);
        toggle.setEnabled(false);
        JSONObject payload = PanelData.put(new JSONObject(), "enabled", targetEnabled);
        panel.request(statusBox, () -> panel.api.put(
                "/v1/skills/" + HermesApi.relativePath(skill.name) + "/enabled", payload),
                ok -> {
                    Diagnostics.event("skills", "toggle_updated");
                    toggle.setEnabled(true);
                    replaceInventoryEnabled(skill.name, targetEnabled);
                    toggle.setContentDescription(skill.name + " · "
                            + panel.t(targetEnabled ? R.string.brain_skills_enabled : R.string.brain_skills_disabled));
                }, error -> {
                    Diagnostics.warning("skills", "toggle_failed", error);
                    toggleState.rollback(skill.name, previous);
                    toggle.setEnabled(true);
                    toggle.setChecked(previous);
                    BrainUi.liveNote(panel, statusBox, panel.t(R.string.brain_skills_toggle_failed));
                });
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
            builder.setPositiveButton(R.string.brain_skills_use_in_thread, (d, w) -> {
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
        box.addView(badges);

        if (!detail.description().isEmpty()) panel.note(box, detail.description());
        if (!detail.supportingFiles().isEmpty()) {
            panel.note(box, panel.t(R.string.brain_skills_supporting_files, String.join(", ", detail.supportingFiles())));
        }
        if (!detail.lintWarnings().isEmpty()) {
            LinearLayout warnCard = BrainUi.card(panel.activity);
            TextView warnTitle = Ui.text(panel.activity, panel.t(R.string.brain_skills_lint_header), 14);
            warnTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            warnTitle.setTextColor(panel.activity.getColor(R.color.danger));
            warnCard.addView(warnTitle);
            for (String w : detail.lintWarnings()) panel.note(warnCard, "• " + w);
            box.addView(warnCard);
        }

        LinearLayout actions = Ui.row(panel.activity);
        if (stageDraft != null && activeThread.getAsBoolean()) {
            actions.addView(Ui.button(panel.activity, panel.t(R.string.brain_skills_use_in_thread), v -> {
                panel.close();
                stageDraft.accept(SkillStudioModel.draftPromptForSkill(panel.activity, detail.name()));
            }));
        }
        String fullText = detail.raw().isEmpty() ? detail.body() : detail.raw();
        if (!fullText.isEmpty()) {
            actions.addView(Ui.button(panel.activity, panel.t(R.string.brain_skills_view_full),
                    v -> panel.present(CodeReader.dialog(panel.activity, detail.name() + " · SKILL.md", "markdown", fullText).create())));
        }
        box.addView(actions);

        if (detail.writable()) {
            LinearLayout editRow = Ui.row(panel.activity);
            editRow.addView(Ui.button(panel.activity, panel.t(R.string.panel_edit), v -> openEditForm(detail)));
            editRow.addView(Ui.button(panel.activity, panel.t(R.string.brain_share_action),
                    v -> ShareWithUserSheet.openShareSheet(panel, UniversalShareModel.KIND_SKILL,
                            detail.name(), detail.name(), () -> openDetail(new SkillInventoryPlaceholder(detail).toInventory()))));
            Button del = Ui.button(panel.activity, panel.t(R.string.panel_delete), v -> confirmDelete(box, detail));
            del.setTextColor(panel.activity.getColor(R.color.danger));
            editRow.addView(del);
            box.addView(editRow);
        } else {
            panel.note(box, panel.t(R.string.brain_skills_builtin_readonly));
        }

        String preview = detail.body().isEmpty() ? detail.raw() : detail.body();
        if (!preview.isEmpty()) {
            LinearLayout previewCard = BrainUi.card(panel.activity);
            TextView bodyView = Ui.text(panel.activity, WorkspacePathGuard.boundContent(preview), 15);
            bodyView.setTextIsSelectable(true);
            previewCard.addView(bodyView);
            box.addView(previewCard);
        }
    }

    private void openCreateForm() {
        LinearLayout page = panel.page("brain_skill_create", R.string.brain_skills_new, this::show);
        EditText nameField = BrainUi.searchField(panel.activity, 0, R.string.brain_skills_name_hint);
        EditText catField = BrainUi.searchField(panel.activity, 0, R.string.brain_skills_category_hint);
        EditText descField = BrainUi.searchField(panel.activity, 0, R.string.brain_skills_desc_hint);
        descField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SkillStudioModel.MAX_DESCRIPTION_CHARS)});
        EditText bodyField = BrainUi.multilineField(panel.activity, R.string.brain_skills_instructions_hint, 6);
        CheckBox rawMode = new CheckBox(panel.activity);
        rawMode.setMinHeight(Ui.dp(panel.activity, 48));
        rawMode.setText(R.string.brain_skills_advanced_raw);

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.setMargins(0, Ui.dp(panel.activity, 6), 0, Ui.dp(panel.activity, 6));
        page.addView(nameField, gap);
        page.addView(catField, gap);
        page.addView(descField, gap);
        page.addView(bodyField, gap);
        page.addView(rawMode);

        LinearLayout feedback = Ui.column(panel.activity);
        page.addView(feedback);

        panel.action(page, R.string.panel_save, () -> {
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
            JSONObject payload = PanelData.put(
                    PanelData.put(new JSONObject(), "name", location.name()), "content", content);
            if (!location.category().isEmpty()) PanelData.put(payload, "category", location.category());
            panel.request(feedback, () -> panel.api.post("/v1/skills", payload),
                    created -> {
                        Diagnostics.event("skills", "skill_created");
                        show();
                    }, error -> {
                        Diagnostics.warning("skills", "create_failed", error);
                        panel.failure(feedback, error, null);
                    });
        });
    }

    private void openEditForm(SkillStudioModel.SkillDetail detail) {
        LinearLayout page = panel.page("brain_skill_edit", R.string.panel_edit, () -> openDetail(
                new SkillInventoryPlaceholder(detail).toInventory()));
        panel.note(page, detail.name());
        EditText editor = BrainUi.multilineField(panel.activity, R.string.brain_skills_instructions_hint, 10);
        editor.setText(detail.raw().isEmpty() ? detail.body() : detail.raw());
        page.addView(editor);
        LinearLayout feedback = Ui.column(panel.activity);
        page.addView(feedback);

        panel.action(page, R.string.panel_save, () -> {
            feedback.removeAllViews();
            String content;
            try {
                content = SkillStudioModel.validateContent(editor.getText().toString());
            } catch (LocalizedInputException invalid) {
                TextView err = BrainUi.liveNote(panel, feedback, invalid.resolve(panel.activity));
                err.setTextColor(panel.activity.getColor(R.color.danger));
                return;
            }
            JSONObject payload = PanelData.put(new JSONObject(), "content", content);
            panel.request(feedback, () -> panel.api.put(
                    "/v1/skills/" + HermesApi.relativePath(detail.name()), payload),
                    updated -> {
                        Diagnostics.event("skills", "skill_updated");
                        show();
                    }, error -> {
                        Diagnostics.warning("skills", "update_failed", error);
                        panel.failure(feedback, error, null);
                    });
        });
    }

    private void confirmDelete(LinearLayout feedbackTarget, SkillStudioModel.SkillDetail detail) {
        if (!detail.writable()) return;
        panel.present(new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.panel_delete)
                .setMessage(panel.t(R.string.brain_skills_delete_confirm, detail.name()))
                .setNegativeButton(R.string.panel_cancel, null)
                .setPositiveButton(R.string.panel_delete, (d, w) -> {
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
