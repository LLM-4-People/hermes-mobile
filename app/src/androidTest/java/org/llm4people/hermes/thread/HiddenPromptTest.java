package org.llm4people.hermes.thread;

import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.json.JSONObject;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

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

    private static void checkSurfaces(MainActivity activity, Account account) throws Exception {
        Method unlock = MainActivity.class.getDeclaredMethod("opened", Account.class);
        unlock.setAccessible(true);
        unlock.invoke(activity, account);
        Field field = MainActivity.class.getDeclaredField("threads");
        field.setAccessible(true);
        ThreadWorkspace page = (ThreadWorkspace) field.get(activity);
        Method select = ThreadWorkspace.class.getDeclaredMethod("open", ThreadIdentity.class, String.class);
        select.setAccessible(true);
        select.invoke(page, new ThreadIdentity("discussion", "discussion"), "Discussion");
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
