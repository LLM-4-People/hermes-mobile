package org.llm4people.hermes.thread;

import android.test.AndroidTestCase;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public class PromptHistoryTest extends AndroidTestCase {
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
        assertEquals(9, display.length());
        assertEquals("Quel format ?", display.getJSONObject(0).getString("content"));
        assertEquals("user", display.getJSONObject(1).getString("role"));
        assertEquals("Une liste", display.getJSONObject(1).getString("content"));
        assertEquals("• Lundi\n• Jeudi", display.getJSONObject(3).getString("content"));
        assertEquals("prompt_status", display.getJSONObject(5).getString("role"));
        assertEquals("Question passée.", display.getJSONObject(5).getString("content"));
        assertEquals("Aucune réponse reçue.", display.getJSONObject(7).getString("content"));
        assertSame(assistant, display.getJSONObject(8));
        assertEquals(3, source.length());
        result.put("session_id", "another-session");
        assertSame(source, PromptHistory.display(source));
        result.put("tool_name", "terminal");
        assertSame(source, PromptHistory.display(source));
        result.put("tool_name", "clarify").put("content", "{invalid");
        assertSame(source, PromptHistory.display(source));
    }

    private static JSONObject response(String question, String status, Object answer) throws Exception {
        return new JSONObject().put("question", question).put("status", status).put("user_response", answer);
    }
}
