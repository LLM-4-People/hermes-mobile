package org.llm4people.hermes.panel;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private Picker picker;

    PanelFrame(Context context, LinearLayout sheet) {
        super(context);
        this.sheet = sheet;
        setFitsSystemWindows(true);
        setBackgroundColor(context.getColor(R.color.paper));
        addView(sheet, new LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
    }

    Sections sections(LinearLayout page) {
        sections = new Sections(getContext());
        page.addView(sections);
        return sections;
    }

    void bindPicker(LinearLayout navigation, LinearLayout results, View header, View search,
                    LinearLayout.LayoutParams fieldParams, boolean[] splitFlag, boolean initialSplit) {
        picker = new Picker(navigation, results, header, search, fieldParams, splitFlag);
        picker.arrange(initialSplit);
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        Context c = getContext();
        float density = getResources().getDisplayMetrics().density;
        float availableW = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? AdaptiveLayout.PANEL_PAIRED_MAX_DP
                : Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight()) / density;
        float availableH = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? getResources().getConfiguration().screenHeightDp
                : Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom()) / density;
        boolean splitPicker = picker != null && AdaptiveLayout.splitPicker(availableW, availableH);
        if (picker != null) picker.arrange(splitPicker);
        float fontScale = getResources().getConfiguration().fontScale;
        AdaptiveLayout.PanelGeometry geometry = AdaptiveLayout.panel(
                availableW, fontScale, sections != null, splitPicker);
        if (sections != null) sections.arrange(geometry.paired());
        sheet.getLayoutParams().width = Ui.dp(c, geometry.sheetWidthDp());
        super.onMeasure(widthSpec, heightSpec);
    }

    /** Moves the same picker views between stacked portrait and split short-landscape layouts. */
    private final class Picker {
        private final LinearLayout controls;
        private final LinearLayout navigation;
        private final LinearLayout results;
        private final View header;
        private final View search;
        private final LinearLayout.LayoutParams fieldParams;
        private final boolean[] splitFlag;
        private Boolean split;

        Picker(LinearLayout navigation, LinearLayout results, View header, View search,
               LinearLayout.LayoutParams fieldParams, boolean[] splitFlag) {
            this.controls = Ui.column(getContext());
            this.navigation = navigation;
            this.results = results;
            this.header = header;
            this.search = search;
            this.fieldParams = fieldParams;
            this.splitFlag = splitFlag;
        }

        void arrange(boolean nextSplit) {
            if (split != null && split == nextSplit) return;
            View focused = sheet.findFocus();
            split = nextSplit;
            splitFlag[0] = nextSplit;
            detach(controls);
            detach(navigation);
            detach(header);
            detach(search);
            if (nextSplit) {
                sheet.setOrientation(LinearLayout.HORIZONTAL);
                Ui.pad(navigation, 4, 0);
                navigation.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
                controls.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
                Ui.pad(header, 12, 2);
                controls.addView(header, new LinearLayout.LayoutParams(-1, 0, 1));
                sheet.addView(controls, 0, new LinearLayout.LayoutParams(0, -1, 1));
                results.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
                Ui.pad(results, 8, 0);
            } else {
                sheet.setOrientation(LinearLayout.VERTICAL);
                Ui.pad(navigation, 12, 0);
                sheet.addView(navigation, 0, new LinearLayout.LayoutParams(-1, -2));
                results.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
                Ui.pad(results, PAGE_PADDING_DP, 12);
                Ui.pad(header, 0, 0);
                results.addView(header, 0, new LinearLayout.LayoutParams(-1, -2));
                results.addView(search, 1, fieldParams);
            }
            if (focused != null) focused.requestFocus();
        }

        private void detach(View view) {
            if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        }
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
