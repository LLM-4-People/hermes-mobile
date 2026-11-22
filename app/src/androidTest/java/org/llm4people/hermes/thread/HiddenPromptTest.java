package org.llm4people.hermes.thread;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;
import org.llm4people.hermes.MainActivity;
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
    private static final String LABEL = "Répondre à Hermes";
    private static final String ARRANGED = "{\"version\":1,\"surface\":\"thread\",\"title\":\"Fil\","
            + "\"blocks\":[{\"type\":\"content\",\"slots\":[\"notice\",\"composer\"]}]}";
    private static final String REPLACED = "{\"version\":1,\"surface\":\"thread\",\"mode\":\"replace\",\"title\":\"Fil\","
            + "\"blocks\":[{\"type\":\"section\",\"title\":\"Accueil\",\"note\":\"Bienvenue\"}]}";

    public void testOmittedTranscriptAndReplacementKeepPendingResponseInProtectedChrome() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(launch);
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(context)) {
            runTestOnUiThread(() -> {
                try { checkSurfaces(activity, profile.account); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
        } finally {
            getInstrumentation().runOnMainSync(activity::finish);
        }
    }

    public void testReadingHistoryOffersAnInPlaceResponseUntilTheCurrentCardIsVisible() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(launch);
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(context)) {
            ThreadWorkspace page = main(() -> selectThread(activity, profile.account));
            JSONArray history = new JSONArray();
            for (int i = 0; i < 60; ++i)
                history.put(new JSONObject().put("role", "user").put("content", "Message de lecture " + i));
            RunDecisions actions = main(() -> {
                page.screen.rows(history, false, false, 0);
                page.screen.displayDraft("Mon brouillon");
                page.journal.observe("pending-approval");
                RunDecisions decisions = new RunDecisions(page);
                decisions.observe(RunAction.capture(page.journal, page::token));
                page.screen.list.setSelectionFromTop(3, 0);
                return decisions;
            });
            Button reveal = main(() -> button(activity.getWindow().getDecorView()));
            awaitUi(() -> page.screen.list.getFirstVisiblePosition() == 3);
            int top = main(() -> page.screen.list.getChildAt(0).getTop());
            JSONObject prompt = new JSONObject().put("prompt_id", "decision").put("kind", "approval")
                    .put("execution_generation", 1).put("description", "Partager le fichier")
                    .put("choices", new JSONArray().put("once").put("deny"));
            runTestOnUiThread(() -> {
                page.screen.rows(history, false, true, 0);
                actions.present(Collections.singletonList(prompt));
            });
            awaitUi(reveal::isShown);
            assertEquals(3, (int) main(() -> page.screen.list.getFirstVisiblePosition()));
            assertEquals(top, (int) main(() -> page.screen.list.getChildAt(0).getTop()));
            assertTrue(main(reveal::performClick));
            awaitUi(() -> !reveal.isShown() && visibleText(page.screen.list, "Refuser"));
            assertEquals("Mon brouillon", main(page.screen::draft));
            runTestOnUiThread(() -> page.screen.list.setSelectionFromTop(3, 0));
            awaitUi(reveal::isShown);
            runTestOnUiThread(() -> actions.present(Collections.emptyList()));
            awaitUi(() -> !reveal.isShown());
            runTestOnUiThread(() -> activity.onAction("previous"));
            assertNull(main(page::sessionId)); // One Back from the original route returns directly to the inbox.
            runTestOnUiThread(() -> activity.onAction("profiles"));
        } finally {
            getInstrumentation().runOnMainSync(activity::finish);
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

    private void awaitUi(Callable<Boolean> ready) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        do {
            getInstrumentation().waitForIdleSync();
            if (main(ready)) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The expected discussion viewport did not appear");
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
        Method unlock = MainActivity.class.getDeclaredMethod("opened", Account.class);
        unlock.setAccessible(true);
        unlock.invoke(activity, account);
        Field field = MainActivity.class.getDeclaredField("threads");
        field.setAccessible(true);
        ThreadWorkspace page = (ThreadWorkspace) field.get(activity);
        Method select = ThreadWorkspace.class.getDeclaredMethod("open", ThreadIdentity.class, String.class);
        select.setAccessible(true);
        select.invoke(page, new ThreadIdentity("discussion", "discussion"), "Discussion");
        return page;
    }

    private static void checkSurfaces(MainActivity activity, Account account) throws Exception {
        ThreadWorkspace page = selectThread(activity, account);
        page.screen.displayDraft("Texte conservé");
        page.journal.observe("run");
        RunDecisions actions = new RunDecisions(page);
        actions.observe(RunAction.capture(page.journal, page::token));
        JSONObject prompt = new JSONObject().put("prompt_id", "q").put("kind", "clarify")
                .put("execution_generation", 1).put("question", "Quel jour ?");
        Button reveal = button(activity.getWindow().getDecorView());
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

    private static Button button(View view) {
        if (view instanceof Button item && LABEL.contentEquals(item.getText())) return item;
        if (view instanceof ViewGroup children) {
            for (int i = 0; i < children.getChildCount(); ++i) {
                Button found = button(children.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
