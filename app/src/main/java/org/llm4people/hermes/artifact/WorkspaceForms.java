package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import org.llm4people.hermes.VisibleText;

final class WorkspaceForms {
    static void mount(LinearLayout group, WorkspaceLayout.Block form, Activity activity, ArtifactDesk.Host host) {
        Context c = group.getContext();
        Map<String,Supplier<String>> answers = new LinkedHashMap<>();
        Map<String,View> controls = new LinkedHashMap<>();
        TextView prompt = Ui.text(c, form.prompt, 16);
        prompt.setTextColor(c.getColor(R.color.muted));
        prompt.setPadding(0, Ui.dp(c, 3), 0, 0);
        group.addView(prompt);
        group.setSaveFromParentEnabled(false);
        group.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        for (DraftForm.Field field : form.fields) {
            TextView label = Ui.text(c,field.label + (field.required ? "" : " (facultatif)"),15);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4));
            group.addView(label);
            if (host == null) {
                String hint = "choice".equals(field.kind) ? String.join(" · ",field.options) : "Votre réponse";
                TextView sample = Ui.text(c,hint,16);
                sample.setTextColor(c.getColor(R.color.muted));
                group.addView(sample);
                continue;
            }
            View control;
            if ("choice".equals(field.kind)) {
                List<String> options = new ArrayList<>();
                options.add("Choisir…");
                options.addAll(field.options);
                Spinner choice = new Spinner(c);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(c,android.R.layout.simple_spinner_item,options);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                choice.setAdapter(adapter);
                choice.setMinimumHeight(Ui.dp(c,48));
                answers.put(field.id, () -> choice.getSelectedItemPosition() > 0 ? (String)choice.getSelectedItem() : "");
                control = choice;
            } else {
                EditText input = editor(c, "multiline".equals(field.kind), DraftForm.MAX_INPUT);
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
        TextView error = Ui.text(c,"",15);
        error.setVisibility(View.GONE);
        error.setTextColor(c.getColor(R.color.danger));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        group.addView(error);
        Button prepare = Ui.button(c,"Préparer un brouillon…",v -> {
            Map<String,String> values = new LinkedHashMap<>();
            for (Map.Entry<String,Supplier<String>> a : answers.entrySet()) values.put(a.getKey(),a.getValue().get());
            try {
                String draft = DraftForm.compose(form, values);
                error.setVisibility(View.GONE);
                review(activity, host, draft);
            } catch (DraftForm.MissingAnswer e) {
                error.setText(c.getString(R.string.workspace_missing_field, e.field.label));
                error.setVisibility(View.VISIBLE);
                controls.get(e.field.id).requestFocus();
            }
        });
        prepare.setSingleLine(false);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(c.getColor(R.color.accent_surface));
        fill.setCornerRadius(Ui.dp(c, 8));
        prepare.setBackground(new RippleDrawable(ColorStateList.valueOf(c.getColor(R.color.line)), fill, null));
        Ui.pad(prepare, 16, 8);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-2, -2);
        place.topMargin = Ui.dp(c, 8);
        group.addView(prepare, place);
    }

    static EditText editor(Context c, boolean multiline, int limit) {
        EditText text = new EditText(c);
        text.setSingleLine(!multiline);
        int kind = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if (multiline) kind |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        text.setInputType(kind);
        text.setMinHeight(Ui.dp(c,48));
        text.setBackgroundResource(R.drawable.profile_input);
        text.setBackgroundTintList(null);
        Ui.pad(text,12,9);
        text.setMaxLines(multiline ? 8 : 1);
        text.setTextColor(c.getColor(R.color.ink));
        text.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        text.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        text.setSaveEnabled(false);
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        return text;
    }

    private static void review(Activity a, ArtifactDesk.Host host, String draft) {
        reviewDraft(a, host, draft);
    }

    static void reviewDraft(Activity a, ArtifactDesk.Host host, String draft) {
        LinearLayout wrap = Ui.column(a);
        Ui.pad(wrap, 20, 6);
        EditText edit = editor(a, true, 12000);
        edit.setText(draft);
        edit.setSelection(edit.length());
        wrap.addView(edit);
        AlertDialog dialog = DeskDialogs.open(a, host, new AlertDialog.Builder(a)
                .setTitle("Relire le brouillon").setMessage("Vous pourrez le modifier et l’envoyer depuis la conversation.")
                .setView(wrap).setNegativeButton("Annuler", null).setPositiveButton("Vers le brouillon", null));
        if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!dialog.isShowing()) return;
            String text = edit.getText().toString();
            if (VisibleText.isBlank(text)) { edit.setError("Saisissez un message."); return; }
            DeskDialogs.navigate(a, () -> host.stageDraft(text));
        });
    }
}
