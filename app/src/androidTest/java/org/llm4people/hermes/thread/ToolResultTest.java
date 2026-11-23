package org.llm4people.hermes.thread;

import android.test.InstrumentationTestCase;
import org.json.JSONObject;
import org.llm4people.hermes.R;

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
        assertEquals(ThreadStrings.get(R.string.thread_skill_done), ToolResult.label(row));
        assertEquals(result, ToolResult.raw(row));
        String skillFailed = ThreadStrings.get(R.string.thread_skill_failed);
        row.put("content", "{\"success\":false,\"error\":\"not found\"}");
        assertEquals(skillFailed, ToolResult.label(row));
        row.put("content", "{\"success\":true,\"error\":{\"reason\":\"denied\"}}");
        assertEquals(skillFailed, ToolResult.label(row));
        row.put("content", "{\"status\":\"failed\"}");
        assertEquals(skillFailed, ToolResult.label(row));
        String skillResult = ThreadStrings.get(R.string.thread_skill_result);
        for (String unknown : new String[]{"status pending", "{\"status\":\"running\",\"success\":true}", "{\"nested\":{\"success\":true}}",
                "{\"success\":\"true\"}", "{\"success\":true} truncated", "{{invalid}"}) {
            row.put("content", unknown);
            assertEquals(skillResult, ToolResult.label(row));
            assertEquals(unknown, ToolResult.raw(row));
        }
    }

    public void testLegacyTerminalAndLongUnknownResultsRemainReadable() throws Exception {
        JSONObject row = new JSONObject().put("role", "tool").put("name", "terminal")
                .put("content", "{\"exit_code\":2,\"output\":\"permission denied\"}");
        assertEquals(ThreadStrings.get(R.string.thread_tool_failed_named, "terminal"), ToolResult.label(row));
        row.put("content", "{\"exit_code\":0,\"output\":\"ok\"}");
        assertEquals(ThreadStrings.get(R.string.thread_tool_done_named, "terminal"), ToolResult.label(row));
        row.remove("name");
        String large = "{\"success\":true,\"output\":\"" + "0123456789".repeat(20000) + "\"}\n";
        row.put("content", large);
        assertEquals(ThreadStrings.get(R.string.thread_tool_result), ToolResult.label(row));
        assertSame(large, ToolResult.raw(row));
    }
}
