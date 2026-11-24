package org.llm4people.hermes.thread;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
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
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.VisibleText;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Choices keep their wire values. A multiple selection is a JSON array encoded inside the answer string. */
final class PromptQuestion {
    private static final String RECOMMENDED = "(Recommended)";
    private final PromptCard card;
    private final EditText input;
    private final boolean multiple;
    private Button submit;
    private final VoiceDrafts.Controls voice;
    private final List<CheckBox> checks = new ArrayList<>();

    PromptQuestion(PromptCard card, LinearLayout actions, JSONObject prompt, Button listen, Consumer<String> answer) {
        this.card = card;
        JSONArray choices = prompt.optJSONArray("choices");
        multiple = prompt.optBoolean("multi_select");
        input = new EditText(card.getContext());
        List<String> displayed = new ArrayList<>();
        for (int i = 0; choices != null && i < choices.length(); i++) {
            if (!(choices.opt(i) instanceof String choice)) continue;
            boolean recommended = choice.endsWith(RECOMMENDED);
            String value = recommended ? choice.substring(0, choice.length() - RECOMMENDED.length()).trim() : choice;
            if (VisibleText.isBlank(value)) continue;
            String label = recommended
                    ? card.getContext().getString(R.string.prompt_choice_recommended, value) : choice;
            displayed.add(label);
            CharSequence caption = recommended ? recommendedCaption(label, value.length() + 1) : label;
            if (multiple) {
                CheckBox option = new CheckBox(card.getContext());
                option.setText(caption);
                option.setTag(value);
                option.setTextSize(16);
                option.setTextColor(Ui.enabledDisabledInk(card.getContext(), R.color.ink));
                option.setMinHeight(Ui.dp(card.getContext(), 48));
                option.setOnCheckedChangeListener((v, checked) -> updateSubmit());
                checks.add(option);
                card.spaced(actions, option);
            } else {
                PromptButtons.add(actions, caption, () -> answer.accept(value), PromptButtons.Role.CHOICE);
            }
        }
        card.ui.reader.bindQuestion(listen, ThreadData.string(prompt, "question"), displayed);
        LinearLayout custom = Ui.column(card.getContext());
        if (!displayed.isEmpty()) {
            custom.setVisibility(View.GONE);
            Button write = PromptButtons.add(actions, card.getContext().getString(R.string.prompt_write_answer), null, PromptButtons.Role.UTILITY);
            write.setOnClickListener(v -> {
                write.setVisibility(View.GONE);
                custom.setVisibility(View.VISIBLE);
                input.requestFocus();
                input.post(() -> card.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                        .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
            });
        }
        actions.addView(custom);
        input.setHint(R.string.prompt_your_answer_hint);
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setMaxLines(4);
        input.setMinimumHeight(Ui.dp(card.getContext(), 48));
        input.setTextSize(17);
        input.setTextColor(card.getContext().getColor(R.color.ink));
        input.setHintTextColor(card.getContext().getColor(R.color.muted));
        input.setBackground(null);
        Ui.pad(input, 12, 9);
        voice = card.ui.voice.bind(input);
        LinearLayout entry = Ui.row(card.getContext());
        entry.setGravity(Gravity.BOTTOM);
        entry.setAddStatesFromChildren(true);
        entry.setBackgroundResource(R.drawable.profile_input);
        entry.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        entry.addView(voice.button);
        card.spaced(custom, entry);
        custom.addView(voice.feedback);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) { updateSubmit(); }
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });
        submit = PromptButtons.add(multiple ? actions : custom, card.getContext().getString(R.string.prompt_send_answer), () -> {
            String value = selectedAnswer();
            if (!value.isEmpty()) answer.accept(value);
        }, PromptButtons.Role.SUBMIT);
        // Only canonical clarify prompts reach this view. Their API defines an empty answer as skip.
        PromptButtons.add(actions, card.getContext().getString(R.string.prompt_skip_question), () -> answer.accept(""), PromptButtons.Role.SKIP);
    }

    private static CharSequence recommendedCaption(String label, int noteStart) {
        SpannableString caption = new SpannableString(label);
        caption.setSpan(new RelativeSizeSpan(0.875f), noteStart, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        caption.setSpan(new TypefaceSpan(Typeface.DEFAULT), noteStart, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return caption;
    }

    private String selectedAnswer() {
        String draft = input.getText().toString();
        if (VisibleText.isBlank(draft)) draft = "";
        if (!multiple) return draft;
        JSONArray selected = new JSONArray();
        for (CheckBox check : checks) if (check.isChecked()) selected.put(check.getTag());
        if (!draft.isEmpty()) selected.put(draft);
        return selected.length() == 0 ? "" : selected.toString();
    }

    void stateChanged() {
        if (!card.ready()) voice.cancel();
        updateSubmit();
    }

    private void updateSubmit() {
        if (submit != null) submit.setEnabled(card.ready() && !selectedAnswer().isEmpty());
    }

}
