package org.llm4people.hermes.profile;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Native, scrollable profile forms. The IME and larger font settings can move every action into view. */
final class ProfileScreen {
    private final Activity activity;
    final LinearLayout body;
    private TextView status;

    ProfileScreen(Activity activity, String title, String description) {
        this.activity = activity;
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));
        FrameLayout frame = new FrameLayout(activity);
        scroll.addView(frame);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams measure = new FrameLayout.LayoutParams(
                Math.min(width, Ui.dp(activity, 568)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        body = Ui.column(activity);
        Ui.pad(body, 24, 28);
        body.setFocusableInTouchMode(true);
        frame.addView(body, measure);
        TextView heading = Ui.text(activity, title, 30);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        body.addView(heading);
        if (description != null) {
            TextView detail = Ui.text(activity, description, 16);
            detail.setTextColor(activity.getColor(R.color.muted));
            add(detail, 8);
        }
        activity.setContentView(scroll);
    }

    EditText field(String label, String hint, int type, int maxLength, boolean sensitive) {
        TextView caption = Ui.text(activity, label, 15);
        add(caption, 16);
        EditText edit = new EditText(activity);
        edit.setId(View.generateViewId());
        edit.setSingleLine(true);
        edit.setTextSize(17);
        edit.setInputType(type);
        edit.setHint(hint);
        edit.setMinHeight(Ui.dp(activity, 48));
        edit.setSaveEnabled(false);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        edit.setImeOptions(EditorInfo.IME_ACTION_NEXT | (sensitive ? EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING : 0));
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        caption.setLabelFor(edit.getId());
        add(edit, 0);
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
        }
        button.setMinHeight(Ui.dp(activity, 48));
        add(button, primary ? 18 : 8);
        return button;
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Ui.dp(activity, top);
        body.addView(view, params);
    }
}
