package org.llm4people.hermes.panel;

import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PanelDataTest extends TestCase {
    public void testFiltersKeepOmittedEmptyAndNamedSelectionsDistinct() throws Exception {
        assertEquals(0, PanelData.restoreSelection(null).length());
        JSONObject options = PanelData.selection("custom:lab", "group/model", new JSONArray(), new JSONArray("[\"read\"]"));
        JSONObject restored = PanelData.restoreSelection(options.toString());
        assertEquals("custom:lab", restored.getString("provider"));
        assertEquals("group/model", restored.getString("model"));
        assertEquals(0, restored.getJSONArray("allowed_toolsets").length());
        assertEquals("read", restored.getJSONArray("excluded_tools").getString(0));
        JSONObject onlyExclusions = PanelData.selection("", "", null, new JSONArray("[\"read\"]"));
        assertFalse(onlyExclusions.has("allowed_toolsets"));
        assertFalse(onlyExclusions.has("model"));
        assertEquals("read", onlyExclusions.getJSONArray("excluded_tools").getString(0));
    }

    public void testMalformedFiltersFailClosed() {
        String[] cases = {"{", "{\"allowed_toolsets\":null}", "{\"excluded_tools\":\"all\"}",
                "{\"allowed_toolsets\":[\"read\",1]}", "{\"model\":\"x\"}", "{\"provider\":null}"};
        for (String raw : cases) {
            try { PanelData.restoreSelection(raw); fail("Invalid preferences accepted"); }
            catch (IllegalStateException expected) { /* The caller must not submit a run. */ }
        }
    }

    public void testUnknownCountersAreNeverReportedAsMeasuredZero() throws Exception {
        assertTrue(Double.isNaN(PanelData.cacheRatio(new JSONObject())));
        assertTrue(Double.isNaN(PanelData.cacheRatio(buckets(0, 0, 0))));
        assertTrue(Double.isNaN(PanelData.cacheRatio(buckets(-1, 4, 0))));
        assertTrue(Double.isNaN(PanelData.cacheRatio(buckets(4, -1, 0))));
        assertTrue(Double.isNaN(PanelData.cacheRatio(buckets(4, 1, -1))));
        JSONObject incomplete = buckets(30, 60, 10);
        incomplete.remove("cache_write_tokens");
        assertTrue(Double.isNaN(PanelData.cacheRatio(incomplete)));
        assertEquals(100, PanelData.promptTokens(buckets(30, 60, 10)), 0);
        assertEquals(.60, PanelData.cacheRatio(buckets(30, 60, 10)), 0);
        assertEquals(0, PanelData.cacheRatio(buckets(40, 0, 12)), 0);
        assertEquals(1, PanelData.cacheRatio(buckets(0, 42, 0)), 0);
        assertTrue(Double.isNaN(PanelData.endToEndRate(new JSONObject("{\"elapsed_ms\":1000}"))));
        assertTrue(Double.isNaN(PanelData.endToEndRate(new JSONObject("{\"elapsed_ms\":0,\"output_tokens\":4}"))));
        assertEquals(2.5, PanelData.endToEndRate(new JSONObject("{\"elapsed_ms\":2000,\"output_tokens\":5}")), 0);
    }

    private static JSONObject buckets(int input, int read, int write) throws Exception {
        return new JSONObject().put("input_tokens", input).put("cache_read_tokens", read)
                .put("cache_write_tokens", write);
    }

    public void testEffectiveToolsUseTheUnionAcrossEnabledSets() throws Exception {
        java.util.Map<String, java.util.List<String>> sets = new java.util.LinkedHashMap<>();
        sets.put("a", java.util.Arrays.asList("shared", "first"));
        sets.put("b", java.util.Arrays.asList("shared", "second"));
        assertEquals(3, PanelData.enabledMembers(sets, null).size());
        assertTrue(PanelData.enabledMembers(sets, new JSONArray()).isEmpty());
        java.util.Set<String> enabled = PanelData.enabledMembers(sets, new JSONArray().put("b"));
        assertTrue(enabled.contains("shared"));
        assertTrue(enabled.contains("second"));
        assertFalse(enabled.contains("first"));
    }

    public void testRecurringScheduleErrorSurvivesEnabledFlag() throws Exception {
        JSONObject row = new JSONObject().put("state", "error").put("enabled", true);
        assertEquals("error", PanelData.jobState(row));
        row.put("state", "completed").put("enabled", false);
        assertEquals("completed", PanelData.jobState(row));
        row.put("state", "paused");
        assertEquals("paused", PanelData.jobState(row));
        row.put("enabled", true);
        assertEquals("scheduled", PanelData.jobState(row));
        row.put("state", "scheduled");
        assertEquals("scheduled", PanelData.jobState(row));
    }

    public void testScheduleRoundTripsServerShapesWithoutSendingDisplayCopy() throws Exception {
        assertEquals("5m", PanelData.schedule(new JSONObject("{\"schedule\":{\"kind\":\"interval\",\"minutes\":5}}")));
        assertEquals("0 9 * * *", PanelData.schedule(new JSONObject("{\"schedule\":{\"kind\":\"cron\",\"expr\":\"0 9 * * *\"}}")));
        assertEquals("2027-01-01T10:00:00+01:00", PanelData.schedule(new JSONObject(
                "{\"schedule\":{\"kind\":\"once\",\"run_at\":\"2027-01-01T10:00:00+01:00\"}}")));
        assertEquals("", PanelData.schedule(new JSONObject("{\"schedule\":{\"kind\":\"new-server-format\"}}")));
    }
}
