package org.llm4people.hermes.panel;

import android.content.res.ColorStateList;
import android.view.Gravity;
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
 * Everyday usage & performance telemetry panel.
 * Highlights primary metrics inside Material 3 surface cards and preserves localized "Unavailable"
 * for every missing/unknown value — NEVER turning unknown into 0.
 * Technical counters and 30-day gateway analytics tuck cleanly under persisted disclosures.
 */
final class UsagePanel {
    private final SessionPanel panel;
    private final String stableId;
    private final String remoteId;
    private final Runnable parent;
    private boolean localTechnicalExpanded;
    private boolean serverTechnicalExpanded;

    UsagePanel(SessionPanel panel, String stableId, String remoteId, Runnable parent) {
        this.panel = panel;
        this.stableId = stableId;
        this.remoteId = remoteId;
        this.parent = parent;
    }

    void show() {
        LinearLayout page = panel.page("stats", R.string.panel_stats, parent);
        if (stableId == null || stableId.isEmpty() || remoteId == null || remoteId.isEmpty()) {
            showProfileWideOnly(page);
            return;
        }
        addRefreshButton(page);
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
            TextView err = panel.note(totals, panel.t(R.string.panel_usage_incomplete));
            err.setTextColor(panel.activity.getColor(R.color.danger));
            err.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
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

    private void addRefreshButton(LinearLayout page) {
        Button refresh = Ui.outlinedButton(panel.activity, panel.t(R.string.panel_refresh), v -> show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, Ui.dp(panel.activity, 4), 0, Ui.dp(panel.activity, 8));
        page.addView(refresh, lp);
    }

    private void showProfileWideOnly(LinearLayout page) {
        addRefreshButton(page);
        LinearLayout box = Ui.column(panel.activity);
        page.addView(box);
        panel.request(box, this::fetchOptionalAnalytics, analytics -> {
            if (analytics == null) {
                panel.note(box, panel.t(R.string.panel_no_session));
                return;
            }
            LinearLayout card = Ui.card(panel.activity);
            renderProfileAnalytics(card, analytics);
            box.addView(card, cardParams());
        }, error -> {
            Diagnostics.warning("usage", "profile_analytics_unavailable", error);
            TextView err = panel.note(box, panel.t(R.string.panel_usage_incomplete));
            err.setTextColor(panel.activity.getColor(R.color.danger));
            err.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            panel.action(box, R.string.panel_retry, this::show);
        });
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
        LinearLayout primaryCard = Ui.card(panel.activity);
        metric(primaryCard, R.string.brain_usage_response_time,
                UsageFormatter.formatResponseTime(panel.activity, telemetry.ttftMs()));
        metric(primaryCard, R.string.brain_usage_speed,
                UsageFormatter.formatSpeed(panel.activity, telemetry.primarySpeedTps()));

        LinearLayout technical = Ui.tonalCard(panel.activity);
        technical.setId(R.id.brain_usage_technical_box);
        technical.setVisibility(localTechnicalExpanded ? View.VISIBLE : View.GONE);
        metric(technical, R.string.panel_first_text,
                UsageFormatter.formatResponseTime(panel.activity, telemetry.ttftMs()));
        metric(technical, R.string.brain_usage_gen_tps,
                UsageFormatter.formatSpeed(panel.activity, telemetry.pureGenerationTps()));
        metric(technical, R.string.panel_rate,
                UsageFormatter.formatSpeed(panel.activity, telemetry.endToEndTps()));
        metric(technical, R.string.panel_duration,
                UsageFormatter.formatSeconds(panel.activity, telemetry.elapsedMs() / 1000d));
        if (Double.isFinite(telemetry.reasoningTokens())) {
            metric(technical, R.string.brain_usage_reasoning_tokens,
                    UsageFormatter.formatCount(panel.activity, telemetry.reasoningTokens()));
        }
        if (Double.isFinite(telemetry.apiCalls())) {
            metric(technical, R.string.brain_usage_api_calls,
                    UsageFormatter.formatCount(panel.activity, telemetry.apiCalls()));
        }

        Button toggle = disclosureToggle(localTechnicalExpanded);
        toggle.setId(R.id.brain_usage_technical_toggle);
        toggle.setOnClickListener(v -> {
            localTechnicalExpanded = !localTechnicalExpanded;
            technical.setVisibility(localTechnicalExpanded ? View.VISIBLE : View.GONE);
            syncDisclosureToggle(toggle, localTechnicalExpanded);
        });
        primaryCard.addView(Ui.insetDivider(panel.activity, 0));
        primaryCard.addView(toggle);
        local.addView(primaryCard, cardParams());
        local.addView(technical, disclosureBodyParams());
    }

    private void display(LinearLayout box, DiscussionUsage usage, BrainJson.AnalyticsSummary analytics) {
        LinearLayout primaryCard = Ui.card(panel.activity);
        metric(primaryCard, R.string.brain_usage_cost_known,
                UsageFormatter.primaryCostSummary(panel.activity, usage.billed, usage.estimated, usage.lastStatus));
        double cache = usage.prompt > 0 ? usage.cacheRead / usage.prompt : Double.NaN;
        metric(primaryCard, R.string.brain_usage_cache_plain, UsageFormatter.formatPercent(panel.activity, cache));
        // Preserved cumulative counters (required by existing journeys & power inspection)
        metric(primaryCard, R.string.panel_in, UsageFormatter.formatCount(panel.activity, usage.prompt));
        metric(primaryCard, R.string.panel_out, UsageFormatter.formatCount(panel.activity, usage.output));

        if (usage.hasUnpricedCalls) panel.note(primaryCard, panel.t(R.string.panel_unpriced));
        if ("included".equals(usage.lastStatus)) panel.note(primaryCard, panel.t(R.string.panel_included));

        LinearLayout details = Ui.tonalCard(panel.activity);
        details.setVisibility(serverTechnicalExpanded ? View.VISIBLE : View.GONE);
        metric(details, R.string.panel_cost, UsageFormatter.formatUsd(panel.activity, usage.billed));
        metric(details, R.string.panel_estimate, UsageFormatter.formatUsd(panel.activity, usage.estimated));
        int source = sourceName(usage.lastSource);
        if (source != 0) panel.note(details, panel.t(R.string.panel_last_cost_source, panel.t(source)));
        if (analytics != null) {
            renderProfileAnalytics(details, analytics);
        }

        Button toggle = disclosureToggle(serverTechnicalExpanded);
        toggle.setOnClickListener(v -> {
            serverTechnicalExpanded = !serverTechnicalExpanded;
            details.setVisibility(serverTechnicalExpanded ? View.VISIBLE : View.GONE);
            syncDisclosureToggle(toggle, serverTechnicalExpanded);
        });
        primaryCard.addView(Ui.insetDivider(panel.activity, 0));
        primaryCard.addView(toggle);
        box.addView(primaryCard, cardParams());
        box.addView(details, disclosureBodyParams());
    }

    private Button disclosureToggle(boolean expanded) {
        Button toggle = Ui.button(panel.activity, "", null);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_prompt_disclosure, 0, 0, 0);
        toggle.setCompoundDrawableTintList(ColorStateList.valueOf(panel.activity.getColor(R.color.accent)));
        toggle.setCompoundDrawablePadding(Ui.dp(panel.activity, 8));
        syncDisclosureToggle(toggle, expanded);
        return toggle;
    }

