package org.llm4people.hermes;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class WorkspaceOverflowTest extends InstrumentationTestCase {
    public void testRetainedModelActionCannotApplyToAnotherThreadWithTheSameTitle() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        List<String> actions = new ArrayList<>();
        try {
            WorkspaceShell[] holder = new WorkspaceShell[1];
            runTestOnUiThread(() -> {
                holder[0] = new WorkspaceShell(activity, actions::add, () -> false);
                activity.setContentView(holder[0].root);
                holder[0].selected("A", "Première discussion");
            });
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                WorkspaceShell shell = holder[0];
                Menu original = open(shell);
                shell.selected("A", "Même titre");
                assertSame(original, currentMenu(shell));
                shell.selected("B", "Même titre");
                assertNull(currentMenu(shell));
                assertFalse(selectModels(context, original));
                assertTrue(actions.isEmpty());
                Menu replacement = open(shell);
                assertTrue(selectModels(context, replacement));
                assertEquals(java.util.Collections.singletonList("models"), actions);
                shell.dismissMenu();
            });
        } finally {
            runTestOnUiThread(activity::finish);
        }
    }

    private static Menu open(WorkspaceShell shell) {
        ArrayList<View> controls = new ArrayList<>();
        shell.root.findViewsWithText(controls, shell.root.getContext().getString(R.string.ws_more_actions),
                View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        assertEquals(1, controls.size());
        assertTrue(controls.get(0).performClick());
        Menu menu = currentMenu(shell);
        assertNotNull(menu);
        return menu;
    }

    private static Menu currentMenu(WorkspaceShell shell) {
        try {
            Field field = WorkspaceShell.class.getDeclaredField("overflow");
            field.setAccessible(true);
            PopupMenu popup = (PopupMenu) field.get(shell);
            return popup == null ? null : popup.getMenu();
        } catch (ReflectiveOperationException missingMenu) {
            throw new AssertionError(missingMenu);
        }
    }

    private static boolean selectModels(Context context, Menu menu) {
        String label = context.getString(R.string.ws_model_and_tools);
        menu.setQwertyMode(true);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            CharSequence title = item.getTitle();
            if (label.contentEquals(title)) {
                item.setAlphabeticShortcut('m', 0);
                return menu.performShortcut(KeyEvent.KEYCODE_M,
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M), 0);
            }
        }
        throw new AssertionError("The connected thread must expose model settings");
    }
}
