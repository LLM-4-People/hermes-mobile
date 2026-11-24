package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.JsonReader;
import android.util.JsonToken;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import java.text.NumberFormat;
import org.llm4people.hermes.AppLanguage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import static org.llm4people.hermes.artifact.WorkspaceLayout.choice;
import static org.llm4people.hermes.artifact.WorkspaceLayout.invalid;
import static org.llm4people.hermes.artifact.WorkspaceLayout.label;
import static org.llm4people.hermes.artifact.WorkspaceLayout.require;

/**
 * Interactive Toggle Switches, Checkboxes, Sliders, and Filter Chips backed by encrypted
 * profile-scoped InstrumentStateStore so Hermes-built trackers and checklists persist across restarts.
 */
public final class InteractiveControlsBlock {
    public static final int MAX_CONTROLS = 12;
    public static final List<String> KINDS = Collections.unmodifiableList(
            Arrays.asList("toggle", "checkbox", "slider", "chips"));

    public static final class Control {
        public final String id, label, kind, description, unit;
        public final boolean defaultChecked;
        public final double min, max, step, defaultNumber;
        public final List<String> options;
        public final boolean multi;
        public final List<String> defaultSelected;

        public Control(String id, String label, String kind, String description, String unit,
                       boolean defaultChecked, double min, double max, double step, double defaultNumber,
                       List<String> options, boolean multi, List<String> defaultSelected) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.description = description == null ? "" : description;
            this.unit = unit == null ? "" : unit;
            this.defaultChecked = defaultChecked;
            this.min = min;
            this.max = max;
            this.step = step;
            this.defaultNumber = defaultNumber;
            this.options = options == null ? Collections.emptyList() : Collections.unmodifiableList(options);
            this.multi = multi;
            this.defaultSelected = defaultSelected == null ? Collections.emptyList() : Collections.unmodifiableList(defaultSelected);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Control other)) return false;
            return defaultChecked == other.defaultChecked && multi == other.multi
                    && Double.compare(min, other.min) == 0 && Double.compare(max, other.max) == 0
                    && Double.compare(step, other.step) == 0 && Double.compare(defaultNumber, other.defaultNumber) == 0
                    && id.equals(other.id) && label.equals(other.label) && kind.equals(other.kind)
                    && description.equals(other.description) && unit.equals(other.unit)
                    && options.equals(other.options) && defaultSelected.equals(other.defaultSelected);
        }

        @Override public int hashCode() {
            return Objects.hash(id, label, kind, description, unit, defaultChecked, min, max, step, defaultNumber, options, multi, defaultSelected);
        }
    }

    private InteractiveControlsBlock() {}

    static List<Control> readControls(JsonReader in) throws IOException {
        List<Control> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        in.beginArray();
        while (in.hasNext()) {
            require(items.size() < MAX_CONTROLS);
            Control item = readControl(in);
            require(ids.add(item.id));
            items.add(item);
        }
        in.endArray();
        require(!items.isEmpty());
        return Collections.unmodifiableList(items);
    }

    private static Control readControl(JsonReader in) throws IOException {
        String id = null, ctrlLabel = null, kind = null, description = "", unit = "";
        boolean defaultChecked = false, multi = false;
        double min = 0, max = 100, step = 1, defaultNumber = Double.NaN;
        boolean hasMin = false, hasMax = false, hasStep = false, hasDefaultBool = false, hasDefaultNum = false;
        List<String> options = Collections.emptyList();
        List<String> defaultSelected = Collections.emptyList();
        Set<String> keys = new HashSet<>();
        in.beginObject();
        while (in.hasNext()) {
            String key = in.nextName();
            require(keys.add(key));
            switch (key) {
                case "id" -> { id = label(in, 36); require(DraftForm.validId(id)); }
                case "label" -> ctrlLabel = label(in, 64);
                case "kind" -> kind = choice(in, KINDS);
                case "description" -> description = label(in, 160);
                case "unit" -> unit = label(in, 16);
                case "min" -> { min = SparklineView.readFiniteDouble(in); hasMin = true; }
                case "max" -> { max = SparklineView.readFiniteDouble(in); hasMax = true; }
                case "step" -> { step = SparklineView.readFiniteDouble(in); hasStep = true; }
                case "multi" -> { require(in.peek() == JsonToken.BOOLEAN); multi = in.nextBoolean(); }
                case "options" -> options = WorkspaceParts.strings(in, null, 12);
                case "default" -> {
                    JsonToken token = in.peek();
                    if (token == JsonToken.BOOLEAN) {
                        defaultChecked = in.nextBoolean();
                        hasDefaultBool = true;
                    } else if (token == JsonToken.NUMBER) {
                        defaultNumber = SparklineView.readFiniteDouble(in);
                        hasDefaultNum = true;
                    } else if (token == JsonToken.STRING) {
                        defaultSelected = Collections.singletonList(label(in, 64));
                    } else if (token == JsonToken.BEGIN_ARRAY) {
                        defaultSelected = WorkspaceParts.strings(in, null, 12);
                    } else {
                        throw invalid();
                    }
                }
                default -> throw invalid();
            }
        }
        in.endObject();
        require(id != null && ctrlLabel != null && kind != null);
        switch (kind) {
            case "toggle", "checkbox" -> {
                require(!hasMin && !hasMax && !hasStep && options.isEmpty() && !multi && !hasDefaultNum && defaultSelected.isEmpty());
                if (!hasDefaultBool) defaultChecked = false;
            }
            case "slider" -> {
                require(hasMin && hasMax && max > min && options.isEmpty() && !multi && !hasDefaultBool && defaultSelected.isEmpty());
                if (!hasStep) step = Math.max(1.0, (max - min) / 20.0);
                require(step > 0 && step <= (max - min));
                if (!hasDefaultNum) defaultNumber = min;
                require(defaultNumber >= min && defaultNumber <= max);
            }
            case "chips" -> {
                require(!options.isEmpty() && !hasMin && !hasMax && !hasStep && !hasDefaultBool && !hasDefaultNum);
                for (String sel : defaultSelected) require(options.contains(sel));
                if (!multi) require(defaultSelected.size() <= 1);
            }
            default -> throw invalid();
        }
        return new Control(id, ctrlLabel, kind, description, unit, defaultChecked, min, max, step,
                Double.isNaN(defaultNumber) ? min : defaultNumber, options, multi, defaultSelected);
    }

    static String composeSummary(Context context, String prompt, List<Control> controls, Map<String, Object> state) {
        Locale locale = context == null ? Locale.getDefault() : AppLanguage.resolvedLocale(context);
        NumberFormat fmt = NumberFormat.getNumberInstance(locale);
        fmt.setMaximumFractionDigits(2);
        StringBuilder out = new StringBuilder(prompt == null ? "" : prompt);
        for (Control ctrl : controls) {
            Object val = state.get(ctrl.id);
            String rendered;
            switch (ctrl.kind) {
                case "toggle", "checkbox" -> {
                    boolean checked = val instanceof Boolean b ? b : ctrl.defaultChecked;
                    rendered = context.getString(checked
                            ? R.string.ws_yes
                            : R.string.ws_no);
                }
                case "slider" -> {
                    double num = val instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : ctrl.defaultNumber;
                    rendered = fmt.format(num) + (ctrl.unit.isEmpty() ? "" : " " + ctrl.unit);
                }
                case "chips" -> {
                    List<String> selected = asStringList(val, ctrl.defaultSelected);
                    rendered = selected.isEmpty()
                            ? context.getString(R.string.ws_none)
                            : String.join(", ", selected);
                }
                default -> rendered = "";
            }
            out.append("\n\n").append(ctrl.label).append("\n").append(rendered);
        }
        return out.toString();
    }

    private static List<String> asStringList(Object val, List<String> fallback) {
        if (val instanceof List<?> raw) {
            List<String> out = new ArrayList<>();
            for (Object o : raw) if (o instanceof String s) out.add(s);
            return out;
        }
        if (val instanceof String s && !s.isEmpty()) return Collections.singletonList(s);
        return fallback;
    }

    static void mount(LinearLayout group, WorkspaceLayout layout, WorkspaceLayout.Block block,
                      ThemePalette palette, InstrumentStateStore store, String scope,
                      Activity activity, ArtifactDesk.Host host) {
        Context c = group.getContext();
        ThemePalette colors = palette == null
                ? ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(c))
                : palette;
        if (!block.note.isEmpty()) {
            TextView note = Ui.text(c, block.note, colors.scaledSp(15));
            note.setTextColor(colors.muted);
            note.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 6));
            group.addView(note);
        }
        String surfaceKey = block.stateKey != null && !block.stateKey.isEmpty() ? block.stateKey : layout.surface;
        String effectiveScope = scope == null || scope.isEmpty() ? "atelier" : scope;
        Map<String, Object> state = new LinkedHashMap<>();
        if (store != null) state.putAll(store.load(effectiveScope, surfaceKey));
        boolean interactive = host != null;

        for (Control ctrl : block.controls) {
            switch (ctrl.kind) {
                case "toggle" -> addToggle(group, ctrl, state, store, effectiveScope, surfaceKey, colors, interactive);
                case "checkbox" -> addCheckbox(group, ctrl, state, store, effectiveScope, surfaceKey, colors, interactive);
                case "slider" -> addSlider(group, ctrl, state, store, effectiveScope, surfaceKey, colors, interactive);
                case "chips" -> addChips(group, ctrl, state, store, effectiveScope, surfaceKey, colors, interactive);
                default -> {}
            }
        }

        if (interactive && block.prompt != null && !block.prompt.isEmpty()) {
            Button prepare = Ui.button(c, c.getString(R.string.ws_form_prepare_draft), v -> {
                String draft = composeSummary(c, block.prompt, block.controls, state);
                WorkspaceForms.reviewDraft(activity, host, draft);
            });
            prepare.setSingleLine(false);
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(colors.accentSurface);
            fill.setCornerRadius(Ui.dp(c, Ui.BUTTON_CORNER_DP));
            prepare.setBackground(Ui.roundedRipple(c, Ui.BUTTON_CORNER_DP, fill));
            prepare.setTextColor(statefulColor(colors.accent, colors.muted));
            Ui.pad(prepare, 16, 8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(c, 10);
            group.addView(prepare, lp);
        }
    }

    private static ColorStateList statefulColor(int enabledColor, int disabledColor) {
        return new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{disabledColor, enabledColor});
    }

    private static void addToggle(LinearLayout group, Control ctrl, Map<String, Object> state,
                                  InstrumentStateStore store, String scope, String surfaceKey,
                                  ThemePalette colors, boolean interactive) {
        Context c = group.getContext();
        boolean checked = state.get(ctrl.id) instanceof Boolean b ? b : ctrl.defaultChecked;
        state.put(ctrl.id, checked);
        Switch sw = new Switch(c);
        sw.setText(ctrl.label);
        sw.setTextSize(colors.scaledSp(16));
        sw.setTextColor(statefulColor(colors.ink, colors.muted));
        sw.setMinHeight(Ui.dp(c, 48));
        sw.setChecked(checked);
        sw.setEnabled(interactive);
        sw.setSaveEnabled(false);
        if (interactive) {
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                state.put(ctrl.id, isChecked);
                persistBoolean(store, scope, surfaceKey, ctrl.id, isChecked);
            });
        }
        group.addView(sw);
        if (!ctrl.description.isEmpty()) addHint(group, ctrl.description, colors);
    }

    private static void addCheckbox(LinearLayout group, Control ctrl, Map<String, Object> state,
                                    InstrumentStateStore store, String scope, String surfaceKey,
                                    ThemePalette colors, boolean interactive) {
        Context c = group.getContext();
        boolean checked = state.get(ctrl.id) instanceof Boolean b ? b : ctrl.defaultChecked;
        state.put(ctrl.id, checked);
        CheckBox box = new CheckBox(c);
        box.setText(ctrl.label);
        box.setTextSize(colors.scaledSp(16));
        box.setTextColor(statefulColor(colors.ink, colors.muted));
        box.setMinHeight(Ui.dp(c, 48));
        box.setChecked(checked);
        box.setEnabled(interactive);
        box.setSaveEnabled(false);
        if (interactive) {
            box.setOnCheckedChangeListener((buttonView, isChecked) -> {
                state.put(ctrl.id, isChecked);
                persistBoolean(store, scope, surfaceKey, ctrl.id, isChecked);
            });
        }
        group.addView(box);
        if (!ctrl.description.isEmpty()) addHint(group, ctrl.description, colors);
    }

    private static void addSlider(LinearLayout group, Control ctrl, Map<String, Object> state,
                                  InstrumentStateStore store, String scope, String surfaceKey,
                                  ThemePalette colors, boolean interactive) {
        Context c = group.getContext();
        double current = state.get(ctrl.id) instanceof Number n && Double.isFinite(n.doubleValue())
                ? Math.max(ctrl.min, Math.min(ctrl.max, n.doubleValue())) : ctrl.defaultNumber;
        state.put(ctrl.id, current);
        int steps = Math.max(1, (int) Math.round((ctrl.max - ctrl.min) / ctrl.step));
        int initialProgress = Math.max(0, Math.min(steps, (int) Math.round((current - ctrl.min) / ctrl.step)));

        LinearLayout header = Ui.row(c);
        header.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 2));
        TextView labelView = Ui.text(c, ctrl.label, colors.scaledSp(15));
        labelView.setTypeface(Ui.mediumTypeface());
        labelView.setTextColor(colors.ink);
        TextView valueView = Ui.text(c, formatSliderValue(c, current, ctrl.unit), colors.scaledSp(15));
        valueView.setTypeface(Typeface.MONOSPACE);
        valueView.setTextColor(colors.accent);
        header.addView(labelView, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(valueView);
        group.addView(header);

        SeekBar seek = new SeekBar(c);
        int touchMin = Ui.dp(c, 48);
        seek.setMinimumHeight(touchMin);
        seek.setPaddingRelative(seek.getPaddingStart(), Ui.dp(c, 12), seek.getPaddingEnd(), Ui.dp(c, 12));
        seek.setMax(steps);
        seek.setProgress(initialProgress);
        seek.setEnabled(interactive);
        seek.setSaveEnabled(false);
        seek.setContentDescription(ctrl.label + ", " + formatSliderValue(c, current, ctrl.unit));
        if (interactive) {
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                private boolean dragging;

                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    double value = Math.min(ctrl.max, ctrl.min + progress * ctrl.step);
                    String formatted = formatSliderValue(c, value, ctrl.unit);
                    valueView.setText(formatted);
                    bar.setContentDescription(ctrl.label + ", " + formatted);
                    state.put(ctrl.id, value);
                    if (fromUser && !dragging) {
                        persistNumber(store, scope, surfaceKey, ctrl.id, value);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {
                    dragging = true;
                }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    double value = Math.min(ctrl.max, ctrl.min + bar.getProgress() * ctrl.step);
                    state.put(ctrl.id, value);
                    persistNumber(store, scope, surfaceKey, ctrl.id, value);
                }
            });
        }
        group.addView(seek, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, touchMin));
        if (!ctrl.description.isEmpty()) addHint(group, ctrl.description, colors);
    }

    private static void addChips(LinearLayout group, Control ctrl, Map<String, Object> state,
                                 InstrumentStateStore store, String scope, String surfaceKey,
                                 ThemePalette colors, boolean interactive) {
        Context c = group.getContext();
        List<String> selected = new ArrayList<>(asStringList(state.get(ctrl.id), ctrl.defaultSelected));
        selected.retainAll(ctrl.options);
        if (!ctrl.multi && selected.size() > 1) selected = new ArrayList<>(selected.subList(0, 1));
        state.put(ctrl.id, Collections.unmodifiableList(new ArrayList<>(selected)));

        TextView labelView = Ui.text(c, ctrl.label, colors.scaledSp(15));
        labelView.setTypeface(Ui.mediumTypeface());
        labelView.setTextColor(colors.ink);
        labelView.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
        group.addView(labelView);

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(c);
        List<Button> chipButtons = new ArrayList<>();
        List<String> mutableSelected = new ArrayList<>(selected);
        Runnable restyle = () -> {
            int radiusPx = Ui.dp(c, Ui.BUTTON_CORNER_DP);
            int strokePx = Math.max(1, Ui.dp(c, 1));
            for (int i = 0; i < ctrl.options.size(); i++) {
                String option = ctrl.options.get(i);
                boolean active = mutableSelected.contains(option);
                Button b = chipButtons.get(i);
                GradientDrawable enabledBg = new GradientDrawable();
                enabledBg.setCornerRadius(radiusPx);
                if (active) {
                    enabledBg.setColor(colors.accent);
                    b.setTextColor(statefulColor(colors.onAccent, colors.muted));
                } else {
                    enabledBg.setColor(colors.surface);
                    enabledBg.setStroke(strokePx, colors.line);
                    b.setTextColor(statefulColor(colors.ink, colors.muted));
                }
                GradientDrawable disabledBg = new GradientDrawable();
                disabledBg.setCornerRadius(radiusPx);
                disabledBg.setColor(colors.surface);
                disabledBg.setStroke(strokePx, colors.line);
                StateListDrawable states = new StateListDrawable();
                states.addState(new int[]{-android.R.attr.state_enabled}, disabledBg);
                states.addState(new int[]{}, enabledBg);
                b.setBackground(Ui.roundedRipple(c, Ui.BUTTON_CORNER_DP, states));
                b.setSelected(active);
                b.setContentDescription(c.getString(active
                        ? R.string.ws_ctrl_chip_selected
                        : R.string.ws_ctrl_chip_unselected, option));
            }
        };

        for (String option : ctrl.options) {
            Button chip = Ui.button(c, option, v -> {
                if (!interactive) return;
                if (ctrl.multi) {
                    if (mutableSelected.contains(option)) mutableSelected.remove(option);
                    else mutableSelected.add(option);
                } else {
                    boolean already = mutableSelected.contains(option);
                    mutableSelected.clear();
                    if (!already) mutableSelected.add(option);
                }
                List<String> snapshot = Collections.unmodifiableList(new ArrayList<>(mutableSelected));
                state.put(ctrl.id, snapshot);
                restyle.run();
                persistStringList(store, scope, surfaceKey, ctrl.id, snapshot);
            });
            chip.setEnabled(interactive);
            chip.setTextSize(colors.scaledSp(14));
            int touchMin = Ui.dp(c, 48);
            chip.setMinHeight(touchMin);
            chip.setMinimumHeight(touchMin);
            chip.setGravity(Gravity.CENTER);
            Ui.pad(chip, 12, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(Ui.dp(c, 8));
            chipButtons.add(chip);
            row.addView(chip, lp);
        }
        restyle.run();
        scroll.addView(row);
        group.addView(scroll);
        if (!ctrl.description.isEmpty()) addHint(group, ctrl.description, colors);
    }

    private static void addHint(LinearLayout group, String description, ThemePalette colors) {
        TextView hint = Ui.text(group.getContext(), description, colors.scaledSp(13));
        hint.setTextColor(colors.muted);
        hint.setPadding(0, 0, 0, Ui.dp(group.getContext(), 4));
        group.addView(hint);
    }

    private static String formatSliderValue(Context context, double value, String unit) {
        Locale locale = context == null ? Locale.getDefault() : AppLanguage.resolvedLocale(context);
        NumberFormat fmt = NumberFormat.getNumberInstance(locale);
        fmt.setMaximumFractionDigits(2);
        return fmt.format(value) + (unit.isEmpty() ? "" : " " + unit);
    }

    static void persistBoolean(InstrumentStateStore store, String scope, String surfaceKey, String id, boolean value) {
        if (store == null) return;
        try {
            store.putBoolean(scope, surfaceKey, id, value);
        } catch (IOException | IllegalStateException e) {
            Diagnostics.warning("instrument_state", "save_failed");
        }
    }

    static void persistNumber(InstrumentStateStore store, String scope, String surfaceKey, String id, double value) {
        if (store == null) return;
        try {
            store.putNumber(scope, surfaceKey, id, value);
        } catch (IOException | IllegalStateException e) {
            Diagnostics.warning("instrument_state", "save_failed");
        }
    }

    static void persistStringList(InstrumentStateStore store, String scope, String surfaceKey, String id, List<String> value) {
        if (store == null) return;
        try {
            store.putStringList(scope, surfaceKey, id, value);
        } catch (IOException | IllegalStateException e) {
            Diagnostics.warning("instrument_state", "save_failed");
        }
    }
}
