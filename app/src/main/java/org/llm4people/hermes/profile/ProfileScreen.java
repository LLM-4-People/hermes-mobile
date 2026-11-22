package org.llm4people.hermes.profile;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Native, scrollable profile forms inside the activity's inset-aware content area. */
final class ProfileScreen {
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Activity activity;
    private final LinearLayout body;
    private EditText lastField;
    private TextView status;

    ProfileScreen(Activity activity, String title, String description) {
        this.activity = activity;
        body = Ui.column(activity);
        // The activity already reserves system bars, cutouts and the IME. Center the form
        // inside the remaining space, including in split-screen and rotated windows.
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                int gutter = Math.max(Ui.dp(activity, 22), (width - Ui.dp(activity, 520)) / 2);
                if (body.getPaddingStart() != gutter) {
                    body.setPaddingRelative(gutter, Ui.dp(activity, 20), gutter, Ui.dp(activity, 24));
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));
        body.setFocusableInTouchMode(true);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        heading(title, 30, 0);
        if (description != null) description(description);
        activity.setContentView(scroll);
    }

    void section(String title, String description) {
        add(Ui.divider(activity), 22);
        heading(title, 20, 16);
        description(description);
    }

    private void heading(String title, int size, int top) {
        TextView heading = Ui.text(activity, title, size);
        heading.setTypeface(MEDIUM);
        heading.setAccessibilityHeading(true);
        add(heading, top);
    }

    private void description(String text) {
        TextView detail = Ui.text(activity, text, 15);
        detail.setTextColor(activity.getColor(R.color.muted));
        add(detail, 4);
    }

    EditText field(String label, String hint, int type, int maxLength, boolean sensitive) {
        TextView caption = Ui.text(activity, label, 14);
        caption.setTypeface(MEDIUM);
        add(caption, 14);
        EditText edit = new EditText(activity);
        edit.setId(View.generateViewId());
        edit.setSingleLine(true);
        edit.setTextSize(17);
        edit.setInputType(type);
        edit.setTypeface(Typeface.DEFAULT);
        edit.setTextColor(activity.getColor(R.color.ink));
        edit.setHintTextColor(activity.getColor(R.color.muted));
        edit.setBackgroundResource(R.drawable.profile_input);
        edit.setBackgroundTintList(null);
        Ui.pad(edit, 12, 9);
        edit.setHint(hint);
        edit.setMinHeight(Ui.dp(activity, 48));
        edit.setSaveEnabled(false);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        edit.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | (sensitive ? EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING : 0));
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        caption.setLabelFor(edit.getId());
        if (lastField != null) {
            lastField.setNextFocusForwardId(edit.getId());
            lastField.setNextFocusDownId(edit.getId());
        }
        lastField = edit;
        add(edit, 4);
        return edit;
    }

    Button action(String label, boolean primary, View.OnClickListener listener) {
        Button button = primary ? new Button(activity) : Ui.button(activity, label, listener);
        if (primary) {
            button.setText(label);
            button.setAllCaps(false);
            button.setOnClickListener(listener);
            button.setBackgroundTintList(ColorStateList.valueOf(activity.getColor(R.color.accent)));
            button.setTextColor(activity.getColor(R.color.on_accent));
            if (lastField != null) connectSubmission(lastField, button);
        }
        button.setMinHeight(Ui.dp(activity, 48));
        add(button, primary ? 20 : 8);
        return button;
    }

    private static void connectSubmission(EditText field, Button submit) {
        field.setImeOptions((field.getImeOptions() & ~EditorInfo.IME_MASK_ACTION) | EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((edit, action, event) -> {
            boolean enter = event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER);
            if (action != EditorInfo.IME_ACTION_DONE && !enter) return false;
            if (submit.isEnabled() && (event == null || (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)))
                submit.performClick();
            return true;
        });
    }

    void status(String message, boolean error) {
        if (status == null) {
            status = Ui.text(activity, "", 15);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            add(status, 12);
        }
        status.setTextColor(activity.getColor(error ? R.color.danger : R.color.muted));
        status.setText(message);
    }

    private void add(View view, int top) {
        ViewGroup.LayoutParams initial = view.getLayoutParams();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, initial == null ? -2 : initial.height);
        params.topMargin = Ui.dp(activity, top);
        body.addView(view, params);
    }
}
