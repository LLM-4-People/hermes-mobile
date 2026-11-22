package org.llm4people.hermes.panel;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Sizes the native sheet to the space actually allocated by the dialog or customized layout. */
final class PanelFrame extends FrameLayout {
    static final int PAGE_PADDING_DP = AdaptiveLayout.PANEL_PAGE_PADDING_DP;
    private final LinearLayout sheet;
    private Sections sections;
    private boolean wideSingle;

    PanelFrame(Context context, LinearLayout sheet) {
        super(context);
        this.sheet = sheet;
        setFitsSystemWindows(true);
        setBackgroundColor(context.getColor(R.color.paper));
        addView(sheet, new LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
    }

    void allowWide() {
        wideSingle = true;
        requestLayout();
    }

    Sections sections(LinearLayout page) {
        sections = new Sections(getContext());
        page.addView(sections);
        return sections;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        Context c = getContext();
        float density = getResources().getDisplayMetrics().density;
        float availableDp = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? AdaptiveLayout.PANEL_PAIRED_MAX_DP
                : Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight()) / density;
        float fontScale = getResources().getConfiguration().fontScale;
        AdaptiveLayout.PanelGeometry geometry = AdaptiveLayout.panel(
                availableDp, fontScale, sections != null, wideSingle);
        if (sections != null) sections.arrange(geometry.paired());
        sheet.getLayoutParams().width = Ui.dp(c, geometry.sheetWidthDp());
        super.onMeasure(widthSpec, heightSpec);
    }

    /** The same views change position together, preserving focus, edits and pending requests. */
    static final class Sections extends LinearLayout {
        final LinearLayout first, second;

        Sections(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBaselineAligned(false);
            first = Ui.column(context);
            second = Ui.column(context);
            addView(first, new LayoutParams(-1, -2));
            addView(second, new LayoutParams(-1, -2));
        }

        private void arrange(boolean paired) {
            int orientation = paired ? HORIZONTAL : VERTICAL;
            if (getOrientation() == orientation) return;
            setOrientation(orientation);
            first.setLayoutParams(new LayoutParams(paired ? 0 : -1, -2, paired ? 1 : 0));
            LayoutParams end = new LayoutParams(paired ? 0 : -1, -2, paired ? 1 : 0);
            end.setMarginStart(paired ? Ui.dp(getContext(), AdaptiveLayout.PANEL_GAP_DP) : 0);
            second.setLayoutParams(end);
        }
    }
}
