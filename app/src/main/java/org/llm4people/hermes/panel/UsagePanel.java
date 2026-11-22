package org.llm4people.hermes.panel;

import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.function.BooleanSupplier;

final class UsagePanel {
    private final SessionPanel panel;
    private final String stableId;
    private final String remoteId;
    private final Runnable parent;

    UsagePanel(SessionPanel panel, String stableId, String remoteId, Runnable parent) {
        this.panel = panel; this.stableId = stableId; this.remoteId = remoteId; this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("stats", R.string.panel_stats, parent);
        if (stableId == null || stableId.isEmpty() || remoteId == null || remoteId.isEmpty()) { panel.note(page, panel.t(R.string.panel_no_session)); return; }
        panel.action(page, R.string.panel_refresh, this::show);
        panel.section(page, R.string.panel_server_totals);
        LinearLayout totals = Ui.column(panel.activity);
        page.addView(totals);
        BooleanSupplier current = panel.currentPage();
        panel.request(totals, () -> {
            // Resolve the tip anew on refresh: another device can compact this discussion.
            JSONObject snapshot = panel.api.get("/api/sessions/" + HermesApi.segment(stableId) + "/messages?order=latest&limit=0");
            String tip = PanelData.text(snapshot, "session_id");
            return DiscussionUsage.read(stableId, tip, id -> panel.api.get(
                    "/api/sessions/" + HermesApi.segment(id)).getJSONObject("session"), current);
        }, result -> display(totals, result), error -> {
            panel.note(totals, panel.t(R.string.panel_usage_incomplete))
                    .setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
            panel.action(totals, R.string.panel_retry, this::show);
        });
        panel.section(page, R.string.panel_last_run);
        JSONObject timing;
        try { timing = PanelData.parse(panel.account.read("panel/timing/" + stableId)); }
        catch (IllegalStateException error) { panel.failure(page, error, null); return; }
        if (timing.length() == 0) { panel.note(page, panel.t(R.string.panel_timing_empty)); return; }
        metric(page, R.string.panel_first_text, format(PanelData.measure(timing, "first_ms") / 1000, R.string.panel_seconds));
        metric(page, R.string.panel_duration, format(PanelData.measure(timing, "elapsed_ms") / 1000, R.string.panel_seconds));
        metric(page, R.string.panel_rate, format(PanelData.endToEndRate(timing), R.string.panel_tps));
    }

    private void display(LinearLayout box, DiscussionUsage usage) {
        metric(box, R.string.panel_cost, format(usage.billed, R.string.panel_usd));
        metric(box, R.string.panel_estimate, format(usage.estimated, R.string.panel_usd));
        if (usage.hasUnpricedCalls) panel.note(box, panel.t(R.string.panel_unpriced));
        if (usage.lastStatus.equals("included")) panel.note(box, panel.t(R.string.panel_included));
        int source = sourceName(usage.lastSource);
        if (source != 0) panel.note(box, panel.t(R.string.panel_last_cost_source, panel.t(source)));
        double cache = usage.cacheRead / usage.prompt;
        metric(box, R.string.panel_cache, Double.isFinite(cache) ? NumberFormat.getPercentInstance(Locale.FRENCH).format(cache)
                : panel.t(R.string.panel_unknown));
        metric(box, R.string.panel_in, format(usage.prompt, 0));
        metric(box, R.string.panel_out, format(usage.output, 0));
    }

    private static int sourceName(String source) {
        switch (source) {
            case "provider_cost_api": return R.string.panel_source_billing;
            case "provider_generation_api": return R.string.panel_source_generation;
            case "provider_models_api": return R.string.panel_source_models;
            case "official_docs_snapshot": return R.string.panel_source_docs;
            case "user_override": return R.string.panel_source_override;
            case "custom_contract": return R.string.panel_source_contract;
            default: return 0;
        }
    }

    private String format(double value, int unit) {
        if (!Double.isFinite(value)) return panel.t(R.string.panel_unknown);
        NumberFormat number = NumberFormat.getNumberInstance(Locale.FRENCH);
        number.setMaximumFractionDigits(unit == R.string.panel_usd ? 8 : 3);
        return unit == 0 ? number.format(value) : panel.t(unit, number.format(value));
    }

    private void metric(LinearLayout box, int label, String value) {
        TextView name = panel.note(box, panel.t(label));
        TextView number = Ui.text(panel.activity, value, 20);
        number.setTypeface(android.graphics.Typeface.MONOSPACE);
        number.setContentDescription(name.getText() + ", " + value);
        name.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(number);
        box.addView(Ui.divider(panel.activity));
    }
}
