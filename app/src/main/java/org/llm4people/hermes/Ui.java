package org.llm4people.hermes;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native primitives. Dimensions follow screen density and text follows font scale. */
public final class Ui {
    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static void pad(View view, int horizontalDp, int verticalDp) {
        int h = dp(view.getContext(), horizontalDp);
        int v = dp(view.getContext(), verticalDp);
        view.setPaddingRelative(h, v, h, v);
    }

    public static TextView text(Context context, String label, float sp) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(sp);
        view.setTextColor(context.getColor(R.color.ink));
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return view;
    }

    public static Button button(Context context, String label, View.OnClickListener action) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(context.getColor(R.color.accent));
        button.setMinHeight(dp(context, 48));
        button.setOnClickListener(action);
        return button;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(context.getColor(R.color.line));
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(context, 1))));
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }
}
