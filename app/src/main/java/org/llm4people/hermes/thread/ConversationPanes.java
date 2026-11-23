package org.llm4people.hermes.thread;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import java.util.function.BooleanSupplier;

/** The inbox and selected discussion stay mounted as the available window changes. */
final class ConversationPanes extends LinearLayout {
    private final View sidebar;
    private final View rule;
    private final FrameLayout detail;
    private final TextView empty;
    private final BooleanSupplier inspectorOpen;
    private final Runnable changed;
    private boolean selected, dual, showing, retired, selectionChanged;
    private Boolean preferredSidebar;

    ConversationPanes(Context context, View sidebar, BooleanSupplier inspectorOpen, Runnable changed) {
        super(context);
        this.sidebar = sidebar;
        this.inspectorOpen = inspectorOpen;
        this.changed = changed;
        setId(R.id.discussion_panes);
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        sidebar.setId(R.id.discussion_sidebar);
        addView(sidebar, new LayoutParams(0, -1, 1));
        rule = new View(context);
        rule.setBackgroundColor(context.getColor(R.color.line));
        rule.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(rule, new LayoutParams(Math.max(1, Ui.dp(context, 1)), -1));
        detail = new FrameLayout(context);
        detail.setId(R.id.discussion_detail);
        empty = Ui.text(context, context.getString(R.string.thread_choose_to_open), 18);
        Ui.pad(empty, 24, 16);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(context.getColor(R.color.muted));
        detail.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        addView(detail, new LayoutParams(0, -1, 1));
    }

    void select(View chat) {
        selectionChanged = true;
        selected = chat != null;
        if (!selected) preferredSidebar = null;
        if (detail.getChildCount() > 1) detail.removeViewAt(1);
        if (chat != null) detail.addView(chat, new FrameLayout.LayoutParams(-1, -1));
        empty.setVisibility(selected ? GONE : VISIBLE);
        requestLayout();
    }

    boolean canToggle() { return selected && dual; }
    boolean sidebarVisible() { return showing; }
    boolean dualPane() { return dual; }

    void toggle() {
        if (!canToggle()) return;
        preferredSidebar = !showing;
        requestLayout();
    }

    void retire() {
        retired = true;
        removeCallbacks(changed);
        detail.removeAllViews();
        removeAllViews();
    }

    @Override protected void onMeasure(int width, int height) {
        float density = getResources().getDisplayMetrics().density;
        float widthDp = MeasureSpec.getSize(width) / density;
        float heightDp = MeasureSpec.getSize(height) / density;
        float scale = getResources().getConfiguration().fontScale;
        AdaptiveLayout.ConversationGeometry geometry = AdaptiveLayout.conversationPanes(
                widthDp, heightDp, scale, selected, inspectorOpen.getAsBoolean(), preferredSidebar);
        boolean changedMode = dual != geometry.dual() || showing != geometry.sidebarVisible();
        dual = geometry.dual();
        showing = geometry.sidebarVisible();
        sidebar.setVisibility(showing ? VISIBLE : GONE);
        detail.setVisibility(geometry.detailVisible() ? VISIBLE : GONE);
        rule.setVisibility(dual && showing ? VISIBLE : GONE);
        LayoutParams rail = (LayoutParams) sidebar.getLayoutParams();
        rail.width = dual ? Math.round(geometry.railDp() * density) : 0;
        rail.weight = dual ? 0 : 1;
        super.onMeasure(width, height);
        if ((changedMode || selectionChanged) && !retired) {
            selectionChanged = false;
            removeCallbacks(changed);
            post(changed);
        }
    }
}
