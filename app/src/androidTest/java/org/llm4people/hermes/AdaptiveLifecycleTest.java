package org.llm4people.hermes;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.thread.ThreadWorkspace;

@SuppressWarnings("deprecation")
public final class AdaptiveLifecycleTest extends InstrumentationTestCase {
    public void testMainActivityHandlesGeometryConfigChangesWithoutSwallowingThemeOrFontScale() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ActivityInfo info = context.getPackageManager().getActivityInfo(
                new ComponentName(context, MainActivity.class), 0);
        int required = ActivityInfo.CONFIG_ORIENTATION
                | ActivityInfo.CONFIG_SCREEN_SIZE
                | ActivityInfo.CONFIG_SCREEN_LAYOUT
                | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
                | ActivityInfo.CONFIG_KEYBOARD
                | ActivityInfo.CONFIG_KEYBOARD_HIDDEN
                | ActivityInfo.CONFIG_NAVIGATION;
        assertEquals("MainActivity must handle geometry/keyboard config changes in place",
                required, info.configChanges & required);
        int recreate = ActivityInfo.CONFIG_UI_MODE | ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_DENSITY;
        assertEquals("Theme, font scale and density must recreate to reload resources cleanly",
                0, info.configChanges & recreate);
    }

    public void testInPlaceConfigurationChangeKeepsUnlockedWorkspaceAndDraftIntact() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        String transcript = new JSONObject()
                .put("thread_id", "root")
                .put("session_id", "root")
                .put("thread_title", "Discussion adaptative")
                .put("data", new JSONArray())
                .put("has_more", false)
                .put("total", 0)
                .toString();
        try (Exchange server = new Exchange("200 OK", "Content-Type: application/json\r\n", transcript,
                "{\"data\":[],\"has_more\":false}");
             WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context, server.endpoint())) {
            fixture.account.write("last-thread", "root");
            Method open = MainActivity.class.getDeclaredMethod("opened", Account.class);
            open.setAccessible(true);
            Field threadsField = MainActivity.class.getDeclaredField("threads");
            threadsField.setAccessible(true);
            try {
                runTestOnUiThread(() -> {
                    try { open.invoke(activity, fixture.account); }
                    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                getInstrumentation().waitForIdleSync();
                runTestOnUiThread(() -> {
                    try {
                        ThreadWorkspace before = (ThreadWorkspace) threadsField.get(activity);
                        assertNotNull(before);
                        EditText composer = findComposer(activity.getWindow().getDecorView());
                        assertNotNull(composer);
                        composer.setText("Brouillon conservé après rotation");
                        composer.setSelection(10, 18);

                        Configuration next = new Configuration(activity.getResources().getConfiguration());
                        next.orientation = next.orientation == Configuration.ORIENTATION_LANDSCAPE
                                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
                        activity.onConfigurationChanged(next);

                        assertSame(before, threadsField.get(activity));
                        assertSame(composer, findComposer(activity.getWindow().getDecorView()));
                        assertEquals("Brouillon conservé après rotation", composer.getText().toString());
                        assertEquals(10, composer.getSelectionStart());
                        assertEquals(18, composer.getSelectionEnd());
                    } catch (IllegalAccessException error) {
                        throw new AssertionError(error);
                    }
                });
            } finally {
                runTestOnUiThread(activity::finish);
            }
        }
    }

    public void testWorkspaceShellSwitchesLeadingToggleActionAndCentersAtelierOnWideWindows() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        List<String> actions = new ArrayList<>();
        try {
            runTestOnUiThread(() -> {
                WorkspaceShell shell = new WorkspaceShell(activity, actions::add, () -> false);
                activity.setContentView(shell.root);
                shell.selected("root", "Discussion");

                ImageButton leading = findLeading(shell.root, activity.getString(R.string.ws_all_discussions));
                assertNotNull(leading);
                assertTrue(leading.performClick());
                assertEquals("threads", actions.get(actions.size() - 1));

                shell.discussionPanes(true, true);
                assertEquals(activity.getString(R.string.ws_hide_discussions), leading.getContentDescription());
                assertTrue(leading.isActivated());
                assertTrue(leading.performClick());
                assertEquals("toggle_discussions", actions.get(actions.size() - 1));

                shell.discussionPanes(true, false);
                assertEquals(activity.getString(R.string.ws_show_discussions), leading.getContentDescription());
                assertFalse(leading.isActivated());

                View desk = shell.desk("Profil test");
                int wide = Ui.dp(activity, 1200);
                int tall = Ui.dp(activity, 800);
                desk.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY));
                desk.layout(0, 0, wide, tall);
                View list = ((ScrollView) desk).getChildAt(0);
                assertEquals(Ui.dp(activity, 260), list.getPaddingStart(), 2);

                int narrow = Ui.dp(activity, 360);
                desk.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY));
                desk.layout(0, 0, narrow, tall);
                assertEquals(Ui.dp(activity, 22), list.getPaddingStart(), 2);
            });
        } finally {
            runTestOnUiThread(activity::finish);
        }
    }

    private static EditText findComposer(View root) {
        if (root instanceof EditText edit && root.getContext().getString(R.string.thread_message_hint).contentEquals(edit.getHint())) return edit;
        if (root instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findComposer(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ImageButton findLeading(View root, String description) {
        if (root instanceof ImageButton button && description.contentEquals(button.getContentDescription())) return button;
        if (root instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageButton found = findLeading(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
