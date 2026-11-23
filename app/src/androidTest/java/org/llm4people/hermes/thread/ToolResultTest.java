package org.llm4people.hermes.thread;

import android.test.InstrumentationTestCase;
import org.json.JSONObject;

@SuppressWarnings("deprecation")
public final class ToolResultTest extends InstrumentationTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        ThreadStrings.attach(getInstrumentation().getTargetContext());
    }
    public void testSkillReceiptUsesItsExplicitOutcomeAndKeepsItsExactTechnicalText() throws Exception {
        String result = " { \"success\": true, \"name\": \"synthese\", \"content\": \"Étape\\n2\", "
                + "\"path\": \"qualite/synthese/SKILL.md\", \"skill_dir\": \"/profil/skills/qualite/synthese\" }\n";
        JSONObject row = new JSONObject().put("role", "tool").put("tool_name", "skill_view")
                .put("content", result);
        assertEquals("Compétence consultée", ToolResult.label(row));
        assertEquals(result, ToolResult.raw(row));
        row.put("content", "{\"success\":false,\"error\":\"not found\"}");
        assertEquals("Échec de la consultation", ToolResult.label(row));
        row.put("content", "{\"success\":true,\"error\":{\"reason\":\"denied\"}}");
        assertEquals("Échec de la consultation", ToolResult.label(row));
        row.put("content", "{\"status\":\"failed\"}");
        assertEquals("Échec de la consultation", ToolResult.label(row));
        for (String unknown : new String[]{"status pending", "{\"status\":\"running\",\"success\":true}", "{\"nested\":{\"success\":true}}",
                "{\"success\":\"true\"}", "{\"success\":true} truncated", "{{invalid}"}) {
            row.put("content", unknown);
            assertEquals("Consultation d’une compétence", ToolResult.label(row));
            assertEquals(unknown, ToolResult.raw(row));
        }
    }

    public void testLegacyTerminalAndLongUnknownResultsRemainReadable() throws Exception {
        JSONObject row = new JSONObject().put("role", "tool").put("name", "terminal")
                .put("content", "{\"exit_code\":2,\"output\":\"permission denied\"}");
        assertEquals("Échec de l’outil · terminal", ToolResult.label(row));
        row.put("content", "{\"exit_code\":0,\"output\":\"ok\"}");
        assertEquals("Outil terminé · terminal", ToolResult.label(row));
        row.remove("name");
        String large = "{\"success\":true,\"output\":\"" + "0123456789".repeat(20000) + "\"}\n";
        row.put("content", large);
        assertEquals("Résultat de l’outil", ToolResult.label(row));
        assertSame(large, ToolResult.raw(row));
    }
}
