package org.llm4people.hermes.artifact;

import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public final class ArtifactContractTest extends InstrumentationTestCase {
    private static final String EXAMPLE = "{\"version\":1,\"surface\":\"home\",\"title\":\"Atelier\"," +
            "\"blocks\":[{\"title\":\"Code\",\"actions\":[\"artifacts\"]},{\"type\":\"content\"}]}";

    public void testRunInstructionsContainRecognizedFormAndPhoneActions() throws Exception {
        String instructions = WorkspaceLayout.instructions() + WorkspaceLayout.pinnedReference("page/revue", "12", 0);
        ArtifactCatalog catalog = new ArtifactCatalog(new JSONArray().put(message("assistant", instructions)));
        assertEquals(2, catalog.records.size());
        Map<String, WorkspaceLayout> pages = new HashMap<>();
        for (ArtifactCatalog.Record record : catalog.records) {
            assertEquals(WorkspaceLayout.FENCE, record.language);
            WorkspaceLayout layout = WorkspaceLayout.parse(record.versions.get(0));
            assertTrue(WorkspaceLayout.custom(layout.surface));
            assertEquals("replace", layout.mode);
            pages.put(layout.blocks.get(0).type, layout);
        }
        assertNotNull(pages.get("form"));
        WorkspaceLayout.Block form = pages.get("form").blocks.get(0);
        assertFalse(form.fields.isEmpty());
        assertFalse(form.prompt.isEmpty());
        WorkspaceLayout actions = pages.get("device");
        assertNotNull(actions);
        assertEquals(3, actions.blocks.size());
        for (WorkspaceLayout.Block block : actions.blocks) assertEquals("device", block.type);
        assertEquals(DeviceAction.Kind.CALENDAR, actions.blocks.get(0).request.kind());
        assertEquals(DeviceAction.Kind.SHARE, actions.blocks.get(1).request.kind());
        assertEquals(DeviceAction.Kind.MAP, actions.blocks.get(2).request.kind());
    }

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
        invalid(pad('[', 10000));
    }

    public void testLayoutRequiresJsonStringSyntaxAndVisibleLabelBoundaries() throws Exception {
        for (String title : new String[]{"Alph\\q", "A\\u＋023", "A\\u００４１", "A\nB",
                "\u2003", "\u00a0", "\u202fNom", "Nom\u2002"}) {
            invalid(EXAMPLE.replace("Atelier", title));
        }
        invalid(EXAMPLE.replace("\"title\"", "\"tit\\le\""));
        String wire = "Code \\\\q \\\"été\\\"\\u00a0\\/ Notes";
        assertEquals("Code \\q \"été\"\u00a0/ Notes",
                WorkspaceLayout.parse(EXAMPLE.replace("Atelier", wire)).title);
    }

    public void testNewestFencesSurviveTheBoundWithinAndAcrossMessages() throws Exception {
        StringBuilder older = new StringBuilder();
        for (int revision = 1; revision <= 257; revision++)
            older.append("~~~txt file=build.txt\n").append(revision).append("\n~~~\n");
        JSONArray rows = new JSONArray().put(message("assistant", older.toString()));
        assertWindow(new ArtifactCatalog(rows), "2", "257");
        rows.put(message("assistant", "~~~txt file=build.txt\n258\n~~~\n~~~txt file=build.txt\nunfinished"));
        assertWindow(new ArtifactCatalog(rows), "3", "258");
    }

    private static void assertWindow(ArtifactCatalog catalog, String oldest, String latest) {
        assertTrue(catalog.limited);
        assertEquals(1, catalog.records.size());
        ArtifactCatalog.Record record = catalog.records.get(0);
        assertEquals(256, record.versions.size());
        assertEquals(oldest, record.versions.get(0));
        assertEquals(latest, record.versions.get(record.versions.size() - 1));
    }

    public void testTextAndTypedPartsShareInclusiveMessageLimit() throws Exception {
        String fence = "~~~txt file=answer.txt\nx\n~~~";
        String padding = pad(' ', ArtifactCatalog.MAX_MESSAGE_CHARS - fence.length() - 1);
        for (String prefix : new String[]{padding, padding + " "}) {
            String full = prefix + "\n" + fence;
            for (Object content : new Object[]{full,
                    new JSONArray().put(part("output_text", full)),
                    new JSONArray().put(part("text", prefix)).put(part("output_text", fence))}) {
                ArtifactCatalog catalog = new ArtifactCatalog(new JSONArray().put(message("assistant", content)));
                boolean tooLong = full.length() > ArtifactCatalog.MAX_MESSAGE_CHARS;
                assertEquals(tooLong, catalog.limited);
                assertEquals(tooLong ? 0 : 1, catalog.records.size());
                if (!tooLong) assertEquals("x", catalog.records.get(0).versions.get(0));
            }
        }
    }

    private static JSONObject part(String type, String text) throws Exception {
        return new JSONObject().put("type", type).put("text", text);
    }

    private static String pad(char c, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, c);
        return new String(chars);
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

    public void testExplicitExtensionlessNamesRetainRevisions() throws Exception {
        JSONArray rows = new JSONArray();
        for (String name : new String[]{"Dockerfile", "Makefile", "Dockerfile"}) {
            rows.put(message("assistant", "```text file=" + name + "\n" + rows.length() + "\n```"));
        }
        ArtifactCatalog catalog = new ArtifactCatalog(rows);
        assertEquals(2, catalog.records.size());
        assertEquals("Dockerfile", catalog.records.get(0).title);
        assertEquals(2, catalog.records.get(0).versions.size());
        assertEquals("2", catalog.records.get(0).versions.get(1));
        assertEquals("Makefile", catalog.records.get(1).title);
    }

    private static JSONObject message(String role, Object value) throws Exception {
        return new JSONObject().put("role", role).put("content", value);
    }

    private static void invalid(String raw) {
        try { WorkspaceLayout.parse(raw); fail("Unexpected layout acceptance"); }
        catch (IOException expected) { }
    }
}
