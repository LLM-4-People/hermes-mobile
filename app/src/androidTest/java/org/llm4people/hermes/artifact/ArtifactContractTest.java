package org.llm4people.hermes.artifact;

import android.test.InstrumentationTestCase;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public final class ArtifactContractTest extends InstrumentationTestCase {
    private static final String EXAMPLE = "{\"version\":1,\"surface\":\"home\",\"title\":\"Atelier\"," +
            "\"blocks\":[{\"title\":\"Code\",\"actions\":[\"artifacts\"]},{\"type\":\"content\"}]}";

    public void testVocabularyAndLimits() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.parse(EXAMPLE);
        assertEquals("home", layout.surface);
        assertEquals("artifacts", layout.blocks.get(0).actions.get(0));
        assertTrue(layout.blocks.get(1).nativeContent);
        invalid(EXAMPLE.replace("\"artifacts\"", "\"launch_intent\""));
        invalid(EXAMPLE.replace("\"artifacts\"", "\"artifacts\",\"artifacts\""));
        invalid(EXAMPLE.replace("\"home\"", "\"home\",\"surface\":\"thread\""));
        invalid(EXAMPLE.replace("\"version\":1", "\"version\":\"1\""));
        invalid(EXAMPLE.replace("\"title\":\"Atelier\"", "\"title\":\"Atelier\",\"script\":true"));
        invalid(EXAMPLE.replace(",{\"type\":\"content\"}", ""));
        invalid(EXAMPLE.replace("{\"type\":\"content\"}", "{\"type\":\"content\",\"note\":\"X\"}"));
        invalid(EXAMPLE.replace("Atelier", "A\u202eX"));
        invalid(EXAMPLE + " {}");
        invalid("[".repeat(10000));
    }

    public void testVersionsAreIsolatedByFilenameSurfaceAndRole() throws Exception {
        JSONArray rows = new JSONArray();
        rows.put(message("assistant", "```java file=A.java\na\n```\n```java file=B.java\nb\n```"));
        rows.put(message("user", "```java file=A.java\nignored\n```"));
        rows.put(message("assistant", "```java file=A.java\na2\n```"));
        rows.put(message("assistant", new JSONArray().put(new JSONObject().put("type","output_text")
                .put("text","```java file=A.java\na2\n```"))));
        rows.put(message("assistant", "```hermes-workspace\n" + EXAMPLE + "\n```"));
        rows.put(message("assistant", "```hermes-workspace\n" + EXAMPLE.replace("\"home\"", "\"thread\"") + "\n```"));
        ArtifactCatalog catalog = new ArtifactCatalog(rows);
        assertFalse(catalog.limited);
        assertEquals(4, catalog.records.size());
        assertEquals("Disposition · Conversation", catalog.records.get(0).title);
        assertEquals("A.java", catalog.records.get(2).title);
        assertEquals(2, catalog.records.get(2).versions.size());
        assertEquals("a2", catalog.records.get(2).versions.get(1));
    }

    private static JSONObject message(String role, Object value) throws Exception {
        return new JSONObject().put("role", role).put("content", value);
    }

    private static void invalid(String raw) {
        try { WorkspaceLayout.parse(raw); fail("Unexpected layout acceptance"); }
        catch (IOException expected) { }
    }
}
