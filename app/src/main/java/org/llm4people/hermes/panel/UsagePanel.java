package org.llm4people.hermes.panel;

import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import java.util.function.BooleanSupplier;

/**
 * Plain-French usage & performance telemetry panel.
 * Highlights everyday metrics ("Temps de réponse", "Vitesse", "Coût connu", "Réutilisation du cache")
 * and preserves "Indisponible" for every missing/unknown value — NEVER turning unknown into 0.
 * Technical counters and 30-day gateway analytics tuck cleanly under "Détails techniques".
 */
final class UsagePanel {
    private final SessionPanel panel;
    private final String stableId;
    private final String remoteId;
    private final Runnable parent;
    private boolean technicalExpanded;

    UsagePanel(SessionPanel panel, String stableId, String remoteId, Runnable parent) {
        this.panel = panel;
        this.stableId = stableId;
        this.remoteId = remoteId;
        this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("stats", R.string.panel_stats, parent);
        if (stableId == null || stableId.isEmpty() || remoteId == null || remoteId.isEmpty()) {
            panel.note(page, panel.t(R.string.panel_no_session));
            return;
        }
        panel.action(page, R.string.panel_refresh, this::show).setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        PanelFrame.Sections sections = panel.sections(page);

        panel.section(sections.first, R.string.panel_server_totals);
        LinearLayout totals = Ui.column(panel.activity);
        sections.first.addView(totals);
        BooleanSupplier current = panel.currentPage();
        panel.request(totals, () -> {
            // Resolve the tip anew on refresh: another device can compact this discussion.
            JSONObject snapshot = panel.api.get("/api/sessions/" + HermesApi.segment(stableId) + "/messages?order=latest&limit=0");
            String tip = PanelData.text(snapshot, "session_id");
            DiscussionUsage usage = DiscussionUsage.read(stableId, tip, id -> panel.api.get(
                    "/api/sessions/" + HermesApi.segment(id)).getJSONObject("session"), current);
            BrainJson.AnalyticsSummary analytics = fetchOptionalAnalytics();
            return new Object[]{usage, analytics};
        }, result -> display(totals, (DiscussionUsage) result[0], (BrainJson.AnalyticsSummary) result[1]), error -> {
            Diagnostics.warning("usage", "totals_unavailable", error);
            panel.note(totals, panel.t(R.string.panel_usage_incomplete))
                    .setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            panel.action(totals, R.string.panel_retry, this::show);
        });

        LinearLayout local = sections.second;
        panel.section(local, R.string.panel_last_run);
        JSONObject timing;
        try {
            timing = PanelData.parse(panel.account.read("panel/timing/" + stableId));
        } catch (IllegalStateException error) {
            panel.failure(local, error, null);
            return;
        }
        if (timing.length() == 0) {
            panel.note(local, panel.t(R.string.panel_timing_empty));
            return;
        }
        displayLocalTiming(local, BrainJson.parseRunTelemetry(timing));
    }

    private BrainJson.AnalyticsSummary fetchOptionalAnalytics() {
        try {
            BrainCapabilities caps = BrainJson.parseCapabilities(panel.api.capabilities());
            if (!caps.usageAnalytics()) return null;
            return BrainJson.parseAnalyticsSummary(panel.api.get("/api/analytics/usage?days=30"), 30);
        } catch (Exception ignored) {
            Diagnostics.debug("usage", "analytics_optional_skipped", ignored);
            return null;
        }
    }

    private void displayLocalTiming(LinearLayout local, UsageFormatter.RunTelemetry telemetry) {
        // Plain everyday French first
        metric(local, R.string.brain_usage_response_time, UsageFormatter.formatResponseTime(telemetry.ttftMs()));
        metric(local, R.string.brain_usage_speed, UsageFormatter.formatSpeed(telemetry.primarySpeedTps()));

        LinearLayout technical = Ui.column(panel.activity);
        technical.setId(R.id.brain_usage_technical_box);
        technical.setVisibility(technicalExpanded ? View.VISIBLE : View.GONE);
        metric(technical, R.string.panel_first_text, UsageFormatter.formatResponseTime(telemetry.ttftMs()));
        metric(technical, R.string.brain_usage_gen_tps, UsageFormatter.formatSpeed(telemetry.pureGenerationTps()));
        metric(technical, R.string.panel_rate, UsageFormatter.formatSpeed(telemetry.endToEndTps()));
        metric(technical, R.string.panel_duration, UsageFormatter.formatSeconds(telemetry.elapsedMs() / 1000d));
        if (Double.isFinite(telemetry.reasoningTokens())) {
            metric(technical, R.string.brain_usage_reasoning_tokens, UsageFormatter.formatCount(telemetry.reasoningTokens()));
        }
        if (Double.isFinite(telemetry.apiCalls())) {
            metric(technical, R.string.brain_usage_api_calls, UsageFormatter.formatCount(telemetry.apiCalls()));
        }

        Button toggle = Ui.button(panel.activity, panel.t(technicalExpanded
                ? R.string.brain_usage_technical_hide : R.string.brain_usage_technical_toggle), null);
        toggle.setId(R.id.brain_usage_technical_toggle);
        toggle.setOnClickListener(v -> {
            technicalExpanded = !technicalExpanded;
            technical.setVisibility(technicalExpanded ? View.VISIBLE : View.GONE);
            toggle.setText(technicalExpanded ? R.string.brain_usage_technical_hide : R.string.brain_usage_technical_toggle);
        });
        local.addView(toggle);
        local.addView(technical);
    }

