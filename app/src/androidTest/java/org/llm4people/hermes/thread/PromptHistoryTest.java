package org.llm4people.hermes.thread;

import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;

@SuppressWarnings("deprecation")
public class PromptHistoryTest extends InstrumentationTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        ThreadStrings.attach(getInstrumentation().getTargetContext());
    }
    public void testCanonicalResultsBecomeConversationalTurnsWithoutDuplicatingTheToolRequest() throws Exception {
        JSONObject call = new JSONObject().put("id", "a").put("function", new JSONObject().put("name", "clarify"));
        JSONObject ask = new JSONObject().put("role", "assistant").put("session_id", "t").put("tool_calls", new JSONArray().put(call));
        JSONArray responses = new JSONArray()
                .put(response("Quel format ?", "answered", "Une liste"))
                .put(response("Quels jours ?", "answered", new JSONArray().put("Lundi").put("Jeudi")))
                .put(response("Un titre ?", "skipped", JSONObject.NULL))
                .put(response("Une date ?", "unanswered", JSONObject.NULL));
        JSONObject result = new JSONObject().put("role", "tool").put("session_id", "t").put("tool_call_id", "a")
                .put("content", new JSONObject().put("responses", responses).put("outcome", "submitted").toString());
        JSONObject assistant = new JSONObject().put("role", "assistant").put("content", "Voici votre note.");
        JSONArray source = new JSONArray().put(ask).put(result).put(assistant);
        JSONArray display = PromptHistory.display(source);
        assertEquals(8, display.length());
        assertEquals("Quel format ?", display.getJSONObject(0).getString("content"));
        assertEquals("user", display.getJSONObject(1).getString("role"));
        assertEquals("Une liste", display.getJSONObject(1).getString("content"));
        assertEquals("• Lundi\n• Jeudi", display.getJSONObject(3).getString("content"));
        assertEquals("prompt_status", display.getJSONObject(5).getString("role"));
        assertEquals(ThreadStrings.get(R.string.prompt_history_skipped), display.getJSONObject(5).getString("content"));
        assertEquals(ThreadStrings.get(R.string.prompt_history_missing), display.getJSONObject(6).getString("content"));
        assertSame(assistant, display.getJSONObject(7));
        assertEquals(3, source.length());
        result.put("session_id", "another-session");
        assertSame(source, PromptHistory.display(source));
        result.put("tool_name", "terminal");
        assertSame(source, PromptHistory.display(source));
        result.put("tool_name", "clarify").put("content", "{invalid");
        assertSame(source, PromptHistory.display(source));
    }

    public void testBatchOutcomeAndNoticeSurviveWithoutPresentingUnreachedQuestions() throws Exception {
        JSONArray responses = new JSONArray().put(response("Question répondue ?", "answered", "Oui"))
                .put(response("Question suivante ?", "unanswered", JSONObject.NULL))
                .put(response("Dernière question ?", "unanswered", JSONObject.NULL));
        for (String[] example : new String[][] {
                {"undelivered", ThreadStrings.get(R.string.prompt_history_undelivered)},
                {"timed_out", ThreadStrings.get(R.string.prompt_history_timed_out)},
                {"cancelled", ThreadStrings.get(R.string.prompt_history_cancelled)}}) {
            JSONObject receipt = new JSONObject().put("responses", responses).put("outcome", example[0])
                    .put("notice", "Précision de la passerelle");
            JSONObject tool = new JSONObject().put("role", "tool").put("tool_name", "clarify")
                    .put("content", receipt.toString());
            JSONArray turns = PromptHistory.display(new JSONArray().put(tool));
            assertEquals(3, turns.length());
            assertEquals("Question répondue ?", turns.getJSONObject(0).getString("content"));
            assertEquals("Oui", turns.getJSONObject(1).getString("content"));
            assertEquals("prompt_status", turns.getJSONObject(2).getString("role"));
            assertEquals(example[1] + "\nPrécision de la passerelle", turns.getJSONObject(2).getString("content"));
        }
    }

    public void testTerminalHistoryUsesExplicitHumanDecisionProvenance() throws Exception {
        JSONObject result = new JSONObject().put("exit_code", 0).put("output", "fichier prêt");
        JSONObject row = new JSONObject().put("role", "tool").put("tool_name", "terminal");
        JSONArray input = new JSONArray().put(row);
        row.put("content", result.toString());
        assertSame(input, PromptHistory.display(input));
        result.put("approval", "Command was flagged (reason) and auto-approved by smart approval.");
        row.put("content", result.toString());
        assertSame(input, PromptHistory.display(input));
        result.put("approval", "Command required approval (reason) and was approved by the user.");
        row.put("content", result.toString());
        JSONObject allow = PromptHistory.display(input).getJSONObject(0);
        assertEquals(ThreadStrings.get(R.string.prompt_history_approved) + "\n\nfichier prêt", allow.getString("content"));
        assertEquals(result.toString(), allow.getString("receipt_details"));
        result = new JSONObject().put("status", "blocked").put("user_summary", "You denied this command — it did not run.");
        row.put("content", result.toString());
        assertEquals(ThreadStrings.get(R.string.prompt_history_denied), PromptHistory.display(input)
                .getJSONObject(0).getString("content"));
    }

    private static JSONObject response(String question, String status, Object answer) throws Exception {
        return new JSONObject().put("question", question).put("status", status).put("user_response", answer);
    }
}
