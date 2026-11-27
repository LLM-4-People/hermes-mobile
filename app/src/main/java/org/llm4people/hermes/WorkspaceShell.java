package org.llm4people.hermes;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.settings.SettingsHubPanel;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Adaptive Material 3 navigation frame shared by Chats, Notes &amp; tasks, Apps, top-bar Settings,
 * and custom pages.
 */
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
    private final ImageButton settingsButton;
    private final ImageButton moreButton;
    private final AdaptiveNavBar navBar;
    private final View navDivider;
    private String pageTitle;
    private String threadId;
    private String threadTitle;
    private boolean canTogglePanes;
    private boolean listVisible;
    private boolean imeVisible;
    private boolean customMenuAllowed;
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
        header.setBackgroundColor(activity.getColor(R.color.surface));
        header.setMinimumHeight(Ui.dp(activity, 56));
        Ui.pad(header, 8, 4);
        leading = icon(R.drawable.ic_threads, activity.getString(R.string.ws_discussions));
        leading.setOnClickListener(v -> action.accept(
                pageTitle != null ? "previous" : canTogglePanes ? "toggle_discussions" : "threads"));
        header.addView(leading);
        heading = Ui.text(activity, activity.getString(R.string.ws_discussions), 20);
        heading.setTypeface(Ui.mediumTypeface());
        heading.setLetterSpacing(-0.01f);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        Ui.pad(heading, 8, 2);
        heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        searchButton = icon(R.drawable.ic_search, activity.getString(R.string.top_search));
        searchButton.setId(R.id.top_search_action);
        searchButton.setOnClickListener(v -> action.accept("search"));
        header.addView(searchButton);

        settingsButton = icon(R.drawable.ic_settings, activity.getString(R.string.nav_settings));
        settingsButton.setId(R.id.top_settings_action);
        settingsButton.setOnClickListener(v -> action.accept(SettingsHubPanel.ROUTE_ID));
        header.addView(settingsButton);

        moreButton = icon(R.drawable.ic_more, activity.getString(R.string.ws_more_actions));
        moreButton.setOnClickListener(this::menu);
        moreButton.setVisibility(View.GONE);
        header.addView(moreButton);

        contentPane.addView(header);
        contentPane.addView(Ui.divider(activity));
        pending = Ui.tonalButton(activity, "", null);
        pending.setTag("ws-pending-response");
        pending.setBackground(Ui.roundedRipple(activity, 0,
                Ui.shape(activity, 0, R.color.accent_surface, 0, 0)));
        pending.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        Ui.pad(pending, 18, 10);
        pending.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pending.setVisibility(View.GONE);
        contentPane.addView(pending, new LinearLayout.LayoutParams(-1, -2));
        body = new FrameLayout(activity);
        contentPane.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        navDivider = new View(activity);
        navDivider.setBackgroundColor(activity.getColor(R.color.line));
        navDivider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        navBar = new AdaptiveNavBar(activity, route -> {
            switch (route) {
                case WorkspaceNavigation.DISCUSSION -> action.accept("nav_discussions");
                case WorkspaceNavigation.NOTES_TASKS -> action.accept("nav_tasks");
                case WorkspaceNavigation.APPS -> action.accept("nav_apps");
                default -> action.accept("nav_discussions");
            }
        });

        android.content.res.Configuration config = activity.getResources().getConfiguration();
        applyChrome(AdaptiveLayout.navigationChrome(config.screenWidthDp, config.screenHeightDp, false));
    }

    AdaptiveNavBar navBar() { return navBar; }

    void setActiveRoute(String route) {
        navBar.setSelectedRoute(route);
        boolean inSettings = WorkspaceNavigation.SETTINGS.equals(WorkspaceNavigation.topDestination(route));
        settingsButton.setSelected(inSettings);
        settingsButton.setImageTintList(ColorStateList.valueOf(
                activity.getColor(inSettings ? R.color.accent : R.color.ink)));
        settingsButton.setBackground(Ui.roundedRipple(activity, 24,
                inSettings ? Ui.shape(activity, 24, R.color.accent_surface, 0, 0) : null));
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
        show(page, title, false);
    }

    void show(View page, String title, boolean allowCustomMenu) {
        pageTitle = title;
        customMenuAllowed = allowCustomMenu;
        updateHeading();
        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
        body.removeAllViews();
        body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    void pendingResponse(Runnable open) {
        if (open == null) {
            Ui.clearLiveStatus(pending);
            pending.setOnClickListener(null);
            return;
        }
        boolean wasVisible = pending.getVisibility() == View.VISIBLE;
        pending.setOnClickListener(v -> open.run());
        pending.setVisibility(View.VISIBLE);
        if (!wasVisible) {
            Ui.announceLiveStatus(pending, activity.getString(R.string.ws_reply_hermes));
        }
    }

    View content() { return body; }

    private boolean isDiscussionSurface() {
        return pageTitle == null || activity.getString(R.string.ws_original_discussion).equals(pageTitle);
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
        boolean showMore = customMenuAllowed || (isDiscussionSurface() && threadTitle != null);
        moreButton.setVisibility(showMore ? View.VISIBLE : View.GONE);
        if (!showMore) dismissMenu();
    }

    private ImageButton icon(int resource, String name) {
        ImageButton view = new ImageButton(activity);
        view.setBackground(Ui.roundedRipple(activity, 24, null));
        view.setImageResource(resource);
        view.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.ink)));
        view.setContentDescription(name);
        view.setTooltipText(name);
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        return view;
    }

    /** Overflow menu shown strictly for active chat actions or custom screen recovery. */
    private void menu(View anchor) {
        dismissMenu();
        if (anchor == null || !anchor.isAttachedToWindow() || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        PopupMenu menu = new PopupMenu(activity, anchor);
        overflow = menu;
        menu.setOnDismissListener(closed -> {
            if (overflow == closed) overflow = null;
        });
        if (customMenuAllowed) {
            item(menu, activity.getString(R.string.ws_my_screens), WorkspaceNavigation.GALLERY);
            item(menu, activity.getString(R.string.ws_restore_desk), "restore");
        } else if (isDiscussionSurface() && threadTitle != null) {
            if (reading.getAsBoolean()) item(menu, activity.getString(R.string.read_stop_label), "stop_reading");
            else item(menu, activity.getString(R.string.read_reply_label), "read_reply");
            item(menu, activity.getString(R.string.instrument_create), "create_instrument");
            item(menu, activity.getString(R.string.ws_attach_image), "attach_photo");
            if (org.llm4people.hermes.camera.ThreadCameraCapture.isAvailable(activity)) {
                item(menu, activity.getString(R.string.ws_capture_photo), "capture_camera");
            }
            item(menu, activity.getString(R.string.ws_location_for_message), "nearby_area");
            item(menu, activity.getString(R.string.ws_rename_discussion), "rename");
            item(menu, activity.getString(R.string.brain_thread_pin), BrainStudioRoutes.ACTION_PIN_THREAD);
            item(menu, activity.getString(R.string.brain_thread_fork), BrainStudioRoutes.ACTION_FORK_THREAD);
            item(menu, activity.getString(R.string.brain_thread_export), BrainStudioRoutes.ACTION_EXPORT_THREAD);
            item(menu, activity.getString(R.string.brain_share_action), BrainStudioRoutes.ACTION_SHARE_THREAD);
            item(menu, activity.getString(R.string.brain_files_title), BrainStudioRoutes.ROUTE_WORKSPACE_FILES);
            item(menu, activity.getString(R.string.ws_artifacts), "artifacts");
            item(menu, activity.getString(R.string.ws_model_and_tools), "models");
            item(menu, activity.getString(R.string.ws_usage), "stats");
            if (pageTitle == null) {
                item(menu, activity.getString(R.string.ws_original_discussion_view), "native_thread");
            }
        } else {
            return;
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
}
