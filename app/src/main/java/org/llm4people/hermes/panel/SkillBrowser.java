package org.llm4people.hermes.panel;

import android.app.AlertDialog;
import android.app.Dialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The catalog belongs to the unlocked gateway. Its only write is an explicitly requested local draft. */
final class SkillBrowser {
    private final SessionPanel panel;
    private final BooleanSupplier activeThread;
    private final Consumer<String> stageDraft;
    private final Runnable parent;
    private BooleanSupplier activePage;
    private List<SkillInventory> inventory;
    private EditText search;
    private ImageButton refresh;
    private TextView empty;
    private LinearLayout status;
    private ArrayAdapter<SkillInventory> adapter;

    SkillBrowser(SessionPanel panel, BooleanSupplier activeThread, Consumer<String> stageDraft, Runnable parent) {
        this.panel = panel;
        this.activeThread = activeThread;
        this.stageDraft = stageDraft;
        this.parent = parent;
    }

    void show() {
        LinearLayout box = panel.page("settings", R.string.skills_title, () -> {
            if (current()) parent.run();
        }, false);
        activePage = panel.currentPage();
        box.setFocusableInTouchMode(true);
        box.requestFocus();
        LinearLayout toolbar = Ui.row(panel.activity);
        search = new EditText(panel.activity);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setSingleLine();
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        search.setSaveEnabled(false);
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        search.setHint(R.string.skills_search);
        search.setTextColor(panel.activity.getColor(R.color.ink));
        search.setHintTextColor(panel.activity.getColor(R.color.muted));
        search.setBackgroundResource(R.drawable.profile_input);
        search.setBackgroundTintList(null);
        search.setMinHeight(Ui.dp(panel.activity, 48));
        Ui.pad(search, 10, 6);
        toolbar.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = new ImageButton(panel.activity);
        TypedValue ripple = new TypedValue();
        panel.activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        refresh.setBackgroundResource(ripple.resourceId);
        refresh.setImageResource(R.drawable.ic_skills_refresh);
        refresh.setContentDescription(panel.t(R.string.skills_refresh));
        refresh.setTooltipText(panel.t(R.string.skills_refresh));
        refresh.setOnClickListener(v -> load());
        int target = Ui.dp(panel.activity, 48);
        toolbar.addView(refresh, new LinearLayout.LayoutParams(target, target));
        box.addView(toolbar);
        status = Ui.column(panel.activity);
        box.addView(status);
        empty = panel.note(box, "");
        empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ListView list = new ListView(panel.activity);
        adapter = new ArrayAdapter<SkillInventory>(panel.activity, R.layout.skill_list_row, new ArrayList<>()) {
            @Override public View getView(int position, View recycled, ViewGroup container) {
                View row = recycled != null ? recycled : panel.activity.getLayoutInflater()
                        .inflate(R.layout.skill_list_row, container, false);
                SkillInventory skill = getItem(position);
                ((TextView) row.findViewById(R.id.skill_name)).setText(skill.name);
                TextView summary = row.findViewById(R.id.skill_summary);
                summary.setText(skill.description);
                summary.setVisibility(skill.description.isEmpty() ? View.GONE : View.VISIBLE);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, index, rowId) -> detail(adapter.getItem(index)));
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            panel.activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        load();
    }

    private boolean current() {
        if (!activePage.getAsBoolean()) return false;
        if (activeThread.getAsBoolean()) return true;
        panel.close();
        return false;
    }

    private void load() {
        if (!current()) return;
        refresh.setEnabled(false);
        refresh.setAlpha(0.45f);
        inventory = null;
        adapter.clear();
        empty.setVisibility(View.GONE);
        status.removeAllViews();
        panel.request(status, () -> SkillInventory.parse(panel.api.get("/v1/skills")), rows -> {
            if (!current()) return;
            enableRefresh();
            inventory = rows;
            filter();
        }, error -> {
            if (!current()) return;
            enableRefresh();
            int code = error instanceof HermesApi.HttpError ? ((HermesApi.HttpError) error).status : 0;
            int message = switch (code) {
                case 404, 405, 501 -> R.string.skills_missing;
                case 401, 403 -> R.string.skills_denied;
                default -> 0;
            };
            if (message == 0) panel.failure(status, error, null);
            else panel.note(status, panel.t(message)).setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        });
    }

    private void enableRefresh() {
        refresh.setEnabled(true);
        refresh.setAlpha(1f);
    }

    private void filter() {
        if (inventory == null) return;
        List<SkillInventory> matches = SkillInventory.search(inventory, search.getText().toString());
        adapter.setNotifyOnChange(false);
        adapter.clear();
        adapter.addAll(matches);
        adapter.notifyDataSetChanged();
        empty.setText(inventory.isEmpty() ? R.string.skills_empty : R.string.skills_no_matches);
        empty.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void detail(SkillInventory skill) {
        if (skill == null || !current()) return;
        String description = skill.description.isEmpty() ? panel.t(R.string.skills_no_summary) : skill.description;
        String summary = skill.category.isEmpty() ? description
                : panel.t(R.string.skills_category, skill.category) + "\n\n" + description;
        panel.present(new AlertDialog.Builder(panel.activity).setTitle(skill.name).setMessage(summary)
                .setNegativeButton(R.string.panel_back, null)
                .setPositiveButton(R.string.skills_prepare, (dialog, which) -> {
                    if (!((Dialog) dialog).isShowing() || !current()) return;
                    panel.close();
                    stageDraft.accept(panel.t(R.string.skills_request, skill.name));
                }).create());
    }
}
