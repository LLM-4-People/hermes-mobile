package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Material 3 Profile Defaults Panel (/api/profile, PATCH /api/profile/defaults, /api/profile/soul).
 * Lets users inspect and update profile-wide default model/provider, reasoning effort,
 * default toolsets, and assistant personality with live unsaved-change tracking and searchable pickers.
 */
final class ProfileDefaultsPanel {
    private static final String[] EFFORT_VALUES = {
            ProfileDefaultsModel.EFFORT_AUTO,
            ProfileDefaultsModel.EFFORT_LOW,
            ProfileDefaultsModel.EFFORT_MEDIUM,
            ProfileDefaultsModel.EFFORT_HIGH
    };

    private final SessionPanel panel;
    private final Runnable parent;
    private final ProfileDefaultsModel model = new ProfileDefaultsModel();
    private final Map<String, List<ModelCapabilityBadges.ModelInfo>> modelsByProvider = new LinkedHashMap<>();
    private final Map<String, String> providerNames = new LinkedHashMap<>();
    private final List<ProfileDefaultsModel.ModelOption> allModelOptions = new ArrayList<>();

    private LinearLayout body;
    private BrainJson.ProfileSummary summary;
    private String soulPreviewText = "";
    private TextView statusPill;
    private Button saveButton;
    private LinearLayout saveFeedback;
    private TextView toolsetCountPill;
    private LinearLayout toolsetListContainer;
    private String toolsetFilterQuery = "";
    private String lastToolsetEmptyAnnouncement;
    private boolean saving;

    ProfileDefaultsPanel(SessionPanel panel, Runnable parent) {
        this.panel = panel;
        this.parent = parent;
    }

    void show() {
        mountMainPage();
        load();
    }

    private void reopenMainPage() {
        mountMainPage();
        render();
    }

    private void mountMainPage() {
        Runnable exitAction = parent != null ? parent : panel::close;
        Runnable guardedBack = () -> {
            if (!model.isDirty()) {
                exitAction.run();
                return;
            }
            panel.present(new AlertDialog.Builder(panel.activity)
                    .setTitle(R.string.profile_defaults_discard_title)
                    .setMessage(R.string.profile_defaults_discard_message)
                    .setPositiveButton(R.string.profile_defaults_soul_discard_confirm, (d, w) -> exitAction.run())
                    .setNegativeButton(R.string.profile_defaults_soul_keep_editing, null)
                    .create());
        };
        LinearLayout page = panel.page("brain_profile", R.string.brain_profile_defaults_title, guardedBack);
        panel.note(page, panel.t(R.string.brain_profile_defaults_subtitle));
        body = Ui.column(panel.activity);
        page.addView(body);
    }

    private void load() {
        body.removeAllViews();
        toolsetFilterQuery = "";
        saving = false;
        panel.request(body, () -> {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            if (!caps.profileDefaults()) return null;
            BrainJson.ProfileSummary profile = BrainJson.parseProfileSummary(panel.api.get("/api/profile"));
            JSONObject options = panel.api.get("/api/model/options?include_unconfigured=false");
            JSONObject toolsetsReply = panel.api.get("/v1/toolsets");
            return new Object[]{profile, options, toolsetsReply};
        }, payload -> {
            if (payload == null) {
                Diagnostics.event("profile_defaults", "capability_absent");
                BrainUi.showUnavailable(panel, body, R.string.brain_profile_unavailable, this::load);
                return;
            }
            summary = (BrainJson.ProfileSummary) payload[0];
            soulPreviewText = summary.soulPreview();
            List<String> catalogToolsets = populateCatalogs((JSONObject) payload[1], (JSONObject) payload[2]);
            model.load(summary.defaultProvider(), summary.defaultModel(), summary.reasoningEffort(),
                    summary.enabledToolsets(), catalogToolsets);
            render();
        }, error -> {
            if (error instanceof HermesApi.HttpError http && (http.status == 404 || http.status == 405 || http.status == 501)) {
                Diagnostics.warning("profile_defaults", "endpoint_absent", "http_" + http.status);
                BrainUi.showUnavailable(panel, body, R.string.brain_profile_unavailable, this::load);
            } else {
                Diagnostics.warning("profile_defaults", "load_failed", error);
                panel.failure(body, error, this::load);
            }
        });
    }

