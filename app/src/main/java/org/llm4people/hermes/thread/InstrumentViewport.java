package org.llm4people.hermes.thread;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Only the transcript viewport adapts. The real composer and its selection stay attached. */
final class InstrumentViewport extends LinearLayout {
    static final int SPLIT_DP = AdaptiveLayout.SPLIT_TOOL_DP;
    private final ListView transcript;
    private final LinearLayout dock;
    private final TextView dockTitle;
    private final Button dockAction;
    private final FrameLayout tool;
    private final View rule;
    private boolean opened;
    private boolean split;
    private Runnable visibilityChanged;
    private int first, top;

    InstrumentViewport(ListView transcript) {
        super(transcript.getContext());
        this.transcript = transcript;
        Context c = getContext();
        setOrientation(VERTICAL);
        dock = Ui.row(c);
        Ui.pad(dock, 12, 0);
        dock.setBackgroundColor(c.getColor(R.color.accent_surface));
        dockTitle = Ui.text(c, "", 16);
        dockTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        dockAction = Ui.button(c, "", v -> {});
        dockAction.setId(View.generateViewId());
        dockTitle.setLabelFor(dockAction.getId());
        dock.addView(dockTitle, new LayoutParams(0, -2, 1));
        dock.addView(dockAction);
        dock.setVisibility(GONE);
        addView(dock);

        LinearLayout panes = Ui.row(c);
        panes.setGravity(Gravity.TOP);
        panes.setBaselineAligned(false);
        panes.addView(transcript, new LayoutParams(0, -1, 1));
        rule = new View(c);
        rule.setBackgroundColor(c.getColor(R.color.line));
        rule.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        panes.addView(rule, new LayoutParams(Math.max(1, Ui.dp(c, 1)), -1));
        tool = new FrameLayout(c);
        tool.setBackgroundColor(c.getColor(R.color.surface));
        panes.addView(tool, new LayoutParams(0, -1, 1));
        addView(panes, new LayoutParams(-1, 0, 1));
        showPanes();
    }

    void dock(String title, String label, String description, Runnable action) {
        dockTitle.setText(title);
        dockAction.setText(label);
        dockAction.setContentDescription(description);
        dockAction.setOnClickListener(action == null ? null : v -> action.run());
        dock.setVisibility(!opened && action != null ? VISIBLE : GONE);
    }

    void open(View content) {
        if (!opened) {
            first = transcript.getFirstVisiblePosition();
            top = transcript.getChildCount() == 0 ? 0 : transcript.getChildAt(0).getTop();
        }
        if (tool.getChildAt(0) != content) {
            tool.removeAllViews();
            tool.addView(content);
        }
        opened = true;
        dock.setVisibility(GONE);
        showPanes();
    }

    boolean open() { return opened; }
    boolean wide() { return split; }

    void close() {
        opened = false;
        tool.removeAllViews();
        showPanes();
        if (!split && first >= 0) transcript.setSelectionFromTop(first, top);
    }

    void onTranscriptVisibilityChanged(Runnable action) { visibilityChanged = action; }

    private void showPanes() {
        int visibility = !opened || split ? VISIBLE : GONE;
        if (transcript.getVisibility() != visibility) {
            transcript.setVisibility(visibility);
            if (visibilityChanged != null) {
                removeCallbacks(visibilityChanged);
                post(visibilityChanged);
            }
        }
        rule.setVisibility(opened && split ? VISIBLE : GONE);
        tool.setVisibility(opened ? VISIBLE : GONE);
    }

    @Override protected void onMeasure(int width, int height) {
        float density = getResources().getDisplayMetrics().density;
        boolean wide = AdaptiveLayout.splitInstrument(MeasureSpec.getSize(width) / density);
        if (wide != split) {
            split = wide;
            showPanes();
        }
        super.onMeasure(width, height);
    }
}
