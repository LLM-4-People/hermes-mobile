package org.llm4people.hermes.thread;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Native prompt actions retain a single accessible label, including when it wraps. */
final class PromptButtons {
    enum Role { UTILITY, SKIP, CHOICE, DECISION, SUBMIT }

    private PromptButtons() {}

    static Button add(LinearLayout parent, CharSequence label, Runnable click, Role role) {
        Context context = parent.getContext();
        Button button = Ui.button(context, null, click == null ? null : v -> click.run());
        button.setText(label);
        button.setSingleLine(false);
        button.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        button.setEllipsize(null);
        button.setMinWidth(0);
        button.setTextSize(role == Role.CHOICE || role == Role.DECISION ? 16 : 14);
        boolean quiet = role == Role.UTILITY || role == Role.SKIP;
        Ui.pad(button, quiet ? 8 : 12, 8);
        button.setGravity(Gravity.CENTER_VERTICAL | (role == Role.SUBMIT ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        int text = switch (role) {
            case SUBMIT -> R.color.on_accent;
            case DECISION -> R.color.ink;
            case SKIP -> R.color.muted;
            default -> R.color.accent;
        };
        ColorStateList ink = colors(context, R.color.muted, text, text);
        button.setTextColor(ink);
        button.setCompoundDrawableTintList(ink);
        if (!quiet) {
            int fill = switch (role) {
                case SUBMIT -> R.color.accent;
                case CHOICE -> R.color.accent_surface;
                default -> R.color.surface;
            };
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(Ui.dp(context, 12));
            shape.setColor(colors(context, R.color.paper, fill, fill));
            shape.setStroke(Ui.dp(context, 1), colors(context, R.color.line, R.color.ink,
                    role == Role.DECISION ? R.color.muted : fill));
            TypedValue highlight = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.colorControlHighlight, highlight, true);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(highlight.data), shape, null));
        }
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(quiet ? -2 : -1, -2);
        position.gravity = role == Role.SKIP ? Gravity.END : Gravity.START;
        position.topMargin = Ui.dp(context, quiet ? 2 : 8);
        parent.addView(button, position);
        return button;
    }

    static void icon(Button button, int drawable) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, 0, 0, 0);
        button.setCompoundDrawablePadding(Ui.dp(button.getContext(), 8));
    }

    private static ColorStateList colors(Context context, int disabled, int focused, int normal) {
        return new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {}
        }, new int[]{context.getColor(disabled), context.getColor(focused), context.getColor(normal)});
    }
}
