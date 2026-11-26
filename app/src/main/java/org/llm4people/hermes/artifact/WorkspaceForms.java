package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.PlainTextSanitizer;

final class WorkspaceForms {
    static void mount(LinearLayout group, WorkspaceLayout.Block form, ThemePalette palette,
                      Activity activity, ArtifactDesk.Host host) {
        Context c = group.getContext();
        ThemePalette colors = palette == null
                ? ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(c))
                : palette;
        Map<String,Supplier<String>> answers = new LinkedHashMap<>();
        Map<String,View> controls = new LinkedHashMap<>();
        TextView prompt = Ui.text(c, form.prompt, colors.scaledSp(16));
        prompt.setTextColor(colors.muted);
        prompt.setPadding(0, Ui.dp(c, 3), 0, 0);
        group.addView(prompt);
        group.setSaveFromParentEnabled(false);
        group.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        for (DraftForm.Field field : form.fields) {
            String fieldTitle = field.required
                    ? field.label : c.getString(R.string.ws_form_optional_suffix, field.label);
            TextView label = Ui.text(c, fieldTitle, colors.scaledSp(15));
            label.setTypeface(Ui.mediumTypeface());
            label.setTextColor(colors.ink);
            label.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4));
            group.addView(label);
            if (host == null) {
                String hint = "choice".equals(field.kind)
                        ? String.join(" · ", field.options) : c.getString(R.string.ws_form_your_answer);
                TextView sample = Ui.text(c, hint, colors.scaledSp(16));
                sample.setTextColor(colors.muted);
                group.addView(sample);
                continue;
            }
            View control;
            if ("choice".equals(field.kind)) {
                List<String> options = new ArrayList<>();
                options.add(c.getString(R.string.ws_form_choose));
                options.addAll(field.options);
                Spinner choice = new Spinner(c);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(c,android.R.layout.simple_spinner_item,options);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                choice.setAdapter(adapter);
                choice.setMinimumHeight(Ui.dp(c,48));
                answers.put(field.id, () -> choice.getSelectedItemPosition() > 0 ? (String)choice.getSelectedItem() : "");
                control = choice;
            } else {
                EditText input = editor(c, "multiline".equals(field.kind), DraftForm.MAX_INPUT, colors);
                answers.put(field.id, () -> input.getText().toString());
                control = input;
            }
            control.setId(View.generateViewId());
            label.setLabelFor(control.getId());
            control.setSaveEnabled(false);
            group.addView(control);
            controls.put(field.id,control);
        }
        if (host == null) return;
        TextView error = Ui.text(c,"",colors.scaledSp(15));
        error.setVisibility(View.GONE);
        error.setTextColor(colors.danger);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        group.addView(error);
        Button prepare = Ui.button(c, c.getString(R.string.ws_form_prepare_draft), v -> {
            Map<String,String> values = new LinkedHashMap<>();
            for (Map.Entry<String,Supplier<String>> a : answers.entrySet()) values.put(a.getKey(),a.getValue().get());
            try {
                String draft = DraftForm.compose(form, values);
                error.setVisibility(View.GONE);
                reviewDraft(activity, host, draft);
            } catch (DraftForm.MissingAnswer e) {
                error.setText(c.getString(R.string.workspace_missing_field, e.field.label));
                error.setVisibility(View.VISIBLE);
                controls.get(e.field.id).requestFocus();
            }
        });
        prepare.setSingleLine(false);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(colors.accentSurface);
        fill.setCornerRadius(Ui.dp(c, Ui.BUTTON_CORNER_DP));
        prepare.setBackground(Ui.roundedRipple(c, Ui.BUTTON_CORNER_DP, fill));
        prepare.setTextColor(Ui.enabledDisabledInk(colors.accent, colors.muted));
        Ui.pad(prepare, 16, 8);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-2, -2);
        place.topMargin = Ui.dp(c, 8);
        group.addView(prepare, place);
    }

    static EditText editor(Context c, boolean multiline, int limit) {
        return editor(c, multiline, limit, null);
    }

    static EditText editor(Context c, boolean multiline, int limit, ThemePalette colors) {
        EditText text = new EditText(c);
        text.setSingleLine(!multiline);
        int kind = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if (multiline) kind |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        text.setInputType(kind);
        text.setMinHeight(Ui.dp(c,48));
        if (colors != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(c, Ui.BUTTON_CORNER_DP));
            bg.setColor(colors.surface);
            bg.setStroke(Math.max(1, Ui.dp(c, 1)), colors.line);
            text.setBackground(bg);
            text.setTextColor(colors.ink);
            text.setHintTextColor(colors.muted);
        } else {
            Ui.styleDialogInput(c, text);
        }
        Ui.pad(text,12,9);
        text.setMaxLines(multiline ? 8 : 1);
        text.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        text.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        text.setSaveEnabled(false);
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        return text;
    }

    static void reviewDraft(Activity a, ArtifactDesk.Host host, String draft) {
        LinearLayout wrap = Ui.column(a);
        Ui.pad(wrap, 20, 6);
        EditText edit = editor(a, true, 12000);
        edit.setHint(R.string.ws_form_enter_message);
        edit.setText(draft);
        edit.setSelection(edit.length());
        wrap.addView(edit);
        AlertDialog dialog = DeskDialogs.open(a, host, new AlertDialog.Builder(a)
                .setTitle(R.string.ws_form_review_title).setMessage(R.string.ws_form_review_message)
                .setView(wrap).setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.ws_form_send_to_draft, null));
        if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!dialog.isShowing()) return;
            String text = edit.getText().toString();
            if (!PlainTextSanitizer.hasVisibleText(text)) { edit.setError(a.getString(R.string.ws_form_enter_message)); return; }
            DeskDialogs.navigate(a, () -> host.stageDraft(text));
        });
    }
}
