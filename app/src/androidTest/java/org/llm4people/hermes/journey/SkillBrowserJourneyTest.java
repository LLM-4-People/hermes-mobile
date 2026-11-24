package org.llm4people.hermes.journey;

import android.content.Context;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.ListView;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.panel.PickerWindow;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

@SuppressWarnings("deprecation")
public final class SkillBrowserJourneyTest extends InstrumentationTestCase {
    private static final String JSON = "Content-Type: application/json\r\n";
    private static final String CAPS = "{\"features\":{}}";
    private static final String EMPTY = "{\"object\":\"list\",\"data\":[]}";
    private static final String CATALOG = """
            {"object":"list","data":[
              {"name":"travail/revue","description":"Vérifier les changements.","category":"Qualité"}]}
            """;

    public void testRefreshSearchAndSelectionStageOnlyIntoTheOriginalDraft() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        String transcript = ThreadNoticeContinuityTest.transcript("discussion", "discussion", "Relecture", new JSONArray());
        try {
            try (Exchange gateway = new Exchange("200 OK", JSON, transcript, CAPS, EMPTY, EMPTY, CATALOG);
                 WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
                profile.account.write("last-thread", "discussion");
                String policy = "{\"allowed_toolsets\":[\"skills\"]}";
                profile.account.write("panel/options/discussion", policy);
                Method open = MainActivity.class.getDeclaredMethod("opened", Account.class);
                open.setAccessible(true);
                MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(app.getPackageManager()
                        .getLaunchIntentForPackage(app.getPackageName()));
                try {
                    runTestOnUiThread(() -> {
                        try { open.invoke(activity, profile.account); }
                        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                    });
                    JourneyUi ui = new JourneyUi(getInstrumentation(), activity, app.getCacheDir());
                    ui.see(activity.getString(R.string.starter_heading));
                    ui.fill(activity.getString(R.string.thread_message_hint), "Mon brouillon");
                    runTestOnUiThread(() -> activity.onAction("models"));
                    ui.tap(activity.getString(R.string.skills_title));
                    ui.see(activity.getString(R.string.skills_empty));
                    ui.tap(activity.getString(R.string.skills_refresh));
                    ui.see("travail/revue");
                    ui.fill(activity.getString(R.string.skills_search), "absente");
                    ui.see(activity.getString(R.string.skills_no_matches));
                    ui.fill(activity.getString(R.string.skills_search), "qualite");
                    ui.tap("travail/revue");
                    ui.contains(activity.getString(R.string.skills_category, "Qualité"), "Vérifier les changements.");
                    ui.tap(activity.getString(R.string.skills_prepare));
                    ui.see("Mon brouillon\n" + activity.getString(R.string.skills_request, "travail/revue"));
                    assertEquals(policy, profile.account.read("panel/options/discussion"));
                    assertEquals(2, gateway.observed().split("GET /p/travail/v1/skills HTTP/", -1).length - 1);
                    assertFalse(gateway.observed().contains("POST "));
                    assertTrue(gateway.observed().contains("Authorization: Bearer fixture"));
                } finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testSkillsSearchAndFirstMatchRemainUsableAboveCompactKeyboards() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        try {
            try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app)) {
                MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(app.getPackageManager()
                        .getLaunchIntentForPackage(app.getPackageName()));
                try {
                    JourneyUi ui = new JourneyUi(getInstrumentation(), activity, app.getCacheDir());
                    for (boolean landscape : new boolean[]{false, true}) {
                        try (Exchange gateway = new Exchange("200 OK", JSON, CAPS, EMPTY, CATALOG)) {
                            SessionPanel panel = new SessionPanel(activity, new HermesApi(gateway.endpoint(), "test"), profile.account);
                            try {
                                runTestOnUiThread(() -> PickerWindow.open(panel, landscape, draft -> fail("Unexpected draft")));
                                ui.tap(activity.getString(R.string.skills_title));
                                ui.fill(activity.getString(R.string.skills_search), "qualite");
                                ui.see("travail/revue");
                                runTestOnUiThread(() -> checkSearchWindow(landscape));
                                ui.see(activity.getString(R.string.skills_prepare));
                            } finally { runTestOnUiThread(panel::close); }
                        }
                    }
                } finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    private void checkSearchWindow(boolean landscape) {
        for (View window : WindowInspector.getGlobalWindowViews()) {
            EditText search = window.findViewById(R.id.skill_search_field);
            if (search == null || !search.isShown()) continue;
            EditorInfo editor = new EditorInfo();
            assertNotNull(search.onCreateInputConnection(editor));
            assertEquals(EditorInfo.IME_ACTION_SEARCH, editor.imeOptions & EditorInfo.IME_MASK_ACTION);
            int required = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            assertEquals(required, editor.imeOptions & required);
            ListView list = window.findViewById(R.id.skill_results);
            PickerWindow.check(list, search, landscape, row ->
                    assertTrue(list.performItemClick(row, 0, list.getItemIdAtPosition(0))));
            return;
        }
        fail("No live skills search field");
    }

    public void testChangedThreadDiscardsAnOpenSkillAndUnsupportedListingHasRecovery() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicReference<String> staged = new AtomicReference<>();
        try {
            try (Exchange gateway = new Exchange("200 OK", JSON, CAPS, EMPTY, CATALOG);
                 Exchange missing = new Exchange("404 Not Found", JSON, "{}", "{}");
                 WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app)) {
                MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(app.getPackageManager()
                        .getLaunchIntentForPackage(app.getPackageName()));
                SessionPanel first = null;
                SessionPanel second = null;
                try {
                    JourneyUi ui = new JourneyUi(getInstrumentation(), activity, app.getCacheDir());
                    first = new SessionPanel(activity, new HermesApi(gateway.endpoint(), "test"), profile.account);
                    second = new SessionPanel(activity, new HermesApi(missing.endpoint(), "test"), profile.account);
                    SessionPanel firstPanel = first;
                    SessionPanel secondPanel = second;
                    runTestOnUiThread(() -> firstPanel.show("one", "one", active::get, staged::set));
                    ui.tap(activity.getString(R.string.skills_title));
                    ui.tap("travail/revue");
                    ui.see(activity.getString(R.string.skills_prepare));
                    active.set(false);
                    ui.tap(activity.getString(R.string.skills_prepare));
                    assertNull(staged.get());
                    runTestOnUiThread(() -> secondPanel.show("two", "two", () -> true, staged::set));
                    ui.tap(activity.getString(R.string.skills_title));
                    ui.see(activity.getString(R.string.skills_missing));
                    ui.see(activity.getString(R.string.skills_refresh));
                    assertNull(staged.get());
                } finally {
                    SessionPanel closeFirst = first;
                    SessionPanel closeSecond = second;
                    try {
                        runTestOnUiThread(() -> {
                            if (closeFirst != null) closeFirst.close();
                            if (closeSecond != null) closeSecond.close();
                        });
                    } finally {
                        ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                    }
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }
}
