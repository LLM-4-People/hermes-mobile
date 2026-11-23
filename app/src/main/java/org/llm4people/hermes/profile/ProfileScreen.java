package org.llm4people.hermes.profile;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.TextUtils;
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

import java.util.function.Consumer;

/** Native, scrollable Material 3 profile forms inside the activity's inset-aware content area. */
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
                    body.setPaddingRelative(gutter, Ui.dp(activity, 24), gutter, Ui.dp(activity, 28));
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));
        body.setFocusableInTouchMode(true);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        heading(title, 28, 0, true);
        if (description != null) description(description);
        activity.setContentView(scroll);
    }

    void section(String title, String description) {
        add(Ui.divider(activity), 24);
        heading(title, 19, 18, false);
        description(description);
    }

    private void heading(String title, int size, int top, boolean display) {
        TextView heading = Ui.text(activity, title, size);
        heading.setTypeface(display ? Typeface.DEFAULT_BOLD : MEDIUM);
        heading.setLetterSpacing(display ? -0.02f : -0.01f);
        heading.setAccessibilityHeading(true);
        add(heading, top);
    }

    private void description(String text) {
        TextView detail = Ui.text(activity, text, 15);
        detail.setTextColor(activity.getColor(R.color.muted));
        detail.setLineSpacing(0f, 1.25f);
        add(detail, 6);
    }

    TextView note(String text) {
        TextView caption = Ui.text(activity, text, 13);
        caption.setTextColor(activity.getColor(R.color.muted));
        caption.setLineSpacing(0f, 1.2f);
        add(caption, 6);
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
        return field(body, label, hint, type, maxLength, sensitive, 16);
    }

    EditText field(LinearLayout container, String label, String hint, int type, int maxLength, boolean sensitive) {
        return field(container, label, hint, type, maxLength, sensitive, 12);
    }

    private EditText field(LinearLayout container, String label, String hint, int type, int maxLength,
                           boolean sensitive, int topMarginDp) {
        EditText edit = styledField(activity, container, label, hint, type, maxLength, sensitive, topMarginDp);
        if (lastField != null) {
            lastField.setNextFocusForwardId(edit.getId());
            lastField.setNextFocusDownId(edit.getId());
        }
        lastField = edit;
        return edit;
    }

    /** Single source of truth for Material 3 labeled form inputs across profile setup and onboarding wizard. */
    static EditText styledField(Activity activity, LinearLayout container, String label, String hint,
                                int type, int maxLength, boolean sensitive, int topMarginDp) {
        TextView caption = Ui.text(activity, label, 14);
        caption.setTypeface(MEDIUM);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(-1, -2);
        capLp.topMargin = Ui.dp(activity, topMarginDp);
        container.addView(caption, capLp);

        EditText edit = new EditText(activity);
        edit.setId(View.generateViewId());
        edit.setSingleLine(true);
        edit.setTextSize(16);
        edit.setInputType(type);
        edit.setTypeface(Typeface.DEFAULT);
        edit.setTextColor(activity.getColor(R.color.ink));
        edit.setHintTextColor(activity.getColor(R.color.muted));
        edit.setBackgroundResource(R.drawable.profile_input);
        edit.setBackgroundTintList(null);
        Ui.pad(edit, 14, 12);
        edit.setHint(hint);
        edit.setMinHeight(Ui.dp(activity, 48));
        edit.setSaveEnabled(false);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        edit.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | (sensitive ? EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING : 0));
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        caption.setLabelFor(edit.getId());
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(-1, -2);
        editLp.topMargin = Ui.dp(activity, 6);
        container.addView(edit, editLp);
        return edit;
    }

    /** Shared Material 3 selectable choice chip (14dp rounded ripple, >=48dp touch target, wrap-safe at 1.3x). */
    static Button choiceChip(Activity activity, String label, View.OnClickListener listener) {
        Button chip = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(13);
        chip.setTypeface(MEDIUM);
        chip.setSingleLine(false);
        chip.setMaxLines(2);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER);
        chip.setStateListAnimator(null);
        int touchMin = Ui.dp(activity, 48);
        chip.setMinHeight(touchMin);
        chip.setMinimumHeight(touchMin);
        Ui.pad(chip, 10, 8);
        chip.setOnClickListener(listener);
        return chip;
    }

    /** Applies Material 3 selected/unselected tonal surface, border, and rounded ripple to a choice chip. */
    static void styleChoiceChip(Activity activity, Button chip, boolean selected) {
        GradientDrawable fill = Ui.shape(activity, Ui.BUTTON_CORNER_DP,
                selected ? R.color.accent_surface : R.color.surface,
                selected ? R.color.accent : R.color.line,
                selected ? 2 : 1);
        chip.setBackground(Ui.roundedRipple(activity, Ui.BUTTON_CORNER_DP, fill));
        chip.setTextColor(activity.getColor(selected ? R.color.accent : R.color.ink));
        chip.setSelected(selected);
    }

    /** Equal-width wrap-safe choice chip row sharing one consistent visual grammar across setup and wizard. */
    static LinearLayout choiceRow(Activity activity, String[] labels, String[] values,
                                  String initial, Consumer<String> onSelected) {
        LinearLayout row = Ui.row(activity);
        row.setBaselineAligned(false);
        Button[] buttons = new Button[labels.length];
        String[] active = {initial};

        Runnable paint = () -> {
            for (int i = 0; i < buttons.length; i++) {
                styleChoiceChip(activity, buttons[i], values[i].equals(active[0]));
            }
        };

        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            Button chip = choiceChip(activity, labels[i], v -> {
                active[0] = value;
                paint.run();
                onSelected.accept(value);
            });
            buttons[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.setMarginStart(Ui.dp(activity, 6));
            row.addView(chip, lp);
        }
        paint.run();
        return row;
    }

    /**
     * Profile chooser row preserving the exact profile {@code label} on the clickable {@link Button}
     * while pairing a crisp circular initials avatar and WCAG-AA status pill beside it.
     */
    Button profileRow(String label, String badgeText, BadgeTone tone, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 60));
        row.setPaddingRelative(Ui.dp(activity, 14), Ui.dp(activity, 8), Ui.dp(activity, 14), Ui.dp(activity, 8));
        GradientDrawable card = Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.line, 1);
        row.setBackground(Ui.roundedRipple(activity, Ui.CARD_CORNER_DP, card));

        TextView avatar = Ui.avatarBadge(activity, label);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 36), Ui.dp(activity, 36));
        avatarParams.setMarginEnd(Ui.dp(activity, 12));
        row.addView(avatar, avatarParams);

        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setBackground(null);
        button.setStateListAnimator(null);
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
            TextView pill = buildStatusPill(badgeText, tone);
            LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pillParams.setMarginStart(Ui.dp(activity, 8));
            row.addView(pill, pillParams);
        }

        row.setOnClickListener(v -> {
            if (button.isEnabled()) button.performClick();
        });
        add(row, 10);
        return button;
    }

    private TextView buildStatusPill(String badgeText, BadgeTone tone) {
        if (tone == BadgeTone.BIOMETRIC) {
            return Ui.pill(activity, badgeText, R.color.accent_surface, R.color.accent);
        }
        if (tone == BadgeTone.COOLDOWN) {
            return Ui.pill(activity, badgeText, R.color.warning_surface, R.color.warning);
        }
        TextView pill = Ui.pill(activity, badgeText, R.color.surface_container, R.color.muted);
        pill.setBackground(Ui.shape(activity, 999, R.color.surface_container, R.color.line, 1));
        return pill;
    }

    Button action(String label, boolean primary, View.OnClickListener listener) {
        Button button = primary
                ? Ui.filledButton(activity, label, listener)
                : Ui.button(activity, label, listener);
        if (primary && lastField != null) {
            connectSubmission(lastField, button);
        }
        add(button, primary ? 22 : 8);
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
            status.setLineSpacing(0f, 1.25f);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            add(status, 14);
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
