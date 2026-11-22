package org.llm4people.hermes.panel;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Exact session rows have disjoint usage counters. The sidebar supplies the stable conversation root. */
final class DiscussionUsage {
    // The Hermes compression walker accepts 1,000 edges, including lineages over 100 segments long.
    private static final int MAX_SEGMENTS = 1001;

    interface RowReader {
        JSONObject read(String sessionId) throws IOException, JSONException;
    }

    double prompt;
    double cacheRead;
    double output;
    double billed = Double.NaN;
    double estimated = Double.NaN;
    boolean hasUnpricedCalls;
    private boolean hasLastObservation;
    String lastStatus = "";
    String lastSource = "";

    static DiscussionUsage read(String root, String tip, RowReader rows, BooleanSupplier active)
            throws IOException, JSONException {
        DiscussionUsage totals = new DiscussionUsage();
        Set<String> seen = new HashSet<>();
        String id = tip;
        while (active.getAsBoolean() && seen.size() < MAX_SEGMENTS && !id.isEmpty() && seen.add(id)) {
            JSONObject row = rows.read(id);
            if (row == null || !id.equals(row.opt("id"))) break;
            if (!id.equals(tip) && !"compression".equals(row.opt("end_reason"))) break;
            totals.add(row);
            // A branch's root can have a parent in another conversation. Stop before visiting it.
            if (id.equals(root)) return totals;
            id = PanelData.text(row, "parent_session_id");
        }
        throw new IOException("Incomplete compression lineage");
    }

    private void add(JSONObject row) {
        prompt += PanelData.promptTokens(row);
        cacheRead += PanelData.measure(row, "cache_read_tokens");
        output += PanelData.measure(row, "output_tokens");

        String status = PanelData.text(row, "cost_status");
        double actualCost = PanelData.measure(row, "actual_cost_usd");
        double estimatedCost = PanelData.measure(row, "estimated_cost_usd");
        // Hermes initializes an unknown estimate to zero. A positive amount or a matching
        // reported status identifies a real observation; actual_cost_usd is nullable.
        if (Double.isFinite(actualCost)) billed = addKnown(billed, actualCost);
        if (estimatedCost > 0 || (Double.isFinite(estimatedCost) && status.equals("estimated")))
            estimated = addKnown(estimated, estimatedCost);

        boolean hasCalls = PanelData.measure(row, "api_call_count") > 0
                || PanelData.measure(row, "input_tokens") > 0 || PanelData.measure(row, "output_tokens") > 0
                || PanelData.measure(row, "cache_read_tokens") > 0 || PanelData.measure(row, "cache_write_tokens") > 0
                || actualCost > 0 || estimatedCost > 0 || !status.isEmpty();
        if (!hasCalls) return;
        if (!hasLastObservation) {
            lastStatus = status;
            lastSource = PanelData.text(row, "cost_source");
            hasLastObservation = true;
        }
        if (!(status.equals("included") || (status.equals("actual") && Double.isFinite(actualCost))
                || (status.equals("estimated") && Double.isFinite(estimatedCost)))) hasUnpricedCalls = true;
    }

    private static double addKnown(double total, double value) {
        return Double.isNaN(total) ? value : total + value;
    }
}
