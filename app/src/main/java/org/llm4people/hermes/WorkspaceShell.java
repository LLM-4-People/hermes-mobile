package org.llm4people.hermes;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Adaptive navigation frame shared by Discussions, Atelier, Cerveau, Activité and custom pages. */
final class WorkspaceShell {
    final LinearLayout root;
    private final Activity activity;
    private final Consumer<String> action;
    private final BooleanSupplier reading;
    private final LinearLayout contentPane;
    private final LinearLayout header;
    private final FrameLayout body;
    private final TextView heading;
    private final Button pending;
    private final ImageButton leading;
    private final ImageButton searchButton;
    private final ImageButton lockButton;
    private final AdaptiveNavBar navBar;
    private final View navDivider;
    private String pageTitle;
    private String threadId;
    private String threadTitle;
    private boolean canTogglePanes;
    private boolean listVisible;
    private boolean imeVisible;
    private AdaptiveLayout.NavChrome chrome;
    private PopupMenu overflow;

    WorkspaceShell(Activity activity, Consumer<String> action, BooleanSupplier reading) {
        this.reading = reading;
        this.activity = activity;
        this.action = action;
        root = new LinearLayout(activity) {
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                if (w > 0 && (w != oldw || h != oldh)) {
                    float density = getResources().getDisplayMetrics().density;
                    if (density > 0f) {
                        float widthDp = w / density;
                        float heightDp = h / density;
                        if (AdaptiveLayout.navigationChrome(widthDp, heightDp, imeVisible) != chrome) {
                            post(() -> updateNavigationChrome(widthDp, heightDp));
                        }
                    }
                }
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(activity.getColor(R.color.paper));

        contentPane = Ui.column(activity);
        header = Ui.row(activity);
        Ui.pad(header, 6, 2);
        leading = icon(R.drawable.ic_threads, "Discussions");
        leading.setOnClickListener(v -> action.accept(
                pageTitle != null ? "previous" : canTogglePanes ? "toggle_discussions" : "threads"));
        header.addView(leading);

        heading = Ui.text(activity, "Discussions", 20);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        Ui.pad(heading, 6, 2);
        heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        searchButton = icon(R.drawable.ic_search, activity.getString(R.string.top_search));
        searchButton.setId(R.id.top_search_action);
        searchButton.setOnClickListener(v -> action.accept("search"));
        header.addView(searchButton);

        lockButton = icon(R.drawable.ic_lock, activity.getString(R.string.top_profile_lock));
        lockButton.setId(R.id.top_profile_lock_action);
        lockButton.setOnClickListener(v -> action.accept("security"));
        header.addView(lockButton);

        ImageButton more = icon(R.drawable.ic_more, "Autres actions");
        more.setOnClickListener(this::menu);
        header.addView(more);

        contentPane.addView(header);
        contentPane.addView(Ui.divider(activity));
        pending = Ui.button(activity, "Répondre à Hermes", null);
        pending.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pending.setVisibility(View.GONE);
        contentPane.addView(pending);
        body = new FrameLayout(activity);
        contentPane.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        navDivider = new View(activity);
        navDivider.setBackgroundColor(activity.getColor(R.color.line));
        navDivider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        navBar = new AdaptiveNavBar(activity, route -> {
            switch (route) {
                case WorkspaceNavigation.DISCUSSION -> action.accept("nav_discussions");
                case WorkspaceNavigation.HOME -> action.accept("nav_atelier");
                case WorkspaceNavigation.BRAIN -> action.accept("nav_brain");
                case WorkspaceNavigation.ACTIVITY -> action.accept("nav_activity");
                default -> action.accept("nav_discussions");
            }
        });

        android.content.res.Configuration config = activity.getResources().getConfiguration();
        applyChrome(AdaptiveLayout.navigationChrome(config.screenWidthDp, config.screenHeightDp, false));
    }

    AdaptiveNavBar navBar() { return navBar; }

    void setActiveRoute(String route) {
        navBar.setSelectedRoute(route);
    }

    void setImeVisible(boolean visible) {
        if (imeVisible == visible) return;
        imeVisible = visible;
        float density = activity.getResources().getDisplayMetrics().density;
        float widthDp = root.getWidth() > 0 && density > 0f
                ? root.getWidth() / density
                : activity.getResources().getConfiguration().screenWidthDp;
        float heightDp = root.getHeight() > 0 && density > 0f
                ? root.getHeight() / density
                : activity.getResources().getConfiguration().screenHeightDp;
        updateNavigationChrome(widthDp, heightDp);
    }

    void updateNavigationChrome(float widthDp, float heightDp) {
        AdaptiveLayout.NavChrome next = AdaptiveLayout.navigationChrome(widthDp, heightDp, imeVisible);
        if (next != chrome) applyChrome(next);
    }

    private void applyChrome(AdaptiveLayout.NavChrome next) {
        chrome = next;
        navBar.applyChrome(next);
        int rule = Math.max(1, Ui.dp(activity, 1));
        root.removeAllViews();
        if (next == AdaptiveLayout.NavChrome.RAIL) {
            root.setOrientation(LinearLayout.HORIZONTAL);
            navDivider.setVisibility(View.VISIBLE);
            root.addView(navBar, new LinearLayout.LayoutParams(
                    Ui.dp(activity, AdaptiveLayout.NAV_RAIL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(navDivider, new LinearLayout.LayoutParams(rule, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(contentPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else {
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(contentPane, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            boolean showBar = next == AdaptiveLayout.NavChrome.BOTTOM_BAR;
            navDivider.setVisibility(showBar ? View.VISIBLE : View.GONE);
            root.addView(navDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rule));
            root.addView(navBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    void selected(String id, String title) {
        if (!Objects.equals(threadId, id)) dismissMenu();
        threadId = id;
        threadTitle = title;
        updateHeading();
    }

    void discussionPanes(boolean canToggle, boolean visible) {
        if (canTogglePanes == canToggle && listVisible == visible) return;
        canTogglePanes = canToggle;
        listVisible = visible;
        updateHeading();
    }

    void show(View page, String title) {
        pageTitle = title;
        updateHeading();
        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
        body.removeAllViews();
        body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    void pendingResponse(Runnable open) {
        pending.setVisibility(open == null ? View.GONE : View.VISIBLE);
        pending.setOnClickListener(open == null ? null : v -> open.run());
    }

    View content() { return body; }

    View desk(String accountName) {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = centeredScroll(list);
        subtitle(list, activity.getString(R.string.atelier_subtitle));
        if (threadTitle != null) {
            section(list, threadTitle);
            link(list, "Reprendre la discussion", null, "return");
            link(list, "Artefacts", null, "artifacts");
            link(list, "Modèle et outils", null, "models");
            link(list, "Utilisation", null, "stats");
        }
        section(list, "Espace de travail");
        link(list, "Mes écrans", null, "pages");
        link(list, "Nouvelle discussion", null, "new_thread");
        link(list, "Toutes les discussions", null, "threads");
        link(list, activity.getString(R.string.widget_pin_action), null, "pin_widget");
        link(list, "Rétablir l’atelier", null, "restore");
        section(list, accountName);
        link(list, activity.getString(R.string.activity_security_title), null, "security");
        link(list, "Notifications", null, "notifications");
        link(list, "Verrouiller le profil", null, "profiles");
        return scroll;
    }

    View brain() {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = centeredScroll(list);
        subtitle(list, activity.getString(R.string.brain_subtitle));
        section(list, activity.getString(R.string.nav_brain));
        link(list, activity.getString(R.string.brain_skills_title),
                activity.getString(R.string.brain_skills_hint), "skills");
        link(list, activity.getString(R.string.brain_models_title),
                activity.getString(R.string.brain_models_hint), "models");
        link(list, activity.getString(R.string.brain_create_tool_title),
                activity.getString(R.string.brain_create_tool_hint), "create_instrument");
        section(list, activity.getString(R.string.brain_memory_title));
        subtitle(list, activity.getString(R.string.brain_memory_hint));
        return scroll;
    }

    View activitySurface(String securitySummary) {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = centeredScroll(list);
        subtitle(list, activity.getString(R.string.activity_subtitle));
        section(list, activity.getString(R.string.nav_activity));
        link(list, activity.getString(R.string.activity_reminders_title),
                activity.getString(R.string.activity_reminders_hint), "reminders");
        link(list, activity.getString(R.string.activity_notifications_title),
                activity.getString(R.string.activity_notifications_hint), "notifications");
        link(list, activity.getString(R.string.activity_usage_title),
                activity.getString(R.string.activity_usage_hint), "stats");
        link(list, activity.getString(R.string.activity_security_title), securitySummary, "security");
        return scroll;
    }

    private ScrollView centeredScroll(LinearLayout list) {
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                float density = getResources().getDisplayMetrics().density;
                int gutter = Ui.dp(activity, AdaptiveLayout.centeredGutterDp(
                        MeasureSpec.getSize(widthSpec) / density, AdaptiveLayout.READING_COLUMN_MAX_DP, 22));
                int vertical = Ui.dp(activity, 14);
                if (list.getPaddingStart() != gutter || list.getPaddingTop() != vertical) {
                    list.setPaddingRelative(gutter, vertical, gutter, vertical);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroll.setFillViewport(true);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void updateHeading() {
        heading.setText(pageTitle != null ? pageTitle : threadTitle == null ? "Discussions" : threadTitle);
        leading.setImageResource(pageTitle != null ? R.drawable.ic_back : R.drawable.ic_threads);
        String description = pageTitle != null ? "Revenir à l’écran précédent"
                : canTogglePanes ? (listVisible ? "Masquer les discussions" : "Afficher les discussions")
                : "Toutes les discussions";
        leading.setContentDescription(description);
        leading.setTooltipText(description);
        leading.setActivated(pageTitle == null && canTogglePanes && listVisible);
    }

    private ImageButton icon(int resource, String name) {
        ImageButton view = new ImageButton(activity);
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        view.setImageResource(resource);
        view.setContentDescription(name);
        view.setTooltipText(name);
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        return view;
    }

    /** Overflow menu shrunk to thread-contextual (or Atelier-contextual) actions only. */
    private void menu(View anchor) {
        dismissMenu();
        PopupMenu menu = new PopupMenu(activity, anchor);
        overflow = menu;
        menu.setOnDismissListener(closed -> {
            if (overflow == closed) overflow = null;
        });
        boolean atelierSurface = "Atelier".equals(pageTitle)
                || "Mes écrans".equals(pageTitle)
                || "Écran personnalisé".equals(pageTitle);
        if (atelierSurface) {
            item(menu, "Mes écrans", "pages");
            item(menu, "Rétablir l’atelier", "restore");
        } else if (threadTitle != null) {
            if (reading.getAsBoolean()) item(menu, activity.getString(R.string.read_stop_label), "stop_reading");
            else item(menu, activity.getString(R.string.read_reply_label), "read_reply");
            item(menu, activity.getString(R.string.instrument_create), "create_instrument");
            if (pageTitle == null) {
                item(menu, "Joindre une image…", "attach_photo");
                if (org.llm4people.hermes.camera.ThreadCameraCapture.isAvailable(activity)) {
                    item(menu, "Prendre une photo…", "capture_camera");
                }
                item(menu, "Position pour ce message…", "nearby_area");
            }
            item(menu, "Renommer la discussion", "rename");
            item(menu, "Artefacts", "artifacts");
            item(menu, "Modèle et outils", "models");
            item(menu, "Utilisation", "stats");
            item(menu, "Vue de discussion d’origine", "native_thread");
        } else {
            item(menu, "Nouvelle discussion", "new_thread");
        }
        menu.show();
    }

    private void item(PopupMenu menu, String label, String id) {
        menu.getMenu().add(label).setOnMenuItemClickListener(item -> {
            if (overflow != menu) return false;
            action.accept(id);
            return true;
        });
    }

    void dismissMenu() {
        PopupMenu previous = overflow;
        overflow = null;
        if (previous != null) previous.dismiss();
    }

    private void subtitle(LinearLayout list, String text) {
        TextView note = Ui.text(activity, text, 15);
        note.setTextColor(activity.getColor(R.color.muted));
        note.setPaddingRelative(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 8));
        list.addView(note);
    }

    private void section(LinearLayout list, String name) {
        TextView section = Ui.text(activity, name, 24);
        section.setTypeface(Typeface.SERIF);
        section.setPaddingRelative(0, Ui.dp(activity, 18), 0, Ui.dp(activity, 10));
        section.setAccessibilityHeading(true);
        list.addView(section);
    }

    private void link(LinearLayout list, String label, String hint, String id) {
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(Ui.dp(activity, 53));
        row.setPaddingRelative(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
        row.setOnClickListener(v -> action.accept(id));
        row.setFocusable(true);
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setContentDescription(hint == null || hint.isEmpty() ? label : label + ". " + hint);
        LinearLayout copy = Ui.column(activity);
        TextView text = Ui.text(activity, label, 16);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        copy.addView(text);
        if (hint != null && !hint.isEmpty()) {
            TextView detail = Ui.text(activity, hint, 14);
            detail.setTextColor(activity.getColor(R.color.muted));
            detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            copy.addView(detail);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_navigate_forward);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, Ui.dp(activity, 18), Ui.dp(activity, 18));
        list.addView(row);
        list.addView(Ui.divider(activity));
    }
}
