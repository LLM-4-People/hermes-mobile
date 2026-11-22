package org.llm4people.hermes.panel;

import junit.framework.TestCase;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class DiscussionUsageTest extends TestCase {
    private final Map<String, JSONObject> rows = new HashMap<>();
    private final List<String> requested = new ArrayList<>();

    private JSONObject row(String id, String parent, int input, int read, int write, int output) throws Exception {
        JSONObject row = new JSONObject().put("id", id).put("parent_session_id", parent)
                .put("input_tokens", input).put("cache_read_tokens", read).put("cache_write_tokens", write)
                .put("output_tokens", output);
        rows.put(id, row);
        return row;
    }

    private DiscussionUsage read(String root, String tip) throws Exception {
        return DiscussionUsage.read(root, tip, id -> {
            requested.add(id);
            return rows.get(id);
        }, () -> true);
    }

    public void testCompressionSumsEachSegmentOnceAndStopsAtBranchRoot() throws Exception {
        row("branch", "other-thread", 30, 10, 0, 20).put("end_reason", "compression")
                .put("actual_cost_usd", 0.5).put("cost_status", "actual").put("cost_source", "provider_cost_api");
        row("middle", "branch", 2, 0, 3, 4).put("end_reason", "compression")
                .put("estimated_cost_usd", .25).put("cost_status", "estimated");
        row("tip", "middle", 5, 0, 0, 7).put("actual_cost_usd", .1)
                .put("cost_status", "actual").put("cost_source", "provider_generation_api");
        DiscussionUsage result = read("branch", "tip");
        assertEquals(Arrays.asList("tip", "middle", "branch"), requested);
        assertEquals(50, result.prompt, 0);
        assertEquals(.2, result.cacheRead / result.prompt, 0);
        assertEquals(31, result.output, 0);
        assertEquals(.6, result.billed, 1e-12);
        assertEquals(.25, result.estimated, 0);
        assertEquals("provider_generation_api", result.lastSource);
        assertFalse(result.hasUnpricedCalls);
    }

    public void testUnknownAmountsAndMissingCountersPreserveIndependentKnownTotals() throws Exception {
        row("first", null, 40, 0, 0, 10).put("end_reason", "compression")
                .put("actual_cost_usd", .5).put("cost_status", "actual").put("cost_source", "provider_cost_api");
        JSONObject last = row("tip", "first", 3, 0, 0, 6).put("cost_status", "unknown").put("estimated_cost_usd", 0);
        last.remove("cache_write_tokens");
        DiscussionUsage result = read("first", "tip");
        assertTrue(Double.isNaN(result.prompt));
        assertEquals(16, result.output, 0);
        assertEquals(.5, result.billed, 0);
        assertTrue(Double.isNaN(result.estimated));
        assertTrue(result.hasUnpricedCalls);
        assertEquals("unknown", result.lastStatus);
        assertEquals("", result.lastSource);
    }

    public void testPricingProvenanceSurvivesAnEmptyNewTipAndRecognizesIncludedCalls() throws Exception {
        row("root", null, 1, 0, 0, 2).put("end_reason", "compression")
                .put("cost_status", "estimated").put("estimated_cost_usd", 0).put("cost_source", "user_override");
        row("tip", "root", 0, 0, 0, 0);
        DiscussionUsage result = read("root", "tip");
        assertEquals("estimated", result.lastStatus);
        assertEquals("user_override", result.lastSource);
        assertEquals(0, result.estimated, 0);
        assertTrue(Double.isNaN(result.billed));
        rows.get("tip").put("cost_status", "included").put("cost_source", "none");
        result = read("root", "tip");
        assertEquals("included", result.lastStatus);
        assertFalse(result.hasUnpricedCalls);
    }

    public void testIncompleteAndMismatchedLineagesNeverReturnPartialTotals() throws Exception {
        JSONObject tip = row("tip", "gone", 9, 0, 0, 2);
        expectIncomplete();
        JSONObject ancestor = row("gone", "tip", 2, 0, 0, 2).put("end_reason", "compression");
        expectIncomplete();
        tip.put("parent_session_id", "root");
        rows.put("root", ancestor);
        expectIncomplete();
        ancestor.put("id", "root").put("end_reason", "user_exit");
        expectIncomplete();
        assertEquals(Arrays.asList("tip", "gone", "tip", "gone", "tip", "root", "tip", "root"), requested);
    }

    public void testLongCompressedConversationAndCancellationBetweenReads() throws Exception {
        for (int i = 0; i < 181; i++) {
            JSONObject segment = row("id" + i, i == 0 ? null : "id" + (i - 1), 1, 1, 1, 1);
            if (i < 180) segment.put("end_reason", "compression");
        }
        assertEquals(181, read("id0", "id180").output, 0);
        int[] reads = {0};
        try {
            DiscussionUsage.read("id0", "id180", id -> { reads[0]++; return rows.get(id); }, () -> reads[0] < 2);
            fail("Cancelled lineage returned totals");
        } catch (IOException expected) { assertEquals(2, reads[0]); }
    }

    private void expectIncomplete() throws Exception {
        try { read("root", "tip"); fail("Incomplete lineage returned totals"); }
        catch (IOException expected) { /* No total may escape. */ }
    }
}
