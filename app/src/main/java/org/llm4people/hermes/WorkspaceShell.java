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

import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.os.PermissionsPanel;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.presence.PresenceSettingsPanel;
import org.llm4people.hermes.templates.TemplatesPanel;

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
        leading = icon(R.drawable.ic_threads, activity.getString(R.string.ws_discussions));
        leading.setOnClickListener(v -> action.accept(
                pageTitle != null ? "previous" : canTogglePanes ? "toggle_discussions" : "threads"));
        header.addView(leading);
        heading = Ui.text(activity, activity.getString(R.string.ws_discussions), 20);
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

        ImageButton more = icon(R.drawable.ic_more, activity.getString(R.string.ws_more_actions));
        more.setOnClickListener(this::menu);
        header.addView(more);

        contentPane.addView(header);
        contentPane.addView(Ui.divider(activity));
        pending = Ui.button(activity, activity.getString(R.string.ws_reply_hermes), null);
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
        boolean rail = next == AdaptiveLayout.NavChrome.RAIL;
        boolean mounted = root.getChildCount() > 0;
        boolean mountedRail = root.getOrientation() == LinearLayout.HORIZONTAL;
        if (mounted && rail == mountedRail) {
            // BOTTOM_BAR <-> HIDDEN (IME) only toggles visibility: detaching contentPane would drop the
            // focused composer's InputConnection and bounce the keyboard closed.
            if (!rail) navDivider.setVisibility(next == AdaptiveLayout.NavChrome.BOTTOM_BAR ? View.VISIBLE : View.GONE);
            return;
        }
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
            link(list, activity.getString(R.string.ws_resume_discussion), null, "return");
            link(list, activity.getString(R.string.ws_artifacts), null, "artifacts");
            link(list, activity.getString(R.string.brain_files_title), null, BrainStudioRoutes.ROUTE_WORKSPACE_FILES);
            link(list, activity.getString(R.string.ws_model_and_tools), null, "models");
            link(list, activity.getString(R.string.ws_usage), null, "stats");
        }
        section(list, activity.getString(R.string.ws_workspace));
        link(list, activity.getString(R.string.templates_route_label),
                activity.getString(R.string.templates_route_hint), TemplatesPanel.ROUTE_ID);
        link(list, activity.getString(R.string.palette_title),
                activity.getString(R.string.palette_search_hint), "command_palette");
        link(list, activity.getString(R.string.ws_my_screens), null, "pages");
        link(list, activity.getString(R.string.ws_new_discussion), null, "new_thread");
        link(list, activity.getString(R.string.ws_all_discussions), null, "threads");
        link(list, activity.getString(R.string.widget_pin_action), null, "pin_widget");
        link(list, activity.getString(R.string.ws_restore_desk), null, "restore");
        section(list, accountName);
        link(list, activity.getString(R.string.activity_security_title), null, "security");
        link(list, activity.getString(R.string.ws_notifications), null, "notifications");
        link(list, activity.getString(R.string.language_action), null, "language");
        link(list, activity.getString(R.string.ws_lock_profile), null, "profiles");
        return scroll;
    }

    View brain() {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = centeredScroll(list);
        subtitle(list, activity.getString(R.string.brain_subtitle));
        section(list, activity.getString(R.string.nav_brain));
        link(list, activity.getString(R.string.brain_open_all_title),
                activity.getString(R.string.brain_open_all_hint), "brain");
        link(list, activity.getString(R.string.brain_memory_title),
                activity.getString(R.string.brain_memory_card_hint), BrainStudioRoutes.ROUTE_MEMORY);
        link(list, activity.getString(R.string.brain_notes_title),
                activity.getString(R.string.brain_notes_card_hint), BrainStudioRoutes.ROUTE_NOTES);
        link(list, activity.getString(R.string.brain_shared_with_me_title),
                activity.getString(R.string.brain_shared_card_hint),
                BrainStudioRoutes.ROUTE_SHARED_WITH_ME);
        link(list, activity.getString(R.string.brain_search_title),
                activity.getString(R.string.brain_search_card_hint), BrainStudioRoutes.ROUTE_SEARCH);
        link(list, activity.getString(R.string.brain_skills_title),
                activity.getString(R.string.brain_skills_hint), BrainStudioRoutes.ROUTE_SKILLS);
        link(list, activity.getString(R.string.brain_files_title),
                activity.getString(R.string.brain_files_card_hint),
                BrainStudioRoutes.ROUTE_WORKSPACE_FILES);
        link(list, activity.getString(R.string.brain_profile_defaults_title),
                activity.getString(R.string.brain_profile_defaults_card_hint),
                BrainStudioRoutes.ROUTE_PROFILE_DEFAULTS);
        link(list, activity.getString(R.string.brain_models_title),
                activity.getString(R.string.brain_models_hint), "models");
        link(list, activity.getString(R.string.brain_create_tool_title),
                activity.getString(R.string.brain_create_tool_hint), "create_instrument");
        return scroll;
    }

    View activitySurface(String securitySummary) {
        return activitySurface(securitySummary, null);
    }

    View activitySurface(String securitySummary, String updateBadge) {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = centeredScroll(list);
        subtitle(list, activity.getString(R.string.activity_subtitle));
        View notifBanner = PermissionsPanel.buildNotificationPromptBanner(
                activity, () -> action.accept(PermissionsPanel.ROUTE_ID));
        if (notifBanner != null) {
            LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, -2);
            bannerLp.topMargin = Ui.dp(activity, 8);
            bannerLp.bottomMargin = Ui.dp(activity, 8);
            list.addView(notifBanner, bannerLp);
        }
        section(list, activity.getString(R.string.nav_activity));
        link(list, activity.getString(R.string.activity_reminders_title),
                activity.getString(R.string.activity_reminders_hint), "reminders");
        link(list, activity.getString(R.string.activity_deliveries_title),
                activity.getString(R.string.activity_deliveries_hint), "deliveries");
        link(list, activity.getString(R.string.activity_notifications_title),
                activity.getString(R.string.activity_notifications_hint), "notifications");
        link(list, activity.getString(R.string.activity_push_policy_title),
                activity.getString(R.string.activity_push_policy_hint), "push_policy");
        link(list, activity.getString(R.string.ambient_route_label),
                activity.getString(R.string.ambient_route_hint), AmbientAccessPanel.ROUTE_ID);
        link(list, activity.getString(R.string.presence_route_label),
                activity.getString(R.string.presence_route_hint), PresenceSettingsPanel.ROUTE_ID);
        link(list, activity.getString(R.string.activity_usage_title),
                activity.getString(R.string.activity_usage_hint), "stats");
        link(list, activity.getString(R.string.activity_security_title), securitySummary, "security");
        link(list, activity.getString(R.string.perm_route_label),
                PermissionsPanel.summaryLine(activity), PermissionsPanel.ROUTE_ID);
        link(list, activity.getString(R.string.doctor_route_label),
                activity.getString(R.string.doctor_route_hint), ConnectionDoctorPanel.ROUTE_ID);
        String updateHint = updateBadge != null && !updateBadge.isEmpty()
                ? updateBadge : activity.getString(R.string.update_route_hint);
        link(list, activity.getString(R.string.update_route_label), updateHint,
                org.llm4people.hermes.update.UpdatePanel.ROUTE_ID);
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
        heading.setText(pageTitle != null ? pageTitle
                : threadTitle == null ? activity.getString(R.string.ws_discussions) : threadTitle);
        leading.setImageResource(pageTitle != null ? R.drawable.ic_back : R.drawable.ic_threads);
        String description = pageTitle != null ? activity.getString(R.string.ws_back_previous)
                : canTogglePanes ? activity.getString(listVisible ? R.string.ws_hide_discussions : R.string.ws_show_discussions)
                : activity.getString(R.string.ws_all_discussions);
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
        boolean atelierSurface = activity.getString(R.string.ws_desk).equals(pageTitle)
                || activity.getString(R.string.ws_my_screens).equals(pageTitle)
                || activity.getString(R.string.ws_custom_screen).equals(pageTitle);
        if (atelierSurface) {
            item(menu, activity.getString(R.string.ws_my_screens), "pages");
            item(menu, activity.getString(R.string.ws_restore_desk), "restore");
        } else if (threadTitle != null) {
            if (reading.getAsBoolean()) item(menu, activity.getString(R.string.read_stop_label), "stop_reading");
            else item(menu, activity.getString(R.string.read_reply_label), "read_reply");
            item(menu, activity.getString(R.string.instrument_create), "create_instrument");
            if (pageTitle == null) {
                item(menu, activity.getString(R.string.ws_attach_image), "attach_photo");
                if (org.llm4people.hermes.camera.ThreadCameraCapture.isAvailable(activity)) {
                    item(menu, activity.getString(R.string.ws_capture_photo), "capture_camera");
                }
                item(menu, activity.getString(R.string.ws_location_for_message), "nearby_area");
            }
            item(menu, activity.getString(R.string.ws_rename_discussion), "rename");
            item(menu, activity.getString(R.string.brain_thread_pin), BrainStudioRoutes.ACTION_PIN_THREAD);
            item(menu, activity.getString(R.string.brain_thread_fork), BrainStudioRoutes.ACTION_FORK_THREAD);
            item(menu, activity.getString(R.string.brain_thread_export), BrainStudioRoutes.ACTION_EXPORT_THREAD);
            item(menu, activity.getString(R.string.brain_share_action), BrainStudioRoutes.ACTION_SHARE_THREAD);
            item(menu, activity.getString(R.string.brain_files_title), BrainStudioRoutes.ROUTE_WORKSPACE_FILES);
            item(menu, activity.getString(R.string.ws_artifacts), "artifacts");
            item(menu, activity.getString(R.string.ws_model_and_tools), "models");
            item(menu, activity.getString(R.string.ws_usage), "stats");
            item(menu, activity.getString(R.string.ws_original_discussion_view), "native_thread");
        } else {
            item(menu, activity.getString(R.string.ws_new_discussion), "new_thread");
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
