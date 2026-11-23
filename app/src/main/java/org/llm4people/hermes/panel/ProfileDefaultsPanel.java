package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native Profile Defaults Panel (/api/profile, PATCH /api/profile/defaults, optional /api/profile/soul).
 * Lets users view and update profile-wide default model/provider, default toolsets, and reasoning effort
 * without ever exposing API keys or raw environment secrets.
 */
final class ProfileDefaultsPanel {
    private final SessionPanel panel;
    private final Runnable parent;
    private LinearLayout body;
    private BrainJson.ProfileSummary summary;
    private final Map<String, List<ModelCapabilityBadges.ModelInfo>> modelsByProvider = new LinkedHashMap<>();
    private final Map<String, String> providerNames = new LinkedHashMap<>();
    private final List<String> availableToolsets = new ArrayList<>();
    private String selectedProvider = "";
    private String selectedModel = "";
    private String selectedEffort = "";
    private final Set<String> selectedToolsets = new LinkedHashSet<>();

    ProfileDefaultsPanel(SessionPanel panel, Runnable parent) {
        this.panel = panel;
        this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("brain_profile", R.string.brain_profile_defaults_title, parent);
        panel.note(page, panel.t(R.string.brain_profile_defaults_subtitle));
        body = Ui.column(panel.activity);
        page.addView(body);
        load();
    }

