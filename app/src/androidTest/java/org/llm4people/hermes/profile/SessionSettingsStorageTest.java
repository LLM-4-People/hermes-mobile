package org.llm4people.hermes.profile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.UUID;
import org.llm4people.hermes.R;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.panel.PickerWindow;
import org.llm4people.hermes.panel.SessionPanel;

@SuppressWarnings("deprecation")
public final class SessionSettingsStorageTest extends InstrumentationTestCase {
    public void testFailedToolSelectionsShowCommittedStateAndRemainRetryable() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        String name = "test.policy." + UUID.randomUUID();
        SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        File xml = new File(context.getApplicationInfo().dataDir, "shared_prefs/" + name + ".xml");
        File backup = new File(xml + ".bak");
        File original = new File(xml + ".original");
        File blocker = new File(xml, "block");
        String initial = "{\"provider\":\"vendor\",\"model\":\"model\",\"allowed_toolsets\":[\"coding\"],"
                + "\"excluded_tools\":[\"edit\"]}";
        String capabilities = "{\"features\":{\"run_toolset_selection\":true,\"run_tool_exclusions\":true}}";
        String catalog = "{\"data\":[{\"name\":\"coding\",\"enabled\":true,\"tools\":[\"read\",\"edit\"]},"
                + "{\"name\":\"web\",\"enabled\":true,\"tools\":[\"search\"]}]}";
        try (Exchange server = new Exchange("200 OK", "", capabilities, catalog);
             Account account = new Account(name, name, server.endpoint(), "test", prefs, new byte[Account.KEY_BYTES])) {
            account.write("panel/options/thread", initial);
            assertTrue(xml.renameTo(original));
            // Refuse writes to this test record even after SharedPreferences updates its memory cache.
            assertTrue(xml.mkdir());
            assertTrue(blocker.createNewFile());
            assertTrue(backup.mkdir());
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            Activity activity = getInstrumentation().startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SessionPanel panel = new SessionPanel(activity, new HermesApi(server.endpoint(), "test"), account);
            try {
                runTestOnUiThread(() -> panel.show("thread", "thread"));
                server.observed();
                long end = SystemClock.elapsedRealtime() + 5000;
                boolean[] ready = {false};
                while (!ready[0] && SystemClock.elapsedRealtime() < end) {
                    runTestOnUiThread(() -> ready[0] = !matching("coding").isEmpty());
                    if (!ready[0]) SystemClock.sleep(30);
                }
                assertTrue("Tool list never appeared", ready[0]);
                runTestOnUiThread(() -> {
                    String defaults = activity.getString(R.string.panel_tools_defaults);
                    String error = activity.getString(R.string.panel_storage_error);
                    rejected(defaults, false, error);
                    rejected("coding", true, error);
                    rejected("web", false, error);
                    matching(activity.getString(R.string.panel_profile_default)).get(0).performClick();
                    assertEquals(1, matching(error).size());
                    assertEquals(1, matching("vendor · model").size());
                    assertEquals(1, matching(activity.getString(R.string.panel_excluded, "edit")).size());
                    matching(activity.getString(R.string.panel_choose_tools, "coding")).get(0).performClick();
                    rejected("read", true, error);
                    rejected("edit", false, error);
                });
                assertEquals(initial, account.read("panel/options/thread"));
                assertTrue(blocker.delete() && xml.delete() && backup.delete() && original.renameTo(xml));
                runTestOnUiThread(() -> {
                    CheckBox edit = (CheckBox) matching("edit").get(0);
                    edit.performClick();
                    assertTrue(edit.isChecked());
                    assertTrue(matching(activity.getString(R.string.panel_storage_error)).isEmpty());
                });
                assertFalse(SessionPanel.runOptions(context, account, "thread").has("excluded_tools"));
            } finally {
                runTestOnUiThread(() -> { panel.close(); activity.finish(); });
            }
        } finally {
            blocker.delete();
            xml.delete();
            backup.delete();
            original.delete();
            context.deleteSharedPreferences(name);
        }
    }

    public void testRejectedModelChoiceRestoresTheRadioAndCanBeRetriedThroughSearch() throws Throwable {
        if (Build.VERSION.SDK_INT >= 29) exerciseModelPicker(null);
    }

    public void testSearchAndFilteredModelRemainReachableInBothCompactKeyboardViewports() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        exerciseModelPicker(false);
        exerciseModelPicker(true);
    }

    public void testBadgeDecoratedModelRowExposesModelIdContentDescription() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        AccountIndexCommitTest.IndexContext storage = new AccountIndexCommitTest.IndexContext(context);
        String features = "{\"features\":{}}";
        String catalog = "{\"data\":[]}";
        JSONObject provider = new JSONObject().put("name", "vendor").put("slug", "vendor")
                .put("models", new JSONArray().put("Alpha").put("Beta"))
                .put("capabilities", new JSONObject().put("Alpha", new JSONObject().put("reasoning", true)));
        String routes = new JSONObject().put("providers", new JSONArray().put(provider)).toString();
        try (Exchange server = new Exchange("200 OK", "", features, catalog, routes, features, catalog);
             Account owner = new Account("model-badge-test", "Profil", server.endpoint(), "test", storage.index,
                     new byte[Account.KEY_BYTES])) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            Activity activity = getInstrumentation().startActivitySync(launch);
            SessionPanel panel = new SessionPanel(activity, new HermesApi(server.endpoint(), "test"), owner);
            try {
                runTestOnUiThread(() -> {
                    panel.show("thread", "thread");
                    View choice = matching(activity.getString(R.string.panel_route)).get(0);
                    while (!choice.isClickable()) choice = (View) choice.getParent();
                    assertTrue(choice.performClick());
                });
                CheckedTextView vendor = awaitChoice("vendor", 1);
                runTestOnUiThread(() -> tapChoice(vendor));
                CheckedTextView alpha = awaitChoice("Alpha\nRéflexion", 2);
                runTestOnUiThread(() -> {
                    assertEquals("Alpha", String.valueOf(alpha.getContentDescription()));
                    tapChoice(alpha);
                    assertEquals("Alpha", SessionPanel.runOptions(context, owner, "thread").optString("model"));
                });
            } finally {
                runTestOnUiThread(() -> { panel.close(); activity.finish(); });
            }
        }
    }

    private void exerciseModelPicker(Boolean landscape) throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AccountIndexCommitTest.IndexContext storage = new AccountIndexCommitTest.IndexContext(context);
        String features = "{\"features\":{}}";
        String catalog = "{\"data\":[]}";
        String name = landscape == null ? "vendor"
                : String.join("\n", java.util.Collections.nCopies(20, "Atelier de recherche et de documentation"));
        String providerLabel = name.equals("vendor") ? name : name + " · vendor";
        JSONObject provider = new JSONObject().put("name", name).put("slug", "vendor")
                .put("models", new JSONArray().put("Alpha").put("Beta"));
        String routes = new JSONObject().put("providers", new JSONArray().put(provider)).toString();
        try (Exchange server = new Exchange("200 OK", "", features, catalog, routes, features, catalog);
             Account owner = new Account("model-test", "Profil", server.endpoint(), "test", storage.index,
                     new byte[Account.KEY_BYTES])) {
            owner.write("panel/options/thread", "{\"provider\":\"vendor\",\"model\":\"Alpha\"}");
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            Activity activity = getInstrumentation().startActivitySync(launch);
            SessionPanel panel = new SessionPanel(activity, new HermesApi(server.endpoint(), "test"), owner);
            try {
                runTestOnUiThread(() -> {
                    if (landscape == null) panel.show("thread", "thread");
                    else PickerWindow.open(panel, landscape);
                    View choice = matching(activity.getString(R.string.panel_route)).get(0);
                    while (!choice.isClickable()) choice = (View) choice.getParent();
                    assertTrue(choice.performClick());
                });
                CheckedTextView vendor = awaitChoice(providerLabel, 1);
                runTestOnUiThread(() -> tapChoice(vendor));
                CheckedTextView beta = awaitChoice("Beta", 2);
                runTestOnUiThread(() -> {
                    ListView list = (ListView) beta.getParent();
                    if (landscape != null) PickerWindow.check(list, searchField(list), landscape, null);
                    storage.failNext = true;
                    tapChoice((CheckedTextView) list.getChildAt(1));
                    assertEquals(0, list.getCheckedItemPosition());
                    assertRadio((CheckedTextView) list.getChildAt(0), true);
                    assertRadio((CheckedTextView) list.getChildAt(1), false);
                    assertEquals(1, matching(activity.getString(R.string.panel_storage_error)).size());
                    searchField(list).setText("Bet");
                });
                CheckedTextView filtered = awaitChoice("Beta", 1);
                runTestOnUiThread(() -> {
                    ListView list = (ListView) filtered.getParent();
                    storage.failNext = true;
                    tapChoice(filtered);
                    assertEquals(ListView.INVALID_POSITION, list.getCheckedItemPosition());
                    assertRadio(filtered, false);
                    assertEquals("Alpha", SessionPanel.runOptions(context, owner, "thread").optString("model"));
                    if (landscape == null) tapChoice((CheckedTextView) list.getChildAt(0));
                    else PickerWindow.check(list, searchField(list), landscape, SessionSettingsStorageTest::tapChoice);
                    assertEquals("Beta", SessionPanel.runOptions(context, owner, "thread").optString("model"));
                });
                assertTrue(server.observed().contains("/api/model/options?include_unconfigured=false"));
            } finally {
                runTestOnUiThread(() -> { panel.close(); activity.finish(); });
            }
        }
    }

    private static EditText searchField(ListView list) {
        for (View view : list.getRootView().getFocusables(View.FOCUS_FORWARD))
            if (view instanceof EditText field) return field;
        throw new AssertionError("Missing model search field");
    }

    private CheckedTextView awaitChoice(String label, int count) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 5000;
        CheckedTextView[] found = {null};
        do {
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                for (View view : matching(label)) {
                    if (view instanceof CheckedTextView option && option.getParent() instanceof ListView list
                            && list.getCount() == count && list.getChildCount() == count) found[0] = option;
                }
            });
            if (found[0] != null) return found[0];
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Missing laid-out model choice " + label);
    }

    private static void tapChoice(View row) {
        ListView list = (ListView) row.getParent();
        int position = list.getPositionForView(row);
        assertTrue(list.performItemClick(row, position, list.getItemIdAtPosition(position)));
    }

    private static void assertRadio(CheckedTextView row, boolean checked) {
        assertEquals(checked, row.isChecked());
        AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
        try { assertEquals(checked, node.isChecked()); }
        finally { node.recycle(); }
    }

    private void rejected(String label, boolean committed, String error) {
        CheckBox view = (CheckBox) matching(label).get(0);
        assertEquals(label, committed, view.isChecked());
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(label, committed, view.isChecked());
        assertTrue(label, view.isEnabled());
        assertEquals(label, committed, view.createAccessibilityNodeInfo().isChecked());
        assertEquals(1, matching(error).size());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, matching(error).get(0).getAccessibilityLiveRegion());
    }

    private static ArrayList<View> matching(String label) {
        ArrayList<View> matches = new ArrayList<>();
        for (View window : WindowInspector.getGlobalWindowViews())
            window.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT);
        matches.removeIf(view -> !(view instanceof TextView) || !label.contentEquals(((TextView) view).getText()));
        return matches;
    }
}
