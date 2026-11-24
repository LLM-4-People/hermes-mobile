package org.llm4people.hermes.thread;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import java.util.function.Consumer;

/** Three native, mutually exclusive ways of reading the same proposed change. */
final class InstrumentVersions extends RadioGroup {
    enum Side {
        PINNED(R.string.instrument_tab_pin, R.string.instrument_tab_pin_role, R.color.ink, R.color.paper),
        CHANGES(R.string.instrument_tab_diff, R.string.instrument_tab_diff_role, R.color.ink, R.color.paper),
        PROPOSED(R.string.instrument_tab_next, R.string.instrument_tab_next_role, R.color.accent, R.color.on_accent);

        final int text, description, fill, ink;
        Side(int text, int description, int fill, int ink) {
            this.text = text;
            this.description = description;
            this.fill = fill;
            this.ink = ink;
        }
    }

    InstrumentVersions(Context c, Consumer<Side> choose) {
        super(c);
        setOrientation(LinearLayout.HORIZONTAL);
        setBaselineAligned(false);
        Ui.pad(this, 3, 3);
        GradientDrawable track = shape(c, c.getColor(R.color.surface));
        track.setStroke(Ui.dp(c, 1), c.getColor(R.color.line));
        setBackground(track);
        for (Side side : Side.values()) {
            RadioButton tab = new RadioButton(c);
            tab.setId(View.generateViewId());
            tab.setTag(side);
            tab.setButtonDrawable(null);
            tab.setGravity(Gravity.CENTER);
            tab.setMinWidth(0);
            tab.setMinimumWidth(0);
            tab.setMinHeight(Ui.dp(c, 48));
            tab.setTextSize(15);
            tab.setTypeface(Ui.mediumTypeface());
            tab.setText(side.text);
            tab.setContentDescription(c.getString(side.description));
            Ui.pad(tab, 2, 2);
            int[] selected = { android.R.attr.state_checked };
            StateListDrawable surface = new StateListDrawable();
            surface.addState(selected, shape(c, c.getColor(side.fill)));
            surface.addState(new int[0], shape(c, Color.TRANSPARENT));
            tab.setBackground(new RippleDrawable(ColorStateList.valueOf(c.getColor(R.color.line)), surface, null));
            tab.setTextColor(new ColorStateList(new int[][]{selected, new int[0]},
                    new int[]{c.getColor(side.ink), c.getColor(R.color.muted)}));
            addView(tab, new RadioGroup.LayoutParams(0, -2, 1));
            if (side == Side.CHANGES) check(tab.getId());
        }
        setOnCheckedChangeListener((group, id) -> {
            View selected = group.findViewById(id);
            if (selected != null) choose.accept((Side) selected.getTag());
        });
    }

    private static GradientDrawable shape(Context c, int color) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(Ui.dp(c, 6));
        return fill;
    }
}
