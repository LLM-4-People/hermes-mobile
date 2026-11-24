package org.llm4people.hermes;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Material 3 adaptive 3-destination navigation chrome (Chats · Notes &amp; tasks · Apps).
 * Renders as a bottom bar with spacious 64x32dp active capsule indicators on phones (&lt; 720dp,
 * auto-hidden when IME is open) and a calm leading navigation rail on tablets (&gt;= 720dp).
 */
public final class AdaptiveNavBar extends LinearLayout {
    public record Destination(String route, int viewId, int iconRes, int labelRes) {}

    private static final Destination[] DESTINATIONS = {
            new Destination(WorkspaceNavigation.DISCUSSION, R.id.nav_tab_discussions,
                    R.drawable.ic_threads, R.string.nav_discussions),
            new Destination(WorkspaceNavigation.NOTES_TASKS, R.id.nav_tab_tasks,
                    R.drawable.ic_tasks, R.string.nav_notes_tasks),
            new Destination(WorkspaceNavigation.APPS, R.id.nav_tab_apps,
                    R.drawable.ic_apps, R.string.nav_apps)
    };

    private final Map<String, ItemViews> items = new LinkedHashMap<>();
    private final Consumer<String> onSelect;
    private AdaptiveLayout.NavChrome chrome = AdaptiveLayout.NavChrome.BOTTOM_BAR;

    public AdaptiveNavBar(Context context, Consumer<String> onSelect) {
        super(context);
        this.onSelect = Objects.requireNonNull(onSelect);
        setId(R.id.adaptive_nav_bar);
        setBackgroundColor(context.getColor(R.color.surface));
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        for (Destination dest : DESTINATIONS) {
            ItemViews item = createItem(context, dest);
            items.put(dest.route(), item);
            addView(item.container);
        }
        applyChrome(AdaptiveLayout.NavChrome.BOTTOM_BAR);
        setSelectedRoute(WorkspaceNavigation.DISCUSSION);
    }

    public AdaptiveLayout.NavChrome mode() {
        return chrome;
    }

    public void setSelectedRoute(String route) {
        String top = WorkspaceNavigation.topDestination(route);
        int activeInk = getContext().getColor(R.color.accent);
        int idleInk = getContext().getColor(R.color.muted);
        for (Map.Entry<String, ItemViews> entry : items.entrySet()) {
            boolean active = top.equals(entry.getKey());
            ItemViews item = entry.getValue();
            item.container.setSelected(active);
            item.pill.setBackgroundResource(active ? R.drawable.nav_indicator : 0);
            item.icon.setImageTintList(ColorStateList.valueOf(active ? activeInk : idleInk));
            item.label.setTextColor(active ? activeInk : idleInk);
            item.label.setTypeface(active ? Ui.mediumTypeface() : Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    public void applyChrome(AdaptiveLayout.NavChrome next) {
        AdaptiveLayout.NavChrome target = next == null ? AdaptiveLayout.NavChrome.BOTTOM_BAR : next;
        chrome = target;
        if (target == AdaptiveLayout.NavChrome.HIDDEN) {
            setVisibility(View.GONE);
            return;
        }
        setVisibility(View.VISIBLE);
        boolean rail = target == AdaptiveLayout.NavChrome.RAIL;
        setOrientation(rail ? VERTICAL : HORIZONTAL);
        setGravity(rail ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        int padH = Ui.dp(getContext(), rail ? 4 : 8);
        int padV = Ui.dp(getContext(), rail ? 12 : 6);
        setPaddingRelative(padH, padV, padH, padV);
        setMinimumHeight(rail ? 0 : Ui.dp(getContext(), AdaptiveLayout.NAV_BAR_MIN_HEIGHT_DP));
        setMinimumWidth(rail ? Ui.dp(getContext(), AdaptiveLayout.NAV_RAIL_WIDTH_DP) : 0);
        int pillW = Ui.dp(getContext(), rail ? 56 : 64);
        int pillH = Ui.dp(getContext(), 32);
        for (ItemViews item : items.values()) {
            LinearLayout.LayoutParams params = rail
                    ? new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            if (rail) {
                params.topMargin = Ui.dp(getContext(), 6);
                params.bottomMargin = Ui.dp(getContext(), 6);
            } else {
                params.topMargin = 0;
                params.bottomMargin = 0;
            }
            item.container.setLayoutParams(params);
            item.pill.setLayoutParams(new LinearLayout.LayoutParams(pillW, pillH));
        }
    }

    private ItemViews createItem(Context context, Destination dest) {
        String title = context.getString(dest.labelRes());
        LinearLayout container = new LinearLayout(context);
        container.setId(dest.viewId());
        container.setOrientation(VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setMinimumHeight(Ui.dp(context, 48));
        container.setMinimumWidth(Ui.dp(context, 48));
        container.setPaddingRelative(Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2));
        container.setFocusable(true);
        container.setClickable(true);
        container.setBackground(Ui.roundedRipple(context, 16, null));
        container.setContentDescription(title);
        container.setTooltipText(title);
        Ui.setSelectableButtonRole(container);
        container.setOnClickListener(v -> onSelect.accept(dest.route()));

        LinearLayout pill = new LinearLayout(context);
        pill.setGravity(Gravity.CENTER);
        pill.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 32)));

        ImageView icon = new ImageView(context);
        icon.setImageResource(dest.iconRes());
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconSize = Ui.dp(context, 22);
        pill.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        container.addView(pill);

        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(12);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Ui.dp(context, 4);
        container.addView(label, labelParams);

        return new ItemViews(container, pill, icon, label);
    }

    private record ItemViews(LinearLayout container, LinearLayout pill, ImageView icon, TextView label) {}
}
