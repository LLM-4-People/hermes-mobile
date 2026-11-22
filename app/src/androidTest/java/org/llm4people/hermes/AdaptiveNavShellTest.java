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

                // All 4 primary destinations are present and clickable.
                assertNotNull(shell.root.findViewById(R.id.nav_tab_discussions));
                assertNotNull(shell.root.findViewById(R.id.nav_tab_atelier));
                assertNotNull(shell.root.findViewById(R.id.nav_tab_brain));
                assertNotNull(shell.root.findViewById(R.id.nav_tab_activity));
                assertTrue(shell.root.findViewById(R.id.nav_tab_brain).performClick());
                assertEquals("nav_brain", actions.get(actions.size() - 1));
                assertTrue(shell.root.findViewById(R.id.nav_tab_activity).performClick());
                assertEquals("nav_activity", actions.get(actions.size() - 1));

                // Top bar 1-tap Search and Profile/Lock buttons have >= 48dp touch targets.
                View search = shell.root.findViewById(R.id.top_search_action);
                View lock = shell.root.findViewById(R.id.top_profile_lock_action);
                assertNotNull(search);
                assertNotNull(lock);
                assertTrue(search.getWidth() >= Ui.dp(activity, 48));
                assertTrue(lock.getWidth() >= Ui.dp(activity, 48));
                assertTrue(search.performClick());
                assertEquals("search", actions.get(actions.size() - 1));
                assertTrue(lock.performClick());
                assertEquals("security", actions.get(actions.size() - 1));

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
            runTestOnUiThread(activity::finish);
        }
    }

    public void testOverflowMenuIsShrunkToThreadContextualActionsOnly() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        try {
            runTestOnUiThread(() -> {
                WorkspaceShell shell = new WorkspaceShell(activity, id -> {}, () -> false);
                activity.setContentView(shell.root);
                shell.selected("t1", "Fil courant");

                ArrayList<View> more = new ArrayList<>();
                shell.root.findViewsWithText(more, "Autres actions", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertEquals(1, more.size());
                assertTrue(more.get(0).performClick());

                Menu menu = overflowMenu(shell);
                assertNotNull(menu);
                List<String> titles = new ArrayList<>();
                for (int i = 0; i < menu.size(); i++) {
                    titles.add(menu.getItem(i).getTitle().toString());
                }
                assertTrue(titles.contains("Modèle et outils"));
                assertTrue(titles.contains("Artefacts"));
                assertFalse("Rappels belongs in Activité tab", titles.contains("Rappels"));
                assertFalse("Mes écrans belongs in Atelier tab", titles.contains("Mes écrans"));
                assertFalse("Verrouiller belongs in top bar Profile/Lock", titles.contains("Verrouiller le profil"));
                shell.dismissMenu();
            });
        } finally {
            runTestOnUiThread(activity::finish);
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
