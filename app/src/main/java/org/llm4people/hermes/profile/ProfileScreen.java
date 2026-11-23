package org.llm4people.hermes.profile;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Native, scrollable profile forms inside the activity's inset-aware content area. */
final class ProfileScreen {
    enum BadgeTone { NEUTRAL, BIOMETRIC, COOLDOWN }

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
                float density = getResources().getDisplayMetrics().density;
                int gutter = Ui.dp(activity, AdaptiveLayout.centeredGutterDp(
                        MeasureSpec.getSize(widthSpec) / density, AdaptiveLayout.PROFILE_COLUMN_MAX_DP, 22));
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

    TextView note(String text) {
        TextView caption = Ui.text(activity, text, 13);
        caption.setTextColor(activity.getColor(R.color.muted));
        add(caption, 4);
        return caption;
    }

    TextView liveNote(String text) {
        TextView caption = note(text);
        caption.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return caption;
    }

    void setNote(TextView target, String text, int colorRes) {
        if (target == null) return;
        target.setTextColor(activity.getColor(colorRes));
        target.setText(text == null ? "" : text);
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

    /**
     * Profile chooser row preserving the exact profile {@code label} on the clickable {@link Button}
     * while rendering a subtle WCAG-AA status pill beside it.
     */
    Button profileRow(String label, String badgeText, BadgeTone tone, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 52));
        row.setPaddingRelative(Ui.dp(activity, 14), Ui.dp(activity, 4), Ui.dp(activity, 12), Ui.dp(activity, 4));
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(Ui.dp(activity, 12));
        card.setColor(activity.getColor(R.color.surface));
        card.setStroke(Ui.dp(activity, 1), activity.getColor(R.color.line));
        row.setBackground(card);

        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setBackground(null);
        button.setPaddingRelative(0, Ui.dp(activity, 8), Ui.dp(activity, 10), Ui.dp(activity, 8));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(Ui.dp(activity, 48));
        button.setMinimumHeight(Ui.dp(activity, 48));
        button.setTextSize(16);
        button.setTypeface(MEDIUM);
        button.setTextColor(activity.getColor(R.color.ink));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(button, btnParams);

        if (badgeText != null && !badgeText.isEmpty()) {
            TextView pill = Ui.text(activity, badgeText, 12);
            pill.setTypeface(MEDIUM);
            pill.setSingleLine(true);
            pill.setPaddingRelative(Ui.dp(activity, 10), Ui.dp(activity, 4), Ui.dp(activity, 10), Ui.dp(activity, 4));
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setCornerRadius(Ui.dp(activity, 999));
            if (tone == BadgeTone.BIOMETRIC) {
                pill.setTextColor(activity.getColor(R.color.accent));
                pillBg.setColor(activity.getColor(R.color.accent_surface));
            } else if (tone == BadgeTone.COOLDOWN) {
                pill.setTextColor(activity.getColor(R.color.warning));
                pillBg.setColor(activity.getColor(R.color.warning_surface));
            } else {
                pill.setTextColor(activity.getColor(R.color.muted));
                pillBg.setColor(activity.getColor(R.color.paper));
                pillBg.setStroke(Ui.dp(activity, 1), activity.getColor(R.color.line));
            }
            pill.setBackground(pillBg);
            LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pillParams.setMarginStart(Ui.dp(activity, 8));
            row.addView(pill, pillParams);
        }

        row.setOnClickListener(v -> {
            if (button.isEnabled()) button.performClick();
        });
        add(row, 8);
        return button;
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

    void card(View view, int top) {
        add(view, top);
    }

    private void add(View view, int top) {
        ViewGroup.LayoutParams initial = view.getLayoutParams();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, initial == null ? -2 : initial.height);
        params.topMargin = Ui.dp(activity, top);
        body.addView(view, params);
    }
}