    private void display(LinearLayout box, DiscussionUsage usage, BrainJson.AnalyticsSummary analytics) {
        metric(box, R.string.brain_usage_cost_known,
                UsageFormatter.primaryCostSummary(usage.billed, usage.estimated, usage.lastStatus));
        double cache = usage.prompt > 0 ? usage.cacheRead / usage.prompt : Double.NaN;
        metric(box, R.string.brain_usage_cache_plain, UsageFormatter.formatPercent(cache));
        // Preserved cumulative counters (required by existing journeys & power inspection)
        metric(box, R.string.panel_in, UsageFormatter.formatCount(usage.prompt));
        metric(box, R.string.panel_out, UsageFormatter.formatCount(usage.output));

        if (usage.hasUnpricedCalls) panel.note(box, panel.t(R.string.panel_unpriced));
        if ("included".equals(usage.lastStatus)) panel.note(box, panel.t(R.string.panel_included));

        LinearLayout details = Ui.column(panel.activity);
        details.setVisibility(View.GONE);
        metric(details, R.string.panel_cost, UsageFormatter.formatUsd(usage.billed));
        metric(details, R.string.panel_estimate, UsageFormatter.formatUsd(usage.estimated));
        int source = sourceName(usage.lastSource);
        if (source != 0) panel.note(details, panel.t(R.string.panel_last_cost_source, panel.t(source)));
        if (analytics != null) {
            panel.section(details, R.string.brain_usage_profile_analytics);
            metric(details, R.string.brain_usage_cost_known,
                    UsageFormatter.primaryCostSummary(analytics.actualCostUsd(), analytics.estimatedCostUsd(), ""));
            metric(details, R.string.brain_usage_profile_runs, UsageFormatter.formatCount(analytics.sessions()));
            if (Double.isFinite(analytics.apiCalls())) {
                metric(details, R.string.brain_usage_api_calls, UsageFormatter.formatCount(analytics.apiCalls()));
            }
            if (Double.isFinite(analytics.avgTtftMs())) {
                metric(details, R.string.brain_usage_profile_avg_ttft, UsageFormatter.formatResponseTime(analytics.avgTtftMs()));
            }
            if (Double.isFinite(analytics.avgTps())) {
                metric(details, R.string.brain_usage_profile_avg_tps, UsageFormatter.formatSpeed(analytics.avgTps()));
            }
            metric(details, R.string.panel_in, UsageFormatter.formatCount(analytics.inputTokens()));
            metric(details, R.string.panel_out, UsageFormatter.formatCount(analytics.outputTokens()));
        }

        boolean[] open = {false};
        Button toggle = Ui.button(panel.activity, panel.t(R.string.brain_usage_technical_toggle), null);
        toggle.setOnClickListener(v -> {
            open[0] = !open[0];
            details.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            toggle.setText(open[0] ? R.string.brain_usage_technical_hide : R.string.brain_usage_technical_toggle);
        });
        box.addView(toggle);
        box.addView(details);
    }

    private static int sourceName(String source) {
        return switch (source) {
            case "provider_cost_api" -> R.string.panel_source_billing;
            case "provider_generation_api" -> R.string.panel_source_generation;
            case "provider_models_api" -> R.string.panel_source_models;
            case "official_docs_snapshot" -> R.string.panel_source_docs;
            case "user_override" -> R.string.panel_source_override;
            case "custom_contract" -> R.string.panel_source_contract;
            default -> 0;
        };
    }

    private void metric(LinearLayout box, int label, String value) {
        TextView name = panel.note(box, panel.t(label));
        TextView number = Ui.text(panel.activity, value, 20);
        number.setTypeface(Typeface.MONOSPACE);
        number.setContentDescription(name.getText() + ", " + value);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(number);
        box.addView(Ui.divider(panel.activity));
    }
}
