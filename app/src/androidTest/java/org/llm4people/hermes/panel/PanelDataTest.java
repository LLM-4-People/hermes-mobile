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
        assertTrue(Double.isNaN(PanelData.cacheRatio(new JSONObject("{\"input_tokens\":0,\"cache_read_tokens\":5}"))));
        assertTrue(Double.isNaN(PanelData.cacheRatio(new JSONObject("{\"input_tokens\":5,\"cache_read_tokens\":8}"))));
        assertEquals(.75, PanelData.cacheRatio(new JSONObject("{\"input_tokens\":12,\"cache_read_tokens\":9}")), 0);
        assertTrue(Double.isNaN(PanelData.endToEndRate(new JSONObject("{\"elapsed_ms\":1000}"))));
        assertTrue(Double.isNaN(PanelData.endToEndRate(new JSONObject("{\"elapsed_ms\":0,\"output_tokens\":4}"))));
        assertEquals(2.5, PanelData.endToEndRate(new JSONObject("{\"elapsed_ms\":2000,\"output_tokens\":5}")), 0);
    }

    public void testScheduleRoundTripsServerShapesWithoutSendingDisplayCopy() throws Exception {
        assertEquals("5m", PanelData.schedule(new JSONObject("{\"schedule\":{\"kind\":\"interval\",\"minutes\":5}}")));
        assertEquals("0 9 * * *", PanelData.schedule(new JSONObject("{\"schedule\":{\"kind\":\"cron\",\"expr\":\"0 9 * * *\"}}")));
        assertEquals("2027-01-01T10:00:00+01:00", PanelData.schedule(new JSONObject(
                "{\"schedule\":{\"kind\":\"once\",\"run_at\":\"2027-01-01T10:00:00+01:00\"}}")));
        assertEquals("", PanelData.schedule(new JSONObject("{\"schedule\":{\"kind\":\"new-server-format\"}}")));
    }
}
