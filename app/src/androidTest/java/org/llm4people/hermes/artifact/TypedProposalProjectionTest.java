package org.llm4people.hermes.artifact;

import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import junit.framework.TestCase;

public final class TypedProposalProjectionTest extends TestCase {
    private static final String RAW = "{\"version\":1}";
    private static final String FENCE = "~~~hermes-workspace\n" + RAW + "\n~~~";
    private static JSONObject part(String type, Object text) throws Exception {
        return new JSONObject().put("type", type).put("text", text);
    }
    private static JSONObject row(JSONObject... parts) throws Exception {
        return new JSONObject().put("role", "assistant").put("content", new JSONArray(java.util.Arrays.asList(parts)));
    }
    private static String project(JSONObject row, String label) {
        return MessageParts.project(row, Collections.singletonMap(0, RAW), ignored -> label);
    }

    public void testInputWithEqualFenceIsPreservedAlongsideOrdinaryCodeAndImages() throws Exception {
        for (String example : new String[] {FENCE, "~~~python\nprint(2)\n~~~"}) {
            JSONObject row = row(part("input_text", "Contexte\n" + example + "\nFIN-INPUT"),
                    part("input_image", ""), part("output_text", "ORIGINE\n" + FENCE + "\nFIN-OUTPUT"));
            String context = "Contexte\n" + example + "\nFIN-INPUT\n[Image jointe]\nORIGINE\n";
            assertEquals(context + FENCE + "\nFIN-OUTPUT", MessageParts.display(row));
            assertEquals(context + "FIN-OUTPUT", project(row, ""));
            assertEquals(context + "Outil de revue\nFIN-OUTPUT", project(row, "Outil de revue"));
        }
    }

    public void testOneFenceAcrossOutputPartsRetainsInterleavedDisplayContent() throws Exception {
        JSONObject row = row(part("text", "Avant\n~~~hermes-workspace"),
                part("input_text", "Contexte illustratif"), part("output_image", ""),
                part("output_text", RAW + "\n~~~\nAprès"));
        assertEquals("Avant\n~~~hermes-workspace\n" + RAW + "\n~~~\nAprès",
                MessageParts.eligible(row.get("content")));
        assertEquals("Avant\n\nContexte illustratif\n[Image jointe]\nAprès", project(row, ""));
        assertEquals("Avant\nOutil de revue\nContexte illustratif\n[Image jointe]\nAprès", project(row, "Outil de revue"));
        JSONObject adjacent = row(part("text", "Avant\n~~~hermes-workspace"), part("output_text", RAW),
                part("output_text", "~~~"), part("text", "Après"));
        assertEquals("Avant\nAprès", project(adjacent, ""));
        assertEquals("Avant\nOutil de revue\nAprès", project(adjacent, "Outil de revue"));
    }

    public void testOrdinaryCodeBeforeAndAfterAndUnmatchedMessagesRemainVisible() throws Exception {
        String code = "```java\r\nint value = 1;\r\n```";
        JSONObject row = row(part("text", code), part("input_text", "Extrait"), part("output_text", FENCE),
                part("output_text", code));
        assertEquals(code + "\nExtrait\n" + code,
                MessageParts.project(row, Collections.singletonMap(1, RAW), i -> ""));
        assertEquals(MessageParts.display(row), project(row, ""));
        row.put("role", "user");
        assertEquals(MessageParts.display(row),
                MessageParts.project(row, Collections.singletonMap(1, RAW), i -> ""));
    }
}
