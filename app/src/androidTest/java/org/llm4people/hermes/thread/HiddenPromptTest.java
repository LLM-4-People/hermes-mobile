package org.llm4people.hermes.thread;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise the protected shell and both native-content layout modes with a disposable account. */
@SuppressWarnings("deprecation")
public class HiddenPromptTest extends InstrumentationTestCase {
    private static final String ARRANGED = "{\"version\":1,\"surface\":\"thread\",\"title\":\"Fil\","
            + "\"blocks\":[{\"type\":\"content\",\"slots\":[\"notice\",\"composer\"]}]}";
    private static final String REPLACED = "{\"version\":1,\"surface\":\"thread\",\"mode\":\"replace\",\"title\":\"Fil\","
            + "\"blocks\":[{\"type\":\"section\",\"title\":\"Accueil\",\"note\":\"Bienvenue\"}]}";

    public void testOmittedTranscriptAndReplacementKeepPendingResponseInProtectedChrome() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        try {
            try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(context)) {
                MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(launch);
                try {
                    runTestOnUiThread(() -> {
                        try { checkSurfaces(activity, profile.account); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testReadingHistoryOffersAnInPlaceResponseUntilTheCurrentCardIsVisible() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        JSONArray history = new JSONArray();
        for (int i = 0; i < ThreadData.PAGE; ++i)
            history.put(new JSONObject().put("id", "line-" + i).put("role", "user").put("content", "Message de lecture " + i));
        String transcript = new JSONObject().put("data", history).put("session_id", "discussion")
                .put("thread_title", "Discussion").put("pagination", new JSONObject()
                        .put("visible_only", true).put("total", history.length())).toString();
        try {
            try (Exchange gateway = new Exchange("200 OK", "Content-Type: application/json\r\n",
                         transcript, "{\"data\":[],\"has_more\":false}");
                 WorkspaceAccountFixture profile = new WorkspaceAccountFixture(context, gateway.endpoint())) {
                MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(launch);
                try {
                    ThreadWorkspace page = main(() -> selectThread(activity, profile.account));
                    ListView list = page.screen.list;
                    String tail = history.getJSONObject(history.length() - 1).getString("content");
                    awaitUi(list, "hydrated transcript tail", () -> list.isLaidOut() && !list.isLayoutRequested()
                            && page.messages().length() == history.length() && visibleText(list, tail));
                    RunDecisions actions = main(() -> {
                        page.screen.displayDraft("Mon brouillon");
                        page.journal.observe("pending-approval");
                        RunDecisions decisions = new RunDecisions(page);
                        decisions.observe(RunAction.capture(page.journal, page::token));
                        return decisions;
                    });
                    Button reveal = main(() -> button(activity, activity.getWindow().getDecorView()));
                    JSONObject anchor = history.getJSONObject(6);
                    String label = anchor.getString("content");
                    int position = main(() -> {
                        for (int i = 0; i < list.getCount(); ++i) {
                            if (list.getItemAtPosition(i) instanceof JSONObject row && anchor.getString("id").equals(row.opt("id"))) {
                                list.setSelectionFromTop(i, 0);
                                return i;
                            }
                        }
                        throw new AssertionError("Missing seeded message " + label);
                    });
                    awaitUi(list, label + " at the top", () -> list.getFirstVisiblePosition() == position
                            && visibleText(list.getChildAt(0), label));
                    int top = main(() -> list.getChildAt(0).getTop());
                    JSONObject prompt = new JSONObject().put("prompt_id", "decision").put("kind", "approval")
                            .put("execution_generation", 1).put("description", "Partager le fichier")
                            .put("choices", new JSONArray().put("once").put("deny"));
                    runTestOnUiThread(() -> {
                        page.screen.rows(history, false, true, 0);
                        actions.present(Collections.singletonList(prompt));
                    });
                    awaitUi(list, "response action over the reading position", reveal::isShown);
                    assertEquals(position, (int) main(list::getFirstVisiblePosition));
                    assertTrue(main(() -> visibleText(list.getChildAt(0), label)));
                    assertEquals(top, (int) main(() -> list.getChildAt(0).getTop()));
                    assertTrue(main(reveal::performClick));
                    awaitUi(list, "visible approval", () -> !reveal.isShown()
                            && visibleText(list, activity.getString(R.string.share_bridge_deny)));
                    assertEquals("Mon brouillon", main(page.screen::draft));
                    runTestOnUiThread(() -> list.setSelectionFromTop(position, 0));
                    awaitUi(list, "response action after scrolling back", () -> reveal.isShown()
                            && visibleText(list.getChildAt(0), label));
                    runTestOnUiThread(() -> actions.present(Collections.emptyList()));
                    awaitUi(list, "settled response action", () -> !reveal.isShown());
                    runTestOnUiThread(() -> activity.onAction("previous"));
                    assertNull(main(page::sessionId)); // One Back from the original route returns directly to the inbox.
                    assertTrue(gateway.observed().contains("/api/sessions/discussion/messages?"));
                    runTestOnUiThread(() -> activity.onAction("profiles"));
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    private <T> T main(Callable<T> work) throws Throwable {
        AtomicReference<T> result = new AtomicReference<>();
        runTestOnUiThread(() -> {
            try { result.set(work.call()); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        return result.get();
    }

    private void awaitUi(ListView list, String expectation, Callable<Boolean> ready) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        do {
            getInstrumentation().waitForIdleSync();
            if (main(ready)) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Expected " + expectation + main(() -> " (visible positions " + list.getFirstVisiblePosition()
                + "–" + list.getLastVisiblePosition() + ", children " + list.getChildCount()
                + ", items " + list.getCount() + ", viewport " + list.getWidth() + "×" + list.getHeight() + ")"));
    }

    private static boolean visibleText(View view, String label) {
        if (view instanceof TextView text && label.contentEquals(text.getText()) && view.isShown())
            return view.getGlobalVisibleRect(new Rect());
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); ++i)
                if (visibleText(group.getChildAt(i), label)) return true;
        }
        return false;
    }

    private static ThreadWorkspace selectThread(MainActivity activity, Account account) throws Exception {
        account.write("last-thread", "discussion");
        Method unlock = MainActivity.class.getDeclaredMethod("opened", Account.class);
        unlock.setAccessible(true);
        unlock.invoke(activity, account);
        Field field = MainActivity.class.getDeclaredField("threads");
        field.setAccessible(true);
        return (ThreadWorkspace) field.get(activity);
    }

    private static void checkSurfaces(MainActivity activity, Account account) throws Exception {
        ThreadWorkspace page = selectThread(activity, account);
        page.screen.displayDraft("Texte conservé");
        page.journal.observe("run");
        RunDecisions actions = new RunDecisions(page);
        actions.observe(RunAction.capture(page.journal, page::token));
        JSONObject prompt = new JSONObject().put("prompt_id", "q").put("kind", "clarify")
                .put("execution_generation", 1).put("question", "Quel jour ?");
        Button reveal = button(activity, activity.getWindow().getDecorView());
        assertNotNull(reveal);
        for (String layout : new String[]{ARRANGED, REPLACED}) {
            String record = new JSONObject().put("current", layout).toString();
            account.write("workspace.v1.thread", record);
            activity.onLayoutChanged();
            assertFalse(page.screen.list.isShown());
            assertFalse(reveal.isShown());
            actions.present(Collections.singletonList(prompt));
            assertTrue(reveal.isShown());
            activity.onAction("workspace");
            assertFalse(reveal.isShown());
            assertFalse(reveal.performClick());
            activity.onAction("return");
            assertTrue(reveal.isShown());
            assertTrue(reveal.performClick());
            assertTrue(page.screen.list.isShown());
            assertFalse(reveal.isShown());
            assertEquals("Texte conservé", page.screen.draft());
            assertEquals(record, account.read("workspace.v1.thread"));
            activity.onAction("previous");
            assertTrue(reveal.isShown());
            actions.present(Collections.emptyList());
            assertFalse(reveal.isShown());
        }
        actions.present(Collections.singletonList(prompt));
        Runnable obsolete = page.hiddenPromptAction(() -> fail("Obsolete run navigated"));
        assertNotNull(obsolete);
        actions.disconnected();
        assertFalse(reveal.isShown());
        obsolete.run();
        page.inbox();
        obsolete.run();
        activity.onAction("profiles");
        assertFalse(reveal.isShown());
        obsolete.run();
    }

    private static Button button(Context context, View view) {
        String label = context.getString(R.string.ws_reply_hermes);
        if (view instanceof Button item && label.contentEquals(item.getText())) return item;
        if (view instanceof ViewGroup children) {
            for (int i = 0; i < children.getChildCount(); ++i) {
                Button found = button(context, children.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