    private List<String> populateCatalogs(JSONObject options, JSONObject toolsetsReply) {
        modelsByProvider.clear();
        providerNames.clear();
        allModelOptions.clear();
        JSONArray providers = options == null ? null : options.optJSONArray("providers");
        if (providers != null) {
            for (int i = 0; i < providers.length(); i++) {
                JSONObject row = providers.optJSONObject(i);
                if (row == null || !row.optBoolean("authenticated", true)) continue;
                String slug = PanelData.text(row, "slug");
                if (slug.isEmpty()) continue;
                String rawName = PanelData.text(row, "name");
                String displayName = rawName.isEmpty() ? slug : rawName;
                providerNames.put(slug, displayName);
                List<ModelCapabilityBadges.ModelInfo> parsed = BrainJson.parseProviderModels(row);
                modelsByProvider.put(slug, parsed);
                for (ModelCapabilityBadges.ModelInfo info : parsed) {
                    allModelOptions.add(new ProfileDefaultsModel.ModelOption(slug, displayName, info));
                }
            }
        }
        List<String> catalogToolsets = new ArrayList<>();
        JSONArray rows = toolsetsReply == null ? null : toolsetsReply.optJSONArray("data");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String name = PanelData.text(row, "name");
                if (!name.isEmpty()) catalogToolsets.add(name);
            }
        }
        return catalogToolsets;
    }

    private void render() {
        body.removeAllViews();
        if (summary == null) return;

        buildStatusAndSaveBar();
        buildModelCard();
        buildReasoningCard();
        buildToolsetsCard();
        buildPersonalityCard();
        syncSaveBar();
    }

    private void buildStatusAndSaveBar() {
        LinearLayout bar = Ui.column(panel.activity);
        LinearLayout topRow = Ui.row(panel.activity);
        statusPill = Ui.pill(panel.activity, "", R.color.accent_surface, R.color.accent);
        statusPill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        topRow.addView(statusPill);
        bar.addView(topRow);

        saveButton = Ui.filledButton(panel.activity, panel.t(R.string.profile_defaults_save_cta),
                v -> saveDefaults());
        LinearLayout.LayoutParams ctaParams = new LinearLayout.LayoutParams(-1, -2);
        ctaParams.topMargin = Ui.dp(panel.activity, 10);
        bar.addView(saveButton, ctaParams);

        saveFeedback = Ui.column(panel.activity);
        bar.addView(saveFeedback);

        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2);
        barParams.topMargin = Ui.dp(panel.activity, 6);
        barParams.bottomMargin = Ui.dp(panel.activity, 6);
        body.addView(bar, barParams);
    }

    private void syncSaveBar() {
        boolean dirty = model.isDirty();
        if (statusPill != null) {
            statusPill.setText(dirty ? R.string.profile_defaults_status_unsaved : R.string.profile_defaults_status_saved);
            statusPill.setTextColor(panel.activity.getColor(dirty ? R.color.warning : R.color.accent));
            statusPill.setBackground(Ui.shape(panel.activity, 999,
                    dirty ? R.color.warning_surface : R.color.accent_surface, 0, 0));
        }
        if (saveButton != null) {
            saveButton.setEnabled(dirty && !saving);
        }
    }

    private void buildModelCard() {
        panel.section(body, R.string.brain_profile_default_model);
        LinearLayout card = BrainUi.card(panel.activity);

        String providerSlug = model.selectedProvider();
        String providerLabel = providerSlug.isEmpty()
                ? ""
                : providerNames.getOrDefault(providerSlug, providerSlug);
        if (!providerLabel.isEmpty()) {
            TextView providerSub = Ui.text(panel.activity, providerLabel, 14);
            providerSub.setTextColor(panel.activity.getColor(R.color.muted));
            card.addView(providerSub);
        }

        String modelId = model.selectedModel();
        ModelCapabilityBadges.ModelInfo info = findModelInfo(providerSlug, modelId);
        String modelDisplay = modelId.isEmpty()
                ? panel.t(R.string.panel_unknown)
                : (info != null ? info.label() : modelId);
        TextView modelTitle = Ui.text(panel.activity, modelDisplay, 17);
        modelTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = providerLabel.isEmpty() ? 0 : Ui.dp(panel.activity, 2);
        card.addView(modelTitle, titleLp);

        if (info != null) {
            List<String> badges = info.badges(panel.activity);
            if (!badges.isEmpty()) {
                LinearLayout badgeRow = Ui.row(panel.activity);
                for (String b : badges) badgeRow.addView(BrainUi.badge(panel.activity, b, true));
                card.addView(BrainUi.chipBar(panel.activity, badgeRow));
            }
        }

        Button changeBtn = Ui.tonalButton(panel.activity, panel.t(R.string.profile_defaults_change_model),
                v -> openSearchableModelPicker());
        changeBtn.setEnabled(!allModelOptions.isEmpty());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.topMargin = Ui.dp(panel.activity, 10);
        card.addView(changeBtn, btnLp);
        body.addView(card);
    }

    private void buildReasoningCard() {
        panel.section(body, R.string.brain_profile_reasoning);
        LinearLayout card = BrainUi.card(panel.activity);

        // 2x2 wrap-safe grid keeps all 4 options legible at 1.3x font scale on compact phones.
        for (int rowIdx = 0; rowIdx < EFFORT_VALUES.length; rowIdx += 2) {
            LinearLayout chipRow = Ui.row(panel.activity);
            chipRow.setBaselineAligned(false);
            for (int col = 0; col < 2 && rowIdx + col < EFFORT_VALUES.length; col++) {
                String value = EFFORT_VALUES[rowIdx + col];
                boolean active = value.equals(model.reasoningEffort());
                Button chip = BrainUi.chip(panel.activity,
                        panel.t(ProfileDefaultsModel.effortLabelRes(value)), active, () -> {
                            model.selectEffort(value);
                            render();
                        });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (col == 0) lp.setMarginEnd(Ui.dp(panel.activity, 8));
                chipRow.addView(chip, lp);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (rowIdx > 0) rowLp.topMargin = Ui.dp(panel.activity, 8);
            card.addView(chipRow, rowLp);
        }

        TextView helper = Ui.text(panel.activity,
                panel.t(ProfileDefaultsModel.effortHelperRes(model.reasoningEffort())), 14);
        helper.setTextColor(panel.activity.getColor(R.color.muted));
        helper.setLineSpacing(0f, 1.25f);
        helper.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams helperLp = new LinearLayout.LayoutParams(-1, -2);
        helperLp.topMargin = Ui.dp(panel.activity, 12);
        card.addView(helper, helperLp);

        body.addView(card);
    }

    private void buildToolsetsCard() {
        panel.section(body, R.string.brain_profile_default_tools);
        if (model.availableToolsets().isEmpty()) {
            LinearLayout emptyCard = BrainUi.card(panel.activity);
            TextView empty = Ui.text(panel.activity, panel.t(R.string.panel_tools_empty), 15);
            empty.setTextColor(panel.activity.getColor(R.color.muted));
            emptyCard.addView(empty);
            body.addView(emptyCard);
            return;
        }

        LinearLayout card = BrainUi.card(panel.activity);

        // Header: live enabled counter pill + Enable all / Disable all quick actions
        LinearLayout headerRow = Ui.row(panel.activity);
        toolsetCountPill = Ui.pill(panel.activity, toolsetCountText(), R.color.accent_surface, R.color.accent);
        toolsetCountPill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        headerRow.addView(toolsetCountPill);
        card.addView(headerRow);

        LinearLayout bulkRow = Ui.row(panel.activity);
        Button enableAll = Ui.button(panel.activity, panel.t(R.string.profile_defaults_enable_all), v -> {
            model.enableAllToolsets();
            refreshToolsetRows();
            syncSaveBar();
        });
        Button disableAll = Ui.button(panel.activity, panel.t(R.string.profile_defaults_disable_all), v -> {
            model.disableAllToolsets();
            refreshToolsetRows();
            syncSaveBar();
        });
        LinearLayout.LayoutParams enableLp = new LinearLayout.LayoutParams(-2, -2);
        enableLp.setMarginEnd(Ui.dp(panel.activity, 8));
        bulkRow.addView(enableAll, enableLp);
        bulkRow.addView(disableAll);
        card.addView(BrainUi.chipBar(panel.activity, bulkRow));

        if (model.shouldShowToolsetFilter()) {
            EditText filterField = BrainUi.searchField(
                    panel.activity, 0, R.string.profile_defaults_toolsets_search_hint);
            filterField.setText(toolsetFilterQuery);
            filterField.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    toolsetFilterQuery = s == null ? "" : s.toString();
                    refreshToolsetRows();
                }
            });
            LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(-1, -2);
            filterLp.topMargin = Ui.dp(panel.activity, 6);
            filterLp.bottomMargin = Ui.dp(panel.activity, 6);
            card.addView(filterField, filterLp);
        }

        toolsetListContainer = Ui.column(panel.activity);
        card.addView(toolsetListContainer);
        refreshToolsetRows();

        body.addView(card);
    }

    private void refreshToolsetRows() {
        if (toolsetCountPill != null) {
            toolsetCountPill.setText(toolsetCountText());
        }
        if (toolsetListContainer == null) return;
        toolsetListContainer.removeAllViews();
        List<String> filtered = model.filterToolsets(toolsetFilterQuery);
        if (filtered.isEmpty()) {
            TextView empty = Ui.text(panel.activity, panel.t(R.string.profile_defaults_toolsets_empty_search), 14);
            empty.setTextColor(panel.activity.getColor(R.color.muted));
            String emptyKey = "toolsets_empty";
            if (!emptyKey.equals(lastToolsetEmptyAnnouncement)) {
                empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                lastToolsetEmptyAnnouncement = emptyKey;
            }
            Ui.pad(empty, 4, 10);
            toolsetListContainer.addView(empty);
            return;
        }
        lastToolsetEmptyAnnouncement = null;
        ColorStateList thumbTint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{panel.activity.getColor(R.color.accent), panel.activity.getColor(R.color.muted)});
        ColorStateList trackTint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{panel.activity.getColor(R.color.accent_surface), panel.activity.getColor(R.color.line)});

        for (int i = 0; i < filtered.size(); i++) {
            String toolset = filtered.get(i);
            if (i > 0) {
                toolsetListContainer.addView(Ui.insetDivider(panel.activity, 16));
            }
            Switch toggle = new Switch(panel.activity);
            toggle.setText(toolset);
            toggle.setTextSize(16);
            toggle.setTextColor(panel.activity.getColor(R.color.ink));
            toggle.setMinHeight(Ui.dp(panel.activity, 48));
            toggle.setMinimumHeight(Ui.dp(panel.activity, 48));
            toggle.setBackground(Ui.roundedRipple(panel.activity, 12, null));
            Ui.pad(toggle, 4, 10);
            toggle.setThumbTintList(thumbTint);
            toggle.setTrackTintList(trackTint);
            toggle.setChecked(model.isToolsetEnabled(toolset));
            toggle.setOnCheckedChangeListener((btn, checked) -> {
                model.setToolsetEnabled(toolset, checked);
                if (toolsetCountPill != null) toolsetCountPill.setText(toolsetCountText());
                syncSaveBar();
            });
            toolsetListContainer.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private String toolsetCountText() {
        return panel.t(R.string.profile_defaults_toolsets_count,
                model.enabledToolsetCount(), model.totalToolsetCount());
    }

    private void buildPersonalityCard() {
        panel.section(body, R.string.brain_profile_soul);
        LinearLayout card = BrainUi.card(panel.activity);

        String rawPreview = soulPreviewText == null ? "" : soulPreviewText;
        int charCount = ProfileDefaultsModel.soulCharCount(rawPreview);
        LinearLayout topRow = Ui.row(panel.activity);
        TextView countPill = charCount > 0
                ? Ui.pill(panel.activity, panel.t(R.string.profile_defaults_soul_chars, charCount),
                        R.color.accent_surface, R.color.accent)
                : Ui.pill(panel.activity, panel.t(R.string.profile_defaults_soul_empty),
                        R.color.surface_container, R.color.muted);
        topRow.addView(countPill);
        card.addView(topRow);

        String displayPreview = charCount > 0
                ? ProfileDefaultsModel.soulPreview(rawPreview)
                : panel.t(R.string.profile_defaults_soul_empty_hint);
        TextView preview = Ui.text(panel.activity, displayPreview, 14);
        preview.setTextColor(panel.activity.getColor(R.color.muted));
        preview.setLineSpacing(0f, 1.25f);
        preview.setMaxLines(4);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, -2);
        previewLp.topMargin = Ui.dp(panel.activity, 8);
        card.addView(preview, previewLp);

        Button editBtn = Ui.outlinedButton(panel.activity, panel.t(R.string.brain_profile_soul_edit),
                v -> editSoul());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.topMargin = Ui.dp(panel.activity, 12);
        card.addView(editBtn, btnLp);

        body.addView(card);
    }

    private ModelCapabilityBadges.ModelInfo findModelInfo(String provider, String modelId) {
        if (modelId == null || modelId.isEmpty()) return null;
        List<ModelCapabilityBadges.ModelInfo> list = modelsByProvider.get(provider);
        if (list != null) {
            for (ModelCapabilityBadges.ModelInfo item : list) {
                if (item.id().equals(modelId)) return item;
            }
        }
        return null;
    }

    private void openSearchableModelPicker() {
        if (allModelOptions.isEmpty()) {
            BrainUi.liveNote(panel, body, panel.t(R.string.panel_no_model));
            return;
        }
        LinearLayout root = Ui.column(panel.activity);
        root.setBackgroundColor(panel.activity.getColor(R.color.paper));
        Ui.pad(root, 16, 12);

        EditText search = BrainUi.searchField(
                panel.activity, 0, R.string.profile_defaults_model_search_hint);
        root.addView(search, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout providerRow = Ui.row(panel.activity);
        if (providerNames.size() > 1) {
            root.addView(BrainUi.chipBar(panel.activity, providerRow));
        }

        ScrollView scroll = new ScrollView(panel.activity);
        scroll.setFillViewport(true);
        LinearLayout resultsBox = Ui.column(panel.activity);
        scroll.addView(resultsBox, new ScrollView.LayoutParams(-1, -2));
        int screenHeightPx = panel.activity.getResources().getDisplayMetrics().heightPixels;
        int pickerHeight = Math.max(Ui.dp(panel.activity, 180),
                Math.min(Ui.dp(panel.activity, 380), Math.round(screenHeightPx * 0.45f)));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, pickerHeight);
        scrollLp.topMargin = Ui.dp(panel.activity, 6);
        root.addView(scroll, scrollLp);

        final String[] activeProvider = {""};
        final String[] activeQuery = {""};
        final String[] lastModelEmptyAnnouncement = {null};
        final AlertDialog[] dialogRef = {null};

        Runnable[] rebuild = {null};
        Runnable rebuildChips = () -> {
            providerRow.removeAllViews();
            providerRow.addView(BrainUi.chip(panel.activity,
                    panel.t(R.string.profile_defaults_provider_all),
                    activeProvider[0].isEmpty(), () -> {
                        activeProvider[0] = "";
                        rebuild[0].run();
                    }));
            for (Map.Entry<String, String> entry : providerNames.entrySet()) {
                String slug = entry.getKey();
                providerRow.addView(BrainUi.chip(panel.activity, entry.getValue(),
                        slug.equals(activeProvider[0]), () -> {
                            activeProvider[0] = slug;
                            rebuild[0].run();
                        }));
            }
        };

        rebuild[0] = () -> {
            rebuildChips.run();
            resultsBox.removeAllViews();
            List<ProfileDefaultsModel.ModelOption> matches = ProfileDefaultsModel.filterModels(
                    allModelOptions, activeProvider[0], activeQuery[0]);
            if (matches.isEmpty()) {
                TextView empty = Ui.text(panel.activity, panel.t(R.string.profile_defaults_model_search_empty), 15);
                empty.setTextColor(panel.activity.getColor(R.color.muted));
                String emptyKey = "models_empty";
                if (!emptyKey.equals(lastModelEmptyAnnouncement[0])) {
                    empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                    lastModelEmptyAnnouncement[0] = emptyKey;
                }
                Ui.pad(empty, 8, 16);
                resultsBox.addView(empty);
                return;
            }
            lastModelEmptyAnnouncement[0] = null;
            LinearLayout card = Ui.column(panel.activity);
            card.setBackground(Ui.shape(panel.activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.line, 1));
            card.setClipToOutline(true);
            for (int i = 0; i < matches.size(); i++) {
                ProfileDefaultsModel.ModelOption opt = matches.get(i);
                if (i > 0) card.addView(Ui.insetDivider(panel.activity, 16));
                card.addView(buildPickerModelRow(opt, () -> {
                    model.selectModel(opt.providerSlug(), opt.info().id());
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                    render();
                }));
            }
            resultsBox.addView(card, new LinearLayout.LayoutParams(-1, -2));
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                activeQuery[0] = s == null ? "" : s.toString();
                rebuild[0].run();
            }
        });

        rebuild[0].run();
        AlertDialog dialog = new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.panel_choose_model)
                .setView(root)
                .setNegativeButton(R.string.panel_cancel, null)
                .create();
        dialogRef[0] = dialog;
        panel.present(dialog);
    }

    private View buildPickerModelRow(ProfileDefaultsModel.ModelOption opt, Runnable onSelect) {
        LinearLayout row = Ui.row(panel.activity);
        row.setMinimumHeight(Ui.dp(panel.activity, 56));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(Ui.dp(panel.activity, 14), Ui.dp(panel.activity, 10),
                Ui.dp(panel.activity, 14), Ui.dp(panel.activity, 10));
        row.setBackground(Ui.roundedRipple(panel.activity, 0, null));
        row.setFocusable(true);
        row.setClickable(true);
        Ui.setButtonRole(row);
        row.setOnClickListener(v -> onSelect.run());

        LinearLayout copy = Ui.column(panel.activity);
        copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name = Ui.text(panel.activity, opt.info().label(), 16);
        name.setTypeface(Ui.mediumTypeface());
        copy.addView(name);

        String capabilities = opt.info().summaryLine(panel.activity);
        String detailText = capabilities.isEmpty()
                ? opt.providerName()
                : opt.providerName() + " · " + capabilities;
        TextView detail = Ui.text(panel.activity, detailText, 13);
        detail.setTextColor(panel.activity.getColor(R.color.muted));
        detail.setPaddingRelative(0, Ui.dp(panel.activity, 2), 0, 0);
        copy.addView(detail);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        boolean isCurrent = opt.providerSlug().equals(model.selectedProvider())
                && opt.info().id().equals(model.selectedModel());
        String rowDescription = opt.info().label() + ", " + detailText;
        if (isCurrent) {
            String activeLabel = panel.t(R.string.profile_defaults_selected_badge);
            rowDescription = rowDescription + ", " + activeLabel;
            TextView activePill = Ui.pill(panel.activity,
                    activeLabel,
                    R.color.accent_surface, R.color.accent);
            activePill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
            pillLp.setMarginStart(Ui.dp(panel.activity, 8));
            row.addView(activePill, pillLp);
        }
        row.setContentDescription(rowDescription);
        return row;
    }

    private void saveDefaults() {
        if (saving || !model.isDirty()) return;
        saving = true;
        syncSaveBar();
        saveFeedback.removeAllViews();

        ProfileDefaultsModel.Snapshot submitted = model.currentSnapshot();
        JSONObject payload = new JSONObject();
        if (!submitted.provider().isEmpty() && !submitted.model().isEmpty()) {
            PanelData.put(payload, "provider", submitted.provider());
            PanelData.put(payload, "model", submitted.model());
        }
        PanelData.put(payload, "enabled_toolsets", new JSONArray(submitted.enabledToolsets()));
        PanelData.put(payload, "reasoning_effort",
                submitted.reasoningEffort().isEmpty() ? JSONObject.NULL : submitted.reasoningEffort());

        panel.request(saveFeedback, () -> panel.api.patch("/api/profile/defaults", payload),
                updated -> {
                    saving = false;
                    model.markSaved(submitted);
                    syncSaveBar();
                    Diagnostics.event("profile_defaults", "saved");
                    BrainUi.liveNote(panel, saveFeedback, panel.t(R.string.brain_profile_saved));
                }, error -> {
                    saving = false;
                    syncSaveBar();
                    Diagnostics.warning("profile_defaults", "save_failed", error);
                    panel.failure(saveFeedback, error, this::saveDefaults);
                });
    }

    private void editSoul() {
        final String[] initialRef = {""};
        final EditText[] editorRef = {null};
        Runnable guardedBack = () -> {
            String current = editorRef[0] == null ? "" : editorRef[0].getText().toString();
            if (!ProfileDefaultsModel.isSoulDirty(initialRef[0], current)) {
                reopenMainPage();
                return;
            }
            panel.present(new AlertDialog.Builder(panel.activity)
                    .setTitle(R.string.profile_defaults_soul_discard_title)
                    .setMessage(R.string.profile_defaults_soul_discard_message)
                    .setPositiveButton(R.string.profile_defaults_soul_discard_confirm, (d, w) -> reopenMainPage())
                    .setNegativeButton(R.string.profile_defaults_soul_keep_editing, null)
                    .create());
        };

        LinearLayout page = panel.page("brain_profile_soul", R.string.brain_profile_soul, guardedBack);
        LinearLayout container = Ui.column(panel.activity);
        page.addView(container);

        panel.load(container, () -> panel.api.get("/api/profile/soul"),
                reply -> {
                    String currentText = reply.opt("content") instanceof String s ? s : "";
                    initialRef[0] = currentText;

                    LinearLayout metaRow = Ui.row(panel.activity);
                    TextView counterPill = Ui.pill(panel.activity, soulCountLabel(currentText),
                            R.color.accent_surface, R.color.accent);
                    counterPill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                    metaRow.addView(counterPill);
                    LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
                    metaLp.bottomMargin = Ui.dp(panel.activity, 10);
                    container.addView(metaRow, metaLp);

                    EditText editor = BrainUi.multilineField(panel.activity, R.string.brain_profile_soul_hint, 8);
                    editor.setText(currentText);
                    editorRef[0] = editor;
                    container.addView(editor, new LinearLayout.LayoutParams(-1, -2));

                    LinearLayout feedback = Ui.column(panel.activity);
                    container.addView(feedback);

                    LinearLayout actions = Ui.row(panel.activity);
                    final boolean[] soulSaving = {false};
                    final Button[] saveBtnRef = {null};
                    Button saveSoulBtn = Ui.filledButton(panel.activity, panel.t(R.string.panel_save), v -> {
                        if (soulSaving[0]) return;
                        String text = editor.getText().toString();
                        if (!ProfileDefaultsModel.isSoulDirty(initialRef[0], text)) return;
                        soulSaving[0] = true;
                        if (saveBtnRef[0] != null) saveBtnRef[0].setEnabled(false);
                        feedback.removeAllViews();
                        panel.request(feedback, () -> panel.api.put("/api/profile/soul",
                                        PanelData.put(new JSONObject(), "content", text)),
                                ok -> {
                                    soulSaving[0] = false;
                                    initialRef[0] = text;
                                    soulPreviewText = text;
                                    Diagnostics.event("profile_defaults", "soul_saved");
                                    reopenMainPage();
                                }, err -> {
                                    soulSaving[0] = false;
                                    if (saveBtnRef[0] != null) {
                                        saveBtnRef[0].setEnabled(
                                                ProfileDefaultsModel.isSoulDirty(initialRef[0], editor.getText().toString()));
                                    }
                                    Diagnostics.warning("profile_defaults", "soul_save_failed", err);
                                    panel.failure(feedback, err, null);
                                });
                    });
                    saveBtnRef[0] = saveSoulBtn;
                    saveSoulBtn.setEnabled(false);

                    Button cancelSoulBtn = Ui.outlinedButton(panel.activity, panel.t(R.string.panel_cancel),
                            v -> guardedBack.run());
                    LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, -2, 1f);
                    saveLp.setMarginEnd(Ui.dp(panel.activity, 8));
                    actions.addView(saveSoulBtn, saveLp);
                    actions.addView(cancelSoulBtn, new LinearLayout.LayoutParams(-2, -2));

                    LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
                    actionsLp.topMargin = Ui.dp(panel.activity, 14);
                    container.addView(actions, actionsLp);

                    editor.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override public void afterTextChanged(Editable s) {}
                        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                            String next = s == null ? "" : s.toString();
                            counterPill.setText(soulCountLabel(next));
                            saveSoulBtn.setEnabled(!soulSaving[0]
                                    && ProfileDefaultsModel.isSoulDirty(initialRef[0], next));
                        }
                    });
                }, this::editSoul);
    }

    private String soulCountLabel(String text) {
        int count = ProfileDefaultsModel.soulCharCount(text);
        return count > 0
                ? panel.t(R.string.profile_defaults_soul_chars, count)
                : panel.t(R.string.profile_defaults_soul_empty);
    }
}
