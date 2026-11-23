package org.llm4people.hermes.thread;

import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public class ThreadTitlesTest extends InstrumentationTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        ThreadStrings.attach(getInstrumentation().getTargetContext());
    }
    public void testOpeningIsVisibleUserTextAndMultimodalTextWorks() throws Exception {
        JSONArray rows = new JSONArray("[{role:'tool',content:'SECRET'},{role:'session_meta',content:'system'},"
                + "{role:'user',display_kind:'hidden',content:'summary'},{role:'assistant',content:'Generated title'},"
                + "{role:'user',content:[{type:'image_url',image_url:'SECRET'},{type:'text',text:'  Relire\\nle guide  '}]},"
                + "{role:'user',content:'Later turn'}]");
        assertEquals("Relire le guide", ThreadTitles.firstPrompt(rows));
        assertEquals("", ThreadTitles.firstPrompt(new JSONArray("[{role:'user',content:[{type:'image_url'}]},"
                + "{role:'user',content:'Later turn'}]")));
        assertEquals("", ThreadTitles.snippet("data:image/png;base64,SECRET"));
    }

    public void testInboxAndHeaderShareOpeningAndRetainExplicitName() throws Exception {
        JSONObject row = new JSONObject("{id:'root',title:null,preview:'Bonjour depuis le téléphone',message_count:3}");
        assertEquals("Bonjour depuis le téléphone", ThreadData.title(row));
        assertEquals(ThreadData.title(row), ThreadIdentity.of(row).label);
        assertEquals("Bonjour depuis le téléphone", ThreadTitles.display("", "Bonjour depuis le téléphone", false));
        row.put("title", "Notes");
        assertEquals("Notes", ThreadIdentity.of(row).label);
        assertEquals("Notes", ThreadTitles.display("Notes", "Bonjour depuis le téléphone", false));
    }

    public void testCompactedTipPreviewCannotClaimToBeTheOpening() throws Exception {
        JSONObject row = new JSONObject("{id:'tip',_lineage_root_id:'root',title:null,preview:'Dernière demande',message_count:4}");
        assertEquals("Conversation", ThreadData.title(row));
        row.put("message_count", 0);  // A freshly rotated tip can be empty even though its root has history.
        assertEquals("Conversation", ThreadData.title(row));
        assertEquals("Conversation", ThreadIdentity.of(row).label);
        assertEquals("Nouvelle conversation", ThreadData.title(null));
        row.put(ThreadTitles.FIRST_PROMPT, "Vérifier le plan");
        assertEquals("Vérifier le plan", ThreadData.title(row));
        assertEquals("Conversation", ThreadTitles.display("", "", false));
        assertEquals("Nouvelle conversation", ThreadTitles.display("", "", true));
    }

    public void testSnippetHasAUnicodeSafeBoundAndNoControlText() {
        assertEquals("Lire le code", ThreadTitles.snippet("\tLire \u202ele\u00a0code"));
        String many = "🙂".repeat(81);
        assertEquals("🙂".repeat(80) + "…", ThreadTitles.snippet(many));
    }
}
