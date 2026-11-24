package org.llm4people.hermes.settings;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import org.llm4people.hermes.Ui;

/**
 * Compact wrap-safe flow container for Material 3 choice chips and status pills.
 * Ensures zero horizontal clipping on 360dp phones and at 1.3x+ accessibility font scales.
 */
public final class SettingsWrapLayout extends ViewGroup {
    private final int hGapPx;
    private final int vGapPx;

    public SettingsWrapLayout(Context context) {
        this(context, 8, 8);
    }

    public SettingsWrapLayout(Context context, int hGapDp, int vGapDp) {
        super(context);
        this.hGapPx = Ui.dp(context, hGapDp);
        this.vGapPx = Ui.dp(context, vGapDp);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight());
        int childWSpec = maxWidth == Integer.MAX_VALUE
                ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                : MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
        int childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        int lineW = 0;
        int lineH = 0;
        int totalH = 0;
        int maxLineW = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(childWSpec, childHSpec);
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (lineW > 0 && lineW + hGapPx + cw > maxWidth) {
                maxLineW = Math.max(maxLineW, lineW);
                totalH += lineH + vGapPx;
                lineW = cw;
                lineH = ch;
            } else {
                lineW = lineW == 0 ? cw : lineW + hGapPx + cw;
                lineH = Math.max(lineH, ch);
            }
        }
        if (lineH > 0) {
            maxLineW = Math.max(maxLineW, lineW);
            totalH += lineH;
        }
        int measuredW = resolveSize(maxLineW + getPaddingLeft() + getPaddingRight(), widthMeasureSpec);
        int measuredH = resolveSize(totalH + getPaddingTop() + getPaddingBottom(), heightMeasureSpec);
        setMeasuredDimension(measuredW, measuredH);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int padStart = getPaddingLeft();
        int maxX = (r - l) - getPaddingRight();
        int x = padStart;
        int y = getPaddingTop();
        int lineH = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > padStart && x + cw > maxX) {
                x = padStart;
                y += lineH + vGapPx;
                lineH = 0;
            }
            child.layout(x, y, Math.min(x + cw, maxX), y + ch);
            x += cw + hGapPx;
            lineH = Math.max(lineH, ch);
        }
    }
}
