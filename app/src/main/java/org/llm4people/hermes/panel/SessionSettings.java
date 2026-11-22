package org.llm4people.hermes.panel;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class SessionSettings {
    private final SessionPanel panel;
    private final String id;
    private final String remoteId;
    private JSONObject saved;
    private boolean individualTools;
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final Map<String, Set<String>> toolsets = new LinkedHashMap<>();

    private static final class Provider {
        final String slug;
        final String name;
        final Set<String> models;
        Provider(JSONObject row) {
            slug = PanelData.text(row, "slug");
            name = PanelData.text(row, "name");
            models = names(row.optJSONArray("models"));
        }
        @Override public String toString() { return name.isEmpty() || name.equals(slug) ? slug : name + " · " + slug; }
    }

    SessionSettings(SessionPanel panel, String id, String remoteId) {
        this.panel = panel; this.id = id; this.remoteId = remoteId;
    }

    void show() {
        LinearLayout box = panel.page("settings", R.string.panel_settings, null);
        if (id == null || id.isEmpty()) {
            panel.note(box, panel.t(R.string.panel_no_session));
            panel.action(box, R.string.panel_reminders, panel::showReminders);
            return;
        }
        try { saved = SessionPanel.runOptions(panel.activity, panel.account, id); }
        catch (IllegalStateException error) {
            panel.failure(box, error, null);
            panel.action(box, R.string.panel_reset_settings, () -> panel.present(
                    new android.app.AlertDialog.Builder(panel.activity).setTitle(R.string.panel_reset_settings)
                            .setMessage(R.string.panel_reset_confirm)
                            .setNegativeButton(R.string.panel_cancel, null)
                            .setPositiveButton(R.string.panel_reset, (d, which) -> {
                                try { panel.resetOptions(id); show(); }
                                catch (IllegalStateException failure) { panel.failure(box, failure, null); }
                            }).create()));
            return;
        }
        panel.note(box, panel.t(R.string.panel_session_scope));
        panel.section(box, R.string.panel_model);
        String model = PanelData.text(saved, "model");
        panel.note(box, model.isEmpty() ? panel.t(R.string.panel_profile_default) :
                PanelData.text(saved, "provider") + " · " + model);
        panel.action(box, R.string.panel_route, this::pickProvider);
        if (!model.isEmpty()) panel.action(box, R.string.panel_profile_default, () -> selectModel(box, "", ""));
        panel.section(box, R.string.panel_tools);
        LinearLayout controls = Ui.column(panel.activity);
        box.addView(controls);
        loadTools(controls);
        panel.action(box, R.string.panel_stats, () -> new UsagePanel(panel, id, remoteId, this::show).show());
        panel.action(box, R.string.panel_reminders, panel::showReminders);
    }

    private void pickProvider() {
        LinearLayout box = panel.page("settings", R.string.panel_choose_provider, this::show, false);
        panel.load(box, () -> panel.api.get("/api/model/options?include_unconfigured=false").getJSONArray("providers"),
                rows -> {
                    providers.clear();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        Provider provider = new Provider(row);
                        if (!provider.slug.isEmpty() && row.optBoolean("authenticated", true))
                            providers.put(provider.slug, provider);
                    }
                    picker(box, new ArrayList<>(providers.values()), this::pickModel);
                }, this::pickProvider);
    }

    private void pickModel(Provider provider) {
        LinearLayout box = panel.page("settings", R.string.panel_choose_model, this::pickProvider, false);
        panel.note(box, provider.toString());
        if (provider.models.isEmpty()) { panel.note(box, panel.t(R.string.panel_no_model)); return; }
        picker(box, new ArrayList<>(provider.models), model -> selectModel(box, provider.slug, model));
    }

    private void selectModel(LinearLayout box, String provider, String model) {
        JSONObject options = PanelData.selection(provider, model, saved.optJSONArray("allowed_toolsets"), saved.optJSONArray("excluded_tools"));
        if (save(box, options)) show();
    }

    private <T> void picker(LinearLayout box, ArrayList<T> choices, Consumer<T> select) {
        EditText search = new EditText(panel.activity);
        search.setSingleLine();
        search.setHint(R.string.panel_search);
        box.addView(search);
        TextView empty = panel.note(box, panel.t(R.string.panel_empty_search));
        ListView list = new ListView(panel.activity);
        ArrayAdapter<T> adapter = new ArrayAdapter<>(panel.activity, android.R.layout.simple_list_item_1,
                new ArrayList<>(choices));
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((p, v, i, rowId) -> select.accept(adapter.getItem(i)));
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String needle = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                adapter.setNotifyOnChange(false);
                adapter.clear();
                for (T choice : choices)
                    if (choice.toString().toLowerCase(java.util.Locale.ROOT).contains(needle)) adapter.add(choice);
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private static Set<String> names(JSONArray array) {
        Set<String> result = new LinkedHashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (item instanceof String && !((String) item).isEmpty()) result.add((String) item);
        }
        return result;
    }

    private void loadTools(LinearLayout box) {
        box.removeAllViews();
        panel.load(box, () -> {
            JSONObject capabilities = panel.api.get("/v1/capabilities").getJSONObject("features");
            return new Object[]{capabilities,
                    panel.api.get("/v1/toolsets").getJSONArray("data")};
        }, payload -> {
            toolsets.clear();
            JSONArray rows = (JSONArray) payload[1];
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || !row.optBoolean("enabled")) continue;
                String name = PanelData.text(row, "name");
                if (!name.isEmpty()) toolsets.put(name, names(row.optJSONArray("tools")));
            }
            JSONObject features = (JSONObject) payload[0];
            individualTools = features.optBoolean("run_tool_exclusions");
            renderTools(box, features.optBoolean("run_toolset_selection"));
        }, () -> loadTools(box));
    }

    private void renderTools(LinearLayout box, boolean editable) {
        box.removeAllViews();
        JSONArray custom = saved.optJSONArray("allowed_toolsets");
        Set<String> checked = custom == null ? new LinkedHashSet<>(toolsets.keySet()) : names(custom);
        if (editable) {
            CheckBox defaults = toggle(box, panel.t(R.string.panel_tools_defaults), custom == null, true);
            defaults.setOnCheckedChangeListener((v, on) -> {
                JSONObject next = withTools(on ? null : new JSONArray(toolsets.keySet()));
                if (save(box, next)) renderTools(box, true);
                else { defaults.setEnabled(false); }
            });
        } else {
            panel.note(box, panel.t(R.string.panel_tools_readonly));
            if (custom != null) panel.action(box, R.string.panel_tools_defaults, () -> {
                if (save(box, withTools(null))) renderTools(box, false);
            });
        }
        if (saved.has("excluded_tools")) {
            panel.note(box, panel.t(R.string.panel_excluded,
                    String.join(", ", names(saved.optJSONArray("excluded_tools")))));
            panel.action(box, R.string.panel_clear_excluded, () -> {
                JSONObject clean = PanelData.selection(PanelData.text(saved, "provider"), PanelData.text(saved, "model"),
                        saved.optJSONArray("allowed_toolsets"), null);
                if (save(box, clean)) renderTools(box, editable);
            });
        }
        if (toolsets.isEmpty()) panel.note(box, panel.t(R.string.panel_tools_empty));
        for (Map.Entry<String, Set<String>> item : toolsets.entrySet()) {
            String name = item.getKey();
            CheckBox control = toggle(box, name, checked.contains(name), editable && custom != null);
            String members = String.join(", ", item.getValue());
            if (!members.isEmpty()) {
                if (individualTools) box.addView(Ui.button(panel.activity, panel.t(R.string.panel_choose_tools, name),
                        v -> editMembers(name)));
                else panel.note(box, members);
            }
            control.setOnCheckedChangeListener((v, on) -> {
                Set<String> next = new LinkedHashSet<>(checked);
                if (on) next.add(name); else next.remove(name);
                if (save(box, withTools(new JSONArray(next)))) { checked.clear(); checked.addAll(next); }
                else control.setEnabled(false);
            });
        }
    }

    private JSONObject withTools(JSONArray value) {
        return PanelData.selection(PanelData.text(saved, "provider"), PanelData.text(saved, "model"), value, saved.optJSONArray("excluded_tools"));
    }

    private void editMembers(String set) {
        LinearLayout box = panel.page("settings", R.string.panel_individual_tools, this::show);
        panel.note(box, panel.t(R.string.panel_set_members, set));
        Set<String> excluded = names(saved.optJSONArray("excluded_tools"));
        Set<String> available = PanelData.enabledMembers(toolsets, saved.optJSONArray("allowed_toolsets"));
        if (!available.containsAll(toolsets.get(set))) panel.note(box, panel.t(R.string.panel_tools_unavailable));
        for (String name : toolsets.get(set)) {
            boolean present = available.contains(name);
            CheckBox toggle = toggle(box, name, present && !excluded.contains(name), present);
            toggle.setOnCheckedChangeListener((v, on) -> {
                Set<String> updated = new LinkedHashSet<>(excluded);
                if (on) updated.remove(name); else updated.add(name);
                JSONObject options = PanelData.selection(PanelData.text(saved, "provider"),
                        PanelData.text(saved, "model"), saved.optJSONArray("allowed_toolsets"), new JSONArray(updated));
                if (save(box, options)) { excluded.clear(); excluded.addAll(updated); }
                else toggle.setEnabled(false);
            });
        }
    }

    private CheckBox toggle(LinearLayout box, String label, boolean checked, boolean enabled) {
        CheckBox view = new CheckBox(panel.activity);
        view.setMinHeight(Ui.dp(panel.activity, 48));
        view.setText(label);
        view.setChecked(checked);
        view.setEnabled(enabled);
        box.addView(view);
        return view;
    }

    private boolean save(LinearLayout box, JSONObject next) {
        try {
            panel.saveOptions(id, next);
            saved = next;
            return true;
        } catch (IllegalStateException error) { panel.failure(box, error, null); return false; }
    }
}
