package org.llm4people.hermes.panel;

import android.content.res.Configuration;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class SessionSettings {
    private final SessionPanel panel;
    private final String id;
    private final String remoteId;
    private final BooleanSupplier activeThread;
    private final Consumer<String> stageDraft;
    private final Supplier<Configuration> currentWindow;
    private JSONObject saved;
    private TextView saveError;
    private boolean individualTools;
    private boolean skillStudioSupported;
    private boolean capabilitiesLoaded;
    private boolean pendingOpenSkills;
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final Map<String, Set<String>> toolsets = new LinkedHashMap<>();

    private static final class Provider {
        final String slug;
        final String name;
        final java.util.List<ModelCapabilityBadges.ModelInfo> models;
        Provider(JSONObject row) {
            slug = PanelData.text(row, "slug");
            name = PanelData.text(row, "name");
            models = BrainJson.parseProviderModels(row);
        }
        ModelCapabilityBadges.ModelInfo findModel(String id) {
            if (id == null || id.isEmpty()) return null;
            for (ModelCapabilityBadges.ModelInfo info : models) {
                if (info.id().equals(id)) return info;
            }
            return null;
        }
        @Override public String toString() { return name.isEmpty() || name.equals(slug) ? slug : name + " · " + slug; }
    }

    SessionSettings(SessionPanel panel, String id, String remoteId, BooleanSupplier activeThread,
                    Consumer<String> stageDraft, Supplier<Configuration> currentWindow) {
        this.panel = panel;
        this.id = id;
        this.remoteId = remoteId;
        this.activeThread = activeThread;
        this.stageDraft = stageDraft;
        this.currentWindow = currentWindow;
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
        capabilitiesLoaded = false;
        pendingOpenSkills = false;
        panel.note(box, panel.t(R.string.panel_session_scope));
        PanelFrame.Sections settings = panel.sections(box);
        panel.section(settings.first, R.string.panel_model);
        modelSetting(settings.first);
        if (stageDraft != null) panel.action(settings.first, R.string.skills_title, () -> {
            if (!activeThread.getAsBoolean()) return;
            if (!capabilitiesLoaded) {
                pendingOpenSkills = true;
                return;
            }
            openSkills();
        });
        panel.section(settings.second, R.string.panel_tools);
        LinearLayout controls = Ui.column(panel.activity);
        settings.second.addView(controls);
        loadTools(controls);
        settings.second.addView(Ui.divider(panel.activity));
        panel.action(settings.second, R.string.panel_stats, () -> new UsagePanel(panel, id, remoteId, this::show).show());
        panel.action(settings.second, R.string.panel_reminders, panel::showReminders);
    }

    private void modelSetting(LinearLayout box) {
        String model = PanelData.text(saved, "model");
        String current = model.isEmpty() ? panel.t(R.string.panel_profile_default)
                : PanelData.text(saved, "provider") + " · " + model;
        LinearLayout modelControls = Ui.column(panel.activity);
        box.addView(modelControls);
        View choice = panel.activity.getLayoutInflater().inflate(R.layout.discussion_model_setting, modelControls, false);
        TextView value = choice.findViewById(R.id.discussion_model_value);
        value.setText(current);
        choice.setContentDescription(current + ". " + panel.t(R.string.panel_route));
        choice.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setText(panel.t(R.string.panel_route));
            }
        });
        choice.setOnClickListener(v -> pickProvider());
        modelControls.addView(choice);
        if (!model.isEmpty()) {
            panel.action(modelControls, R.string.panel_profile_default, () -> selectModel(modelControls, "", ""));
        }
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
                    Provider current = providers.get(PanelData.text(saved, "provider"));
                    picker(box, new ArrayList<>(providers.values()), current, this::pickModel);
                }, this::pickProvider);
    }

    private void pickModel(Provider provider) {
        LinearLayout box = panel.page("settings", R.string.panel_choose_model, this::pickProvider, provider.models.isEmpty());
        panel.note(box, provider.toString());
        if (provider.models.isEmpty()) { panel.note(box, panel.t(R.string.panel_no_model)); return; }
        LinearLayout feedback = Ui.column(panel.activity);
        box.addView(feedback, 0);
        ModelCapabilityBadges.ModelInfo current = provider.slug.equals(PanelData.text(saved, "provider"))
                ? provider.findModel(PanelData.text(saved, "model")) : null;
        picker(box, new ArrayList<>(provider.models), current,
                model -> selectModel(feedback, provider.slug, model.id()));
    }

    private void selectModel(View anchor, String provider, String model) {
        JSONObject options = PanelData.selection(provider, model, saved.optJSONArray("allowed_toolsets"), saved.optJSONArray("excluded_tools"));
        if (save(anchor, options)) show();
    }

    private <T> void picker(LinearLayout box, ArrayList<T> choices, T current, Consumer<T> select) {
        EditText search = new EditText(panel.activity);
        search.setSingleLine();
        search.setSaveEnabled(false);
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        search.setHint(R.string.panel_search);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setMinHeight(Ui.dp(panel.activity, 48));
        search.setTextColor(panel.activity.getColor(R.color.ink));
        search.setHintTextColor(panel.activity.getColor(R.color.muted));
        search.setBackgroundResource(R.drawable.profile_input);
        Ui.pad(search, 12, 8);
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(-1, -2);
        field.setMargins(0, Ui.dp(panel.activity, 8), 0, Ui.dp(panel.activity, 8));
        ScrollView header = attachPickerControls(box, search, field);
        TextView empty = panel.note(box, panel.t(R.string.panel_empty_search));
        ListView list = new ListView(panel.activity);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        ArrayAdapter<T> adapter = new ArrayAdapter<>(panel.activity, R.layout.discussion_setting_choice,
                new ArrayList<>(choices)) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                T item = getItem(position);
                if (item instanceof ModelCapabilityBadges.ModelInfo info) {
                    if (row instanceof TextView tv) tv.setText(info.displayText(panel.activity));
                    row.setContentDescription(info.id());
                } else {
                    row.setContentDescription(null);
                }
                return row;
            }
        };
        list.setAdapter(adapter);
        restoreChoice(list, adapter.getPosition(current));
        list.setEmptyView(empty);
        BooleanSupplier currentPage = panel.currentPage();
        list.setOnItemClickListener((p, v, i, rowId) -> {
            select.accept(adapter.getItem(i));
            // Both successful choices navigate away. A failed save leaves the picker open.
            if (currentPage.getAsBoolean()) {
                header.scrollTo(0, 0);
                int committed = adapter.getPosition(current);
                restoreChoice(list, committed);
                // Native clicks update visible radio buttons immediately; setItemChecked defers layout.
                int first = list.getFirstVisiblePosition();
                for (int child = 0; child < list.getChildCount(); child++) {
                    ((CheckedTextView) list.getChildAt(child)).setChecked(first + child == committed);
                }
            }
        });
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String needle = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                adapter.setNotifyOnChange(false);
                adapter.clear();
                for (T choice : choices) {
                    String searchable = choice instanceof ModelCapabilityBadges.ModelInfo info
                            ? info.displayText(panel.activity) : choice.toString();
                    if (searchable.toLowerCase(java.util.Locale.ROOT).contains(needle)) adapter.add(choice);
                }
                adapter.notifyDataSetChanged();
                restoreChoice(list, adapter.getPosition(current));
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    /** Keep the picker heading scrollable while reserving room for its search controls and a result. */
    ScrollView attachPickerControls(LinearLayout page, View search, LinearLayout.LayoutParams field) {
        LinearLayout context = Ui.column(panel.activity);
        while (page.getChildCount() > 0) {
            View detail = page.getChildAt(0);
            page.removeViewAt(0);
            context.addView(detail);
        }
        boolean[] splitState = {false};
        ScrollView header = new ScrollView(panel.activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (!splitState[0] && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                    int available = MeasureSpec.getSize(heightSpec);
                    search.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    int reserve = search.getMeasuredHeight() + field.topMargin + field.bottomMargin + Ui.dp(getContext(), 48);
                    int limit = Math.max(0, Math.min(available / 2, available - reserve));
                    heightSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        header.addView(context);
        Configuration window = currentWindow.get();
        boolean initialSplit = AdaptiveLayout.splitPicker(window.screenWidthDp, window.screenHeightDp);
        panel.bindPicker(page, header, search, field, splitState, initialSplit);
        return header;
    }

    private void restoreChoice(ListView list, int committed) {
        list.clearChoices();
        if (committed >= 0) list.setItemChecked(committed, true);
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
        panel.request(box, () -> {
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
            skillStudioSupported = features.optBoolean(BrainCapabilities.SKILL_STUDIO);
            capabilitiesLoaded = true;
            if (pendingOpenSkills) {
                pendingOpenSkills = false;
                if (activeThread.getAsBoolean()) {
                    openSkills();
                    return;
                }
            }
            renderTools(box, features.optBoolean("run_toolset_selection"));
            if (features.optBoolean(BrainCapabilities.PROFILE_DEFAULTS)) {
                box.addView(Ui.divider(panel.activity));
                panel.action(box, R.string.brain_profile_defaults_title,
                        () -> new ProfileDefaultsPanel(panel, this::show).show());
            }
        }, error -> {
            skillStudioSupported = false;
            capabilitiesLoaded = true;
            if (pendingOpenSkills) {
                pendingOpenSkills = false;
                if (activeThread.getAsBoolean()) {
                    openSkills();
                    return;
                }
            }
            panel.failure(box, error, () -> loadTools(box));
        });
    }

    private void openSkills() {
        if (skillStudioSupported) new SkillStudio(panel, activeThread, stageDraft, this::show).show();
        else new SkillBrowser(panel, activeThread, stageDraft, this).show();
    }

    private void renderTools(LinearLayout box, boolean editable) {
        box.removeAllViews();
        JSONArray custom = saved.optJSONArray("allowed_toolsets");
        Set<String> checked = custom == null ? new LinkedHashSet<>(toolsets.keySet()) : names(custom);
        if (editable) {
            CheckBox defaults = toggle(box, panel.t(R.string.panel_tools_defaults), custom == null, true);
            defaults.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (custom == null) toolDetail(box, panel.t(R.string.panel_tools_inherited_hint));
            defaults.setOnClickListener(v -> {
                boolean on = defaults.isChecked();
                JSONObject next = withTools(on ? null : new JSONArray(toolsets.keySet()));
                if (save(defaults, next)) renderTools(box, true);
                else defaults.setChecked(custom == null);
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
            box.addView(Ui.divider(panel.activity));
            CheckBox control = toggle(box, name, checked.contains(name), editable && custom != null);
            String members = String.join(", ", item.getValue());
            if (!members.isEmpty()) {
                if (individualTools) {
                    Button choose = Ui.button(panel.activity, panel.t(R.string.panel_choose_tools, name), v -> editMembers(name));
                    choose.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    choose.setSingleLine(false);
                    choose.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_navigate_forward, 0);
                    choose.setCompoundDrawableTintList(panel.activity.getColorStateList(R.color.accent));
                    choose.setCompoundDrawablePadding(Ui.dp(panel.activity, 8));
                    choose.setPaddingRelative(Ui.dp(panel.activity, 32), Ui.dp(panel.activity, 6),
                            Ui.dp(panel.activity, 4), Ui.dp(panel.activity, 6));
                    box.addView(choose);
                } else toolDetail(box, members);
            }
            control.setOnClickListener(v -> {
                Set<String> next = new LinkedHashSet<>(checked);
                if (control.isChecked()) next.add(name); else next.remove(name);
                if (save(control, withTools(new JSONArray(next)))) { checked.clear(); checked.addAll(next); }
                else control.setChecked(checked.contains(name));
            });
        }
    }

    private void toolDetail(LinearLayout box, String text) {
        TextView detail = panel.note(box, text);
        detail.setTextSize(14);
        detail.setPaddingRelative(Ui.dp(panel.activity, 32), 0, 0, Ui.dp(panel.activity, 8));
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
            toggle.setOnClickListener(v -> {
                Set<String> updated = new LinkedHashSet<>(excluded);
                if (toggle.isChecked()) updated.remove(name); else updated.add(name);
                JSONObject options = PanelData.selection(PanelData.text(saved, "provider"),
                        PanelData.text(saved, "model"), saved.optJSONArray("allowed_toolsets"), new JSONArray(updated));
                if (save(toggle, options)) { excluded.clear(); excluded.addAll(updated); }
                else toggle.setChecked(present && !excluded.contains(name));
            });
        }
    }

    private CheckBox toggle(LinearLayout box, String label, boolean checked, boolean enabled) {
        CheckBox view = new CheckBox(panel.activity);
        view.setMinHeight(Ui.dp(panel.activity, 48));
        view.setText(label);
        view.setTextSize(16);
        view.setSingleLine(false);
        view.setTextColor(panel.activity.getColor(enabled ? R.color.ink : R.color.muted));
        view.setButtonTintList(panel.activity.getColorStateList(R.color.discussion_tool_check));
        view.setChecked(checked);
        view.setEnabled(enabled);
        box.addView(view);
        return view;
    }

    private boolean save(View anchor, JSONObject next) {
        if (saveError != null && saveError.getParent() instanceof LinearLayout parent) parent.removeView(saveError);
        saveError = null;
        try {
            panel.saveOptions(id, next);
            saved = next;
            return true;
        } catch (IllegalStateException error) {
            LinearLayout box = anchor instanceof LinearLayout ? (LinearLayout) anchor : (LinearLayout) anchor.getParent();
            saveError = panel.failure(box, error, null);
            if (anchor != box) {
                box.removeView(saveError);
                box.addView(saveError, box.indexOfChild(anchor) + 1);
            }
            return false;
        }
    }
}
