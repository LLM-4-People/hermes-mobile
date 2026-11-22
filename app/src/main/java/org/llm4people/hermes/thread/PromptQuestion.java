package org.llm4people.hermes.thread;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Choices keep their wire values. A multiple selection is a JSON array encoded inside the answer string. */
final class PromptQuestion {
    private static final String RECOMMENDED = "(Recommended)";
    private final PromptCard card;
    private final EditText input;
    private Button submit;
    private final List<CheckBox> checks = new ArrayList<>();

    PromptQuestion(PromptCard card, LinearLayout actions, JSONObject prompt, Consumer<String> answer) {
        this.card = card;
        JSONArray choices = prompt.optJSONArray("choices");
        boolean multiple = prompt.optBoolean("multi_select");
        input = new EditText(card.getContext());
        int count = 0;
        for (int i = 0; choices != null && i < choices.length(); i++) {
            if (!(choices.opt(i) instanceof String choice) || choice.trim().isEmpty()) continue;
            count++;
            boolean recommended = choice.endsWith(RECOMMENDED);
            String value = recommended ? choice.substring(0, choice.length() - RECOMMENDED.length()).trim() : choice;
            String label = recommended ? value + " (choix recommandé)" : choice;
            if (multiple) {
                CheckBox option = new CheckBox(card.getContext());
                option.setText(label);
                option.setTag(value);
                option.setTextSize(16);
                option.setMinHeight(Ui.dp(card.getContext(), 48));
                option.setOnCheckedChangeListener((v, checked) -> updateSubmit());
                checks.add(option);
                card.spaced(actions, option);
            } else {
                Button option = card.button(actions, label, () -> answer.accept(value), true);
                option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
        }
        LinearLayout custom = Ui.column(card.getContext());
        if (count > 0) {
            custom.setVisibility(View.GONE);
            Button write = card.button(actions, "Écrire une réponse", () -> {}, false);
            write.setOnClickListener(v -> {
                write.setVisibility(View.GONE);
                custom.setVisibility(View.VISIBLE);
                input.requestFocus();
                input.post(() -> card.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                        .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
            });
        }
        actions.addView(custom);
        input.setHint("Votre réponse");
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setMaxLines(4);
        input.setMinimumHeight(Ui.dp(card.getContext(), 48));
        card.spaced(custom, input);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) { updateSubmit(); }
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });
        submit = card.button(multiple ? actions : custom, "Envoyer la réponse", () -> {
            String value = selectedAnswer();
            if (!value.isEmpty()) answer.accept(value);
        }, true);
        // Only canonical clarify prompts reach this view. Their API defines an empty answer as skip.
        card.button(actions, "Passer la question", () -> answer.accept(""), false);
    }

    private String selectedAnswer() {
        String draft = input.getText().toString().trim();
        if (checks.isEmpty()) return draft;
        JSONArray selected = new JSONArray();
        for (CheckBox check : checks) if (check.isChecked()) selected.put(check.getTag());
        if (!draft.isEmpty()) selected.put(draft);
        return selected.length() == 0 ? "" : selected.toString();
    }

    void updateSubmit() {
        if (submit != null) submit.setEnabled(card.ready() && !selectedAnswer().isEmpty());
    }

}