    private void load() {
        body.removeAllViews();
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
            selectedProvider = summary.defaultProvider();
            selectedModel = summary.defaultModel();
            selectedEffort = summary.reasoningEffort();
            selectedToolsets.clear();
            selectedToolsets.addAll(summary.enabledToolsets());
            populateCatalogs((JSONObject) payload[1], (JSONObject) payload[2]);
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

    private void populateCatalogs(JSONObject options, JSONObject toolsetsReply) {
        modelsByProvider.clear();
        providerNames.clear();
        JSONArray providers = options == null ? null : options.optJSONArray("providers");
        if (providers != null) {
            for (int i = 0; i < providers.length(); i++) {
                JSONObject row = providers.optJSONObject(i);
                if (row == null || !row.optBoolean("authenticated", true)) continue;
                String slug = PanelData.text(row, "slug");
                if (slug.isEmpty()) continue;
                String name = PanelData.text(row, "name");
                providerNames.put(slug, name.isEmpty() ? slug : name);
                modelsByProvider.put(slug, BrainJson.parseProviderModels(row));
            }
        }
        availableToolsets.clear();
        JSONArray rows = toolsetsReply == null ? null : toolsetsReply.optJSONArray("data");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String name = PanelData.text(row, "name");
                if (!name.isEmpty()) availableToolsets.add(name);
            }
        }
    }

    private void render() {
        body.removeAllViews();
        if (summary == null) return;

        // Model & provider card with friendly capability badges
        panel.section(body, R.string.brain_profile_default_model);
        LinearLayout modelCard = BrainUi.card(panel.activity);
        String currentRoute = selectedModel.isEmpty()
                ? panel.t(R.string.panel_unknown)
                : (selectedProvider.isEmpty() ? selectedModel : selectedProvider + " · " + selectedModel);
        TextView modelTitle = Ui.text(panel.activity, currentRoute, 17);
        modelTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        modelCard.addView(modelTitle);
        ModelCapabilityBadges.ModelInfo info = findModelInfo(selectedProvider, selectedModel);
        if (info != null) {
            List<String> badges = info.badges(panel.activity);
            if (!badges.isEmpty()) {
                LinearLayout badgeRow = Ui.row(panel.activity);
                for (String b : badges) badgeRow.addView(BrainUi.badge(panel.activity, b, true));
                modelCard.addView(BrainUi.chipBar(panel.activity, badgeRow));
            }
        }
        panel.action(modelCard, R.string.panel_route, this::pickProviderDialog);
        body.addView(modelCard);

        // Reasoning effort pills (everyday French)
        panel.section(body, R.string.brain_profile_reasoning);
        LinearLayout effortRow = Ui.row(panel.activity);
        effortRow.addView(effortChip("", R.string.brain_profile_reasoning_auto));
        effortRow.addView(effortChip("low", R.string.brain_profile_reasoning_low));
        effortRow.addView(effortChip("medium", R.string.brain_profile_reasoning_medium));
        effortRow.addView(effortChip("high", R.string.brain_profile_reasoning_high));
        body.addView(BrainUi.chipBar(panel.activity, effortRow));

        // Default toolsets ("Capacités actives par défaut")
        panel.section(body, R.string.brain_profile_default_tools);
        if (availableToolsets.isEmpty()) {
            panel.note(body, panel.t(R.string.panel_tools_empty));
        } else {
            for (String toolset : availableToolsets) {
                CheckBox box = new CheckBox(panel.activity);
                box.setMinHeight(Ui.dp(panel.activity, 48));
                box.setText(toolset);
                box.setTextSize(16);
                box.setTextColor(panel.activity.getColor(R.color.ink));
                box.setButtonTintList(panel.activity.getColorStateList(R.color.discussion_tool_check));
                box.setChecked(selectedToolsets.contains(toolset));
                box.setOnClickListener(v -> {
                    if (box.isChecked()) selectedToolsets.add(toolset);
                    else selectedToolsets.remove(toolset);
                });
                body.addView(box);
            }
        }

        // Save button
        LinearLayout feedback = Ui.column(panel.activity);
        body.addView(feedback);
        panel.action(body, R.string.panel_save, () -> saveDefaults(feedback));

        // Optional SOUL.md ("Personnalité de Hermes")
        body.addView(Ui.divider(panel.activity));
        panel.section(body, R.string.brain_profile_soul);
        if (!summary.soulPreview().isEmpty()) panel.note(body, summary.soulPreview());
        panel.action(body, R.string.brain_profile_soul_edit, this::editSoul);
    }

    private View effortChip(String value, int labelRes) {
        boolean active = value.equalsIgnoreCase(selectedEffort);
        return BrainUi.chip(panel.activity, panel.t(labelRes), active, () -> {
            selectedEffort = value;
            render();
        });
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

    private void pickProviderDialog() {
        List<String> slugs = new ArrayList<>(modelsByProvider.keySet());
        if (slugs.isEmpty()) return;
        String[] labels = new String[slugs.size()];
        for (int i = 0; i < slugs.size(); i++) {
            String slug = slugs.get(i);
            String name = providerNames.getOrDefault(slug, slug);
            labels[i] = name.equals(slug) ? slug : name + " · " + slug;
        }
        panel.present(new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.panel_choose_provider)
                .setNegativeButton(R.string.panel_cancel, null)
                .setItems(labels, (d, which) -> pickModelDialog(slugs.get(which)))
                .create());
    }

    private void pickModelDialog(String providerSlug) {
        List<ModelCapabilityBadges.ModelInfo> models = modelsByProvider.get(providerSlug);
        if (models == null || models.isEmpty()) {
            BrainUi.liveNote(panel, body, panel.t(R.string.panel_no_model));
            return;
        }
        String[] labels = new String[models.size()];
        for (int i = 0; i < models.size(); i++) labels[i] = models.get(i).displayText(panel.activity);
        panel.present(new AlertDialog.Builder(panel.activity)
                .setTitle(R.string.panel_choose_model)
                .setNegativeButton(R.string.panel_back, (d, w) -> pickProviderDialog())
                .setItems(labels, (d, which) -> {
                    selectedProvider = providerSlug;
                    selectedModel = models.get(which).id();
                    render();
                }).create());
    }

    private void saveDefaults(LinearLayout feedback) {
        feedback.removeAllViews();
        JSONObject payload = new JSONObject();
        if (!selectedProvider.isEmpty() && !selectedModel.isEmpty()) {
            PanelData.put(payload, "provider", selectedProvider);
            PanelData.put(payload, "model", selectedModel);
        }
        PanelData.put(payload, "enabled_toolsets", new JSONArray(selectedToolsets));
        PanelData.put(payload, "reasoning_effort", selectedEffort.isEmpty() ? JSONObject.NULL : selectedEffort);
        panel.request(feedback, () -> panel.api.patch("/api/profile/defaults", payload),
                updated -> {
                    Diagnostics.event("profile_defaults", "saved");
                    BrainUi.liveNote(panel, feedback, panel.t(R.string.brain_profile_saved));
                }, error -> {
                    Diagnostics.warning("profile_defaults", "save_failed", error);
                    panel.failure(feedback, error, () -> saveDefaults(feedback));
                });
    }

    private void editSoul() {
        LinearLayout page = panel.page("brain_profile_soul", R.string.brain_profile_soul, this::show);
        LinearLayout container = Ui.column(panel.activity);
        page.addView(container);
        panel.load(container, () -> panel.api.get("/api/profile/soul"),
                reply -> {
                    String currentText = reply.opt("content") instanceof String s ? s : "";
                    EditText editor = BrainUi.multilineField(panel.activity, R.string.brain_profile_soul_hint, 8);
                    editor.setText(currentText);
                    container.addView(editor);
                    LinearLayout feedback = Ui.column(panel.activity);
                    container.addView(feedback);
                    panel.action(container, R.string.panel_save, () -> {
                        feedback.removeAllViews();
                        String text = editor.getText().toString();
                        panel.request(feedback, () -> panel.api.put("/api/profile/soul",
                                        PanelData.put(new JSONObject(), "content", text)),
                                ok -> {
                                    Diagnostics.event("profile_defaults", "soul_saved");
                                    show();
                                }, err -> {
                                    Diagnostics.warning("profile_defaults", "soul_save_failed", err);
                                    panel.failure(feedback, err, null);
                                });
                    });
                }, this::editSoul);
    }
}
