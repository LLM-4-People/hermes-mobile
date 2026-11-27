package org.llm4people.hermes.thread;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

@SuppressWarnings("deprecation")
public class InstrumentPromptTest extends InstrumentationTestCase {
    private static final String ARRANGED = "{\"version\":1,\"surface\":\"thread\",\"title\":\"Fil\","
            + "\"blocks\":[{\"type\":\"content\",\"slots\":[\"notice\",\"composer\"]}]}";
    private ThreadWorkspace page;
    private MainActivity activity;
    private RunDecisions actions;
    private JSONObject prompt;
    private EditText form;

    public void testActiveQuestionWaitsForExplicitCompactRevealAndSurvivesWidthChanges() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        try {
            try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(context)) {
                activity = (MainActivity)getInstrumentation().startActivitySync(
                        context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
                try {
                    String respond = activity.getString(R.string.ws_reply_hermes);
                    String openLabel = activity.getString(R.string.instrument_open_pin);
                    runTestOnUiThread(() -> {
                        try { open(profile.account); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                        activity.onAction("native_thread");
                        width(320);
                    });
                    idle();
                    runTestOnUiThread(() -> {
                        click(openLabel);
                        form = visibleField(page.screen.instruments.viewport);
                        assertNotNull(form);
                        form.setText("facture.py");
                        actions.present(Collections.singletonList(prompt));
                        page.screen.live("Question", "", true);
                        assertTrue(page.screen.instruments.viewport.open());
                        assertFalse(page.screen.list.isShown());
                        assertTrue(button(respond).isShown());
                        width(720);
                    });
                    idle();
                    runTestOnUiThread(() -> {
                        assertTrue(page.screen.instruments.viewport.wide());
                        assertTrue(page.screen.list.isShown());
                        assertFalse(button(respond).isShown());
                        assertTrue(form.isShown());
                        width(320);
                    });
                    idle();
                    runTestOnUiThread(() -> {
                        assertTrue(button(respond).isShown());
                        click(respond);
                        assertTrue(page.screen.list.isShown());
                        assertFalse(button(respond).isShown());
                        assertEquals("Demain matin", page.screen.draft());
                        click(openLabel);
                        assertSame(form, visibleField(page.screen.instruments.viewport));
                        assertEquals("facture.py", form.getText().toString());
                        actions.present(Collections.emptyList());
                        assertFalse(button(respond).isShown());
                        // A layout omitting the entire native transcript requires the normal native-route fallback.
                        profile.account.write("workspace.v1.thread", ThreadData.put(new JSONObject(), "current", ARRANGED).toString());
                        activity.onAction("return");
                        activity.onLayoutChanged();
                        actions.present(Collections.singletonList(prompt));
                        assertTrue(button(respond).isShown());
                        click(respond);
                        assertTrue(page.screen.list.isShown());
                        assertFalse(button(respond).isShown());
                        assertTrue(page.screen.promptRows.visible());
                        assertEquals("Demain matin", page.screen.draft());
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    private void open(Account account) throws Exception {
        String root = "discussion";
        String pageJson = "{\"version\":1,\"surface\":\"page/revue\",\"mode\":\"replace\",\"title\":\"Relecture\","
                + "\"blocks\":[{\"type\":\"form\",\"title\":\"Calcul\",\"prompt\":\"Relire la note.\","
                + "\"fields\":[{\"id\":\"fichier\",\"label\":\"Fichier\",\"kind\":\"text\",\"required\":true}]}]}";
        JSONObject message = new JSONObject().put("role", "assistant").put("id", 15).put("session_id", root)
                .put("content", "```hermes-workspace\n" + pageJson + "\n```");
        JSONArray transcript = new JSONArray().put(message);
        ThreadInstruments store = new ThreadInstruments(account, root);
        store.pin(store.state(), store.proposals(transcript).get(0), transcript);
        Method unlock = MainActivity.class.getDeclaredMethod("opened", Account.class);
        unlock.setAccessible(true);
        unlock.invoke(activity, account);
        Field field = MainActivity.class.getDeclaredField("threads");
        field.setAccessible(true);
        page = (ThreadWorkspace)field.get(activity);
        Method select = ThreadWorkspace.class.getDeclaredMethod("open", ThreadIdentity.class, String.class);
        select.setAccessible(true);
        select.invoke(page, new ThreadIdentity(root, root), "Discussion");
        page.screen.displayDraft("Demain matin");
        page.journal.observe("run");
        actions = new RunDecisions(page);
        actions.observe(RunAction.capture(page.journal, page::token));
        prompt = new JSONObject().put("kind", "clarify").put("prompt_id", "jour")
                .put("execution_generation", 1).put("question", "Quel jour ?");
    }

    private void width(int dp) {
        View viewport = page.screen.instruments.viewport;
        ViewGroup.LayoutParams params = viewport.getLayoutParams();
        params.width = Ui.dp(activity, dp);
        viewport.setLayoutParams(params);
    }

    private void idle() { getInstrumentation().waitForIdleSync(); }

    private void click(String label) {
        Button button = button(label);
        assertTrue(label, button.isShown());
        assertTrue(label, button.performClick());
    }

    private Button button(String label) {
        Button button = button(activity.getWindow().getDecorView(), label);
        assertNotNull(label, button);
        return button;
    }

    private static Button button(View view, String label) {
        if (view instanceof Button item
                && (label.contentEquals(item.getText())
                        || label.equals(item.getContentDescription())
                        || ("ws-pending-response".equals(item.getTag())
                                && label.equals(item.getContext().getString(R.string.ws_reply_hermes))))) {
            return item;
        }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button found = button(group.getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private static EditText visibleField(View view) {
        if (!view.isShown()) return null;
        if (view instanceof EditText field) return field;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = visibleField(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
