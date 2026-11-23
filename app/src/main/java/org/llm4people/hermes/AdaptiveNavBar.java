package org.llm4people.hermes;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Effortless 4-destination adaptive navigation chrome.
 * Renders as a bottom bar on phones (&lt; 720dp, auto-hidden when IME is open)
 * and a calm 92dp leading navigation rail on tablets (&gt;= 720dp).
 */
public final class AdaptiveNavBar extends LinearLayout {
    public record Destination(String route, int viewId, int iconRes, int labelRes) {}

    private static final Destination[] DESTINATIONS = {
            new Destination(WorkspaceNavigation.DISCUSSION, R.id.nav_tab_discussions,
                    R.drawable.ic_threads, R.string.nav_discussions),
            new Destination(WorkspaceNavigation.HOME, R.id.nav_tab_atelier,
                    R.drawable.ic_desk, R.string.nav_atelier),
            new Destination(WorkspaceNavigation.BRAIN, R.id.nav_tab_brain,
                    R.drawable.ic_brain, R.string.nav_brain),
            new Destination(WorkspaceNavigation.ACTIVITY, R.id.nav_tab_activity,
                    R.drawable.ic_activity, R.string.nav_activity)
    };

    private final Map<String, ItemViews> items = new LinkedHashMap<>();
    private final Consumer<String> onSelect;
    private AdaptiveLayout.NavChrome chrome = AdaptiveLayout.NavChrome.BOTTOM_BAR;
    private String selectedRoute = WorkspaceNavigation.DISCUSSION;

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

    public String selectedDestination() {
        return selectedRoute;
    }

    public void setSelectedRoute(String route) {
        String top = WorkspaceNavigation.topDestination(route);
        selectedRoute = top;
        int activeInk = getContext().getColor(R.color.accent);
        int idleInk = getContext().getColor(R.color.muted);
        for (Map.Entry<String, ItemViews> entry : items.entrySet()) {
            boolean active = top.equals(entry.getKey());
            ItemViews item = entry.getValue();
            item.container.setSelected(active);
            item.pill.setBackgroundResource(active ? R.drawable.nav_indicator : 0);
            item.icon.setImageTintList(ColorStateList.valueOf(active ? activeInk : idleInk));
            item.label.setTextColor(active ? activeInk : idleInk);
            item.label.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    public AdaptiveLayout.NavChrome updateGeometry(float widthDp, float heightDp, boolean imeVisible) {
        AdaptiveLayout.NavChrome next = AdaptiveLayout.navigationChrome(widthDp, heightDp, imeVisible);
        applyChrome(next);
        return next;
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
        int padH = Ui.dp(getContext(), rail ? 4 : 6);
        int padV = Ui.dp(getContext(), rail ? 10 : 4);
        setPaddingRelative(padH, padV, padH, padV);
        setMinimumHeight(rail ? 0 : Ui.dp(getContext(), AdaptiveLayout.NAV_BAR_MIN_HEIGHT_DP));
        setMinimumWidth(rail ? Ui.dp(getContext(), AdaptiveLayout.NAV_RAIL_WIDTH_DP) : 0);
        for (ItemViews item : items.values()) {
            LinearLayout.LayoutParams params = rail
                    ? new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            if (rail) {
                params.topMargin = Ui.dp(getContext(), 4);
                params.bottomMargin = Ui.dp(getContext(), 4);
            } else {
                params.topMargin = 0;
                params.bottomMargin = 0;
            }
            item.container.setLayoutParams(params);
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
        container.setFocusable(true);
        container.setClickable(true);
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        container.setBackgroundResource(ripple.resourceId);
        container.setContentDescription(title);
        container.setTooltipText(title);
        container.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setSelected(host.isSelected());
            }
        });
        container.setOnClickListener(v -> onSelect.accept(dest.route()));

        LinearLayout pill = new LinearLayout(context);
        pill.setGravity(Gravity.CENTER);
        int pillW = Ui.dp(context, 48);
        int pillH = Ui.dp(context, 28);
        pill.setLayoutParams(new LinearLayout.LayoutParams(pillW, pillH));

        ImageView icon = new ImageView(context);
        icon.setImageResource(dest.iconRes());
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconSize = Ui.dp(context, 22);
        pill.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        container.addView(pill);

        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(12);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Ui.dp(context, 2);
        container.addView(label, labelParams);

        return new ItemViews(container, pill, icon, label);
    }

    private record ItemViews(LinearLayout container, LinearLayout pill, ImageView icon, TextView label) {}
}
