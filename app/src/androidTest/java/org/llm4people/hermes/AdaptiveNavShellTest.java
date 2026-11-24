package org.llm4people.hermes;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.Menu;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class AdaptiveNavShellTest extends InstrumentationTestCase {
    public void testPhoneBottomBarHidesOnImeAndTabletUses72dpRail() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        List<String> actions = new ArrayList<>();
        try {
            runTestOnUiThread(() -> {
                WorkspaceShell shell = new WorkspaceShell(activity, actions::add, () -> false);
                activity.setContentView(shell.root);
                shell.selected("t1", "Projet mobile");

                // Phone width (< 720dp): Bottom Navigation Bar visible.
                int phoneW = Ui.dp(activity, 360);
                int phoneH = Ui.dp(activity, 780);
                shell.updateNavigationChrome(360f, 780f);
                measureAndLayout(shell.root, phoneW, phoneH);
                assertEquals(LinearLayout.VERTICAL, shell.root.getOrientation());
                assertEquals(AdaptiveLayout.NavChrome.BOTTOM_BAR, shell.navBar().mode());
                assertEquals(View.VISIBLE, shell.navBar().getVisibility());

                // All 3 living primary destinations are present and clickable.
                assertEquals(3, shell.navBar().getChildCount());
                assertNotNull(shell.root.findViewById(R.id.nav_tab_discussions));
                assertNotNull(shell.root.findViewById(R.id.nav_tab_tasks));
                assertNotNull(shell.root.findViewById(R.id.nav_tab_apps));
                assertTrue(shell.root.findViewById(R.id.nav_tab_tasks).performClick());
                assertEquals("nav_tasks", actions.get(actions.size() - 1));
                assertTrue(shell.root.findViewById(R.id.nav_tab_apps).performClick());
                assertEquals("nav_apps", actions.get(actions.size() - 1));

                // Top bar 1-tap Search and Settings buttons have >= 48dp touch targets.
                View search = shell.root.findViewById(R.id.top_search_action);
                View settings = shell.root.findViewById(R.id.top_settings_action);
                assertNotNull(search);
                assertNotNull(settings);
                assertTrue(search.getWidth() >= Ui.dp(activity, 48));
                assertTrue(settings.getWidth() >= Ui.dp(activity, 48));
                assertTrue(search.performClick());
                assertEquals("search", actions.get(actions.size() - 1));
                assertTrue(settings.performClick());
                assertEquals("settings", actions.get(actions.size() - 1));

                // Active Settings route highlights top-bar Settings button cleanly.
                shell.setActiveRoute(WorkspaceNavigation.SETTINGS);
                assertTrue(settings.isSelected());
                shell.setActiveRoute(WorkspaceNavigation.DISCUSSION);
                assertFalse(settings.isSelected());

                // IME visible on phone auto-hides bottom navigation bar so composer stays full-height.
                shell.setImeVisible(true);
                shell.updateNavigationChrome(360f, 420f);
                measureAndLayout(shell.root, phoneW, Ui.dp(activity, 420));
                assertEquals(AdaptiveLayout.NavChrome.HIDDEN, shell.navBar().mode());
                assertEquals(View.GONE, shell.navBar().getVisibility());

                // IME hidden restores bottom navigation bar.
                shell.setImeVisible(false);
                shell.updateNavigationChrome(360f, 780f);
                measureAndLayout(shell.root, phoneW, phoneH);
                assertEquals(AdaptiveLayout.NavChrome.BOTTOM_BAR, shell.navBar().mode());
                assertEquals(View.VISIBLE, shell.navBar().getVisibility());

                // Tablet width (>= 720dp): 72dp leading Navigation Rail stays visible even with IME open.
                shell.setImeVisible(true);
                int tabletW = Ui.dp(activity, 840);
                int tabletH = Ui.dp(activity, 1100);
                shell.updateNavigationChrome(840f, 1100f);
                measureAndLayout(shell.root, tabletW, tabletH);
                assertEquals(LinearLayout.HORIZONTAL, shell.root.getOrientation());
                assertEquals(AdaptiveLayout.NavChrome.RAIL, shell.navBar().mode());
                assertEquals(View.VISIBLE, shell.navBar().getVisibility());
                assertEquals(Ui.dp(activity, AdaptiveLayout.NAV_RAIL_WIDTH_DP), shell.navBar().getWidth(), 2);
            });
        } finally {
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
        }
    }

    public void testOverflowMenuIsShrunkToThreadContextualActionsOnly() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        try {
            WorkspaceShell[] holder = new WorkspaceShell[1];
            runTestOnUiThread(() -> {
                WorkspaceShell shell = new WorkspaceShell(activity, id -> {}, () -> false);
                holder[0] = shell;
                activity.setContentView(shell.root);
                // Non-chat surfaces hide More for a clean 2-icon top bar (View.GONE views are excluded by findViewsWithText).
                shell.show(new View(activity), activity.getString(R.string.hub_tasks_title));
                ArrayList<View> hiddenMore = new ArrayList<>();
                shell.root.findViewsWithText(hiddenMore, activity.getString(R.string.ws_more_actions),
                        View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertTrue("More overflow button must be hidden on non-chat surfaces", hiddenMore.isEmpty());

                // Active chat reveals contextual More overflow actions.
                shell.show(new View(activity), null);
                shell.selected("t1", "Fil courant");
            });
            NavShellTestWait.waitUntilOverflowAttached(this, activity, holder[0]);
            runTestOnUiThread(() -> {
                WorkspaceShell shell = holder[0];
                ArrayList<View> more = new ArrayList<>();
                shell.root.findViewsWithText(more, activity.getString(R.string.ws_more_actions),
                        View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertEquals(1, more.size());
                assertEquals(View.VISIBLE, more.get(0).getVisibility());
                assertTrue(more.get(0).isAttachedToWindow());
                assertTrue(more.get(0).performClick());

                Menu menu = overflowMenu(shell);
                assertNotNull(menu);
                List<String> titles = new ArrayList<>();
                for (int i = 0; i < menu.size(); i++) {
                    titles.add(menu.getItem(i).getTitle().toString());
                }
                assertTrue(titles.contains(activity.getString(R.string.ws_model_and_tools)));
                assertTrue(titles.contains(activity.getString(R.string.ws_artifacts)));
                assertFalse("Rappels belongs in Notes & tasks tab",
                        titles.contains(activity.getString(R.string.activity_reminders_title)));
                assertFalse("Mes écrans belongs in Apps tab",
                        titles.contains(activity.getString(R.string.ws_my_screens)));
                assertFalse("Verrouiller belongs in Settings hero card",
                        titles.contains(activity.getString(R.string.security_lock_now)));
                shell.dismissMenu();
            });
        } finally {
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
        }
    }

    private static void measureAndLayout(View root, int widthPx, int heightPx) {
        root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, widthPx, heightPx);
    }

    private static Menu overflowMenu(WorkspaceShell shell) {
        try {
            Field field = WorkspaceShell.class.getDeclaredField("overflow");
            field.setAccessible(true);
            PopupMenu popup = (PopupMenu) field.get(shell);
            return popup == null ? null : popup.getMenu();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
