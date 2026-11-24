package org.llm4people.hermes.palette;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.templates.WorkflowTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public final class CommandPaletteTest extends InstrumentationTestCase {
    public void testDefaultCatalogSecureDialogFilteringAndSelection() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<String> dispatched = new AtomicReference<>();
        AtomicReference<String> stagedDraft = new AtomicReference<>();
        AtomicReference<String> openedThread = new AtomicReference<>();

        List<CommandItem> actions = CommandRegistry.defaultNativeCommands(context, dispatched::set);
        assertEquals(21, actions.size());
        assertTrue(actions.stream().anyMatch(item -> "act:settings".equals(item.id)));
        assertTrue(actions.stream().anyMatch(item -> "act:appearance_settings".equals(item.id)));
        assertFalse(actions.stream().anyMatch(item -> "act:admin_console".equals(item.id)));
        List<CommandItem> adminActions = CommandRegistry.defaultNativeCommands(context, true, dispatched::set);
        assertEquals(23, adminActions.size());
        assertTrue(adminActions.stream().anyMatch(item -> "act:admin_console".equals(item.id)));

        CommandRegistry registry = new CommandRegistry(actions);
        registry.addAll(CommandRegistry.fromTemplates(WorkflowTemplate.builtIn(context), stagedDraft::set));
        registry.addAll(CommandRegistry.fromRecentThreads(
                List.of(new CommandRegistry.RecentThread("sess-42", "Revue architecture", "Notes")),
                openedThread::set));

        // Accent-insensitive filtering across actions, templates, and recent threads.
        assertFalse(registry.search("memoire").isEmpty());
        assertFalse(registry.search("securite").isEmpty());
        assertFalse(registry.search("revue architecture").isEmpty());

        Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        try {
            AtomicReference<CommandPalette> paletteRef = new AtomicReference<>();
            AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
            runTestOnUiThread(() -> {
                CommandPalette palette = new CommandPalette(activity, registry, () -> true);
                paletteRef.set(palette);
                dialogRef.set(palette.show());
            });
            getInstrumentation().waitForIdleSync();

            AlertDialog dialog = dialogRef.get();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);

            int minTouchPx = Ui.dp(activity, 48);
            runTestOnUiThread(() -> {
                View root = dialog.getWindow().getDecorView();
                EditText search = findFirst(root, EditText.class);
                assertNotNull(search);
                assertTrue("Search field must be >= 48dp", search.getMinimumHeight() >= minTouchPx);

                // Filter to the recent thread and select it.
                search.setText("revue architecture");
                List<View> rows = findClickableRows(root);
                assertEquals(1, rows.size());
                assertTrue("Row must be >= 48dp", rows.get(0).getMinimumHeight() >= minTouchPx);
                rows.get(0).performClick();
            });
            getInstrumentation().waitForIdleSync();

            assertEquals("sess-42", openedThread.get());
            assertFalse(paletteRef.get().isShowing());

            // Verify template execution stages draft without sending and closes dialog.
            runTestOnUiThread(() -> {
                AlertDialog second = paletteRef.get().show();
                assertNotNull(second);
                EditText search = findFirst(second.getWindow().getDecorView(), EditText.class);
                assertNotNull(search);
                search.setText(WorkflowTemplate.builtIn(context).get(0).title);
                List<View> rows = findClickableRows(second.getWindow().getDecorView());
                assertFalse(rows.isEmpty());
                rows.get(0).performClick();
            });
            getInstrumentation().waitForIdleSync();

            assertEquals(WorkflowTemplate.builtIn(context).get(0).draft, stagedDraft.get());
            assertFalse(paletteRef.get().isShowing());
        } finally {
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
        }
    }

    private static <T extends View> T findFirst(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findFirst(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<View> findClickableRows(View root) {
        List<View> out = new ArrayList<>();
        collectClickableRows(root, out);
        return out;
    }

    private static void collectClickableRows(View view, List<View> out) {
        if (view instanceof android.widget.LinearLayout && view.isClickable() && view.getContentDescription() != null) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectClickableRows(group.getChildAt(i), out);
            }
        }
    }
}
