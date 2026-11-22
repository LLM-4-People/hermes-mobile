package org.llm4people.hermes.thread;

import android.content.Context;
import android.text.Layout;
import android.widget.Button;
import android.widget.LinearLayout;

import org.llm4people.hermes.Ui;

/** Give both decisions equal space when their labels fit. Larger text falls back to full rows. */
final class ApprovalChoices extends LinearLayout {
    ApprovalChoices(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int gap = Ui.dp(getContext(), 8);
        int half = (MeasureSpec.getSize(widthMeasureSpec) - gap) / 2;
        boolean pair = getChildCount() == 2 && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY;
        for (int i = 0; pair && i < getChildCount(); i++) {
            Button button = (Button) getChildAt(i);
            float width = Layout.getDesiredWidth(button.getText(), button.getPaint())
                    + button.getCompoundPaddingLeft() + button.getCompoundPaddingRight();
            pair = width <= half;
        }
        int orientation = pair ? HORIZONTAL : VERTICAL;
        if (getOrientation() != orientation) {
            setOrientation(orientation);
            for (int i = 0; i < getChildCount(); i++) {
                LayoutParams params = new LayoutParams(pair ? 0 : -1, -2, pair ? 1 : 0);
                params.topMargin = gap;
                if (pair && i > 0) params.setMarginStart(gap);
                getChildAt(i).setLayoutParams(params);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