    private void syncDisclosureToggle(Button toggle, boolean expanded) {
        toggle.setActivated(expanded);
        toggle.setText(expanded ? R.string.brain_usage_technical_hide : R.string.brain_usage_technical_toggle);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(panel.activity, 4), 0, Ui.dp(panel.activity, 8));
        return lp;
    }

    private LinearLayout.LayoutParams disclosureBodyParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(panel.activity, 6);
        return lp;
    }

    private void renderProfileAnalytics(LinearLayout box, BrainJson.AnalyticsSummary analytics) {
        panel.section(box, R.string.brain_usage_profile_analytics);
        metric(box, R.string.brain_usage_cost_known,
                UsageFormatter.primaryCostSummary(panel.activity, analytics.actualCostUsd(), analytics.estimatedCostUsd(), ""));
        metric(box, R.string.brain_usage_profile_runs, UsageFormatter.formatCount(panel.activity, analytics.sessions()));
        if (Double.isFinite(analytics.apiCalls())) {
            metric(box, R.string.brain_usage_api_calls, UsageFormatter.formatCount(panel.activity, analytics.apiCalls()));
        }
        if (Double.isFinite(analytics.avgTtftMs())) {
            metric(box, R.string.brain_usage_profile_avg_ttft,
                    UsageFormatter.formatResponseTime(panel.activity, analytics.avgTtftMs()));
        }
        if (Double.isFinite(analytics.avgTps())) {
            metric(box, R.string.brain_usage_profile_avg_tps,
                    UsageFormatter.formatSpeed(panel.activity, analytics.avgTps()));
        }
        metric(box, R.string.panel_in, UsageFormatter.formatCount(panel.activity, analytics.inputTokens()));
        metric(box, R.string.panel_out, UsageFormatter.formatCount(panel.activity, analytics.outputTokens()));
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
        if (box.getChildCount() > 0 && box.getChildAt(box.getChildCount() - 1) instanceof LinearLayout) {
            box.addView(Ui.insetDivider(panel.activity, 0));
        }
        LinearLayout item = Ui.column(panel.activity);
        Ui.pad(item, 0, 8);
        TextView name = Ui.text(panel.activity, panel.t(label), 14);
        name.setTextColor(panel.activity.getColor(R.color.muted));
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView number = Ui.text(panel.activity, value, 20);
        number.setTypeface(Ui.mediumTypeface());
        number.setFontFeatureSettings("tnum");
        number.setContentDescription(name.getText() + ", " + value);
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(-1, -2);
        numLp.topMargin = Ui.dp(panel.activity, 2);
        item.addView(name);
        item.addView(number, numLp);
        box.addView(item);
    }
}
